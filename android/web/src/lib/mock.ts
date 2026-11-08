// 浏览器里开发用的模拟原生桥:假的文件夹和图片(picsum),让界面不进 App 也能跑起来。App 里不会加载这个文件。
import { BridgeError, emitLocal, installMock } from "./bridge";
import { SETTING_PAGES } from "../screens/settings/schema";
import type { Dialog, Item, SessionState, Settings } from "./types";

const DRIVES = ["D:", "E:", "F:"];
const NAMES = ["旅行 2023", "猫咪", "家庭聚会", "毕业典礼", "风景", "动漫截图", "电影收藏", "截图", "壁纸", "相机胶卷", "无人机航拍", "美食", "演唱会", "宠物视频"];

function rnd(seed: number) { let x = seed; return () => (x = (x * 1103515245 + 12345) % 2147483648) / 2147483648; }

const dialogs: Dialog[] = NAMES.map((n, i) => {
  const r = rnd(i + 7);
  const photo = Math.floor(r() * 400) + 5, video = Math.floor(r() * 60);
  const drive = DRIVES[i % DRIVES.length];
  return {
    id: `d${i}`, rootId: `r${i % DRIVES.length}`, title: n, pathDisplay: `${drive}\\媒体\\${n}`,
    counts: { photo, video, gif: Math.floor(r() * 5) },
    last: { id: `d${i}-0`, type: "photo", name: "IMG_0001.jpg", takenAt: new Date(Date.now() - i * 86400000 * 3).toISOString() },
  };
});

const itemsCache = new Map<string, Item[]>();
function itemsOf(d: Dialog): Item[] {
  let list = itemsCache.get(d.id);
  if (list) return list;
  const r = rnd(d.id.length * 31 + Number(d.id.slice(1)));
  const n = d.counts.photo + d.counts.video + d.counts.gif;
  list = Array.from({ length: n }, (_, k) => {
    const video = k % 9 === 4 && d.counts.video > 0;
    const w = [4000, 3000, 1920, 1080, 3024][Math.floor(r() * 5)], h = [3000, 4000, 1080, 1920, 4032][Math.floor(r() * 5)];
    return {
      id: `${d.id}-${k}`, dialogId: d.id, name: video ? `VID_${String(k).padStart(4, "0")}.mp4` : `IMG_${String(k).padStart(4, "0")}.jpg`,
      ext: video ? "mp4" : "jpg", type: video ? "video" : "photo", size: Math.floor(r() * 8_000_000) + 200_000, w, h,
      takenAt: new Date(Date.now() - k * 3600_000 * 7 - Number(d.id.slice(1)) * 86400000).toISOString(),
      durationMs: video ? Math.floor(r() * 600_000) + 5000 : null, v: "1",
    };
  });
  itemsCache.set(d.id, list);
  return list;
}

function sorted(list: Item[], sort: string, dir: string, types: string[]): Item[] {
  const f = list.filter((x) => types.includes(x.type));
  const key = (x: Item) => sort === "name" ? x.name : sort === "size" ? String(x.size).padStart(12, "0") : sort === "type" ? x.type + x.name : x.takenAt ?? "";
  f.sort((a, b) => key(a).localeCompare(key(b)));
  return dir === "desc" ? f.reverse() : f;
}

function page(list: Item[], start: number, limit: number) {
  const s = Math.max(0, start), e = Math.min(list.length, s + limit);
  return { items: list.slice(s, e), total: list.length, prevCursor: s > 0 ? `i${Math.max(0, s - limit)}:${s}` : null, nextCursor: e < list.length ? `i${e}:${e + limit}` : null };
}

function defaultSettings(): Settings {
  const s: Record<string, unknown> = {};
  for (const rows of Object.values(SETTING_PAGES)) for (const r of rows) {
    if (r.type === "switch") s[r.key] = true;
    if (r.type === "choice") s[r.key] = (r.options.find(([, l]) => l.includes("默认")) ?? r.options[0])[0];
  }
  return { ...s, theme: "light", fontScale: 1, dialogSort: "recent", dialogMinCount: 0, dialogSubtitle: "last", avatarCover: true, defaultSort: "auto", defaultSortDir: "auto",
    gridSpacing: 2, gridThumbWidth: 480, chatImageWidth: 960, defaultColumns: 3, randomSeed: 3, randomType: 0, heicMode: "server", speedSeconds: 15, speedThreads: 1,
    updateSource: "", hwReport: "", rememberPosition: true, showDatePills: true, showVideoName: true, showDuration: true, slideshowBarColor: "#CCCCCC", slideshowBarThickness: 4, hapticLevel: 2, logLevel: "info", enhFrc: "off", enhUpscale: "off" } as unknown as Settings;
}

export function installBrowserMock() {
  let settings = defaultSettings();
  const session: SessionState = {
    activeId: "s1", loggedIn: true, local: false, baseUrl: "https://192.168.1.20:8480", pin: "AB:CD:EF:01:23:45",
    servers: [{ id: "s1", name: "家里的 NAS", kind: "server", host: "192.168.1.20:8480", baseUrl: "https://192.168.1.20:8480", address: "192.168.1.20", user: "admin", loggedIn: true }],
  };
  const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

  installMock(async (method, a) => {
    await sleep(120);
    switch (method) {
      case "boot":
        return { version: "5.0.0-dev", device: "Browser", model: "Browser", android: "浏览器预览", systemDark: true, session, settings, caps: { codecs: ["h264"], thumbWebp: true },
          route: "", notice: "", crash: "", hasCrash: false, insets: { top: 24, bottom: 16, left: 0, right: 0 } };
      case "settings.update": settings = { ...settings, ...a.patch }; return settings;
      case "settings.reset": settings = defaultSettings(); emitLocal("settings", settings); return settings;
      case "peek": return null;
      case "caps": return { codecs: ["h264"], thumbWebp: true };
      case "view.load": return { view: null, from: "" };
      case "view.recent": return { d0: "IMG_0042.jpg", d2: "VID_0013.mp4" };
      case "view.history": return { entries: [], config: { enabled: true, maxEntries: 100, retentionDays: 90, intervalSeconds: 60 } };
      case "session.lastAddress": return "";
      case "viewer.open": console.info("viewer.open", a.index, a.source); return null;
      case "log.info": return { size: 123456, crashes: [] };
      case "log.tail": return "10-31 12:00:00.000 I/app: 启动\n10-31 12:00:01.000 W/net: 慢请求\n";
      case "storage.info": return { images: 52_000_000, playback: 12 };
      case "app.changelog": return [{ version: "5.0.0", items: ["全新界面:React + HeroUI"] }];
      case "update.pending": return { pending: "", downloading: false, progress: -1, version: "", error: "" };
      case "lsfg.status": return { supported: true, lib: false, dllInAssets: false, bundled: false, cacheReady: false, state: 0, error: "" };
      case "codecs": return [{ label: "H.264 / AVC", mime: "video/avc", decoders: [{ name: "c2.qti.avc.decoder", hardware: true, detail: "最高 4096×2160" }] }];
      case "session.info": return { name: "MediaHub", version: "3.4.3" };
      case "net.ping": { const t = Date.now() / 1000; return { ms: Math.round((46 + 22 * Math.sin(t / 7) + Math.random() * 14 + (Math.random() < 0.06 ? 120 : 0)) * 10) / 10, protocol: "h2", tls: "TLSv1.3", lan: false }; }
      case "http": return http(a.path as string);
      default: return null;
    }
  });

  function http(path: string) {
    const u = new URL(path, "https://x");
    const q = Object.fromEntries(u.searchParams);
    if (u.pathname === "/api/v1/server/link") {
      const j = () => Math.round(38 + 10 * Math.sin(Date.now() / 5000) + Math.random() * 8);
      return { transport: { http1: true, http2: true, http3: false, quic: false, udp: false, tunnelProtocol: "quic", tunnelUDP: true },
        tunnel: { enabled: true, type: "frp", state: "running", restarts: 0, latency: { protocol: "quic", udp: true, chain: { last: j(), avg: 41, lossPct: 0, n: 30 }, server: { last: 22, avg: 24, lossPct: 0, n: 30 } } } };
    }
    if (u.pathname === "/api/v1/dialogs") return { dialogs, nextCursor: null };
    let m = u.pathname.match(/^\/api\/v1\/dialogs\/(d\d+)$/);
    if (m) return dialogs.find((d) => d.id === m![1]);
    m = u.pathname.match(/^\/api\/v1\/dialogs\/(d\d+)\/history$/);
    if (m) {
      const d = dialogs.find((x) => x.id === m![1]);
      if (!d) throw new BridgeError("notfound", 404, "notfound");
      const list = sorted(itemsOf(d), q.sort, q.dir, (q.types ?? "photo,video,gif").split(","));
      const limit = Number(q.limit ?? 60);
      if (q.cursor) { const [s] = q.cursor.slice(1).split(":").map(Number); return page(list, s, limit); }
      if (q.around) { const i = list.findIndex((x) => x.id === q.around); return page(list, Math.max(0, i - Math.floor(limit / 2)), limit); }
      return page(list, 0, limit);
    }
    if (u.pathname === "/api/v1/search") {
      const all = dialogs.filter((d) => !q.dialog || d.id === q.dialog).flatMap(itemsOf).filter((x) => x.name.toLowerCase().includes((q.q ?? "").toLowerCase()));
      const s = Number(q.cursor ?? 0);
      return { items: all.slice(s, s + 60), nextCursor: s + 60 < all.length ? String(s + 60) : null };
    }
    if (u.pathname === "/api/v1/random") {
      const all = dialogs.flatMap(itemsOf).filter((x) => (q.types ?? "photo,video,gif").split(",").includes(x.type));
      const r = rnd(Number(q.seed) * 1000 + Number(q.batch));
      return { items: Array.from({ length: 60 }, () => all[Math.floor(r() * all.length)]) };
    }
    throw new BridgeError(`模拟数据里没有 ${u.pathname}`, 404);
  }
}
