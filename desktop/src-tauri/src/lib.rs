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
    menu::{Menu, MenuItem, PredefinedMenuItem},
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

/// 旧位置:程序目录里。更新安装包 / 换一个便携版文件夹时会跟着丢。
fn legacy_config_path() -> PathBuf {
    root_dir().join("runtime").join("mediahub").join("config.json")
}

/// 在其它可能的旧位置里找最近用过的配置(便携版放在不同文件夹、安装版的安装目录)。
fn find_old_config() -> Option<PathBuf> {
    let mut cands = vec![legacy_config_path()];
    let rel = Path::new("runtime").join("mediahub").join("config.json");
    if let Some(parent) = root_dir().parent() {
        if let Ok(rd) = fs::read_dir(parent) {
            for e in rd.flatten() {
                if e.file_name().to_string_lossy().to_lowercase().starts_with("mediahub") {
                    cands.push(e.path().join(&rel));
                }
            }
        }
    }
    if let Ok(la) = std::env::var("LOCALAPPDATA") {
        for d in ["MediaHub", r"Programs\MediaHub"] {
            cands.push(Path::new(&la).join(d).join(&rel));
        }
    }
    cands
        .into_iter()
        .filter(|p| p.exists())
        .max_by_key(|p| fs::metadata(p).and_then(|m| m.modified()).ok())
}

/// 配置文件放在程序目录之外的固定位置(默认 C:\MediaHub\config.json,和数据目录同级),
/// 更新服务端 / 换便携版文件夹都不会丢媒体根目录等设置。第一次使用时把旧位置的配置搬过来。
/// 开发环境(设置了 MEDIAHUB_ROOT)仍用项目里的 runtime\mediahub\config.json。
fn config_path() -> PathBuf {
    if std::env::var("MEDIAHUB_ROOT").map(|v| !v.is_empty()).unwrap_or(false) {
        return legacy_config_path();
    }
    let p = Path::new(DEFAULT_DATA_DIR).parent().map(|d| d.join("config.json")).unwrap_or_else(legacy_config_path);
    if !p.exists() {
        if let Some(old) = find_old_config() {
            if let Some(dir) = p.parent() {
                let _ = fs::create_dir_all(dir);
            }
            let _ = fs::copy(&old, &p);
        }
    }
    p
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

fn http_post_json(url: &str, key: &str, body: &str) -> Result<(), String> {
    agent(3000)
        .post(url)
        .header("X-Admin-Key", key)
        .header("Content-Type", "application/json")
        .send(body)
        .map(|_| ())
        .map_err(|e| e.to_string())
}

/// 桌面管理程序访问服务的地址:开启 TLS(默认)时对外端口是 HTTPS 自签名证书,管理程序改走仅本机的明文端口
/// (配置 adminListen,留空 = 监听端口 + 1,只监听回环地址);关闭 TLS 时就是监听端口本身。
fn base_url(cfg: &Value) -> String {
    let tls = cfg["tls"]["enabled"].as_bool().unwrap_or(true);
    if !tls {
        return format!("http://127.0.0.1:{}", listen_port(cfg));
    }
    if let Some(a) = cfg["adminListen"].as_str().filter(|s| !s.is_empty()) {
        return format!("http://{a}");
    }
    let p = listen_port(cfg);
    format!("http://127.0.0.1:{}", if p >= 65535 { 65534 } else { p + 1 })
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

fn start_service_blocking() -> Result<(), String> {
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
}

fn stop_service_blocking() -> Result<(), String> {
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
}

/// root_id = None 重新扫描全部;Some(id) 只扫描这一个盘(根目录)。
fn rescan_blocking(root_id: Option<i64>) -> Result<(), String> {
    let cfg = read_json(&config_path());
    let key = admin_key(&cfg).ok_or("找不到管理密钥(服务未运行?)")?;
    let url = format!("{}/api/v1/admin/rescan", base_url(&cfg));
    match root_id {
        Some(id) => http_post_json(&url, &key, &format!("{{\"rootId\":\"{id}\"}}")),
        None => http_post_empty(&url, &key),
    }
}

#[tauri::command]
async fn service_start() -> Result<(), String> {
    blocking(start_service_blocking).await
}

#[tauri::command]
async fn service_stop() -> Result<(), String> {
    blocking(stop_service_blocking).await
}

#[tauri::command]
async fn admin_rescan(root_id: Option<i64>) -> Result<(), String> {
    blocking(move || rescan_blocking(root_id)).await
}

// ---------------------------------------------------------------- 命令:软件更新(服务端每 10 分钟自动检查 GitHub Releases,这里只是显示状态 / 手动触发)

/// 服务端的更新状态(服务没运行时返回 state = "offline")。
#[tauri::command]
async fn update_status() -> Value {
    blocking(|| {
        let cfg = read_json(&config_path());
        let Some(key) = admin_key(&cfg) else { return json!({ "state": "offline", "message": "服务没有运行" }) };
        http_get(&format!("{}/api/v1/admin/update", base_url(&cfg)), Some(&key), 3000)
            .unwrap_or_else(|e| json!({ "state": "offline", "message": e }))
    })
    .await
}

/// kind = "check":立即检查;"apply":立即检查并安装(有新版本就下载、替换、重启服务)。
#[tauri::command]
async fn update_action(kind: String) -> Result<(), String> {
    blocking(move || {
        let cfg = read_json(&config_path());
        let key = admin_key(&cfg).ok_or("找不到管理密钥(服务未运行?)")?;
        let path = if kind == "apply" { "apply" } else { "check" };
        http_post_empty(&format!("{}/api/v1/admin/update/{}", base_url(&cfg), path), &key)
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

// ---------------------------------------------------------------- 命令:打开文件夹 / 文件

/// 用资源管理器打开文件夹(或用默认程序打开文件)。
/// 以前走 opener 插件的 openPath,但它需要在权限里逐个声明允许的路径范围,没声明的路径会被拒绝,
/// 前端又没处理这个错误 —— 点"打开日志文件夹"就没有任何反应。
#[tauri::command]
async fn open_path(path: String) -> Result<(), String> {
    blocking(move || {
        let p = PathBuf::from(&path);
        if !p.exists() {
            // 日志目录等还没生成时先建出来,否则资源管理器会退回到"文档"
            if p.extension().is_none() {
                fs::create_dir_all(&p).map_err(|e| format!("无法创建 {}:{}", p.display(), e))?;
            } else {
                return Err(format!("文件不存在:{}", p.display()));
            }
        }
        Command::new("explorer.exe").arg(&p).spawn().map(|_| ()).map_err(|e| format!("打开失败:{}", e))
    })
    .await
}

// ---------------------------------------------------------------- 命令:日志

fn log_dir() -> PathBuf {
    data_dir(&read_json(&config_path())).join("logs")
}

fn line_rank(l: &str) -> u8 {
    if l.contains(" level=ERROR") {
        4
    } else if l.contains(" level=WARN") {
        3
    } else if l.contains(" level=INFO") {
        2
    } else {
        1
    }
}

/// 读日志文件(服务停止时也能看)。level:空 = 全部;debug/info/warn/error = 该级别及以上。
/// 当天的日志不够 n 行时,往前补前面的日志文件。
#[tauri::command]
async fn read_log(lines: usize, level: Option<String>) -> Result<String, String> {
    blocking(move || {
        let dir = log_dir();
        let mut files: Vec<_> = fs::read_dir(&dir)
            .map_err(|_| format!("没有日志目录:{}", dir.display()))?
            .filter_map(|e| e.ok())
            .filter(|e| {
                let n = e.file_name().to_string_lossy().to_string();
                n.starts_with("mediahub-") && n.ends_with(".log")
            })
            .collect();
        files.sort_by_key(|e| e.metadata().and_then(|m| m.modified()).ok());
        let min = match level.as_deref().map(|s| s.to_lowercase()).as_deref() {
            Some("debug") => 1,
            Some("info") => 2,
            Some("warn") => 3,
            Some("error") => 4,
            _ => 0,
        };
        let n = lines.clamp(1, 5000);
        let mut out: Vec<String> = vec![];
        for f in files.iter().rev().take(4) {
            let bytes = fs::read(f.path()).map_err(|e| e.to_string())?;
            let tail = if bytes.len() > 2 * 1024 * 1024 { &bytes[bytes.len() - 2 * 1024 * 1024..] } else { &bytes[..] };
            let text = String::from_utf8_lossy(tail);
            let mut part: Vec<String> = text.lines().filter(|l| min == 0 || line_rank(l) >= min).map(|l| l.to_string()).collect();
            part.append(&mut out);
            out = part;
            if out.len() >= n {
                break;
            }
        }
        Ok(out[out.len().saturating_sub(n)..].join("\n"))
    })
    .await
}

/// 崩溃报告列表(新 → 旧):文件名、大小、修改时间(unix 秒)
#[tauri::command]
fn list_crashes() -> Vec<Value> {
    let mut v: Vec<(String, u64, u64)> = fs::read_dir(log_dir())
        .map(|rd| {
            rd.filter_map(|e| e.ok())
                .filter(|e| e.file_name().to_string_lossy().starts_with("crash-"))
                .map(|e| {
                    let m = e.metadata().ok();
                    let t = m
                        .as_ref()
                        .and_then(|m| m.modified().ok())
                        .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
                        .map(|d| d.as_secs())
                        .unwrap_or(0);
                    (e.file_name().to_string_lossy().to_string(), m.map(|m| m.len()).unwrap_or(0), t)
                })
                .collect()
        })
        .unwrap_or_default();
    v.sort_by(|a, b| b.0.cmp(&a.0));
    v.into_iter().map(|(n, size, t)| json!({ "name": n, "size": size, "time": t })).collect()
}

/// 把日志目录(日志 + 崩溃报告 + stderr.log)打成 zip,返回 zip 路径。服务没运行也能导出;不含密码、令牌和 API 密钥。
#[tauri::command]
async fn export_logs() -> Result<String, String> {
    blocking(|| {
        let dir = log_dir();
        if !dir.exists() {
            return Err(format!("没有日志目录:{}", dir.display()));
        }
        let out_dir = root_dir().join("runtime").join("exports");
        fs::create_dir_all(&out_dir).map_err(|e| e.to_string())?;
        let stamp = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_secs())
            .unwrap_or(0);
        let zip = out_dir.join(format!("mediahub-logs-{stamp}.zip"));
        // Windows 自带的 tar(bsdtar)可以直接打 zip
        let st = Command::new("tar")
            .arg("-a")
            .arg("-c")
            .arg("-f")
            .arg(&zip)
            .arg("-C")
            .arg(&dir)
            .arg(".")
            .creation_flags(CREATE_NO_WINDOW)
            .output()
            .map_err(|e| format!("打包失败:{e}"))?;
        if !st.status.success() || !zip.exists() {
            return Err(format!("打包失败:{}", String::from_utf8_lossy(&st.stderr).trim()));
        }
        Ok(zip.to_string_lossy().to_string())
    })
    .await
}

// ---------------------------------------------------------------- 命令:开机自启 / 防火墙

const RUN_KEY: &str = r"HKCU\Software\Microsoft\Windows\CurrentVersion\Run";
const RUN_NAME: &str = "MediaHubManager";

#[tauri::command]
async fn autostart_get() -> bool {
    blocking(|| {
        Command::new("reg")
            .args(["query", RUN_KEY, "/v", RUN_NAME])
            .creation_flags(CREATE_NO_WINDOW)
            .output()
            .map(|o| o.status.success())
            .unwrap_or(false)
    })
    .await
}

/// 开机自启:登录 Windows 后以"最小化到托盘"的方式启动管理程序(配置里勾选"启动管理程序时自动启动服务"后,服务也随之启动)。
#[tauri::command]
async fn autostart_set(enable: bool) -> Result<(), String> {
    blocking(move || {
        let out = if enable {
            let exe = std::env::current_exe().map_err(|e| e.to_string())?;
            let val = format!("\"{}\" --minimized", exe.display());
            Command::new("reg")
                .args(["add", RUN_KEY, "/v", RUN_NAME, "/t", "REG_SZ", "/d", &val, "/f"])
                .creation_flags(CREATE_NO_WINDOW)
                .output()
        } else {
            Command::new("reg").args(["delete", RUN_KEY, "/v", RUN_NAME, "/f"]).creation_flags(CREATE_NO_WINDOW).output()
        }
        .map_err(|e| e.to_string())?;
        if out.status.success() || !enable {
            Ok(())
        } else {
            Err(format!("写入注册表失败:{}", String::from_utf8_lossy(&out.stderr).trim()))
        }
    })
    .await
}

#[tauri::command]
async fn firewall_status() -> bool {
    blocking(|| {
        Command::new("netsh")
            .args(["advfirewall", "firewall", "show", "rule", "name=MediaHub"])
            .creation_flags(CREATE_NO_WINDOW)
            .output()
            .map(|o| o.status.success())
            .unwrap_or(false)
    })
    .await
}

/// 放行端口(只对"专用网络"):需要管理员权限,会弹出 Windows 的 UAC 确认框。
#[tauri::command]
async fn firewall_add(port: u16) -> Result<(), String> {
    blocking(move || {
        let args = format!(
            "advfirewall firewall add rule name=MediaHub dir=in action=allow protocol=TCP localport={port} profile=private"
        );
        let ps = format!("Start-Process -FilePath netsh -ArgumentList '{args}' -Verb RunAs -WindowStyle Hidden -Wait");
        let out = Command::new("powershell")
            .args(["-NoProfile", "-Command", &ps])
            .creation_flags(CREATE_NO_WINDOW)
            .output()
            .map_err(|e| e.to_string())?;
        if !out.status.success() {
            return Err("没有获得管理员权限,已取消".into());
        }
        let ok = Command::new("netsh")
            .args(["advfirewall", "firewall", "show", "rule", "name=MediaHub"])
            .creation_flags(CREATE_NO_WINDOW)
            .output()
            .map(|o| o.status.success())
            .unwrap_or(false);
        if ok {
            Ok(())
        } else {
            Err("防火墙规则没有创建成功".into())
        }
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
            list_crashes,
            export_logs,
            autostart_get,
            autostart_set,
            firewall_status,
            firewall_add,
            open_path,
            update_status,
            update_action,
        ])
        .setup(|app| {
            let show = MenuItem::with_id(app, "show", "显示窗口", true, None::<&str>)?;
            let start = MenuItem::with_id(app, "start", "启动服务", true, None::<&str>)?;
            let stop = MenuItem::with_id(app, "stop", "停止服务", true, None::<&str>)?;
            let rescan = MenuItem::with_id(app, "rescan", "重新扫描媒体库", true, None::<&str>)?;
            let quit = MenuItem::with_id(app, "quit", "退出管理程序(服务继续运行)", true, None::<&str>)?;
            let sep1 = PredefinedMenuItem::separator(app)?;
            let sep2 = PredefinedMenuItem::separator(app)?;
            let menu = Menu::with_items(app, &[&show, &sep1, &start, &stop, &rescan, &sep2, &quit])?;
            let mut tray = TrayIconBuilder::new()
                .menu(&menu)
                .show_menu_on_left_click(false)
                .tooltip("MediaHub")
                .on_menu_event(|app, ev| match ev.id.as_ref() {
                    "show" => show_main(app),
                    "start" => {
                        tauri::async_runtime::spawn_blocking(|| {
                            let _ = start_service_blocking();
                        });
                    }
                    "stop" => {
                        tauri::async_runtime::spawn_blocking(|| {
                            let _ = stop_service_blocking();
                        });
                    }
                    "rescan" => {
                        tauri::async_runtime::spawn_blocking(|| {
                            let _ = rescan_blocking(None);
                        });
                    }
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
            // 开机自启时带 --minimized:不弹窗口,只留托盘图标
            if std::env::args().any(|a| a == "--minimized") {
                if let Some(w) = app.get_webview_window("main") {
                    let _ = w.hide();
                }
            }
            // 配置里勾选了"启动管理程序时自动启动服务"
            if read_json(&config_path())["desktop"]["autoStartService"].as_bool().unwrap_or(false) {
                tauri::async_runtime::spawn_blocking(|| {
                    let _ = start_service_blocking();
                });
            }
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
