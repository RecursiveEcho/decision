use rand::RngCore;
use serde::{Deserialize, Serialize};
use std::fs::{self, OpenOptions};
use std::io::{self, Write};
use std::net::TcpListener;
use std::net::{Ipv4Addr, SocketAddrV4};
use socket2::{Domain, Protocol, Socket, Type};
use std::os::unix::fs::{OpenOptionsExt, PermissionsExt};
use std::path::{Path, PathBuf};

pub const SERVICE: &str = "dev.decision.desktop.preview";
type Result<T> = std::result::Result<T, Box<dyn std::error::Error + Send + Sync>>;

pub fn random() -> String {
    let mut bytes = [0u8; 32];
    rand::thread_rng().fill_bytes(&mut bytes);
    hex::encode(bytes)
}

pub fn write_private(path: &Path, bytes: &[u8]) -> io::Result<()> {
    let temporary = path.with_extension(format!("next-{}", random()));
    let mut out = OpenOptions::new().create_new(true).write(true).mode(0o600).open(&temporary)?;
    let result = (|| {
        out.write_all(bytes)?;
        out.sync_all()?;
        fs::rename(&temporary, path)?;
        fs::File::open(path.parent().unwrap())?.sync_all()
    })();
    if result.is_err() { let _ = fs::remove_file(&temporary); }
    result
}

#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Config {
    pub installation: String,
    pub postgres_port: u16,
    pub api_port: u16,
    pub wiki_port: u16,
    pub phase: String,
}

#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Secrets {
    pub admin: String,
    pub app: String,
    pub wiki: String,
    pub owner: String,
    pub token: String,
    pub wiki_admin: String,
    pub wiki_token: String,
}

impl Secrets {
    pub fn fresh() -> Self {
        Self { admin: random(), app: random(), wiki: random(), owner: random(),
               token: random(), wiki_admin: random(), wiki_token: String::new() }
    }

    pub fn save(&self, installation: &str) -> Result<()> {
        security_framework::passwords::set_generic_password(
            SERVICE, installation, &serde_json::to_vec(self)?)?;
        Ok(())
    }

    pub fn load(installation: &str) -> Result<Self> {
        let bytes = security_framework::passwords::get_generic_password(SERVICE, installation)?;
        Ok(serde_json::from_slice(&bytes)?)
    }
}

impl Config {
    pub fn load_or_create(directory: &Path) -> Result<Self> {
        let path = directory.join("installation.json");
        if path.exists() {
            if path.is_symlink() { return Err("Installation metadata is a symbolic link".into()); }
            return Ok(serde_json::from_slice(&fs::read(path)?)?);
        }
        if fs::read_dir(directory)?.next().is_some() {
            return Err("数据目录已有内容，但缺少安装信息。请检查日志与备份后再处理。".into());
        }
        // Keep all three sockets reserved until every port has been selected.
        let sockets: Vec<_> = (0..3).map(|_| TcpListener::bind(("127.0.0.1", 0)))
            .collect::<io::Result<_>>()?;
        let ports: Vec<_> = sockets.iter().map(|socket| socket.local_addr().map(|addr| addr.port()))
            .collect::<io::Result<_>>()?;
        let config = Config { installation: random(), postgres_port: ports[0],
            api_port: ports[1], wiki_port: ports[2], phase: "new".into() };
        Secrets::fresh().save(&config.installation)?;
        config.save(directory)?;
        Ok(config)
    }

    pub fn save(&self, directory: &Path) -> Result<()> {
        write_private(&directory.join("installation.json"), &serde_json::to_vec_pretty(self)?)?;
        Ok(())
    }

    pub fn check_ports(&self) -> Result<()> {
        let mut sockets = Vec::new();
        for port in [self.postgres_port, self.api_port, self.wiki_port] {
            // Java/Node leave short-lived TIME_WAIT connections after a clean
            // exit. Match their listener semantics while still rejecting a
            // live listener by checking listen(), not bind() alone.
            let socket = Socket::new(Domain::IPV4, Type::STREAM, Some(Protocol::TCP))?;
            socket.set_reuse_address(true)?;
            let address = SocketAddrV4::new(Ipv4Addr::LOCALHOST, port);
            socket.bind(&address.into()).and_then(|_| socket.listen(1))
                .map_err(|_| format!("本应用端口 {port} 被占用，请关闭占用程序后重试。"))?;
            sockets.push(socket);
        }
        Ok(())
    }

    pub fn validate(&self) -> Result<()> {
        let ports = [self.postgres_port, self.api_port, self.wiki_port];
        if ports.contains(&0) || ports[0] == ports[1] || ports[0] == ports[2] || ports[1] == ports[2]
            || self.installation.len() != 64 {
            return Err("安装信息无效，请检查应用数据目录。".into());
        }
        if !matches!(self.phase.as_str(), "new" | "cluster" | "ready") {
            return Err("上次初始化未完成。请保留当前数据目录并查看诊断日志。".into());
        }
        Ok(())
    }
}

pub fn directory(path: PathBuf) -> Result<PathBuf> {
    if path.is_symlink() { return Err("应用数据目录不能使用符号链接".into()); }
    fs::create_dir_all(&path)?;
    fs::set_permissions(&path, fs::Permissions::from_mode(0o700))?;
    Ok(path)
}

pub fn model_key(installation: &str) -> Result<String> {
    match security_framework::passwords::get_generic_password(SERVICE, &format!("{installation}-model")) {
        Ok(bytes) => Ok(String::from_utf8(bytes)?),
        Err(error) if error.code() == -25300 => Ok(String::new()),
        Err(error) => Err(error.into()),
    }
}

pub fn save_model_key(installation: &str, key: &str) -> Result<()> {
    security_framework::passwords::set_generic_password(
        SERVICE, &format!("{installation}-model"), key.as_bytes())?;
    Ok(())
}
