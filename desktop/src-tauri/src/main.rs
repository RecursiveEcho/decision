mod guardian;
mod services;
mod storage;

use serde::Serialize;
use std::collections::BTreeMap;
use std::fs::OpenOptions;
use std::io::Write;
use std::os::unix::fs::OpenOptionsExt;
use std::sync::{mpsc, Arc, Mutex};
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::Duration;
use tauri::{AppHandle, Manager, WebviewUrl, WebviewWindow};

fn trace_window(app: &AppHandle, event: &str) {
    let Ok(directory) = app.path().app_log_dir() else { return };
    let _ = std::fs::create_dir_all(&directory);
    let Ok(mut file) = OpenOptions::new().create(true).append(true).mode(0o600)
        .open(directory.join("desktop.log")) else { return };
    let time = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default().as_secs();
    let _ = writeln!(file, "{time} pid={} {event}", std::process::id());
}

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct AppState {
    message: String,
    services: BTreeMap<String, String>,
    ready: bool,
    can_retry: bool,
    origin: Option<String>,
    stopped: bool,
}
#[derive(Clone)]
struct Shared(Arc<Mutex<AppState>>, mpsc::Sender<Action>, Arc<AtomicBool>);
enum Action { Start, Quit }

fn startup_only(window: &WebviewWindow) -> Result<(), String> {
    if window.label() != "startup" {
        return Err("该操作仅供应用启动与设置窗口使用。".into());
    }
    let url = window.url().map_err(|_| "无法核验窗口来源。")?;
    if url.scheme() != "tauri" || url.host_str() != Some("localhost") {
        return Err("窗口来源不受信任。".into());
    }
    Ok(())
}

#[tauri::command]
fn desktop_status(window: WebviewWindow, shared: tauri::State<Shared>) -> Result<AppState, String> {
    startup_only(&window)?;
    Ok(shared.0.lock().unwrap().clone())
}

#[tauri::command]
fn retry_start(window: WebviewWindow, shared: tauri::State<Shared>) -> Result<(), String> {
    startup_only(&window)?;
    let mut state = shared.0.lock().unwrap();
    if !state.can_retry { return Err("应用正在启动或已就绪。".into()); }
    state.can_retry = false;
    shared.1.send(Action::Start).map_err(|_| "桌面管理器已退出。".into())
}

#[tauri::command]
fn owner_code(window: WebviewWindow, app: AppHandle) -> Result<String, String> {
    startup_only(&window)?;
    let data = app.path().app_data_dir().map_err(|_| "无法读取应用目录。")?;
    let bytes = std::fs::read(data.join("installation.json")).map_err(|_| "安装尚未就绪。")?;
    let config: storage::Config = serde_json::from_slice(&bytes).map_err(|_| "安装信息无效。")?;
    let secrets = storage::Secrets::load(&config.installation).map_err(|_| "无法读取本机钥匙串。")?;
    Ok(secrets.owner)
}

#[tauri::command]
fn save_model_key(window: WebviewWindow, app: AppHandle, key: String) -> Result<(), String> {
    startup_only(&window)?;
    let key = key.trim();
    if key.is_empty() || key.len() > 512 || key.chars().any(char::is_control) {
        return Err("请填写有效的 DeepSeek API Key。".into());
    }
    let data = app.path().app_data_dir().map_err(|_| "无法读取应用目录。")?;
    let bytes = std::fs::read(data.join("installation.json")).map_err(|_| "安装尚未就绪。")?;
    let config: storage::Config = serde_json::from_slice(&bytes).map_err(|_| "安装信息无效。")?;
    storage::save_model_key(&config.installation, key)
        .map_err(|_| "无法保存到本机钥匙串。".into())
}

#[tauri::command]
fn open_logs(window: WebviewWindow, app: AppHandle) -> Result<(), String> {
    startup_only(&window)?;
    let logs = app.path().app_log_dir().map_err(|_| "无法读取日志目录。")?;
    std::process::Command::new("/usr/bin/open").arg(logs).spawn()
        .map_err(|_| "无法打开日志目录。")?;
    Ok(())
}

fn show(app: &AppHandle, label: &str) {
    if let Some(window) = app.get_webview_window(label) {
        let _ = window.show();
        let _ = window.set_focus();
    }
}

fn open_window(app: &AppHandle, shared: &Shared, settings: bool) -> Result<(), String> {
    let origin = shared.0.lock().unwrap().origin.clone().ok_or("核心服务尚未就绪。")?;
    if let Some(window) = app.get_webview_window("business") {
        show(app, "business");
        if settings {
            // Keep the intent across the login screen, where the settings component is not mounted.
            window.eval("{const u=new URL(location.href);u.searchParams.set('settings','1');history.replaceState(history.state,'',u);window.dispatchEvent(new Event('decision-open-settings'));}")
                .map_err(|_| "无法打开设置，请使用侧栏的设置入口。")?;
        }
        return Ok(());
    }
    let mut address: tauri::Url = origin.parse().map_err(|_| "本机地址无效。")?;
    if settings { address.set_query(Some("settings=1")); }
    let allowed = address.clone();
    let download_origin = address.clone();
    let downloads = app.path().download_dir().map_err(|_| "无法读取下载目录。")?;
    tauri::WebviewWindowBuilder::new(app, "business", WebviewUrl::External(address))
        .title(format!("{} · 预览版", app.config().product_name.as_deref().unwrap_or("Decision")))
        .inner_size(1180.0, 800.0).min_inner_size(420.0, 520.0)
        // Vue consumes HTML file drag/drop events and performs its own validation.
        .disable_drag_drop_handler()
        .on_navigation(move |url| {
            if url.origin() == allowed.origin() { return true; }
            if matches!(url.scheme(), "http" | "https") {
                let _ = std::process::Command::new("/usr/bin/open").arg(url.as_str()).spawn();
            }
            false
        })
        .on_new_window(|url, _| {
            if matches!(url.scheme(), "http" | "https") {
                let _ = std::process::Command::new("/usr/bin/open").arg(url.as_str()).spawn();
            }
            tauri::webview::NewWindowResponse::Deny
        })
        .on_download(move |_, event| {
            if let tauri::webview::DownloadEvent::Requested { url, destination } = event {
                if url.origin() != download_origin.origin() { return false; }
                let Some(name) = destination.file_name() else { return false; };
                let mut target = downloads.join(name);
                if target.exists() {
                    target = downloads.join(format!("{}-{}", &storage::random()[..8], name.to_string_lossy()));
                }
                *destination = target;
            }
            true
        })
        .build().map_err(|_| "无法创建 Decision 窗口。".to_string())?;
    Ok(())
}

#[tauri::command]
fn open_business(window: WebviewWindow, app: AppHandle, shared: tauri::State<Shared>) -> Result<(), String> {
    startup_only(&window)?;
    open_window(&app, &shared, false)
}

#[tauri::command]
fn open_settings(window: WebviewWindow, app: AppHandle, shared: tauri::State<Shared>) -> Result<(), String> {
    startup_only(&window)?;
    open_window(&app, &shared, true)
}

fn worker(app: AppHandle, shared: Shared, actions: mpsc::Receiver<Action>) {
    let mut running: Option<services::Services> = None;
    loop {
        match actions.recv_timeout(Duration::from_secs(1)) {
            Ok(Action::Quit) | Err(mpsc::RecvTimeoutError::Disconnected) => {
                shared.0.lock().unwrap().message = "正在安全停止本机服务…".into();
                if let Some(mut services) = running.take() {
                    if let Err(error) = services.stop() {
                        let mut state = shared.0.lock().unwrap();
                        state.message = error.to_string();
                        state.ready = false;
                        state.can_retry = false;
                        show(&app, "startup");
                        // Guardians retain ownership and continue attempting orderly shutdown.
                        continue;
                    }
                }
                shared.0.lock().unwrap().stopped = true;
                app.exit(0);
                return;
            }
            Ok(Action::Start) if running.is_none() => {
                {
                    let mut state = shared.0.lock().unwrap();
                    state.message = "正在启动本机服务…".into();
                    state.can_retry = false;
                    state.services.clear();
                }
                let result: Result<services::Services, Box<dyn std::error::Error + Send + Sync>> = (|| {
                    let data = storage::directory(app.path().app_data_dir()?)?;
                    let runtime = app.path().resource_dir()?.join("runtime");
                    let logs = storage::directory(app.path().app_log_dir()?)?;
                    services::Services::start(runtime, data, logs, &shared)
                })();
                match result {
                    Ok(services) => {
                        let degraded;
                        {
                            let mut state = shared.0.lock().unwrap();
                            degraded = state.services.values().any(|value| value.contains("失败"));
                            state.origin = Some(services.origin());
                            state.message = if degraded {
                                "核心已就绪，部分服务不可用；请检查运行状态。"
                            } else {
                                "Decision 已就绪。关窗后继续运行，Cmd-Q 退出。"
                            }.into();
                            state.ready = true;
                        }
                        running = Some(services);
                        match open_window(&app, &shared, false) {
                            Ok(()) => {
                                if !degraded {
                                    if let Some(window) = app.get_webview_window("startup") { let _ = window.hide(); }
                                } else { show(&app, "startup"); }
                            }
                            Err(error) => {
                                shared.0.lock().unwrap().message = error;
                                show(&app, "startup");
                            }
                        }
                    }
                    Err(error) => failed(&app, &shared, &error.to_string()),
                }
            }
            _ => {}
        }
        if let Some(services) = running.as_mut() {
            if let Err(error) = services.monitor(&shared) {
                running.take();
                failed(&app, &shared, &error.to_string());
            }
        }
    }
}

fn failed(app: &AppHandle, shared: &Shared, message: &str) {
    let mut state = shared.0.lock().unwrap();
    state.message = message.into();
    state.ready = false;
    state.origin = None;
    state.can_retry = true;
    for value in state.services.values_mut() { *value = "已停止".into(); }
    drop(state);
    show(app, "startup");
}

fn main() {
    if std::env::args().nth(1).as_deref() == Some("--service-guard") {
        let code = guardian::run().unwrap_or(1);
        std::process::exit(code);
    }
    let (sender, receiver) = mpsc::channel();
    let shared = Shared(Arc::new(Mutex::new(AppState {
        message: "正在准备应用…".into(), services: BTreeMap::new(), ready: false,
        can_retry: false, origin: None, stopped: false,
    })), sender, Arc::new(AtomicBool::new(false)));
    let managed = shared.clone();
    let app = tauri::Builder::default()
        .plugin(tauri_plugin_single_instance::init(|app, _, _| {
            let shared = app.state::<Shared>();
            show(app, if shared.0.lock().unwrap().ready { "business" } else { "startup" });
            trace_window(app, "second_instance_reopened");
        }))
        .manage(shared.clone())
        .invoke_handler(tauri::generate_handler![desktop_status, retry_start, owner_code, save_model_key, open_business, open_settings, open_logs])
        .setup(move |app| {
            use tauri::menu::{Menu, MenuItem, PredefinedMenuItem, Submenu};
            let settings = MenuItem::with_id(app, "settings", "设置…", true, Some("CmdOrCtrl+,"))?;
            let status = MenuItem::with_id(app, "status", "运行状态与诊断", true, None::<&str>)?;
            let quit = PredefinedMenuItem::quit(app, None)?;
            let submenu = Submenu::with_items(app, app.config().product_name.as_deref().unwrap_or("Decision"), true, &[&settings, &status, &quit])?;
            let edit = Submenu::with_items(app, "编辑", true, &[
                &PredefinedMenuItem::undo(app, None)?, &PredefinedMenuItem::redo(app, None)?,
                &PredefinedMenuItem::cut(app, None)?, &PredefinedMenuItem::copy(app, None)?,
                &PredefinedMenuItem::paste(app, None)?, &PredefinedMenuItem::select_all(app, None)?,
            ])?;
            let reload = MenuItem::with_id(app, "reload", "重新载入页面", true, Some("CmdOrCtrl+R"))?;
            let view = Submenu::with_items(app, "显示", true, &[&reload])?;
            app.set_menu(Menu::with_items(app, &[&submenu, &edit, &view])?)?;
            let handle = app.handle().clone();
            std::thread::spawn(move || worker(handle, managed, receiver));
            app.state::<Shared>().1.send(Action::Start)?;
            Ok(())
        })
        .on_menu_event(|app, event| {
            if event.id().as_ref() == "settings" {
                let shared = app.state::<Shared>();
                if open_window(app, &shared, true).is_err() { show(app, "startup"); }
            }
            if event.id().as_ref() == "status" { show(app, "startup"); }
            if event.id().as_ref() == "reload" {
                if let Some(window) = app.get_webview_window("business") {
                    if window.reload().is_ok() { trace_window(app, "business_reloaded"); }
                }
            }
        })
        .on_window_event(|window, event| {
            if let tauri::WindowEvent::CloseRequested { api, .. } = event {
                api.prevent_close();
                if window.hide().is_ok() {
                    trace_window(window.app_handle(), &format!("window_hidden label={} visible={}",
                        window.label(), window.is_visible().unwrap_or(true)));
                }
            }
        })
        .build(tauri::generate_context!())
        .expect("Decision could not initialize its desktop window");
    app.run(move |app, event| match event {
        tauri::RunEvent::ExitRequested { api, .. } => {
            if !shared.0.lock().unwrap().stopped {
                api.prevent_exit();
                shared.2.store(true, Ordering::Release);
                let _ = shared.1.send(Action::Quit);
            }
        }
        tauri::RunEvent::Reopen { .. } => {
            show(app, if shared.0.lock().unwrap().ready { "business" } else { "startup" });
            trace_window(app, "application_reopened");
        }
        _ => {}
    });
}
