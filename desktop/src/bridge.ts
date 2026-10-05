// 前端与后端的桥:在 Tauri 里调用 Rust 命令;在普通浏览器里用 mock(方便只调界面)。
import { invoke } from "@tauri-apps/api/core";
import { open as openDialog } from "@tauri-apps/plugin-dialog";
import { openPath as openerOpenPath } from "@tauri-apps/plugin-opener";
import { DEFAULT_CONFIG, type Config, type CrashFile, type Env, type LocalIp, type ServiceStatus, type UserInfo } from "./types";

export interface Bridge {
  readonly isMock: boolean;
  env(): Promise<Env>;
  readConfig(): Promise<unknown>;
  writeConfig(cfg: Config): Promise<void>;
  localIps(): Promise<LocalIp[]>;
  serviceStatus(): Promise<ServiceStatus>;
  start(): Promise<void>;
  stop(): Promise<void>;
  rescan(rootId?: number): Promise<void>;
  listUsers(): Promise<UserInfo[]>;
  addUser(name: string, password: string): Promise<void>;
  setPassword(name: string, password: string): Promise<void>;
  deleteUser(name: string): Promise<void>;
  readLog(lines: number, level?: string): Promise<string>;
  listCrashes(): Promise<CrashFile[]>;
  exportLogs(): Promise<string>;
  autostartGet(): Promise<boolean>;
  autostartSet(enable: boolean): Promise<void>;
  firewallStatus(): Promise<boolean>;
  firewallAdd(port: number): Promise<void>;
  pickFolder(): Promise<string | null>;
  openPath(path: string): Promise<void>;
}

const tauri: Bridge = {
  isMock: false,
  env: () => invoke("get_env"),
  readConfig: () => invoke("read_config"),
  writeConfig: (cfg) => invoke("write_config", { cfg }),
  localIps: () => invoke("local_ips"),
  serviceStatus: () => invoke("service_status"),
  start: () => invoke("service_start"),
  stop: () => invoke("service_stop"),
  rescan: (rootId) => invoke("admin_rescan", { rootId: rootId ?? null }),
  listUsers: () => invoke("list_users"),
  addUser: (name, password) => invoke("add_user", { name, password }),
  setPassword: (name, password) => invoke("set_password", { name, password }),
  deleteUser: (name) => invoke("delete_user", { name }),
  readLog: (lines, level) => invoke("read_log", { lines, level: level || null }),
  listCrashes: () => invoke("list_crashes"),
  exportLogs: () => invoke("export_logs"),
  autostartGet: () => invoke("autostart_get"),
  autostartSet: (enable) => invoke("autostart_set", { enable }),
  firewallStatus: () => invoke("firewall_status"),
  firewallAdd: (port) => invoke("firewall_add", { port }),
  pickFolder: async () => {
    const r = await openDialog({ directory: true, multiple: false, title: "选择文件夹" });
    return typeof r === "string" ? r : null;
  },
  openPath: (path) => openerOpenPath(path),
};

// ------------------------------------------------------------------ mock(仅浏览器调试用)

const MOCK_KEY = "mediahub-mock";
interface MockState { running: boolean; startedAt: number; config: unknown; users: UserInfo[] }

function loadMock(): MockState {
  try {
    const s = localStorage.getItem(MOCK_KEY);
    if (s) return JSON.parse(s);
  } catch { /* ignore */ }
  return {
    running: true, startedAt: Date.now() - 3 * 3600_000,
    config: { ...DEFAULT_CONFIG, roots: [{ path: "D:\\", label: "D:" }, { path: "E:\\", label: "E:" }, { path: "F:\\", label: "F:" }] },
    users: [{ name: "me", created: Math.floor(Date.now() / 1000) - 86400 * 20 }],
  };
}
let mock = loadMock();
const save = () => localStorage.setItem(MOCK_KEY, JSON.stringify(mock));
const delay = <T,>(v: T, ms = 120) => new Promise<T>((r) => setTimeout(() => r(v), ms));

const mockBridge: Bridge = {
  isMock: true,
  env: () => delay({ root: "D:\\Project\\Claude\\本地浏览", serverExe: "D:\\Project\\Claude\\本地浏览\\runtime\\bin\\mediahub.exe", serverExists: true, appVersion: "1.1.1", configPath: "D:\\Project\\Claude\\本地浏览\\runtime\\mediahub\\config.json", configExists: true }),
  readConfig: () => delay(mock.config),
  writeConfig: async (cfg) => { mock.config = cfg; save(); },
  localIps: () => delay([{ name: "以太网", ip: "192.168.1.20", private: true }, { name: "WLAN", ip: "192.168.1.31", private: true }]),
  serviceStatus: async () => {
    if (!mock.running) return { running: false, info: null, status: null, pids: [] };
    const up = Math.floor((Date.now() - mock.startedAt) / 1000);
    return {
      running: true, pids: [4321],
      info: { name: "MediaHub", version: "1.1.1", apiVersion: 1, transcode: true, tls: { enabled: true, fingerprint: "3A:9F:0C:5B:E2:71:44:AD:18:C6:20:9B:7E:D3:55:F1:0A:B8:62:CE:91:47:2D:E0:86:1B:F3:A9:5C:70:D4:28" } },
      status: {
        version: "1.1.1", listen: "0.0.0.0:8480", startedAt: new Date(mock.startedAt).toISOString(), uptimeSec: up,
        media: 2_184_330, dialogs: 41_902,
        index: [
          { rootId: 1, label: "D:", state: "idle", dirs: 18234, files: 612044, enriched: 612044, errors: 0 },
          { rootId: 2, label: "E:", state: "enriching", dirs: 9921, files: 402118, enriched: 188200, errors: 3, skipped: 2, enrichRate: 41.5, enrichTotal: 402118, etaSec: 5150, failedDirs: [{ path: "E:\\Backup\\old", kind: "io", code: 23, reason: "磁盘读取错误(可能有坏道)" }, { path: "E:\\Private", kind: "denied", code: 5, reason: "没有权限访问" }, { path: "E:\\Temp\\gone", kind: "gone", code: 3, reason: "目录已不存在" }] },
          { rootId: 3, label: "F:", state: "idle", dirs: 1203, files: 88122, enriched: 88122, errors: 0 },
        ],
        cache: [
          { rootId: "1", dir: "D:\\.mediahub", mode: "on_drive", usedBytes: 6.2e9, quotaBytes: 50 * 2 ** 30, driveFreeGB: 138 },
          { rootId: "2", dir: "E:\\.mediahub", mode: "on_drive", usedBytes: 3.1e9, quotaBytes: 50 * 2 ** 30, driveFreeGB: 99.7 },
          { rootId: "3", dir: "runtime\\cache\\F", mode: "fallback", reason: "盘剩余 0.0GB < 配额 50GB + 余量 5GB", usedBytes: 0.4e9, quotaBytes: 50 * 2 ** 30, driveFreeGB: 0 },
        ],
        dataDir: "C:\\MediaHub\\data", dataDriveFreeGB: 24.6, warnings: [],
      },
    };
  },
  start: async () => { mock.running = true; mock.startedAt = Date.now(); save(); await delay(null, 600); },
  stop: async () => { mock.running = false; save(); await delay(null, 400); },
  rescan: (_rootId) => delay(undefined),
  listUsers: () => delay(mock.users),
  addUser: async (name) => { if (mock.users.some((u) => u.name === name)) throw "用户已存在"; mock.users.push({ name, created: Math.floor(Date.now() / 1000) }); save(); },
  setPassword: () => delay(undefined),
  deleteUser: async (name) => { mock.users = mock.users.filter((u) => u.name !== name); save(); },
  readLog: (_n, level) => delay(Array.from({ length: 24 }, (_, i) => `time=2026-10-05T10:${String(i).padStart(2, "0")}:12.000+08:00 level=${i % 6 === 0 ? "WARN" : "INFO"} msg="示例日志(浏览器 mock)" n=${i}`).filter((l) => !level || level === "info" || level === "debug" || l.includes("level=WARN")).join("\n")),
  listCrashes: () => delay([]),
  exportLogs: () => delay("D:\\Project\\Claude\\本地浏览\\runtime\\exports\\mediahub-logs-mock.zip"),
  autostartGet: async () => localStorage.getItem("mock-autostart") === "1",
  autostartSet: async (e) => { localStorage.setItem("mock-autostart", e ? "1" : "0"); },
  firewallStatus: async () => localStorage.getItem("mock-fw") === "1",
  firewallAdd: async () => { localStorage.setItem("mock-fw", "1"); },
  pickFolder: async () => window.prompt("(浏览器模式)输入文件夹路径", "G:\\") || null,
  openPath: async (p) => { window.alert("打开:" + p); },
};

export const bridge: Bridge = "__TAURI_INTERNALS__" in window ? tauri : mockBridge;
