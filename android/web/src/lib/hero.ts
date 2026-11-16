// 缩略图 ↔ 原生查看器的展开 / 收回动画(以前版本的 Hero.kt,原生画):网页只报告缩略图在屏幕上的位置。
// 列表里的一格用 data-item="条目 id" 标记,里面真正显示图片的那块用 data-hero 标记(没有就用整格)。
import { call, emitLocal, on } from "./bridge";
import { revealItem } from "../components/Windowed";

export interface HeroRect { x: number; y: number; w: number; h: number }
/** full = 完整的图片框,visible = 没被顶栏 / 列表边缘挡住的部分,r = 圆角(都是 CSS 像素)。 */
export interface HeroInfo { full: HeroRect; visible: HeroRect; r: number }

/** 这一项在当前页面上显示着的那一格(被盖住 / 隐藏的标签页里的不算)。 */
function tileOf(id: string): HTMLElement | null {
  revealItem(id); // 分块渲染:这一项所在的块可能正是占位,先渲染出来
  for (const el of document.querySelectorAll<HTMLElement>(`[data-item="${CSS.escape(id)}"]`)) {
    if (el.closest("[inert]") || !el.checkVisibility({ visibilityProperty: true })) continue;
    return el;
  }
  return null;
}

const heroEl = (tile: HTMLElement) => (tile.matches("[data-hero]") ? tile : tile.querySelector<HTMLElement>("[data-hero]") ?? tile);
const box = (r: DOMRect | { left: number; top: number; right: number; bottom: number }): HeroRect =>
  ({ x: r.left, y: r.top, w: Math.max(0, r.right - r.left), h: Math.max(0, r.bottom - r.top) });

/** 这一格有多少露在可视区里(0 ~ 1;完全在屏幕外为 0)。 */
function shownFrac(i: HeroInfo & { shown: number }) { return i.shown; }

function infoOf(tile: HTMLElement): (HeroInfo & { shown: number }) | null {
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
  const seen = Math.max(0, vis.bottom - vis.top);
  return { full: box(r), visible: seen > 0 ? box(vis) : box(r), r: parseFloat(getComputedStyle(el).borderTopLeftRadius) || 0, shown: seen / r.height };
}

/** 打开查看器时一起传给原生:缩略图位置和网页里显示的那张缩略图地址(原生先拿它垫底)。 */
export function heroArgs(id: string): { hero: HeroInfo | null; thumb: string | null } {
  openedId = id;
  anchor = null;
  const tile = tileOf(id);
  if (!tile) return { hero: null, thumb: null };
  // 记下点开时这一格在屏幕上的位置:没翻到别的项就关闭时,关闭后它必须还在这个位置(见 restoreAnchor)
  anchor = { id, top: heroEl(tile).getBoundingClientRect().top };
  const img = heroEl(tile).querySelector("img");
  return { hero: infoOf(tile), thumb: img?.currentSrc || null };
}

// 查看器正在看的那一项:藏起列表里的这一格(看起来是它被拿起来了),
// 不在屏幕里就滚过去,并把位置报给原生 —— 返回动画落到它在列表里的位置
let style: HTMLStyleElement | null = null;
let timer = 0;
let current = "";
/** 点开时记下的位置;翻到别的项(列表为了让它露出来滚动过)后作废。 */
let anchor: { id: string; top: number } | null = null;

/** 这次点开的那一项:它是用户刚点的、本来就在看得见的地方,不为它滚动列表(见 [track])。 */
let openedId = "";

function report(id: string, tile: HTMLElement) {
  const info = infoOf(tile);
  // 顺带把这一格的缩略图地址给原生:翻到的每一张都有正确的宽高比和垫底图(只有点开的那张预先准备过)
  const thumb = heroEl(tile).querySelector("img")?.currentSrc || null;
  if (info) call("viewer.bounds", { id, hero: { full: info.full, visible: info.visible, r: info.r }, thumb }).catch(() => {});
}

/**
 * 跟住正在看的那一项:露出来不到 60% 就滚到屏幕中间(看不见的列表,用户察觉不到),再把位置报给原生。
 * 列表里还没有这一项(查看器自己往后加载了更多)时,发 "viewerMissing" 让页面围绕它重新加载,这里每 200ms 再找,最多 2 秒。
 */
function track(id: string, tries = 0) {
  if (id !== current) return;
  const tile = tileOf(id);
  if (!tile) {
    if (tries === 0) emitLocal("viewerMissing", id);
    if (tries < 10) timer = window.setTimeout(() => track(id, tries + 1), 200);
    return;
  }
  const now = infoOf(tile);
  // 刚点开的那一项不滚列表:以前它只露出不到 60%(被顶栏挡住一半)时会马上滚到居中,而返回动画的落点还是点开时量的旧位置,
  // 打开后立刻返回 → 动画落到滚动前的位置(那里已经是别的图片了),看起来像跳到了别处。翻到别的项时才需要滚过去。
  if (id !== openedId) anchor = null; // 翻到了别的项:列表会跟着滚,不再按点开时的位置还原
  const scrolled = id !== openedId && (!now || shownFrac(now) < 0.6);
  if (scrolled) {
    tile.scrollIntoView({ block: "center" });
    report(id, tile); // 滚动是同步的:马上报告新位置,返回动画不会落到旧位置
  }
  timer = window.setTimeout(() => { if (id === current) report(id, tile); }, 32);
}

on("viewerCurrent", (id: string) => {
  style ??= document.head.appendChild(document.createElement("style"));
  const sel = id ? `[data-item="${CSS.escape(id)}"]` : "";
  style.textContent = id ? `${sel} [data-hero], ${sel}[data-hero] { opacity: 0 !important; }` : "";
  clearTimeout(timer);
  current = id;
  if (id) track(id);
});

// 返回动画已经落到缩略图上:先把这一格显示出来,画好之后(两帧)告诉原生再移走查看器,中间不会有一帧是空的
/**
 * 关闭查看器后,让点开的那一格回到点开前在屏幕上的位置。没翻页的话列表本来不该动,但状态栏 / 导航栏在查看器里显示、隐藏会改变页面的安全区,
 * 分块渲染的块高度变化等也会让内容挪动,结果就是关闭后图片的位置和点开前不一样。这里直接按"点开时的位置"校正(只在差 1px 以上时滚,
 * 关闭后的 0.8 秒内重复检查几次;用户一碰屏幕就停止)。
 */
let restoreTimers: number[] = [];
function restoreAnchor() {
  const a = anchor;
  if (!a) return;
  const tile = tileOf(a.id);
  if (!tile) return;
  const el = heroEl(tile);
  const delta = el.getBoundingClientRect().top - a.top;
  if (Math.abs(delta) < 1) return;
  const page = el.closest<HTMLElement>(".page");
  if (!page) return;
  console.warn(`关闭查看器后列表位置偏了 ${delta.toFixed(1)}px,已校正(sat=${getComputedStyle(document.documentElement).getPropertyValue("--sat")})`);
  page.scrollBy({ top: delta, behavior: "instant" });
}
function stopRestore() { restoreTimers.forEach(clearTimeout); restoreTimers = []; window.removeEventListener("touchstart", stopRestore, true); }

on("viewerReveal", () => {
  if (style) style.textContent = "";
  stopRestore();
  restoreAnchor();
  window.addEventListener("touchstart", stopRestore, true);
  restoreTimers = [60, 160, 320, 560, 800].map((ms) => window.setTimeout(() => { restoreAnchor(); if (ms === 800) stopRestore(); }, ms));
  requestAnimationFrame(() => requestAnimationFrame(() => { call("viewer.revealed").catch(() => {}); }));
});
