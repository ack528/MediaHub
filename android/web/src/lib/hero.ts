// 缩略图 ↔ 原生查看器的展开 / 收回动画(以前版本的 Hero.kt,原生画):网页只报告缩略图在屏幕上的位置。
// 列表里的一格用 data-item="条目 id" 标记,里面真正显示图片的那块用 data-hero 标记(没有就用整格)。
import { call, on } from "./bridge";

export interface HeroRect { x: number; y: number; w: number; h: number }
/** full = 完整的图片框,visible = 没被顶栏 / 列表边缘挡住的部分,r = 圆角(都是 CSS 像素)。 */
export interface HeroInfo { full: HeroRect; visible: HeroRect; r: number }

/** 这一项在当前页面上显示着的那一格(被盖住 / 隐藏的标签页里的不算)。 */
function tileOf(id: string): HTMLElement | null {
  for (const el of document.querySelectorAll<HTMLElement>(`[data-item="${CSS.escape(id)}"]`)) {
    if (el.closest("[inert]") || !el.checkVisibility({ visibilityProperty: true })) continue;
    return el;
  }
  return null;
}

const heroEl = (tile: HTMLElement) => (tile.matches("[data-hero]") ? tile : tile.querySelector<HTMLElement>("[data-hero]") ?? tile);
const box = (r: DOMRect | { left: number; top: number; right: number; bottom: number }): HeroRect =>
  ({ x: r.left, y: r.top, w: Math.max(0, r.right - r.left), h: Math.max(0, r.bottom - r.top) });

function infoOf(tile: HTMLElement): HeroInfo | null {
  const el = heroEl(tile);
  const r = el.getBoundingClientRect();
  if (r.width <= 0 || r.height <= 0) return null;
  // 看得见的范围:所在滚动容器的可视区,去掉吸顶的顶栏
  let top = 0, bottom = innerHeight;
  const page = el.closest<HTMLElement>(".page");
  if (page) {
    const pr = page.getBoundingClientRect();
    top = Math.max(top, pr.top); bottom = Math.min(bottom, pr.bottom);
    const header = page.querySelector("header");
    if (header) top = Math.max(top, header.getBoundingClientRect().bottom);
  }
  const vis = { left: r.left, right: r.right, top: Math.max(r.top, top), bottom: Math.min(r.bottom, bottom) };
  return { full: box(r), visible: vis.bottom > vis.top ? box(vis) : box(r), r: parseFloat(getComputedStyle(el).borderTopLeftRadius) || 0 };
}

/** 打开查看器时一起传给原生:缩略图位置和网页里显示的那张缩略图地址(原生先拿它垫底)。 */
export function heroArgs(id: string): { hero: HeroInfo | null; thumb: string | null } {
  const tile = tileOf(id);
  if (!tile) return { hero: null, thumb: null };
  const img = heroEl(tile).querySelector("img");
  return { hero: infoOf(tile), thumb: img?.currentSrc || null };
}

// 查看器正在看的那一项:藏起列表里的这一格(看起来是它被拿起来了),
// 不在屏幕里就滚过去,并把位置报给原生 —— 返回动画落到它在列表里的位置
let style: HTMLStyleElement | null = null;
let timer = 0;
on("viewerCurrent", (id: string) => {
  style ??= document.head.appendChild(document.createElement("style"));
  const sel = id ? `[data-item="${CSS.escape(id)}"]` : "";
  style.textContent = id ? `${sel} [data-hero], ${sel}[data-hero] { opacity: 0 !important; }` : "";
  clearTimeout(timer);
  if (!id) return;
  const tile = tileOf(id);
  if (!tile) return;
  const now = infoOf(tile);
  if (!now || now.visible.h < now.full.h * 0.6) tile.scrollIntoView({ block: "nearest" });
  timer = window.setTimeout(() => {
    const info = infoOf(tile);
    // 顺带把这一格的缩略图地址给原生:翻到的每一张都有正确的宽高比和垫底图(只有点开的那张预先准备过)
    const thumb = heroEl(tile).querySelector("img")?.currentSrc || null;
    if (info) call("viewer.bounds", { id, hero: info, thumb }).catch(() => {});
  }, 32);
});

// 返回动画已经落到缩略图上:先把这一格显示出来,画好之后(两帧)告诉原生再移走查看器,中间不会有一帧是空的
on("viewerReveal", () => {
  if (style) style.textContent = "";
  requestAnimationFrame(() => requestAnimationFrame(() => { call("viewer.revealed").catch(() => {}); }));
});
