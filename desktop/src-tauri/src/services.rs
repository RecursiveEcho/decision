use crate::storage::{self, Config, Secrets};
use crate::Shared;
use hmac::{Hmac, Mac};
use sha2::Sha256;
use std::fs::{self, OpenOptions};
use std::io::{self, Read};
use std::sync::{Arc, atomic::{AtomicBool, Ordering}};
use std::os::unix::fs::OpenOptionsExt;
use std::os::unix::process::CommandExt;
use std::path::{Path, PathBuf};
use std::process::{Child, ChildStdin, Command, Stdio};
use std::time::{Duration, Instant};

type Result<T> = std::result::Result<T, Box<dyn std::error::Error + Send + Sync>>;
const PATH: &str = "/usr/bin:/bin:/usr/sbin:/sbin";

struct Service {
    child: Child,
    control: Option<ChildStdin>,
}
impl Service {
    fn stop(&mut self) -> Result<()> {
        self.control.take();
        let deadline = Instant::now() + Duration::from_secs(20);
        while Instant::now() < deadline {
            if let Some(exit) = self.child.try_wait()? {
                return if exit.code() == Some(2) {
                    Err("服务曾强制终止，请检查退出日志。".into())
                } else { Ok(()) };
            }
            std::thread::sleep(Duration::from_millis(100));
        }
        Err("服务退出超时，请保留日志并检查进程状态。".into())
    }
}
impl Drop for Service {
    fn drop(&mut self) { let _ = self.stop(); }
}

pub struct Services {
    data: PathBuf,
    logs: PathBuf,
    runtime: PathBuf,
    config: Config,
    secrets: Secrets,
    postgres: Option<Service>,
    java: Option<Service>,
    wiki: Option<Service>,
    sync: Option<Service>,
    client: reqwest::blocking::Client,
    challenge_key: String,
    restarts: u8,
    wiki_restarts: u8,
    sync_restarts: u8,
    health_failures: u8,
    cancel: Arc<AtomicBool>,
}

fn status(shared: &Shared, name: &str, value: &str) {
    shared.0.lock().unwrap().services.insert(name.into(), value.into());
}

fn program_env(command: &mut Command, data: &Path) {
    command.env_clear().env("PATH", PATH).env("LANG", "en_US.UTF-8")
        .env("HOME", data).env("TMPDIR", data.join("temp"));
}

impl Services {
    pub fn start(runtime: PathBuf, data: PathBuf, logs: PathBuf, shared: &Shared) -> Result<Self> {
        let config = Config::load_or_create(&data)?;
        config.validate()?;
        config.check_ports()?;
        let secrets = Secrets::load(&config.installation)?;
        for name in ["temp", "wiki-data", "sync-state", "Knowledge"] {
            storage::directory(data.join(name))?;
        }
        let client = reqwest::blocking::Client::builder().no_proxy()
            .redirect(reqwest::redirect::Policy::none()).timeout(Duration::from_secs(3)).build()?;
        let mut all = Self { data, logs, runtime, config, secrets, postgres: None, java: None,
            wiki: None, sync: None, client, challenge_key: storage::random(), restarts: 0, wiki_restarts: 0,
            sync_restarts: 0, health_failures: 0, cancel: shared.2.clone() };

        status(shared, "数据库", "正在启动");
        let fresh = all.config.phase == "new";
        let pgdata = all.data.join("postgres");
        if fresh {
            if pgdata.exists() { return Err("数据库目录与安装状态不一致，请查看诊断日志。".into()); }
            all.config.phase = "initializing".into();
            all.config.save(&all.data)?;
            let mut command = all.command("postgres/bin/initdb");
            command.args(["-D"]).arg(&pgdata).args(["-U", "decision_cluster_admin",
                "--pwfile=/dev/stdin", "--auth=scram-sha-256", "--encoding=UTF8", "--locale=C"]);
            all.execute_private(command, &(all.secrets.admin.clone() + "\n"))?;
        } else if !pgdata.join("PG_VERSION").is_file() {
            return Err("已有安装缺少数据库文件，请检查备份与数据目录。".into());
        }

        let args = vec![all.bin("postgres/bin/postgres"), "-D".into(), string(&pgdata),
            "-h".into(), "127.0.0.1".into(), "-p".into(), all.config.postgres_port.to_string(),
            "-c".into(), "unix_socket_directories=".into()];
        all.postgres = Some(all.spawn("postgres", &args, &[], &all.data)?);
        let deadline = Instant::now() + Duration::from_secs(45);
        loop {
            all.check_cancel()?;
            all.check_postgres()?;
            if all.sql("decision_cluster_admin", "postgres", &all.secrets.admin, "SELECT 1;").is_ok() {
                break;
            }
            if Instant::now() >= deadline { return Err("数据库启动超时，请查看日志。".into()); }
            std::thread::sleep(Duration::from_millis(250));
        }
        if fresh {
            let sql = format!(
                "REVOKE CONNECT,TEMPORARY ON DATABASE postgres FROM PUBLIC;\n                 REVOKE CONNECT,TEMPORARY ON DATABASE template1 FROM PUBLIC;\n                 CREATE ROLE decision_app LOGIN PASSWORD '{}';\n                 CREATE DATABASE decision OWNER decision_app;\n                 REVOKE CONNECT,TEMPORARY ON DATABASE decision FROM PUBLIC;\n                 CREATE ROLE decision_wiki LOGIN PASSWORD '{}';\n                 CREATE DATABASE decision_wiki OWNER decision_wiki;\n                 REVOKE CONNECT,TEMPORARY ON DATABASE decision_wiki FROM PUBLIC;\n",
                all.secrets.app, all.secrets.wiki);
            all.sql("decision_cluster_admin", "postgres", &all.secrets.admin, &sql)?;
            all.config.phase = "cluster".into();
            all.config.save(&all.data)?;
        }
        all.sql("decision_app", "decision", &all.secrets.app, "SELECT 1;")?;
        all.sql("decision_wiki", "decision_wiki", &all.secrets.wiki, "SELECT 1;")?;
        status(shared, "数据库", "可用");

        status(shared, "Wiki", "正在启动");
        // Wiki can fail independently while the core remains usable.
        if let Err(_) = all.start_wiki() {
            status(shared, "Wiki", "启动失败，核心可继续使用");
        } else {
            status(shared, "Wiki", "可用");
        }
        status(shared, "核心", "正在启动");
        all.start_java(shared)?;
        status(shared, "核心", "可用");
        all.config.phase = "ready".into();
        all.config.save(&all.data)?;

        status(shared, "知识镜像", "正在启动");
        if all.start_sync().is_ok() {
            status(shared, "知识镜像", "正在同步");
        } else {
            status(shared, "知识镜像", "启动失败，核心可继续使用");
        }
        Ok(all)
    }

    pub fn origin(&self) -> String { format!("http://127.0.0.1:{}", self.config.api_port) }

    fn bin(&self, path: &str) -> String { string(&self.runtime.join(path)) }

    fn command(&self, executable: &str) -> Command {
        let mut command = Command::new(self.runtime.join(executable));
        program_env(&mut command, &self.data);
        command.current_dir(&self.data);
        command
    }

    fn execute_private(&self, command: Command, input: &str) -> Result<String> {
        let mut guardian = Command::new(std::env::current_exe()?);
        guardian.env_clear();
        for (key, value) in command.get_envs() {
            if let Some(value) = value { guardian.env(key, value); }
        }
        let args: Vec<_> = std::iter::once(command.get_program())
            .chain(command.get_args()).map(|arg| arg.to_string_lossy().into_owned()).collect();
        let log = OpenOptions::new().create(true).append(true).mode(0o600)
            .open(self.logs.join("initialization.log"))?;
        guardian.arg("--service-guard").env("DECISION_SERVICE_ARGS", serde_json::to_string(&args)?)
            .env("DECISION_SERVICE_KIND", "helper").env("DECISION_SERVICE_INPUT", input)
            .stdin(Stdio::piped()).stdout(Stdio::piped()).stderr(log);
        if let Some(cwd) = command.get_current_dir() { guardian.current_dir(cwd); }
        unsafe { guardian.pre_exec(|| { if libc::setsid() < 0 { return Err(io::Error::last_os_error()); } Ok(()) }); }
        let mut child = guardian.spawn()?;
        let output = child.stdout.take().ok_or("Missing helper output")?;
        let reader = std::thread::spawn(move || {
            let mut bytes = Vec::new();
            output.take(2 * 1024 * 1024).read_to_end(&mut bytes).map(|_| bytes)
        });
        let control = child.stdin.take();
        let mut service = Service { child, control };
        let deadline = Instant::now() + Duration::from_secs(180);
        loop {
            self.check_cancel()?;
            if let Some(exit) = service.child.try_wait()? {
                let bytes = reader.join().map_err(|_| "Helper output reader failed")??;
                if !exit.success() { return Err("初始化命令或数据库认证失败，请查看诊断信息。".into()); }
                return Ok(String::from_utf8(bytes)?);
            }
            if Instant::now() >= deadline { return Err("初始化命令超时，请重试并查看诊断信息。".into()); }
            std::thread::sleep(Duration::from_millis(100));
        }
    }

    fn check_cancel(&self) -> Result<()> {
        if self.cancel.load(Ordering::Acquire) { return Err("正在退出应用…".into()); }
        Ok(())
    }

    fn sql(&self, role: &str, db: &str, password: &str, sql: &str) -> Result<String> {
        let mut command = self.command("postgres/bin/psql");
        command.args(["-XAtq", "--no-password", "-h", "127.0.0.1", "-p"])
            .arg(self.config.postgres_port.to_string()).args(["-U", role, "-d", db,
                "-v", "ON_ERROR_STOP=1"]).env("PGPASSWORD", password).env("PGCONNECT_TIMEOUT", "2");
        self.execute_private(command, sql)
    }

    fn spawn(&self, name: &str, args: &[String], env: &[(&str, String)], cwd: &Path) -> Result<Service> {
        let logs = self.logs.clone();
        storage::directory(logs.clone())?;
        let path = logs.join(format!("{name}.log"));
        // Bound log growth across launches without logging credentials.
        if path.metadata().is_ok_and(|meta| meta.len() > 4 * 1024 * 1024) {
            fs::rename(&path, logs.join(format!("{name}.previous.log")))?;
        }
        let log = OpenOptions::new().create(true).append(true).mode(0o600).open(path)?;
        let mut command = Command::new(std::env::current_exe()?);
        program_env(&mut command, &self.data);
        command.arg("--service-guard").env("DECISION_SERVICE_ARGS", serde_json::to_string(args)?)
            .env("DECISION_SERVICE_KIND", name).envs(env.iter().cloned())
            .current_dir(cwd).stdin(Stdio::piped()).stdout(log.try_clone()?).stderr(log);
        unsafe { command.pre_exec(|| { if libc::setsid() < 0 { return Err(io::Error::last_os_error()); } Ok(()) }); }
        let mut child = command.spawn()?;
        let control = child.stdin.take();
        Ok(Service { child, control })
    }

    fn check_postgres(&mut self) -> Result<()> {
        if self.postgres.as_mut().unwrap().child.try_wait()?.is_some() {
            return Err("数据库进程已退出，请保留数据并查看诊断日志。".into());
        }
        Ok(())
    }

    fn start_wiki(&mut self) -> Result<()> {
        let config = format!(concat!(
            "port: {}\nbindIP: 127.0.0.1\noffline: true\ndataPath: {}\n",
            "db:\n  type: postgres\n  host: 127.0.0.1\n  port: {}\n",
            "  user: decision_wiki\n  pass: \"$(DECISION_WIKI_DB_PASSWORD)\"\n",
            "  db: decision_wiki\n"),
            self.config.wiki_port, serde_json::to_string(&self.data.join("wiki-data"))?,
            self.config.postgres_port);
        storage::write_private(&self.data.join("wiki.yml"), config.as_bytes())?;
        let args = vec![self.bin("node/bin/node"), self.bin("wiki-entry.cjs")];
        let environment = [("CONFIG_FILE", string(&self.data.join("wiki.yml"))),
            ("DECISION_WIKI_DB_PASSWORD", self.secrets.wiki.clone()),
            ("DECISION_WIKI_CHALLENGE_KEY", self.challenge_key.clone()),
            ("DECISION_WIKI_PORT", self.config.wiki_port.to_string())];
        self.wiki = Some(self.spawn("wiki", &args, &environment, &self.runtime.join("wiki"))?);
        let base = format!("http://127.0.0.1:{}", self.config.wiki_port);
        let deadline = Instant::now() + Duration::from_secs(60);
        loop {
            self.check_cancel()?;
            if self.wiki.as_mut().unwrap().child.try_wait()?.is_some() {
                return Err("Wiki process exited".into());
            }
            if self.prove(&base) { break; }
            if Instant::now() >= deadline { return Err("Wiki readiness timed out".into()); }
            std::thread::sleep(Duration::from_millis(300));
        }
        if self.secrets.wiki_token.is_empty() {
            let mut command = self.command("decision-sync");
            command.args(["wiki-init", "--base", &base])
                .env("DECISION_WIKI_ADMIN_PASSWORD", &self.secrets.wiki_admin)
                .env("DECISION_WIKI_CHALLENGE_KEY", &self.challenge_key);
            let output = self.execute_private(command, "")?;
            let value: serde_json::Value = serde_json::from_str(&output)?;
            self.secrets.wiki_token = value["token"].as_str().ok_or("Missing Wiki token")?.into();
            self.secrets.save(&self.config.installation)?;
        }
        // Wiki binds HTTP before its authentication strategies finish loading.
        // The identity proof above proves ownership, not authenticated readiness.
        // Retry only this read, within the original startup deadline; never reinitialize data.
        loop {
            self.check_cancel()?;
            if self.wiki.as_mut().unwrap().child.try_wait()?.is_some() {
                return Err("Wiki process exited before authenticated readiness".into());
            }
            let ready = self.client.post(format!("{base}/graphql"))
                .bearer_auth(&self.secrets.wiki_token)
                .header("Content-Type", "application/json")
                .body(r#"{"query":"{pages{list{id path}}}"}"#).send()
                .and_then(|response| response.text()).ok()
                .and_then(|body| serde_json::from_str::<serde_json::Value>(&body).ok())
                .map(|response| response["data"]["pages"]["list"].is_array())
                .unwrap_or(false);
            if ready { break; }
            if Instant::now() >= deadline { return Err("Wiki authenticated readiness timed out".into()); }
            std::thread::sleep(Duration::from_millis(300));
        }
        Ok(())
    }

    fn start_java(&mut self, shared: &Shared) -> Result<()> {
        let args = vec![self.bin("java/bin/java"), format!("-Djava.io.tmpdir={}", string(&self.data.join("temp"))),
            format!("-Dloader.path={}", self.bin("decision-desktop.jar")), "-cp".into(),
            self.bin("decision-backend.jar"), "org.springframework.boot.loader.launch.PropertiesLauncher".into()];
        let base = format!("http://127.0.0.1:{}", self.config.wiki_port);
        let model_key = storage::model_key(&self.config.installation)?;
        // Account-level keys live in the backend; the legacy installation key is not its status.
        status(shared, "模型", "按账号配置，请在应用设置中查看");
        let static_root = reqwest::Url::from_directory_path(self.runtime.join("web"))
            .map_err(|_| "无法解析前端资源目录")?.to_string();
        let env = [
            ("SERVER_ADDRESS", "127.0.0.1".into()), ("APP_PORT", self.config.api_port.to_string()),
            ("SPRING_WEB_RESOURCES_STATIC_LOCATIONS", static_root),
            ("DECISION_DESKTOP_CHALLENGE_KEY", self.challenge_key.clone()),
            ("SERVER_SHUTDOWN", "graceful".into()),
            ("SPRING_DATASOURCE_HIKARI_CONNECTIONTIMEOUT", "3000".into()),
            ("DATABASE_URL", format!("jdbc:postgresql://127.0.0.1:{}/decision", self.config.postgres_port)),
            ("DATABASE_USER", "decision_app".into()), ("DATABASE_PASSWORD", self.secrets.app.clone()),
            ("APP_OWNER_ACCESS_CODE", self.secrets.owner.clone()), ("APP_TOKEN_SECRET", self.secrets.token.clone()),
            ("APP_MOCK_MODE", "false".into()), ("DEEPSEEK_API_KEY", model_key),
            ("WIKI_BASE_URL", base.clone()), ("WIKI_PUBLIC_URL", base),
            ("WIKI_ACCESS_TOKEN", self.secrets.wiki_token.clone()), ("WIKI_LOCALE", "en".into()),
        ];
        self.java = Some(self.spawn("java", &args, &env, &self.data)?);
        let deadline = Instant::now() + Duration::from_secs(60);
        loop {
            self.check_cancel()?;
            if self.java.as_mut().unwrap().child.try_wait()?.is_some() {
                return Err("核心进程退出，请查看 Java 服务日志。".into());
            }
            if self.ready() { return Ok(()); }
            if Instant::now() >= deadline { return Err("核心启动超时，请查看数据库迁移和 Java 日志。".into()); }
            std::thread::sleep(Duration::from_millis(250));
        }
    }

    fn ready(&self) -> bool { self.prove(&self.origin()) }

    fn prove(&self, origin: &str) -> bool {
        let challenge = storage::random();
        let Ok(key) = hex::decode(&self.challenge_key) else { return false };
        let Ok(mut signature) = Hmac::<Sha256>::new_from_slice(&key) else { return false };
        signature.update(challenge.as_bytes());
        let Ok(response) = self.client.get(format!("{origin}/_desktop/ready"))
            .header("X-Decision-Challenge", &challenge).send() else { return false };
        if !response.status().is_success() { return false; }
        let Ok(proof) = response.text() else { return false };
        let Ok(bytes) = hex::decode(proof) else { return false };
        signature.verify_slice(&bytes).is_ok()
    }

    fn start_sync(&mut self) -> Result<()> {
        let args = vec![self.bin("decision-sync"), "watch".into(),
            "--vault".into(), string(&self.data.join("Knowledge")), "--state-dir".into(),
            string(&self.data.join("sync-state")), "--api-base".into(), format!("{}/api/v1", self.origin())];
        self.sync = Some(self.spawn("sync", &args,
            &[("APP_OWNER_ACCESS_CODE", self.secrets.owner.clone()),
              ("DECISION_DESKTOP_CHALLENGE_KEY", self.challenge_key.clone())], &self.data)?);
        Ok(())
    }

    pub fn monitor(&mut self, shared: &Shared) -> Result<()> {
        self.check_postgres()?;
        if self.java.as_mut().unwrap().child.try_wait()?.is_some() {
            if self.restarts >= 3 { return Err("核心反复退出，请查看日志后重试启动。".into()); }
            self.restarts += 1;
            status(shared, "核心", "正在恢复，中断任务可在对话中重试");
            std::thread::sleep(Duration::from_secs(2u64.pow(self.restarts.into())));
            self.java.take();
            self.start_java(shared)?;
            status(shared, "核心", "可用");
        }
        if self.ready() {
            if self.health_failures > 0 { status(shared, "核心", "可用"); }
            self.health_failures = 0;
        } else {
            self.health_failures += 1;
            status(shared, "核心", "正在检查数据库连接与服务身份");
            if self.health_failures >= 3 {
                return Err("核心身份或数据库就绪检查失败，请查看诊断日志后重试。".into());
            }
        }
        let wiki_exited = match self.wiki.as_mut() {
            Some(wiki) => wiki.child.try_wait()?.is_some(),
            None => false,
        };
        if wiki_exited {
            if self.wiki_restarts < 3 {
                self.wiki_restarts += 1;
                status(shared, "Wiki", "正在恢复");
                self.wiki.take();
                if self.start_wiki().is_ok() { status(shared, "Wiki", "可用"); }
                else { status(shared, "Wiki", "暂不可用，核心可继续使用"); }
            } else {
                status(shared, "Wiki", "反复退出，请查看诊断日志");
            }
        }
        if let Some(sync) = &mut self.sync {
            if sync.child.try_wait()?.is_some() {
                if self.sync_restarts < 3 {
                    self.sync_restarts += 1;
                    self.sync.take();
                    if self.start_sync().is_err() { status(shared, "知识镜像", "启动失败，请检查诊断日志"); }
                } else { status(shared, "知识镜像", "反复退出，请检查诊断日志"); }
            } else if let Ok(contents) = fs::read(self.data.join("sync-state/status.json")) {
                if let Ok(value) = serde_json::from_slice::<serde_json::Value>(&contents) {
                    status(shared, "知识镜像", if value["status"] == "ready" { "可用" } else { "等待后端或文件检查" });
                }
            }
        }
        Ok(())
    }

    pub fn stop(&mut self) -> Result<()> {
        let mut failure = None;
        for service in [&mut self.sync, &mut self.java, &mut self.wiki, &mut self.postgres] {
            if let Some(mut running) = service.take() {
                if let Err(error) = running.stop() { failure = Some(error); }
            }
        }
        match failure { Some(error) => Err(error), None => Ok(()) }
    }
}
impl Drop for Services { fn drop(&mut self) { let _ = self.stop(); } }

fn string(path: &Path) -> String { path.to_string_lossy().into_owned() }
