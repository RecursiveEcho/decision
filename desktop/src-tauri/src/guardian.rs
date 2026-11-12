// Each guardian owns one service process group. Its input pipe closes if the app dies.
use std::io::{self, Read, Write};
use std::os::unix::process::CommandExt;
use std::process::{Command, Stdio};
use std::sync::mpsc;
use std::time::{Duration, Instant};

pub fn run() -> Result<i32, Box<dyn std::error::Error>> {
    let args: Vec<String> = serde_json::from_str(&std::env::var("DECISION_SERVICE_ARGS")?)?;
    let (program, arguments) = args.split_first().ok_or("Missing service executable")?;
    let postgres = std::env::var("DECISION_SERVICE_KIND")? == "postgres";
    let mut command = Command::new(program);
    let input = std::env::var("DECISION_SERVICE_INPUT").ok();
    command.args(arguments).stdin(if input.is_some() { Stdio::piped() } else { Stdio::null() });
    command.env_remove("DECISION_SERVICE_ARGS").env_remove("DECISION_SERVICE_KIND")
        .env_remove("DECISION_SERVICE_INPUT");
    unsafe { command.pre_exec(|| { if libc::setpgid(0, 0) < 0 { return Err(io::Error::last_os_error()); } Ok(()) }); }
    let mut child = command.spawn()?;
    let pid = child.id() as i32;
    if let Some(input) = input {
        if child.stdin.take().ok_or("Missing helper input")?.write_all(input.as_bytes()).is_err() {
            unsafe { libc::kill(-pid, libc::SIGKILL); }
            child.wait()?;
            return Ok(1);
        }
    }
    let (closed, receiver) = mpsc::channel();
    std::thread::spawn(move || {
        let mut byte = [0u8; 1];
        while io::stdin().read(&mut byte).is_ok_and(|read| read > 0) {}
        let _ = closed.send(());
    });
    let mut status = None;
    let requested;
    loop {
        if receiver.recv_timeout(Duration::from_millis(200)).is_ok() { requested = true; break; }
        if let Some(exit) = child.try_wait()? { status = Some(exit); requested = false; break; }
    }
    // SIGINT requests PostgreSQL's fast, consistent shutdown through its postmaster.
    unsafe { libc::kill(if postgres { pid } else { -pid }, if postgres { libc::SIGINT } else { libc::SIGTERM }); }
    let deadline = Instant::now() + Duration::from_secs(15);
    while Instant::now() < deadline {
        if status.is_none() { status = child.try_wait()?; }
        let group_alive = unsafe { libc::kill(-pid, 0) == 0 };
        if status.is_some() && !group_alive { return Ok(if requested { 0 } else { status.unwrap().code().unwrap_or(1) }); }
        std::thread::sleep(Duration::from_millis(100));
    }
    unsafe { libc::kill(-pid, libc::SIGKILL); }
    if status.is_none() { child.wait()?; }
    let deadline = Instant::now() + Duration::from_secs(3);
    while Instant::now() < deadline && unsafe { libc::kill(-pid, 0) == 0 } {
        std::thread::sleep(Duration::from_millis(100));
    }
    eprintln!("Decision service required forced shutdown; inspect its service log.");
    Ok(2)
}
