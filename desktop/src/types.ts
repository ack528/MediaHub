// 与服务端 config.json 一一对应(见 server/internal/config/config.go)。

export interface Root { path: string; label: string }

export interface Config {
  listen: string;
  dataDir: string;
  roots: Root[];
  exclude: string[];
  cache: { perDriveQuotaGB: number; fallbackDir: string; minFreeMarginGB: number };
  images: { thumbMode: "off" | "exif"; fallbackConvert: boolean };
  video: { engine: "auto" | "ffmpeg" | string; maxTranscodes: number; hwaccel: "qsv" | "none"; softwareFallback: boolean; posterSource: "auto" | "own" };
  jellyfin: { url: string; apiKey: string };
  auth: { tokenDays: number };
  tools: { ffmpeg: string; ffprobe: string; exiftool: string; vips: string };
  indexOtherFiles: boolean;
  scan: { skipWithinHours: number; intervalHours: number };
  log: { level: "debug" | "info" | "warn" | "error" };
  /** 局域网加密传输(HTTPS,自签名证书) */
  tls: { enabled: boolean };
  /** 管理程序自己的设置(服务端忽略) */
  desktop: { autoStartService: boolean };
}

/** 与 Go 的 config.Default() 保持一致。 */
export const DEFAULT_CONFIG: Config = {
  listen: "0.0.0.0:8480",
  dataDir: "C:\\MediaHub\\data",
  roots: [],
  exclude: ["$RECYCLE.BIN", "System Volume Information", ".mediahub", "@eaDir", "Thumbs.db", "desktop.ini"],
  cache: { perDriveQuotaGB: 50, fallbackDir: "", minFreeMarginGB: 5 },
  images: { thumbMode: "off", fallbackConvert: true },
  video: { engine: "auto", maxTranscodes: 2, hwaccel: "qsv", softwareFallback: true, posterSource: "auto" },
  jellyfin: { url: "http://127.0.0.1:8096", apiKey: "" },
  auth: { tokenDays: 180 },
  tools: { ffmpeg: "", ffprobe: "", exiftool: "", vips: "" },
  indexOtherFiles: false,
  scan: { skipWithinHours: 12, intervalHours: 24 },
  log: { level: "info" },
  tls: { enabled: true },
  desktop: { autoStartService: false },
};

export function mergeConfig(partial: unknown): Config {
  const merge = (def: any, src: any): any => {
    if (Array.isArray(def)) return Array.isArray(src) ? src : def;
    if (def && typeof def === "object") {
      const out: any = {};
      for (const k of Object.keys(def)) out[k] = merge(def[k], src && typeof src === "object" ? src[k] : undefined);
      // 保留未知字段,避免保存时丢掉用户手写的内容
      if (src && typeof src === "object") for (const k of Object.keys(src)) if (!(k in out)) out[k] = src[k];
      return out;
    }
    return src === undefined || src === null ? def : src;
  };
  return merge(DEFAULT_CONFIG, partial);
}

export interface Env {
  root: string;
  serverExe: string;
  serverExists: boolean;
  appVersion: string;
  configPath: string;
  configExists: boolean;
}

export interface IndexProgress {
  rootId: number; label: string; state: string;
  dirs: number; files: number; enriched: number; errors: number;
  message?: string;
  /** 实时速度(服务端每秒采样):扫描 文件/秒、元数据 个/秒,以及元数据总数与预计剩余秒数 */
  scanRate?: number; enrichRate?: number; enrichTotal?: number; etaSec?: number;
  /** 本次是接着上次被中断的扫描继续 */
  resumed?: boolean;
  /** 正在读取的目录,以及已经多少秒没有进展(卡住时显示) */
  current?: string; stalledSec?: number;
  /** 因无权限 / 目录已消失而跳过的目录数,以及最近的明细 */
  skipped?: number;
  failedDirs?: DirFailure[];
}

export interface DirFailure { path: string; kind: "denied" | "gone" | "name" | "offline" | "io" | "other"; code?: number; reason: string }

export interface CrashFile { name: string; size: number; time: number }

export interface CacheInfo {
  rootId: string; dir: string; mode: "on_drive" | "fallback"; reason?: string;
  usedBytes: number; quotaBytes: number; driveFreeGB: number;
}

export interface AdminStatus {
  version: string; listen: string; startedAt: string; uptimeSec: number;
  media: number; dialogs: number;
  index: IndexProgress[]; cache: CacheInfo[];
  dataDir: string; dataDriveFreeGB: number; warnings: string[];
}

export interface ServiceStatus {
  running: boolean;
  info: { name: string; version: string; apiVersion: number; transcode?: boolean; tls?: { enabled: boolean; fingerprint: string } } | null;
  status: AdminStatus | null;
  pids: number[];
}

/** 服务端的自动更新状态(/admin/update)。state: idle | checking | uptodate | available | downloading | installing | error | disabled | offline */
export interface UpdateStatus {
  current?: string; latest?: string; tag?: string; notes?: string;
  state: string; message?: string; progress?: number; checkedAt?: string;
  installable?: boolean; auto?: boolean;
}

export interface UserInfo { name: string; created: number }
export interface LocalIp { name: string; ip: string; private: boolean }
