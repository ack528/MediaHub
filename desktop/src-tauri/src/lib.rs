//! MediaHub 桌面管理程序的 Rust 后端。
//!
//! 职责:
//! - 读写服务端的 `config.json`(配置的唯一来源);
//! - 启动 / 停止 / 重启 `mediahub.exe serve`(与本程序解耦,关闭管理界面服务照常运行);
//! - 通过"本机管理密钥"(`<dataDir>\admin.key` + `X-Admin-Key`,仅回环地址有效)查询状态;
//! - 通过 `mediahub.exe user ...` 管理账号;读取日志;托盘。

use std::{
    fs,
    io::Write,
    os::windows::process::CommandExt,
    path::{Path, PathBuf},
    process::{Command, Stdio},
    time::Duration,
};

use serde_json::{json, Value};
use tauri::{
    menu::{Menu, MenuItem},
    tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent},
    AppHandle, Manager, WindowEvent,
};

const CREATE_NO_WINDOW: u32 = 0x0800_0000;
const CREATE_NEW_PROCESS_GROUP: u32 = 0x0000_0200;
const DEFAULT_DATA_DIR: &str = r"C:\MediaHub\data";

// ---------------------------------------------------------------- 路径

/// 安装根目录:优先环境变量 MEDIAHUB_ROOT,其次从 exe 向上找 `runtime\bin\mediahub.exe`。
fn root_dir() -> PathBuf {
    if let Ok(r) = std::env::var("MEDIAHUB_ROOT") {
        if !r.is_empty() {
            return PathBuf::from(r);
        }
    }
    if let Ok(exe) = std::env::current_exe() {
        let mut p = exe.parent().map(|x| x.to_path_buf());
        while let Some(d) = p {
            if d.join("runtime").join("bin").join("mediahub.exe").exists() || d.join("mediahub.exe").exists() {
                return d;
            }
            p = d.parent().map(|x| x.to_path_buf());
        }
    }
    std::env::current_dir().unwrap_or_default()
}

fn server_exe() -> PathBuf {
    let r = root_dir();
    let a = r.join("runtime").join("bin").join("mediahub.exe");
    if a.exists() {
        a
    } else {
        r.join("mediahub.exe")
    }
}

fn config_path() -> PathBuf {
    root_dir().join("runtime").join("mediahub").join("config.json")
}

fn read_json(path: &Path) -> Value {
    fs::read_to_string(path)
        .ok()
        .map(|s| s.trim_start_matches('\u{feff}').to_string())
        .and_then(|s| serde_json::from_str(&s).ok())
        .unwrap_or_else(|| json!({}))
}

fn listen_port(cfg: &Value) -> u16 {
    cfg["listen"]
        .as_str()
        .and_then(|s| s.rsplit(':').next())
        .and_then(|p| p.parse().ok())
        .unwrap_or(8480)
}

fn data_dir(cfg: &Value) -> PathBuf {
    PathBuf::from(cfg["dataDir"].as_str().filter(|s| !s.is_empty()).unwrap_or(DEFAULT_DATA_DIR))
}

fn admin_key(cfg: &Value) -> Option<String> {
    fs::read_to_string(data_dir(cfg).join("admin.key")).ok().map(|s| s.trim().to_string()).filter(|s| !s.is_empty())
}

// ---------------------------------------------------------------- HTTP / 进程

fn agent(timeout_ms: u64) -> ureq::Agent {
    let cfg = ureq::Agent::config_builder()
        .timeout_global(Some(Duration::from_millis(timeout_ms)))
        .build();
    ureq::Agent::new_with_config(cfg)
}

fn http_get(url: &str, key: Option<&str>, timeout_ms: u64) -> Result<Value, String> {
    let mut req = agent(timeout_ms).get(url);
    if let Some(k) = key {
        req = req.header("X-Admin-Key", k);
    }
    let mut resp = req.call().map_err(|e| e.to_string())?;
    let body = resp.body_mut().read_to_string().map_err(|e| e.to_string())?;
    serde_json::from_str(&body).map_err(|e| e.to_string())
}

fn http_post_empty(url: &str, key: &str) -> Result<(), String> {
    agent(3000)
        .post(url)
        .header("X-Admin-Key", key)
        .send_empty()
        .map(|_| ())
        .map_err(|e| e.to_string())
}

fn base_url(cfg: &Value) -> String {
    format!("http://127.0.0.1:{}", listen_port(cfg))
}

fn ping(cfg: &Value) -> Option<Value> {
    http_get(&format!("{}/api/v1/server/info", base_url(cfg)), None, 800).ok()
}

fn find_pids() -> Vec<u32> {
    let out = Command::new("tasklist")
        .args(["/FI", "IMAGENAME eq mediahub.exe", "/FO", "CSV", "/NH"])
        .creation_flags(CREATE_NO_WINDOW)
        .output();
    let Ok(out) = out else { return vec![] };
    String::from_utf8_lossy(&out.stdout)
        .lines()
        .filter(|l| l.starts_with("\"mediahub.exe\""))
        .filter_map(|l| l.split("\",\"").nth(1).and_then(|p| p.trim_matches('"').parse().ok()))
        .collect()
}

fn wait_for(cfg: &Value, up: bool, secs: u64) -> bool {
    for _ in 0..(secs * 4) {
        if ping(cfg).is_some() == up {
            return true;
        }
        std::thread::sleep(Duration::from_millis(250));
    }
    false
}

async fn blocking<T: Send + 'static>(f: impl FnOnce() -> T + Send + 'static) -> T {
    tauri::async_runtime::spawn_blocking(f).await.expect("blocking task panicked")
}

// ---------------------------------------------------------------- 命令:环境与配置

#[tauri::command]
fn get_env() -> Value {
    let exe = server_exe();
    let cfg = config_path();
    json!({
        "root": root_dir(),
        "serverExe": exe,
        "serverExists": exe.exists(),
        "appVersion": env!("CARGO_PKG_VERSION"),
        "configPath": cfg,
        "configExists": cfg.exists(),
    })
}

#[tauri::command]
fn read_config() -> Value {
    read_json(&config_path())
}

#[tauri::command]
fn write_config(cfg: Value) -> Result<(), String> {
    if !cfg.is_object() {
        return Err("配置必须是 JSON 对象".into());
    }
    let path = config_path();
    if let Some(dir) = path.parent() {
        fs::create_dir_all(dir).map_err(|e| e.to_string())?;
    }
    if path.exists() {
        let _ = fs::copy(&path, path.with_extension("json.bak"));
    }
    let tmp = path.with_extension("json.tmp");
    let text = serde_json::to_string_pretty(&cfg).map_err(|e| e.to_string())?;
    fs::write(&tmp, text).map_err(|e| e.to_string())?;
    fs::rename(&tmp, &path).map_err(|e| e.to_string())
}

#[tauri::command]
fn local_ips() -> Vec<Value> {
    let mut out: Vec<Value> = vec![];
    if let Ok(list) = local_ip_address::list_afinet_netifas() {
        for (name, ip) in list {
            if let std::net::IpAddr::V4(v4) = ip {
                if v4.is_loopback() || v4.is_link_local() || v4.is_unspecified() {
                    continue;
                }
                // 198.18.0.0/15 是代理软件(Clash TUN 等)的虚拟网卡,手机不可能通过它访问
                let o = v4.octets();
                if o[0] == 198 && (o[1] == 18 || o[1] == 19) {
                    continue;
                }
                out.push(json!({ "name": name, "ip": v4.to_string(), "private": v4.is_private() }));
            }
        }
    }
    // 私有网段排在前面
    out.sort_by_key(|v| !v["private"].as_bool().unwrap_or(false));
    out
}

// ---------------------------------------------------------------- 命令:服务控制与状态

#[tauri::command]
async fn service_status() -> Value {
    blocking(|| {
        let cfg = read_json(&config_path());
        let info = ping(&cfg);
        let status = if info.is_some() {
            admin_key(&cfg).and_then(|k| {
                http_get(&format!("{}/api/v1/admin/status", base_url(&cfg)), Some(&k), 3000).ok()
            })
        } else {
            None
        };
        json!({ "running": info.is_some(), "info": info, "status": status, "pids": find_pids() })
    })
    .await
}

#[tauri::command]
async fn service_start() -> Result<(), String> {
    blocking(|| {
        let cfg = read_json(&config_path());
        if ping(&cfg).is_some() {
            return Ok(());
        }
        let exe = server_exe();
        if !exe.exists() {
            return Err(format!("找不到服务程序:{}", exe.display()));
        }
        Command::new(&exe)
            .arg("serve")
            .arg("-config")
            .arg(config_path())
            .env("MEDIAHUB_ROOT", root_dir())
            .current_dir(root_dir())
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            // 与管理程序脱离(新进程组),关闭管理界面后服务继续运行。
            // 不能用 DETACHED_PROCESS:无控制台的进程启动 ffmpeg / ExifTool 时,Windows 会为每个子进程新建一个可见的 cmd 窗口;
            // 只用 CREATE_NO_WINDOW,服务端带一个隐藏的控制台,子进程继承它,不再弹窗。
            .creation_flags(CREATE_NO_WINDOW | CREATE_NEW_PROCESS_GROUP)
            .spawn()
            .map_err(|e| format!("启动失败:{e}"))?;
        if wait_for(&cfg, true, 12) {
            Ok(())
        } else {
            Err("服务在 12 秒内没有响应,请查看日志".into())
        }
    })
    .await
}

#[tauri::command]
async fn service_stop() -> Result<(), String> {
    blocking(|| {
        let cfg = read_json(&config_path());
        let _ = Command::new("taskkill")
            .args(["/F", "/IM", "mediahub.exe"])
            .creation_flags(CREATE_NO_WINDOW)
            .output();
        if wait_for(&cfg, false, 8) {
            Ok(())
        } else {
            Err("服务没有停止".into())
        }
    })
    .await
}

#[tauri::command]
async fn admin_rescan() -> Result<(), String> {
    blocking(|| {
        let cfg = read_json(&config_path());
        let key = admin_key(&cfg).ok_or("找不到管理密钥(服务未运行?)")?;
        http_post_empty(&format!("{}/api/v1/admin/rescan", base_url(&cfg)), &key)
    })
    .await
}

// ---------------------------------------------------------------- 命令:账号(调用服务程序的 CLI)

fn run_cli(args: &[&str], stdin: Option<&str>) -> Result<String, String> {
    let exe = server_exe();
    if !exe.exists() {
        return Err(format!("找不到服务程序:{}", exe.display()));
    }
    let cfg = config_path();
    let mut cmd = Command::new(exe);
    cmd.args(args)
        .arg("-config")
        .arg(&cfg)
        .env("MEDIAHUB_ROOT", root_dir())
        .stdin(if stdin.is_some() { Stdio::piped() } else { Stdio::null() })
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .creation_flags(CREATE_NO_WINDOW);
    let mut child = cmd.spawn().map_err(|e| e.to_string())?;
    if let (Some(text), Some(mut si)) = (stdin, child.stdin.take()) {
        let _ = si.write_all(text.as_bytes());
        let _ = si.write_all(b"\n");
    }
    let out = child.wait_with_output().map_err(|e| e.to_string())?;
    if out.status.success() {
        Ok(String::from_utf8_lossy(&out.stdout).to_string())
    } else {
        // 服务程序把"密码: "提示和"失败: 原因"写在 stderr 同一行里,只取原因
        let err = String::from_utf8_lossy(&out.stderr).trim().to_string();
        let last = err.rsplit('\n').next().unwrap_or("操作失败").trim();
        let reason = last.rsplit("失败:").next().unwrap_or(last).trim();
        Err(if reason.is_empty() { "操作失败".to_string() } else { reason.to_string() })
    }
}

#[tauri::command]
async fn list_users() -> Result<Value, String> {
    blocking(|| {
        let text = run_cli(&["user", "list"], None)?;
        serde_json::from_str(text.trim()).map_err(|e| e.to_string())
    })
    .await
}

#[tauri::command]
async fn add_user(name: String, password: String) -> Result<(), String> {
    if name.trim().is_empty() || password.chars().count() < 4 {
        return Err("用户名不能为空,密码至少 4 位".into());
    }
    blocking(move || run_cli(&["user", "add", name.trim()], Some(&password)).map(|_| ())).await
}

#[tauri::command]
async fn set_password(name: String, password: String) -> Result<(), String> {
    if password.chars().count() < 4 {
        return Err("密码至少 4 位".into());
    }
    blocking(move || run_cli(&["user", "passwd", &name], Some(&password)).map(|_| ())).await
}

#[tauri::command]
async fn delete_user(name: String) -> Result<(), String> {
    blocking(move || run_cli(&["user", "delete", &name], None).map(|_| ())).await
}

// ---------------------------------------------------------------- 命令:日志

#[tauri::command]
async fn read_log(lines: usize) -> Result<String, String> {
    blocking(move || {
        let cfg = read_json(&config_path());
        let dir = data_dir(&cfg).join("logs");
        let newest = fs::read_dir(&dir)
            .map_err(|_| format!("没有日志目录:{}", dir.display()))?
            .filter_map(|e| e.ok())
            .filter(|e| e.path().extension().map(|x| x == "log").unwrap_or(false))
            .max_by_key(|e| e.metadata().and_then(|m| m.modified()).ok());
        let Some(f) = newest else { return Ok(String::new()) };
        let bytes = fs::read(f.path()).map_err(|e| e.to_string())?;
        let tail = if bytes.len() > 512 * 1024 { &bytes[bytes.len() - 512 * 1024..] } else { &bytes[..] };
        let text = String::from_utf8_lossy(tail);
        let all: Vec<&str> = text.lines().collect();
        let n = lines.clamp(1, 2000);
        Ok(all[all.len().saturating_sub(n)..].join("\n"))
    })
    .await
}

// ---------------------------------------------------------------- 窗口与托盘

fn show_main(app: &AppHandle) {
    if let Some(w) = app.get_webview_window("main") {
        let _ = w.show();
        let _ = w.unminimize();
        let _ = w.set_focus();
    }
}

pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_dialog::init())
        .plugin(tauri_plugin_opener::init())
        .invoke_handler(tauri::generate_handler![
            get_env,
            read_config,
            write_config,
            local_ips,
            service_status,
            service_start,
            service_stop,
            admin_rescan,
            list_users,
            add_user,
            set_password,
            delete_user,
            read_log,
        ])
        .setup(|app| {
            let show = MenuItem::with_id(app, "show", "显示窗口", true, None::<&str>)?;
            let quit = MenuItem::with_id(app, "quit", "退出管理程序(服务继续运行)", true, None::<&str>)?;
            let menu = Menu::with_items(app, &[&show, &quit])?;
            let mut tray = TrayIconBuilder::new()
                .menu(&menu)
                .show_menu_on_left_click(false)
                .tooltip("MediaHub")
                .on_menu_event(|app, ev| match ev.id.as_ref() {
                    "show" => show_main(app),
                    "quit" => app.exit(0),
                    _ => {}
                })
                .on_tray_icon_event(|tray, ev| {
                    if let TrayIconEvent::Click { button: MouseButton::Left, button_state: MouseButtonState::Up, .. } = ev {
                        show_main(tray.app_handle());
                    }
                });
            if let Some(icon) = app.default_window_icon() {
                tray = tray.icon(icon.clone());
            }
            tray.build(app)?;
            Ok(())
        })
        // 点关闭只是隐藏到托盘;服务本来就是独立进程
        .on_window_event(|window, event| {
            if let WindowEvent::CloseRequested { api, .. } = event {
                api.prevent_close();
                let _ = window.hide();
            }
        })
        .run(tauri::generate_context!())
        .expect("启动 MediaHub 管理程序失败");
}
