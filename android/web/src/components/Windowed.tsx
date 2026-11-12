import { useEffect, useLayoutEffect, useRef, useState, type CSSProperties, type ReactNode } from "react";
import { flushSync } from "react-dom";

/**
 * 长列表 / 网格的"按块虚拟化":条目按块(几十个一块)渲染,离开可视区 1.5 屏以外的块换成一个同样高度的空占位,
 * 滚回来再渲染。每一块的真实高度在它显示时量好(格子都有固定宽高比,高度是确定的),换成占位前后滚动位置不会跳。
 *
 * 为什么不用 content-visibility:它按估算高度占位,滑进来时变成真实高度,快速滑动时上下内容会跳(见 index.css 的说明)。
 *
 * 块的边界从"原点"(这次加载时的第一项)往两边按固定大小切:往后加载 / 往前加载都不会挪动已有块的边界,
 * 已经量好的块不用重新渲染。只有原点前面最靠上的那一块可能不满。
 */

// ---- 查看器 / 定位要找某一项而它所在的块正是占位时:先把那块渲染出来
const reveals = new Map<string, Set<() => void>>();

/**
 * 确保这一项所在的块已经渲染。sync = true(事件 / 原生回调里):同步完成,之后马上能 querySelector 到它;
 * 在 React 的生命周期(layout effect)里不能 flushSync,传 false:只发起显示,调用方下一帧再找。
 */
export function revealItem(id: string, sync = true) {
  const fs = reveals.get(id);
  if (!fs?.size) return;
  if (sync) flushSync(() => fs.forEach((f) => f()));
  else fs.forEach((f) => f());
}

// ---- 每个滚动容器共用一个 IntersectionObserver
type Obs = { io: IntersectionObserver; cbs: Map<Element, (visible: boolean) => void> };
const observers = new WeakMap<Element, Obs>();

function observe(root: Element, el: Element, cb: (visible: boolean) => void) {
  let o = observers.get(root);
  if (!o) {
    const cbs = new Map<Element, (visible: boolean) => void>();
    const io = new IntersectionObserver((es) => { for (const e of es) cbs.get(e.target)?.(e.isIntersecting); }, { root, rootMargin: "150% 0px" });
    o = { io, cbs };
    observers.set(root, o);
  }
  const obs = o;
  obs.cbs.set(el, cb);
  obs.io.observe(el);
  return {
    /** 重新观察一次:会立刻收到一次当前是否可见的回调 */
    refresh: () => { obs.io.unobserve(el); obs.io.observe(el); },
    stop: () => { obs.io.unobserve(el); obs.cbs.delete(el); },
  };
}

/**
 * 一块:可见(含上下 1.5 屏)时渲染 children,否则是一个同高的空 div。
 * ids = 这一块里的条目 id(用于 [revealItem])。
 */
export function Chunk({ ids, className, style, children }: { ids: string[]; className?: string; style?: CSSProperties; children: ReactNode }) {
  const ref = useRef<HTMLDivElement>(null);
  const [hiddenH, setHiddenH] = useState<number | null>(null); // null = 正在显示
  const size = useRef({ h: 0, w: 0 });
  const pinUntil = useRef(0);
  const ctl = useRef<ReturnType<typeof observe> | null>(null);
  const shown = hiddenH == null;

  // 显示期间一直量高度(换成占位时用)
  useLayoutEffect(() => {
    const el = ref.current;
    if (!el || !shown) return;
    const measure = () => { size.current = { h: el.offsetHeight, w: el.offsetWidth }; };
    measure();
    const ro = new ResizeObserver(measure);
    ro.observe(el);
    return () => ro.disconnect();
  }, [shown]);

  useEffect(() => {
    const el = ref.current;
    const root = el?.closest(".page");
    if (!el || !root) return;
    const c = observe(root, el, (visible) => {
      if (visible) { setHiddenH(null); return; }
      // 刚被 reveal 的块先留着;还没量到高度 / 宽度变了(转屏)的块不能换成占位
      if (Date.now() < pinUntil.current || size.current.h <= 0 || el.offsetWidth !== size.current.w) return;
      setHiddenH(size.current.h);
    });
    ctl.current = c;
    return () => { c.stop(); ctl.current = null; };
  }, []);

  // 转屏 / 窗口变宽:占位的高度不再对,先全部显示出来重新量,量完再按是否可见收起
  useEffect(() => {
    let t = 0;
    const onResize = () => {
      if (!ref.current || ref.current.offsetWidth === size.current.w) return;
      setHiddenH(null);
      clearTimeout(t);
      t = window.setTimeout(() => ctl.current?.refresh(), 400);
    };
    window.addEventListener("resize", onResize);
    return () => { window.removeEventListener("resize", onResize); clearTimeout(t); };
  }, []);

  const idsKey = ids.join(",");
  useEffect(() => {
    let t = 0;
    const f = () => {
      pinUntil.current = Date.now() + 1500;
      setHiddenH(null);
      clearTimeout(t);
      t = window.setTimeout(() => ctl.current?.refresh(), 1600);
    };
    const list = idsKey ? idsKey.split(",") : [];
    for (const id of list) {
      let s = reveals.get(id);
      if (!s) reveals.set(id, (s = new Set()));
      s.add(f);
    }
    return () => {
      clearTimeout(t);
      for (const id of list) { const s = reveals.get(id); s?.delete(f); if (s && !s.size) reveals.delete(id); }
    };
  }, [idsKey]);

  return (
    <div ref={ref} className={className} style={shown ? style : { ...style, height: hiddenH }}>
      {shown ? children : null}
    </div>
  );
}

export interface ChunkRange { start: number; end: number; key: string }

/**
 * 把列表切成块:边界在 原点 ± k×size(原点 = 这次加载的第一项,往前加载时它的下标变大,已有块的边界不变)。
 * 列表整体换掉(原点不在了)时以新的第一项为原点。
 */
export function useChunks<T extends { id: string }>(items: T[], size: number): ChunkRange[] {
  const origin = useRef<string | null>(null);
  let oi = origin.current ? items.findIndex((x) => x.id === origin.current) : -1;
  if (oi < 0) { oi = 0; origin.current = items[0]?.id ?? null; }
  const out: ChunkRange[] = [];
  const first = oi % size;
  // 键带上块大小:列数变了块的范围全变,旧的高度不能沿用
  if (first > 0) out.push({ start: 0, end: first, key: `${size}:${items[0].id}` });
  for (let i = first; i < items.length; i += size) out.push({ start: i, end: Math.min(items.length, i + size), key: `${size}:${items[i].id}` });
  return out;
}

/** 窗口宽度(CSS 像素),转屏 / 分屏时更新。 */
export function useViewportWidth(): number {
  const [w, setW] = useState(() => window.innerWidth);
  useEffect(() => {
    const on = () => setW(window.innerWidth);
    window.addEventListener("resize", on);
    return () => window.removeEventListener("resize", on);
  }, []);
  return w;
}

/**
 * 宽屏(横屏 / 平板)时的列数:手机竖屏(约 400px 宽)按设置的列数,更宽时按宽度等比例加列,保持每格大小差不多。
 */
export function adaptiveColumns(base: number, width: number, max = 12): number {
  if (width <= 600) return base;
  return Math.min(max, Math.max(base, Math.round((base * width) / 420)));
}

/** 网格里一块放多少格:整行数,约 48 格。 */
export const gridChunk = (columns: number) => columns * Math.max(4, Math.ceil(48 / columns));
