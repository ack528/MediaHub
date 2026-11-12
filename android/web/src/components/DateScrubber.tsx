import { useEffect, useRef, useState, useSyncExternalStore, type RefObject } from "react";
import { haptic } from "../lib/bridge";
import { dayLabel } from "../lib/media";

/** 一个极小的外部状态:屏幕顶端那一项的时间。滚动时每帧更新,只有订阅它的小部件重新渲染(整页不跟着重渲染)。 */
export interface ValueStore<T> { get: () => T; set: (v: T) => void; sub: (l: () => void) => () => void }
export function createStore<T>(init: T): ValueStore<T> {
  let v = init;
  const ls = new Set<() => void>();
  return {
    get: () => v,
    set: (n) => { if (n !== v) { v = n; ls.forEach((l) => l()); } },
    sub: (l) => { ls.add(l); return () => { ls.delete(l); }; },
  };
}

/** 滚动中(以及停下后 1.5 秒内)为 true。 */
function useScrolling(scroller: RefObject<HTMLElement | null>): boolean {
  const [on, setOn] = useState(false);
  useEffect(() => {
    const sc = scroller.current;
    if (!sc) return;
    let t = 0;
    const f = () => { setOn(true); clearTimeout(t); t = window.setTimeout(() => setOn(false), 1500); };
    sc.addEventListener("scroll", f, { passive: true });
    return () => { sc.removeEventListener("scroll", f); clearTimeout(t); };
  }, [scroller]);
  return on;
}

const monthLabel = (ms: number) => { const d = new Date(ms); return `${d.getFullYear()} 年 ${d.getMonth() + 1} 月`; };
const ymd = (ms: number) => { const d = new Date(ms); const p = (n: number) => String(n).padStart(2, "0"); return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`; };
const clamp01 = (x: number) => Math.max(0, Math.min(1, x));
const THUMB = 48;

/**
 * 右侧的日期滑条(按时间排序时):滚动时出现,拖动把手时旁边显示年月,松手跳到那个日期。
 * 位置按时间线性换算:a = 当前顺序里第一项的时间,b = 最后一项的时间(升序 / 降序都成立)。
 * 只加载了文件夹的一部分(分页),所以松手后是"跳到那一天附近重新加载",和「跳到日期」一样。
 */
export function DateScrubber({ scroller, top, range, onJump }: {
  scroller: RefObject<HTMLElement | null>;
  top: ValueStore<number | null>;
  range: { a: number; b: number };
  onJump: (date: string) => void;
}) {
  const cur = useSyncExternalStore(top.sub, top.get);
  const scrolling = useScrolling(scroller);
  const [drag, setDrag] = useState<number | null>(null); // 拖动中 / 刚松手:把手的位置(0 ~ 1)
  const [dragging, setDragging] = useState(false);
  const track = useRef<HTMLDivElement>(null);
  const lastMonth = useRef("");
  const releaseTimer = useRef(0);

  const span = range.b - range.a;
  const f = drag ?? (cur == null || span === 0 ? 0 : clamp01((cur - range.a) / span));
  // 松手后:等列表跳过去(顶端那一项变了)再让把手回到按位置计算
  useEffect(() => { if (!dragging) setDrag(null); }, [cur]); // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => () => clearTimeout(releaseTimer.current), []);

  const fracAt = (clientY: number) => {
    const r = track.current!.getBoundingClientRect();
    return clamp01((clientY - r.top - THUMB / 2) / Math.max(1, r.height - THUMB));
  };
  const at = (fr: number) => range.a + fr * span;

  const down = (e: React.PointerEvent) => {
    e.currentTarget.setPointerCapture(e.pointerId);
    clearTimeout(releaseTimer.current);
    setDragging(true);
    const fr = fracAt(e.clientY);
    lastMonth.current = monthLabel(at(fr));
    setDrag(fr);
    haptic("Tick");
  };
  const move = (e: React.PointerEvent) => {
    if (!dragging) return;
    const fr = fracAt(e.clientY);
    setDrag(fr);
    const m = monthLabel(at(fr));
    if (m !== lastMonth.current) { lastMonth.current = m; haptic("Tick"); }
  };
  const up = (e: React.PointerEvent, cancel = false) => {
    if (!dragging) return;
    setDragging(false);
    if (cancel) { setDrag(null); return; }
    onJump(ymd(at(fracAt(e.clientY))));
    releaseTimer.current = window.setTimeout(() => setDrag(null), 2500); // 没跳动(同一处)时也要回来
  };

  const visible = scrolling || dragging;
  return (
    <div ref={track} aria-hidden className="pointer-events-none fixed right-0 z-30 w-14 transition-opacity duration-200"
      style={{ top: "calc(var(--sat) + 72px)", bottom: "calc(var(--sab) + 20px)", opacity: visible ? 1 : 0 }}>
      <div
        className={`absolute right-1 grid w-9 place-items-center rounded-full bg-surface text-muted shadow-overlay touch-none ${visible ? "pointer-events-auto" : ""}`}
        style={{ height: THUMB, top: `calc((100% - ${THUMB}px) * ${f})` }}
        onPointerDown={down} onPointerMove={move} onPointerUp={(e) => up(e)} onPointerCancel={(e) => up(e, true)}
      >
        <svg viewBox="0 0 24 24" className="size-5 fill-current"><path d="M12 4.5 7.5 9.5h9zM12 19.5l4.5-5h-9z" /></svg>
      </div>
      {dragging && drag != null && (
        <div className="absolute right-12 whitespace-nowrap rounded-full bg-accent px-3 py-1.5 text-sm font-medium text-accent-foreground shadow-overlay tabular-nums"
          style={{ top: `calc((100% - ${THUMB}px) * ${drag} + ${THUMB / 2}px - 16px)` }}>
          {monthLabel(at(drag))}
        </div>
      )}
    </div>
  );
}

/** 时间线顶部浮着的"当前这一天"(以前每个日期胶囊是吸顶的;分块渲染后吸顶只在块内有效,改成一个浮层)。 */
export function DayPill({ top }: { top: ValueStore<number | null> }) {
  const cur = useSyncExternalStore(top.sub, top.get);
  if (cur == null) return null;
  return (
    <div className="pointer-events-none fixed inset-x-0 z-10 flex justify-center" style={{ top: "calc(var(--sat) + 62px)" }}>
      {/* 实底(不是半透明的软色):浮在图片上也看得清 */}
      <span className="rounded-full bg-surface/95 px-3 py-1 text-xs font-medium text-accent shadow-overlay">{dayLabel(new Date(cur).toISOString())}</span>
    </div>
  );
}
