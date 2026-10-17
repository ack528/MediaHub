// 设置项定义:页面、标签页、控件类型、说明文字。
// 界面、"搜索配置项"、"N 项设置"计数都由这份定义生成。
import type { Config } from "./types";

export type PageId = "overview" | "library" | "storage" | "video" | "optimize" | "accounts" | "network" | "clients" | "logs" | "about";

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
  { id: "optimize", label: "存储优化", icon: "zap", tabs: ["重复文件", "视频压缩", "回收区"] },
  { id: "accounts", label: "账号安全", icon: "users", tabs: ["账号", "登录"] },
  { id: "network", label: "网络", icon: "globe", tabs: ["监听与访问", "内网穿透", "开机与防火墙"] },
  { id: "clients", label: "客户端连接", icon: "link", tabs: ["连接状态", "调度设置"] },
  { id: "logs", label: "日志", icon: "filetext", tabs: ["服务日志", "日志设置"] },
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
    title: "转码引擎", desc: "手机解不了的视频由服务端转成 H.264 / AAC 再播放;能直接播放的不经过转码。\"自动\":配置了 Jellyfin 且它已收录该视频就用 Jellyfin,否则用内置 ffmpeg(不需要安装任何东西)。",
    read: (c) => (c.video.engine === "ffmpeg" ? "ffmpeg" : "auto"),
    write: (c, v) => setP(c, "video.engine", v),
    options: [{ value: "auto", label: "自动(Jellyfin 优先,否则内置 ffmpeg)" }, { value: "ffmpeg", label: "只用内置 ffmpeg" }],
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
    id: "tls", page: "network", tab: "监听与访问", icon: "shield", kind: "switch", restart: true,
    title: "加密传输(HTTPS)", desc: "手机与服务器之间的所有数据(登录令牌、图片、视频)都加密。使用自签名证书,手机第一次连接时核对证书指纹后即可;TLS 1.3 + HTTP/2,几乎不影响速度。关闭后退回明文 HTTP(不推荐)。",
    ...at("tls.enabled"),
  },
  {
    id: "port", page: "network", tab: "监听与访问", icon: "link", kind: "number", min: 1024, max: 65535, restart: true,
    title: "端口", desc: "手机应用里输入的服务器地址为 \"电脑IP:端口\"。修改后记得放行防火墙。",
    read: (c) => portOf(c.listen),
    write: (c, v) => ({ ...c, listen: `${hostOf(c.listen)}:${v}` }),
  },

  // ---- 客户端优先调度
  {
    id: "pri-enabled", page: "clients", tab: "调度设置", icon: "zap", kind: "switch", restart: true,
    title: "有客户端连接时让路", desc: "有手机在浏览 / 播放 / 传输时,后台任务(扫描、重复检测、封面预生成、视频压缩)自动降速或暂停,优先保证传输和播放流畅;没有客户端后自动恢复全速。关闭 = 后台任务一直全速。",
    ...at("priority.enabled"),
  },
  {
    id: "pri-window", page: "clients", tab: "调度设置", icon: "clock", kind: "number", restart: true, unit: "秒", min: 10, max: 3600,
    title: "多久没有请求才算「没有客户端」", desc: "最近这么多秒内有手机发过请求,就当作还有客户端连着(手机放着不动时不会一直发请求,所以不要设太短)。默认 120 秒。",
    ...at("priority.activeWindowSec"),
  },
  {
    id: "pri-pause", page: "clients", tab: "调度设置", icon: "film", kind: "switch", restart: true,
    title: "有客户端时暂停视频压缩", desc: "压缩用的 ffmpeg 整个进程会被挂起(进度保留,不占 CPU / 磁盘 / 核显),没有客户端后自动继续。关闭后压缩只降低优先级,仍会和播放抢资源。",
    ...at("priority.pauseCompress"),
  },
  {
    id: "pri-lower", page: "clients", tab: "调度设置", icon: "cpu", kind: "switch", restart: true,
    title: "后台任务用较低的 CPU 优先级", desc: "压缩 / 重复检测里的 ffmpeg、ffprobe 等子进程用低于正常的优先级运行,即使在没有客户端时也不会拖慢电脑上的其它程序。",
    ...at("priority.lowerChild"),
  },

  // ---- 内网穿透
  {
    id: "tun-enabled", page: "network", tab: "内网穿透", icon: "link", kind: "switch", restart: true,
    title: "启用内网穿透", desc: "服务启动时自动拉起穿透客户端(frpc 或你自己的命令),掉线自动重连。手机不在家里的网络时,通过穿透服务器访问这台电脑。",
    ...at("tunnel.enabled"),
  },
  {
    id: "tun-type", page: "network", tab: "内网穿透", icon: "wrench", kind: "select", restart: true,
    title: "穿透方式", desc: "frp:服务端托管 frpc(按下面的 frp 配置自动生成配置文件);自定义命令:运行你自己的穿透客户端(cpolar、natapp、cloudflared 等)。",
    ...at("tunnel.type"),
    options: [{ value: "frp", label: "frp(托管 frpc)" }, { value: "custom", label: "自定义命令" }],
  },
  {
    id: "tun-public", page: "network", tab: "内网穿透", icon: "globe", kind: "text", restart: true, placeholder: "例如 abc.example.com:12345",
    title: "外网地址(手机里填这个)", desc: "穿透服务器分配给你的访问地址,格式 域名:端口。填了以后:管理程序会通过它探测整条链路的延迟;手机在「设置 → 服务器管理」里能同时记下内网地址和外网地址,在家自动走内网直连。",
    ...at("tunnel.publicAddr"),
  },
  {
    id: "tun-health", page: "network", tab: "内网穿透", icon: "gauge", kind: "switch", restart: true,
    title: "定期探测链路", desc: "每 30 秒通过外网地址访问一次服务端自己,量 TCP 连接和 HTTP 首字节的耗时,在状态里显示最近 20 次。需要先填外网地址。",
    ...at("tunnel.healthCheck"),
  },
  {
    id: "tun-restart", page: "network", tab: "内网穿透", icon: "refresh", kind: "switch", restart: true,
    title: "探测连续失败时自动重启穿透", desc: "穿透进程还活着但链路已经不通(穿透服务器那边把连接掐了、NAT 映射过期)是最常见的\"手机突然连不上\"。连续 3 次探测失败就重启穿透进程(至少运行 2 分钟后才会触发,避免反复重启)。",
    ...at("tunnel.restartOnFail"),
  },
  {
    id: "frp-binary", page: "network", tab: "内网穿透", icon: "folder", kind: "path", restart: true, placeholder: "留空 = 服务端内置的 frpc",
    title: "frpc 程序路径", desc: "frp 客户端程序(frpc.exe),版本需要 0.52 及以上。留空 = 用服务端内置的 frpc(第一次启动穿透时释放到数据目录下的 tunnel\\bin);如果 tools\\frp\\frpc.exe、frp\\frpc.exe 或程序同目录里有,优先用那个。想用自己的版本就在这里填路径。",
    ...at("tunnel.binary"),
  },
  {
    id: "frp-addr", page: "network", tab: "内网穿透", icon: "server", kind: "text", restart: true, placeholder: "例如 frp.example.com",
    title: "穿透服务器地址", desc: "frps 所在服务器的域名或 IP(穿透服务商会给你)。",
    ...at("tunnel.serverAddr"),
  },
  {
    id: "frp-port", page: "network", tab: "内网穿透", icon: "link", kind: "number", min: 1, max: 65535, restart: true,
    title: "穿透服务器端口", desc: "frps 的绑定端口,默认 7000。",
    ...at("tunnel.serverPort"),
  },
  {
    id: "frp-token", page: "network", tab: "内网穿透", icon: "key", kind: "secret", restart: true,
    title: "令牌(token)", desc: "和穿透服务器约定的认证令牌。只写在 config.json 和生成的 frpc.toml(仅当前用户可读)里,日志里会自动隐藏。",
    ...at("tunnel.token"),
  },
  {
    id: "frp-user", page: "network", tab: "内网穿透", icon: "users", kind: "text", restart: true,
    title: "用户名(可选)", desc: "有些穿透服务商要求填用户名 / 账号标识,没有就留空。",
    ...at("tunnel.user"),
  },
  {
    id: "frp-proto", page: "network", tab: "内网穿透", icon: "zap", kind: "select", restart: true,
    title: "传输协议", desc: "客户端到穿透服务器之间用什么协议。tcp:最通用;quic:基于 UDP,多个请求互不阻塞、丢包时比 tcp 更流畅(服务器必须也开了 quic);kcp:UDP,延迟更低但更耗带宽,适合丢包多的网络;websocket / wss:被只放行 80 / 443 的网络拦截时用。",
    ...at("tunnel.protocol"),
    options: [{ value: "tcp", label: "tcp(通用)" }, { value: "quic", label: "quic(丢包多 / 延迟高时更流畅)" }, { value: "kcp", label: "kcp(低延迟,更耗带宽)" }, { value: "websocket", label: "websocket" }, { value: "wss", label: "wss(加密的 websocket)" }],
  },
  {
    id: "frp-ptype", page: "network", tab: "内网穿透", icon: "tag", kind: "select", restart: true,
    title: "代理类型", desc: "tcp:穿透服务器上开一个远程端口,手机连这个端口(MediaHub 的 HTTPS 原样透传,证书指纹照常校验,推荐);https:按域名转发(TLS 透传),需要自定义域名或子域名;http:按域名转发明文 HTTP,只在关闭了 MediaHub 的加密传输时才能用。",
    ...at("tunnel.proxyType"),
    options: [{ value: "tcp", label: "tcp(远程端口,推荐)" }, { value: "https", label: "https(域名,TLS 透传)" }, { value: "http", label: "http(域名,明文)" }],
  },
  {
    id: "frp-rport", page: "network", tab: "内网穿透", icon: "link", kind: "number", min: 0, max: 65535, restart: true,
    title: "远程端口(tcp)", desc: "穿透服务器上对外开放的端口,手机访问 \"服务器地址:这个端口\"。0 = 让服务器分配 / 由服务商指定。",
    ...at("tunnel.remotePort"),
  },
  {
    id: "frp-domain", page: "network", tab: "内网穿透", icon: "globe", kind: "text", restart: true, placeholder: "例如 nas.example.com",
    title: "自定义域名(http / https)", desc: "代理类型是 http / https 时填,域名要解析到穿透服务器。",
    ...at("tunnel.customDomain"),
  },
  {
    id: "frp-subdomain", page: "network", tab: "内网穿透", icon: "globe", kind: "text", restart: true,
    title: "子域名(http / https)", desc: "服务器配置了 subdomainHost 时,只填前缀(例如 nas)就能得到 nas.主域名。和自定义域名二选一。",
    ...at("tunnel.subdomain"),
  },
  {
    id: "frp-mux", page: "network", tab: "内网穿透", icon: "zap", kind: "switch", restart: true,
    title: "连接多路复用(tcpMux)", desc: "多个请求共用一条到穿透服务器的 TCP 连接:建立新请求更快,图片缩略图这种大量小请求受益明显。代价:一条连接丢包重传时,同一连接里的其它请求也要等(队头阻塞)。以视频为主、链路丢包多时可以关掉,配合更大的连接池,让更多条 TCP 并行跑满带宽。",
    ...at("tunnel.tcpMux"),
  },
  {
    id: "frp-pool", page: "network", tab: "内网穿透", icon: "database", kind: "number", min: 0, max: 50, restart: true,
    title: "连接池大小(poolCount)", desc: "预先建好的工作连接数。没有预建连接时,每个新请求都要经过 \"穿透服务器通知客户端 → 客户端新建连接\" 一个来回(经云中继一般几十到几百毫秒);预建几条就省掉这一步。默认 5,并发请求多(滑动网格)时可以调到 10 ~ 20。",
    ...at("tunnel.poolCount"),
  },
  {
    id: "frp-hb", page: "network", tab: "内网穿透", icon: "clock", kind: "number", unit: "秒", min: 3, max: 60, restart: true,
    title: "心跳间隔", desc: "每隔多少秒向穿透服务器发一次心跳,超时是 3 倍。越小越快发现断线并重连,但太小会增加空闲流量;不少 NAT / 运营商会掐掉空闲 30 秒以上的连接,默认 10 秒比较稳。",
    ...at("tunnel.heartbeatSec"),
  },
  {
    id: "frp-tls", page: "network", tab: "内网穿透", icon: "shield", kind: "switch", restart: true,
    title: "到穿透服务器的连接加 TLS", desc: "frp 0.50 起默认开启。只加密 frpc 和 frps 之间的连接(你的流量本身在 MediaHub 里已经是 HTTPS)。关掉可以少一点握手耗时和 CPU,但穿透服务商可能要求开着。",
    ...at("tunnel.tls"),
  },
  {
    id: "frp-enc", page: "network", tab: "内网穿透", icon: "shield", kind: "switch", restart: true,
    title: "隧道内加密(useEncryption)", desc: "在隧道里再加一层加密。MediaHub 的 HTTPS 已经端到端加密,再加一层只耗 CPU(视频码率高时会成为瓶颈),建议关闭。",
    ...at("tunnel.encryption"),
  },
  {
    id: "frp-comp", page: "network", tab: "内网穿透", icon: "database", kind: "switch", restart: true,
    title: "隧道内压缩(useCompression)", desc: "图片 / 视频已经是压缩格式,再压缩基本压不小,只耗 CPU;JSON 接口 MediaHub 自己已经 gzip。建议关闭。",
    ...at("tunnel.compression"),
  },
  {
    id: "frp-bw", page: "network", tab: "内网穿透", icon: "gauge", kind: "text", restart: true, placeholder: "例如 2MB(留空不限)",
    title: "带宽限制", desc: "限制这条隧道的速度(单位 KB 或 MB,例如 2MB 表示每秒 2 兆字节)。穿透服务按流量或带宽计费、或者不想让视频把穿透服务器的带宽占满时用;留空不限。",
    ...at("tunnel.bandwidthLimit"),
  },
  {
    id: "tc-cmd", page: "network", tab: "内网穿透", icon: "wrench", kind: "path", restart: true,
    title: "自定义命令:程序", desc: "穿透方式选「自定义命令」时运行的程序(例如 cpolar.exe、natapp.exe、cloudflared.exe 的完整路径)。",
    ...at("tunnel.command"),
  },
  {
    id: "tc-args", page: "network", tab: "内网穿透", icon: "filetext", kind: "text", restart: true, placeholder: "例如 tcp {port}",
    title: "自定义命令:参数", desc: "命令行参数,含空格的参数用双引号括起来。{port} 会替换成 MediaHub 的监听端口。",
    ...at("tunnel.args"),
  },
  {
    id: "tc-wd", page: "network", tab: "内网穿透", icon: "folder", kind: "path", restart: true,
    title: "自定义命令:工作目录", desc: "留空 = 程序所在目录。",
    ...at("tunnel.workDir"),
  },
  {
    id: "autoStartService", page: "network", tab: "开机与防火墙", icon: "play", kind: "switch",
    title: "打开管理程序时自动启动服务", desc: "管理程序启动(包括开机自启)时,如果服务没在运行就自动把它拉起来。服务本身与管理程序是两个进程,关闭管理界面不会停止服务。",
    ...at("desktop.autoStartService"),
  },

  // ---- 日志 / 日志设置
  {
    id: "logLevel", page: "logs", tab: "日志设置", icon: "filetext", kind: "select", restart: true,
    title: "服务端日志级别", desc: "信息:记录启动、扫描、登录等常规事件(推荐);调试:额外记录每个请求,排查问题时临时使用,文件会增长得更快;警告 / 错误:只记录异常。日志保留 14 天,总量不超过 300 MB。",
    ...at("log.level"),
    options: [{ value: "debug", label: "调试(最详细)" }, { value: "info", label: "信息(推荐)" }, { value: "warn", label: "仅警告和错误" }, { value: "error", label: "仅错误" }],
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
  "clients/连接状态": 3, // 概况、客户端列表、后台任务
  "network/内网穿透": 2, // 运行状态、提高流畅度建议
  "network/开机与防火墙": 2, // 开机自启、防火墙
  "logs/服务日志": 1,
  "logs/日志设置": 2, // 导出日志、崩溃报告
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
