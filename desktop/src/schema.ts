// 设置项定义:页面、标签页、控件类型、说明文字。
// 界面、"搜索配置项"、"N 项设置"计数都由这份定义生成。
import type { Config } from "./types";

export type PageId = "overview" | "library" | "storage" | "video" | "accounts" | "network" | "logs" | "about";

export interface Option { value: string; label: string }

export interface Item {
  id: string;
  page: PageId;
  tab: string;
  icon: string;
  title: string;
  desc: string;
  kind: "switch" | "number" | "text" | "path" | "secret" | "select" | "tags";
  read: (c: Config) => any;
  write: (c: Config, v: any) => Config;
  unit?: string;
  min?: number;
  max?: number;
  options?: Option[];
  placeholder?: string;
  /** 修改后需要重启服务才生效(目前全部如此,保留字段以便将来做热更新) */
  restart?: boolean;
}

export interface PageDef {
  id: PageId;
  label: string;
  icon: string;
  tabs: string[];
}

export const PAGES: PageDef[] = [
  { id: "overview", label: "概览", icon: "gauge", tabs: ["运行状态"] },
  { id: "library", label: "媒体库", icon: "folder", tabs: ["根目录", "扫描与过滤"] },
  { id: "storage", label: "缓存与图片", icon: "database", tabs: ["缓存", "图片"] },
  { id: "video", label: "视频转码", icon: "film", tabs: ["转码", "Jellyfin", "工具路径"] },
  { id: "accounts", label: "账号安全", icon: "users", tabs: ["账号", "登录"] },
  { id: "network", label: "网络", icon: "globe", tabs: ["监听与访问"] },
  { id: "logs", label: "日志", icon: "filetext", tabs: ["服务日志"] },
  { id: "about", label: "关于", icon: "info", tabs: ["关于"] },
];

// ------------------------------------------------------------ 工具:点路径读写

function getP(c: any, path: string) { return path.split(".").reduce((o, k) => (o == null ? o : o[k]), c); }
function setP(c: any, path: string, v: any) {
  const keys = path.split(".");
  const next = structuredClone(c);
  let o = next;
  for (let i = 0; i < keys.length - 1; i++) o = o[keys[i]];
  o[keys[keys.length - 1]] = v;
  return next;
}
const at = (path: string) => ({ read: (c: Config) => getP(c, path), write: (c: Config, v: any) => setP(c, path, v) });

const hostOf = (listen: string) => listen.slice(0, listen.lastIndexOf(":")) || "0.0.0.0";
const portOf = (listen: string) => Number(listen.slice(listen.lastIndexOf(":") + 1)) || 8480;

// ------------------------------------------------------------ 设置项

export const ITEMS: Item[] = [
  // ---- 媒体库 / 扫描与过滤
  {
    id: "indexOther", page: "library", tab: "扫描与过滤", icon: "filetext", kind: "switch", restart: true,
    title: "索引非媒体文件", desc: "开启后,文档、压缩包等也会作为\"文件\"出现在对话里。默认只索引图片、视频、GIF 和音频。",
    ...at("indexOtherFiles"),
  },
  {
    id: "skipWithin", page: "library", tab: "扫描与过滤", icon: "clock", kind: "number", unit: "小时", min: 0, max: 720, restart: true,
    title: "启动时复用上次扫描的时限", desc: "启动服务时,距上次完整扫描不足这个时间就不再重新扫描(省去大库重复读盘)。被中断的扫描总是会接着续扫。0 = 每次启动都扫描。需要立即更新时到“概览”点“重新扫描”。",
    ...at("scan.skipWithinHours"),
  },
  {
    id: "scanInterval", page: "library", tab: "扫描与过滤", icon: "refresh", kind: "number", unit: "小时", min: 0, max: 720, restart: true,
    title: "定时重新扫描间隔", desc: "服务运行期间,每隔多久自动重新扫描一次,发现新增、删除和改名的文件。0 = 不定时扫描(只在手动触发时扫描)。",
    ...at("scan.intervalHours"),
  },
  {
    id: "exclude", page: "library", tab: "扫描与过滤", icon: "tag", kind: "tags", restart: true,
    title: "排除的文件夹/文件名", desc: "名称完全匹配(不区分大小写)的文件夹和文件不会被索引。缓存目录 .mediahub 必须保留。",
    ...at("exclude"), placeholder: "输入名称后回车添加",
  },

  // ---- 缓存与图片 / 缓存
  {
    id: "quota", page: "storage", tab: "缓存", icon: "database", kind: "number", unit: "GB", min: 1, max: 2000, restart: true,
    title: "每个盘的缓存配额", desc: "视频封面等生成物放在各媒体盘自己的 .mediahub 文件夹里,超出后按最久未访问淘汰。",
    ...at("cache.perDriveQuotaGB"),
  },
  {
    id: "margin", page: "storage", tab: "缓存", icon: "shield", kind: "number", unit: "GB", min: 0, max: 500, restart: true,
    title: "盘上保留的空余空间", desc: "盘的可用空间小于\"配额 + 此余量\"时,该盘的缓存自动改放到下面的备用目录,避免写满。",
    ...at("cache.minFreeMarginGB"),
  },
  {
    id: "fallbackDir", page: "storage", tab: "缓存", icon: "folderopen", kind: "path", restart: true,
    title: "备用缓存目录", desc: "留空使用项目内的 runtime\\cache。建议放在有空余的固态盘上。",
    ...at("cache.fallbackDir"), placeholder: "默认:runtime\\cache",
  },
  {
    id: "dataDir", page: "storage", tab: "缓存", icon: "drive", kind: "path", restart: true,
    title: "数据目录", desc: "存放索引数据库 hub.sqlite 和日志。建议放在固态盘(如 C:\\MediaHub\\data)。修改后需要手动迁移旧数据。",
    ...at("dataDir"), placeholder: "C:\\MediaHub\\data",
  },

  // ---- 缓存与图片 / 图片
  {
    id: "thumbMode", page: "storage", tab: "图片", icon: "image", kind: "select", restart: true,
    title: "图片缩略图", desc: "默认图片全部使用原图。\"内嵌缩略图\"只读取照片文件头里自带的小图,不生成、不占用缓存空间,可加快网格首屏。",
    ...at("images.thumbMode"),
    options: [{ value: "off", label: "关闭(只用原图)" }, { value: "exif", label: "使用内嵌缩略图" }],
  },
  {
    id: "fallbackConvert", page: "storage", tab: "图片", icon: "refresh", kind: "switch", restart: true,
    title: "转换手机无法显示的格式", desc: "RAW、TIFF、JXL 等格式由服务端转成 JPEG 再发给手机,结果计入缓存配额。",
    ...at("images.fallbackConvert"),
  },

  // ---- 视频转码 / 转码
  {
    id: "engine", page: "video", tab: "转码", icon: "server", kind: "select", restart: true,
    title: "转码引擎", desc: "目前由 Jellyfin 负责转封装和转码;手机能直接播放的视频不经过转码。",
    ...at("video.engine"), options: [{ value: "jellyfin", label: "Jellyfin" }],
  },
  {
    id: "hwaccel", page: "video", tab: "转码", icon: "cpu", kind: "select", restart: true,
    title: "硬件加速", desc: "Intel 核显使用 QSV(i3-8100 的 UHD 630 支持 H.264/HEVC 硬编)。服务器在 BIOS 里必须启用核显。",
    ...at("video.hwaccel"), options: [{ value: "qsv", label: "Intel QSV" }, { value: "none", label: "不使用(CPU 软编)" }],
  },
  {
    id: "maxTranscodes", page: "video", tab: "转码", icon: "zap", kind: "number", unit: "路", min: 1, max: 8, restart: true,
    title: "同时转码路数", desc: "最多同时进行几路转码。i3-8100 建议 2。",
    ...at("video.maxTranscodes"),
  },
  {
    id: "swFallback", page: "video", tab: "转码", icon: "refresh", kind: "switch", restart: true,
    title: "硬件失败时回退到软编", desc: "QSV 不可用时用 CPU(libx264)继续转码;4 核无超线程只建议 1 路 720p。",
    ...at("video.softwareFallback"),
  },

  // ---- 视频转码 / Jellyfin
  {
    id: "jfUrl", page: "video", tab: "Jellyfin", icon: "link", kind: "text", restart: true,
    title: "Jellyfin 地址", desc: "Jellyfin 只在本机回环地址监听,仅供 MediaHub 调用。",
    ...at("jellyfin.url"), placeholder: "http://127.0.0.1:8096",
  },
  {
    id: "jfKey", page: "video", tab: "Jellyfin", icon: "key", kind: "secret", restart: true,
    title: "Jellyfin API 密钥", desc: "在 Jellyfin 管理后台 → API 密钥 中创建。仅保存在服务端配置里,不会发给手机。",
    ...at("jellyfin.apiKey"), placeholder: "粘贴密钥",
  },
  {
    id: "posterSource", page: "video", tab: "Jellyfin", icon: "film", kind: "select", restart: true,
    title: "视频封面来源", desc: "自动:Jellyfin 已生成封面就直接转发(不再缓存第二份),没有再由本程序按需生成;仅自己生成:不使用 Jellyfin 的封面。",
    ...at("video.posterSource"), options: [{ value: "auto", label: "自动(优先用 Jellyfin 的)" }, { value: "own", label: "仅自己生成" }],
  },

  // ---- 视频转码 / 工具路径
  { id: "tFfmpeg", page: "video", tab: "工具路径", icon: "wrench", kind: "path", restart: true, title: "ffmpeg", desc: "留空使用项目内 tools\\ffmpeg\\ffmpeg.exe。", ...at("tools.ffmpeg"), placeholder: "默认" },
  { id: "tFfprobe", page: "video", tab: "工具路径", icon: "wrench", kind: "path", restart: true, title: "ffprobe", desc: "留空使用项目内 tools\\ffmpeg\\ffprobe.exe。", ...at("tools.ffprobe"), placeholder: "默认" },
  { id: "tExif", page: "video", tab: "工具路径", icon: "wrench", kind: "path", restart: true, title: "ExifTool", desc: "读取照片拍摄时间、尺寸和 RAW 内嵌预览。", ...at("tools.exiftool"), placeholder: "默认" },
  { id: "tVips", page: "video", tab: "工具路径", icon: "wrench", kind: "path", restart: true, title: "libvips", desc: "转换 HEIC、RAW 等手机无法显示的图片。", ...at("tools.vips"), placeholder: "默认" },

  // ---- 账号安全 / 登录
  {
    id: "tokenDays", page: "accounts", tab: "登录", icon: "clock", kind: "number", unit: "天", min: 1, max: 3650, restart: true,
    title: "登录有效期", desc: "手机登录后保持登录的天数,使用中会自动顺延。",
    ...at("auth.tokenDays"),
  },

  // ---- 网络
  {
    id: "bind", page: "network", tab: "监听与访问", icon: "globe", kind: "select", restart: true,
    title: "监听范围", desc: "\"局域网\"允许手机访问;\"仅本机\"只有这台电脑能访问。",
    read: (c) => (hostOf(c.listen) === "127.0.0.1" ? "127.0.0.1" : "0.0.0.0"),
    write: (c, v) => ({ ...c, listen: `${v}:${portOf(c.listen)}` }),
    options: [{ value: "0.0.0.0", label: "局域网(所有网卡)" }, { value: "127.0.0.1", label: "仅本机" }],
  },
  {
    id: "port", page: "network", tab: "监听与访问", icon: "link", kind: "number", min: 1024, max: 65535, restart: true,
    title: "端口", desc: "手机应用里输入的服务器地址为 \"电脑IP:端口\"。修改后记得放行防火墙。",
    read: (c) => portOf(c.listen),
    write: (c, v) => ({ ...c, listen: `${hostOf(c.listen)}:${v}` }),
  },
];

export function itemsOf(page: PageId, tab: string) { return ITEMS.filter((i) => i.page === page && i.tab === tab); }

/** 每个标签页的"N 项设置":schema 中的项数 + 页面内自定义区块各算一项。 */
const CUSTOM: Record<string, number> = {
  "overview/运行状态": 3,
  "library/根目录": 1,
  "storage/缓存": 1, // 各盘缓存占用
  "accounts/账号": 1,
  "network/监听与访问": 1,
  "logs/服务日志": 1,
  "about/关于": 1,
};
export function countOf(page: PageId, tab: string) {
  return itemsOf(page, tab).length + (CUSTOM[`${page}/${tab}`] ?? 0);
}

export function searchItems(q: string): Item[] {
  const s = q.trim().toLowerCase();
  if (!s) return [];
  return ITEMS.filter((i) => (i.title + i.desc + i.id).toLowerCase().includes(s));
}
