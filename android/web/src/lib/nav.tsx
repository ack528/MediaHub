// 页面栈 + 返回:系统返回(原生 PredictiveBackHandler → back* 事件)先关最上层的弹层,再退回上一页。
// 转场照 iOS / Telegram:新页从右边推入,下层页面向左视差移动并压暗;返回时反过来。
// 跟手返回:页面上向右横滑,页面跟着手指走,松手按距离 / 速度决定退回还是弹回;
// 系统侧滑返回给进度(Android 14+ 支持的系统)时同样跟手,只在松手时发返回的 ROM 直接播放返回动画。
// 动画都用 Web Animations 改 transform / opacity,在合成线程跑,新页面首次渲染卡一下也不掉帧。
import { createContext, useCallback, useContext, useEffect, useLayoutEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { flushSync } from "react-dom";
import { call, inApp, on } from "./bridge";

export type Route =
  | { name: "chat"; params: { id: string; title?: string } }
  | { name: "search"; params?: { dialogId?: string; title?: string } }
  | { name: "random"; params: { label?: string | null; roots?: string | null } }
  | { name: "settings"; params: { section: string } };

interface Entry { key: number; route: Route }

interface NavState {
  stack: Entry[];
  push: (r: Route) => void;
  pop: () => void;
  /** 退回首页(切换服务器、退出登录后)。 */
  reset: () => void;
}

const NavCtx = createContext<NavState | null>(null);
const EntryCtx = createContext<{ key: number; active: boolean }>({ key: 0, active: true });

export function useNav(): NavState {
  const v = useContext(NavCtx);
  if (!v) throw new Error("useNav outside provider");
  return v;
}

/** 当前页面是不是在最上层(被别的页面盖住时为 false)。 */
export function usePageActive(): boolean { return useContext(EntryCtx).active; }

// ---- 返回键处理器栈(弹层、搜索框、非首页标签…)----
type Handler = { id: number; fn: () => void };
const handlers: Handler[] = [];
let handlerSeq = 0;
let notify: () => void = () => {};

/** enabled 时按返回键调用 fn(后注册的先处理)。 */
export function useBackHandler(enabled: boolean, fn: () => void) {
  const ref = useRef(fn);
  ref.current = fn;
  useEffect(() => {
    if (!enabled) return;
    const h: Handler = { id: ++handlerSeq, fn: () => ref.current() };
    handlers.push(h);
    notify();
    return () => {
      const i = handlers.indexOf(h);
      if (i >= 0) handlers.splice(i, 1);
      notify();
    };
  }, [enabled]);
}

// ---- 转场参数 ----
const PARALLAX = 0.3; // 下层页面移动的比例(相对屏幕宽)
const SHADE = 0.14; // 下层页面最多压暗
const EASE = "cubic-bezier(0.32, 0.72, 0, 1)"; // 推入 / 返回(和 iOS 导航栏转场同一条曲线)
const EASE_OUT = "cubic-bezier(0.22, 1, 0.36, 1)"; // 松手后接着手指的速度减速
const PUSH_MS = 420;
const POP_MS = 340;

const tx = (p: number) => (p > 0 ? `translate3d(${p * innerWidth}px,0,0)` : "none");
const ux = (p: number) => (p < 1 ? `translate3d(${-PARALLAX * (1 - p) * innerWidth}px,0,0)` : "none");

/** 返回进度 p:0 = 上层页面完全盖住,1 = 上层页面完全移出、下层回到原位。 */
interface Pair { top: HTMLElement; under: HTMLElement; shade: HTMLElement }

function paint({ top, under, shade }: Pair, p: number) {
  top.style.transform = tx(p);
  under.style.transform = ux(p);
  shade.style.opacity = String(SHADE * (1 - p));
}

function rest({ top, under, shade }: Pair) {
  top.style.transform = ""; under.style.transform = ""; shade.style.opacity = "0";
  top.style.willChange = ""; under.style.willChange = "";
}

export function NavProvider({ children, render, animate = true }: { children: ReactNode; render: (r: Route) => ReactNode; animate?: boolean }) {
  const [stack, setStack] = useState<Entry[]>([]);
  const seq = useRef(0);
  const [, force] = useState(0);
  const stackRef = useRef(stack);
  stackRef.current = stack;
  const animRef = useRef(animate && !matchMedia("(prefers-reduced-motion: reduce)").matches);
  animRef.current = animate && !matchMedia("(prefers-reduced-motion: reduce)").matches;

  const layers = useRef(new Map<number, HTMLDivElement>()); // key 0 = 首页
  const shadeRef = useRef<HTMLDivElement>(null);
  const hostRef = useRef<HTMLDivElement>(null);
  const running = useRef<Animation[]>([]);
  const popping = useRef(false);
  const queued = useRef(0);
  const progress = useRef(0); // 当前跟手的返回进度
  const sysBack = useRef(false); // 系统侧滑返回正在给进度

  /** 最上层和它下面一层(没有可退的页面时为 null)。 */
  const pair = useCallback((): Pair | null => {
    const s = stackRef.current;
    if (!s.length) return null;
    const top = layers.current.get(s[s.length - 1].key);
    const under = layers.current.get(s.length > 1 ? s[s.length - 2].key : 0);
    const shade = shadeRef.current;
    return top && under && shade ? { top, under, shade } : null;
  }, []);

  /** 正在播放的转场直接跳到结尾(接下来要开始新的转场或手势)。 */
  const settle = useCallback(() => {
    const a = running.current;
    running.current = [];
    a.forEach((x) => x.finish());
  }, []);

  /** 从 from 动画到 to;结束后各层停在终点的内联样式上。 */
  const run = useCallback((pr: Pair, from: number, to: number, ms: number, easing: string) => {
    settle();
    pr.top.style.willChange = "transform"; pr.under.style.willChange = "transform";
    const opts: KeyframeAnimationOptions = { duration: ms, easing, fill: "both" };
    const a = [
      pr.top.animate([{ transform: tx(from) }, { transform: tx(to) }], opts),
      pr.under.animate([{ transform: ux(from) }, { transform: ux(to) }], opts),
      pr.shade.animate([{ opacity: SHADE * (1 - from) }, { opacity: SHADE * (1 - to) }], opts),
    ];
    running.current = a;
    return Promise.all(a.map((x) => x.finished)).catch(() => {}).then(() => {
      paint(pr, to);
      a.forEach((x) => x.cancel());
      if (running.current === a) running.current = [];
    });
  }, [settle]);

  const push = useCallback((route: Route) => { settle(); setStack((s) => [...s, { key: ++seq.current, route }]); }, [settle]);

  /** 退一页:从进度 from 播放返回动画,结束后再移除页面。连续按返回会排队。 */
  const popFrom = useCallback(async (from: number, ms = POP_MS, easing = EASE) => {
    if (popping.current) { queued.current++; return; }
    const pr = pair();
    if (!stackRef.current.length) return;
    popping.current = true;
    if (pr) {
      pr.top.inert = true;
      if (animRef.current) await run(pr, from, 1, ms, easing);
      pr.top.style.visibility = "hidden"; // 移除前不再露出来(避免闪回一帧)
    }
    flushSync(() => setStack((s) => s.slice(0, -1)));
    if (pr) { pr.under.style.transform = ""; pr.under.style.willChange = ""; pr.shade.style.opacity = "0"; }
    popping.current = false;
    if (queued.current > 0) { queued.current--; void popFrom(0); }
  }, [pair, run]);

  const pop = useCallback(() => { void popFrom(0); }, [popFrom]);
  const reset = useCallback(() => { settle(); queued.current = 0; setStack([]); }, [settle]);

  /** 跟手结束:够远或够快就退回,否则弹回原位。 */
  const release = useCallback((p: number, velocity: number) => {
    const pr = pair();
    if (!pr) return;
    const W = innerWidth;
    if (p > 0.4 || (velocity > 0.35 && p > 0.05)) {
      // 剩下的距离按手指速度算时长(快甩就快收),限制在 140~POP_MS
      const ms = Math.min(POP_MS, Math.max(140, velocity > 0 ? ((1 - p) * W) / velocity * 1.6 : POP_MS * (1 - p)));
      void popFrom(p, ms, EASE_OUT);
    } else {
      void run(pr, p, 0, Math.max(160, 280 * p + 120), EASE_OUT).then(() => rest(pr));
    }
  }, [pair, popFrom, run]);

  // 新页面挂上:从右边推入(第一帧之前就把位置摆好,不会先闪一下全屏)
  const seen = useRef(new Set<number>());
  useLayoutEffect(() => {
    const keys = new Set(stack.map((e) => e.key));
    for (const k of seen.current) if (!keys.has(k)) seen.current.delete(k);
    const top = stack[stack.length - 1];
    if (!top || seen.current.has(top.key)) return;
    seen.current.add(top.key);
    const pr = pair();
    if (!pr || !animRef.current) return;
    paint(pr, 1);
    void run(pr, 1, 0, PUSH_MS, EASE).then(() => { if (stackRef.current[stackRef.current.length - 1]?.key === top.key) rest(pr); });
  }, [stack, pair, run]);

  // 原生返回:有处理器 → 交给最上面的;否则退一页。系统侧滑返回给进度时页面跟着走
  useEffect(() => {
    notify = () => force((n) => n + 1);
    const canSwipe = () => stackRef.current.length > 0 && handlers.length === 0 && !popping.current && animRef.current;
    const offs = [
      on("backStart", () => {
        sysBack.current = canSwipe();
        if (!sysBack.current) return;
        settle();
        const pr = pair();
        if (pr) { pr.top.style.willChange = "transform"; pr.under.style.willChange = "transform"; paint(pr, 0); }
        progress.current = 0;
      }),
      on("backProgress", (p: number) => {
        if (!sysBack.current) return;
        const pr = pair();
        progress.current = Math.max(0, Math.min(1, p));
        if (pr) paint(pr, progress.current);
      }),
      on("backCancel", () => {
        if (!sysBack.current) return;
        sysBack.current = false;
        const pr = pair();
        if (pr) void run(pr, progress.current, 0, 220, EASE_OUT).then(() => rest(pr));
      }),
      on("back", () => {
        if (sysBack.current) {
          // 很多 ROM 只在松手时发一次返回(进度一直是 0):照常播放完整的返回动画
          sysBack.current = false;
          const p = progress.current;
          void popFrom(p, POP_MS * Math.max(0.5, 1 - p), p > 0.02 ? EASE_OUT : EASE);
          return;
        }
        const h = handlers[handlers.length - 1];
        if (h) h.fn();
        else if (stackRef.current.length > 0) pop();
      }),
    ];
    // 浏览器里开发:Esc = 返回
    const key = (e: KeyboardEvent) => { if (!inApp && e.key === "Escape") window.__lb.emit("back", null); };
    window.addEventListener("keydown", key);

    // 页面上向右横滑 = 跟手返回(横向能滚动的内容、滑块、输入框里不接管)
    const host = hostRef.current!;
    let st: { x: number; y: number; mode: "wait" | "drag" | "no"; lastX: number; lastT: number; v: number } | null = null;
    const blocked = (t: EventTarget | null) => {
      for (let el = t as HTMLElement | null; el && el !== host; el = el.parentElement) {
        if (el.matches("input,textarea,select,[role=slider],[data-no-swipe]")) return true;
        if (el.scrollWidth > el.clientWidth + 1 && el.scrollLeft > 0) {
          const ox = getComputedStyle(el).overflowX;
          if (ox === "auto" || ox === "scroll") return true;
        }
      }
      return false;
    };
    const start = (e: TouchEvent) => {
      st = null;
      if (e.touches.length !== 1 || !canSwipe() || blocked(e.target)) return;
      const t = e.touches[0];
      if (t.clientX > innerWidth * 0.85) return;
      st = { x: t.clientX, y: t.clientY, mode: "wait", lastX: t.clientX, lastT: e.timeStamp, v: 0 };
    };
    const move = (e: TouchEvent) => {
      if (!st || st.mode === "no") return;
      const t = e.touches[0];
      const dx = t.clientX - st.x, dy = t.clientY - st.y;
      if (st.mode === "wait") {
        if (Math.abs(dx) < 6 && Math.abs(dy) < 6) return;
        if (!e.cancelable || dx <= 0 || Math.abs(dx) < Math.abs(dy) * 1.2) { st.mode = "no"; return; }
        st.mode = "drag";
        st.x = t.clientX; // 从判定为横滑的位置开始跟手,页面不会先跳一下
        settle();
        const pr = pair();
        if (pr) { pr.top.style.willChange = "transform"; pr.under.style.willChange = "transform"; }
      }
      e.preventDefault();
      const dt = Math.max(1, e.timeStamp - st.lastT);
      st.v = 0.7 * ((t.clientX - st.lastX) / dt) + 0.3 * st.v;
      st.lastX = t.clientX; st.lastT = e.timeStamp;
      progress.current = Math.max(0, Math.min(1, (t.clientX - st.x) / innerWidth));
      const pr = pair();
      if (pr) paint(pr, progress.current);
    };
    const end = (e: TouchEvent) => {
      if (st?.mode === "drag") release(progress.current, e.type === "touchcancel" ? 0 : st.v);
      st = null;
    };
    host.addEventListener("touchstart", start, { passive: true });
    host.addEventListener("touchmove", move, { passive: false });
    host.addEventListener("touchend", end);
    host.addEventListener("touchcancel", end);
    return () => {
      offs.forEach((off) => off());
      window.removeEventListener("keydown", key);
      host.removeEventListener("touchstart", start);
      host.removeEventListener("touchmove", move);
      host.removeEventListener("touchend", end);
      host.removeEventListener("touchcancel", end);
    };
  }, [pop, popFrom, pair, release, run, settle]);

  const wantsBack = handlers.length > 0 || stack.length > 0;
  useEffect(() => { if (inApp) call("app.back", { enabled: wantsBack }).catch(() => {}); }, [wantsBack]);

  const layer = (key: number) => (el: HTMLDivElement | null) => { if (el) layers.current.set(key, el); else layers.current.delete(key); };
  const value = useMemo(() => ({ stack, push, pop, reset }), [stack, push, pop, reset]);
  return (
    <NavCtx.Provider value={value}>
      <div ref={hostRef} className="absolute inset-0 overflow-hidden">
        <EntryCtx.Provider value={{ key: 0, active: stack.length === 0 }}>
          <div ref={layer(0)} className="absolute inset-0 isolate" inert={stack.length > 0 || undefined} aria-hidden={stack.length > 0 || undefined}>{children}</div>
        </EntryCtx.Provider>
        {/* 压暗下层页面:层级在最上层页面的正下方 */}
        <div ref={shadeRef} className="pointer-events-none absolute inset-0 bg-black opacity-0" style={{ zIndex: Math.max(0, stack.length * 2 - 1) }} />
        {stack.map((e, i) => (
          <EntryCtx.Provider key={e.key} value={{ key: e.key, active: i === stack.length - 1 }}>
            <div
              ref={layer(e.key)}
              className="absolute inset-0 isolate bg-background shadow-[-6px_0_24px_rgba(0,0,0,0.08)]"
              style={{ zIndex: (i + 1) * 2 }}
              inert={i < stack.length - 1 || undefined}
              aria-hidden={i < stack.length - 1 || undefined}
            >
              {render(e.route)}
            </div>
          </EntryCtx.Provider>
        ))}
      </div>
    </NavCtx.Provider>
  );
}
