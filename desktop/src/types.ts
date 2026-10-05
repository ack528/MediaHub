// 与服务端 config.json 一一对应(见 server/internal/config/config.go)。

export interface Root { path: string; label: string }

export interface Config {
  listen: string;
  dataDir: string;
  roots: Root[];
  exclude: string[];
  cache: { perDriveQuotaGB: number; fallbackDir: string; minFreeMarginGB: number };
  images: { thumbMode: "off" | "exif"; fallbackConvert: boolean };
  video: { engine: string; maxTranscodes: number; hwaccel: "qsv" | "none"; softwareFallback: boolean; posterSource: "auto" | "own" };
  jellyfin: { url: string; apiKey: string };
  auth: { tokenDays: number };
  tools: { ffmpeg: string; ffprobe: string; exiftool: string; vips: string };
  indexOtherFiles: boolean;
  scan: { skipWithinHours: number; intervalHours: number };
}

/** 与 Go 的 config.Default() 保持一致。 */
export const DEFAULT_CONFIG: Config = {
  listen: "0.0.0.0:8480",
  dataDir: "C:\\MediaHub\\data",
  roots: [],
  exclude: ["$RECYCLE.BIN", "System Volume Information", ".mediahub", "@eaDir", "Thumbs.db", "desktop.ini"],
  cache: { perDriveQuotaGB: 50, fallbackDir: "", minFreeMarginGB: 5 },
  images: { thumbMode: "off", fallbackConvert: true },
  video: { engine: "jellyfin", maxTranscodes: 2, hwaccel: "qsv", softwareFallback: true, posterSource: "auto" },
  jellyfin: { url: "http://127.0.0.1:8096", apiKey: "" },
  auth: { tokenDays: 180 },
  tools: { ffmpeg: "", ffprobe: "", exiftool: "", vips: "" },
  indexOtherFiles: false,
  scan: { skipWithinHours: 12, intervalHours: 24 },
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
}

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
  info: { name: string; version: string; apiVersion: number } | null;
  status: AdminStatus | null;
  pids: number[];
}

export interface UserInfo { name: string; created: number }
export interface LocalIp { name: string; ip: string; private: boolean }
