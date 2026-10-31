// 媒体地址与格式化(规则与原生 Api.imageUrl 一致)。媒体都走 /srv/ 前缀,由原生转发给当前服务器(令牌不进网页)。
import type { Dialog, Item, ServerCaps } from "./types";

const NATIVE_IMAGE_EXT = new Set(["jpg", "jpeg", "jpe", "jfif", "png", "webp", "bmp", "gif"]);
const HEIC_EXT = new Set(["heic", "heif", "hif", "avif"]);
const THUMB_EXT = new Set(["jpg", "jpeg", "jpe", "jfif", "png", "webp", "bmp"]);

export interface MediaEnv { caps: ServerCaps; local: boolean; renderHeic: boolean; mock?: boolean }

let env: MediaEnv = { caps: { codecs: [] }, local: false, renderHeic: true };
export function setMediaEnv(e: MediaEnv) { env = e; }

const SRV = "/srv/api/v1/media/";

function needsServerRender(ext: string): boolean {
  const e = ext.toLowerCase();
  if (NATIVE_IMAGE_EXT.has(e)) return false;
  if (HEIC_EXT.has(e)) return env.renderHeic;
  return true;
}

/** 浏览器预览用的假图:按 id 生成一张渐变 SVG。 */
function mockUrl(id: string, w: number, h?: number) {
  let x = 0;
  for (const c of id) x = (x * 31 + c.charCodeAt(0)) >>> 0;
  const a = x % 360, b = (a + 40 + (x % 80)) % 360, hh = h ?? Math.round(w * 0.75);
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="${w}" height="${hh}"><defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="hsl(${a},70%,55%)"/><stop offset="1" stop-color="hsl(${b},60%,35%)"/></linearGradient></defs><rect width="100%" height="100%" fill="url(#g)"/><circle cx="${w * 0.7}" cy="${hh * 0.3}" r="${Math.min(w, hh) * 0.12}" fill="rgba(255,255,255,.55)"/><path d="M0 ${hh} L${w * 0.35} ${hh * 0.55} L${w * 0.6} ${hh * 0.8} L${w * 0.8} ${hh * 0.6} L${w} ${hh} Z" fill="rgba(0,0,0,.25)"/></svg>`;
  return `data:image/svg+xml,${encodeURIComponent(svg)}`;
}

export function fileUrl(item: Item): string { return `${SRV}${item.id}/file?v=${item.v ?? ""}`; }
export function posterUrl(item: Pick<Item, "id" | "v">): string {
  if (env.mock) return mockUrl(item.id, 480);
  return `${SRV}${item.id}/poster?v=${item.v ?? ""}`;
}

/** 显示图片用的地址:w = 需要的长边像素(服务端取最近的一档)。 */
export function imageUrl(item: Item, w: number): string {
  if (env.mock) return mockUrl(item.id, Math.min(w, 800), item.w && item.h ? Math.round((Math.min(w, 800) * item.h) / item.w) : undefined);
  const v = item.v ?? "";
  if (env.local) return item.ext === "gif" || w > 2000 ? fileUrl(item) : `${SRV}${item.id}/render?w=${Math.min(w, 4096)}&v=${v}`;
  if (needsServerRender(item.ext)) return `${SRV}${item.id}/render?w=${w}&v=${v}`;
  const eligible = !!env.caps.thumbWebp && item.type === "photo" && THUMB_EXT.has(item.ext.toLowerCase()) && w >= 1 && w <= 1440 &&
    (item.size ?? 0) > 120_000 && (item.w ?? 0) > w;
  return eligible ? `${SRV}${item.id}/render?w=${w}&fmt=webp&v=${v}` : fileUrl(item);
}

/** 缩略图:视频用封面帧,其余用图片地址。 */
export function thumbUrl(item: Item, w: number): string {
  return item.type === "video" ? posterUrl(item) : imageUrl(item, w);
}

/** 文件夹头像(最近一项的缩略图)。 */
export function dialogCover(d: Dialog): string | undefined {
  const l = d.last;
  if (!l) return undefined;
  const ext = l.name.includes(".") ? l.name.slice(l.name.lastIndexOf(".") + 1).toLowerCase() : "";
  const item: Item = { id: l.id, dialogId: d.id, name: l.name, ext, type: l.type, w: l.w, h: l.h, v: d.version };
  return l.type === "video" ? posterUrl(item) : imageUrl(item, 160);
}

export function rootLabel(d: Dialog): string { return d.pathDisplay.split("\\")[0] ?? ""; }
export function mediaCount(d: Dialog): number { return (d.counts?.photo ?? 0) + (d.counts?.video ?? 0) + (d.counts?.gif ?? 0); }

export function formatDuration(ms?: number | null): string {
  if (!ms || ms <= 0) return "";
  const s = Math.round(ms / 1000);
  const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), sec = s % 60;
  const pad = (n: number) => String(n).padStart(2, "0");
  return h > 0 ? `${h}:${pad(m)}:${pad(sec)}` : `${m}:${pad(sec)}`;
}

export function formatSize(b?: number | null): string {
  if (b == null || b < 0) return "";
  if (b < 1024) return `${b} B`;
  const u = ["KB", "MB", "GB", "TB"];
  let v = b / 1024, i = 0;
  while (v >= 1024 && i < u.length - 1) { v /= 1024; i++; }
  return `${v >= 100 ? v.toFixed(0) : v.toFixed(1)} ${u[i]}`;
}

/** 列表右侧的时间:当天显示时刻,今年内显示月日,更早显示年月日。 */
export function formatShortTime(iso?: string): string {
  if (!iso) return "";
  const t = new Date(iso);
  if (isNaN(t.getTime())) return "";
  const now = new Date();
  const pad = (n: number) => String(n).padStart(2, "0");
  if (t.toDateString() === now.toDateString()) return `${pad(t.getHours())}:${pad(t.getMinutes())}`;
  if (t.getFullYear() === now.getFullYear()) return `${t.getMonth() + 1}月${t.getDate()}日`;
  return `${t.getFullYear()}/${t.getMonth() + 1}/${t.getDate()}`;
}

/** 时间线的日期分组标题:今天 / 昨天 / M月D日 星期X / YYYY年M月D日。 */
export function dayKey(iso?: string): string {
  if (!iso) return "";
  const t = new Date(iso);
  if (isNaN(t.getTime())) return "";
  return `${t.getFullYear()}-${t.getMonth() + 1}-${t.getDate()}`;
}

export function dayLabel(iso?: string): string {
  if (!iso) return "未知日期";
  const t = new Date(iso);
  if (isNaN(t.getTime())) return "未知日期";
  const now = new Date();
  const y = new Date(now); y.setDate(now.getDate() - 1);
  if (t.toDateString() === now.toDateString()) return "今天";
  if (t.toDateString() === y.toDateString()) return "昨天";
  const wd = "日一二三四五六"[t.getDay()];
  return t.getFullYear() === now.getFullYear() ? `${t.getMonth() + 1}月${t.getDate()}日 星期${wd}` : `${t.getFullYear()}年${t.getMonth() + 1}月${t.getDate()}日`;
}

export function formatDateTime(ms?: number): string {
  if (!ms) return "";
  const t = new Date(ms);
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${t.getFullYear()}-${pad(t.getMonth() + 1)}-${pad(t.getDate())} ${pad(t.getHours())}:${pad(t.getMinutes())}`;
}

export function stripExt(name: string): string { const i = name.lastIndexOf("."); return i > 0 ? name.slice(0, i) : name; }

export function aspectOf(item: Item): number {
  let w = item.w ?? 0, h = item.h ?? 0;
  if (item.rotation === 90 || item.rotation === 270) [w, h] = [h, w];
  return w > 0 && h > 0 ? w / h : 1;
}

export function typesLabel(types: string[]): string {
  const m: Record<string, string> = { photo: "照片", video: "视频", gif: "动图" };
  return types.map((t) => m[t] ?? t).join("、");
}
