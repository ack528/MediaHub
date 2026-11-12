// 与服务端 / 原生数据层(Models.kt、Session.kt、ViewState.kt)一致的数据结构

export type MediaType = "photo" | "video" | "gif" | "audio" | "file";

export interface Counts { photo: number; video: number; gif: number; audio?: number; file?: number }

export interface Last { id: string; type: string; name: string; takenAt?: string; w?: number | null; h?: number | null }

export interface Dialog {
  id: string;
  parentId?: string | null;
  rootId: string;
  title: string;
  pathDisplay: string;
  counts: Counts;
  recursiveCount?: number;
  last?: Last | null;
  cover?: string[];
  topicsCount?: number;
  version?: string;
}

export interface VideoInfo { container?: string; vcodec?: string; profile?: string; bitrateKbps?: number; hdr?: string; acodecs?: string[]; subs?: number }

export interface Item {
  id: string;
  dialogId: string;
  name: string;
  ext: string;
  type: MediaType | string;
  mime?: string;
  size?: number;
  w?: number | null;
  h?: number | null;
  rotation?: number;
  takenAt?: string;
  modifiedAt?: string;
  createdAt?: string;
  durationMs?: number | null;
  thumbhash?: string | null;
  v?: string;
  video?: VideoInfo | null;
  flags?: { animated?: boolean; corrupt?: boolean; truncated?: boolean };
  problem?: string | null;
}

export interface DialogsResp { dialogs: Dialog[]; nextCursor?: string | null }

export interface HistoryResp { items: Item[]; total?: number; firstRank?: number; nextCursor?: string | null; prevCursor?: string | null }

export interface ServerCaps {
  codecs: string[];
  qsvH264?: boolean;
  qsvH265?: boolean;
  maxTranscodeBitrate?: number;
  probing?: boolean;
  speedtest?: boolean;
  playerPrefs?: boolean;
  hlsPrepare?: boolean;
  thumbWebp?: boolean;
  optimize?: boolean;
  compat?: boolean;
}

export interface ServerEntry {
  id: string;
  name: string;
  kind: "server" | "local" | string;
  host: string;
  baseUrl: string;
  address: string;
  user: string;
  loggedIn: boolean;
}

export interface SessionState {
  activeId: string;
  loggedIn: boolean;
  local: boolean;
  baseUrl: string;
  pin: string;
  servers: ServerEntry[];
}

export interface DialogView {
  itemId?: string | null;
  itemName?: string;
  offsetPx?: number;
  sort: string;
  ascChat?: boolean;
  ascGrid: boolean;
  grid: boolean;
  feed?: boolean;
  types: string[];
  columns: number;
  savedAt?: number;
  device?: string;
}

export interface BrowseCheckpoint { view: DialogView; savedAt: number }
export interface BrowseHistoryResult {
  entries: BrowseCheckpoint[];
  config: { enabled: boolean; maxEntries: number; retentionDays: number; intervalSeconds: number };
}

export interface Insets { top: number; bottom: number; left: number; right: number }

/** AppSettings(Settings.kt)的全部字段:网页只读写自己用到的,其余原样保留。 */
export type Settings = Record<string, unknown> & {
  theme: "dark" | "light" | "system";
  fontScale: number;
  dialogSort: "recent" | "name" | "count" | "path";
  dialogMinCount: number;
  dialogSubtitle: "last" | "path" | "none";
  avatarCover: boolean;
  defaultSort: string;
  defaultSortDir: string;
  gridSpacing: number;
  gridThumbWidth: number;
  showVideoName: boolean;
  showDuration: boolean;
  chatImageWidth: number;
  defaultColumns: number;
  rememberPosition: boolean;
  showDatePills: boolean;
  /** 底部导航的玻璃效果:on(折射)/ frosted(只要毛玻璃)/ off(半透明底色) */
  glass?: string;
  heicMode: "server" | "native";
  randomSeed: number;
  randomType: number;
  hapticLevel: number;
  logLevel: string;
  speedSeconds: number;
  speedThreads: number;
  updateSource: string;
  hwReport: string;
  enhFrc: string;
  enhUpscale: string;
};

export interface BootInfo {
  version: string;
  device: string;
  model: string;
  android: string;
  systemDark: boolean;
  session: SessionState;
  settings: Settings;
  caps: ServerCaps;
  route: string;
  notice: string;
  crash: string;
  hasCrash: boolean;
  insets: Insets | null;
  /** 这个 WebView 显示的二级页面(每个页面一个原生 Activity);null = 首页。 */
  page?: import("./nav").Route | null;
}
