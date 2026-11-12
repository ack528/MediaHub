// 页面导航:和以前的版本一样,每个二级页面是一个原生 Activity(各自一个 WebView),
// 打开 / 返回的转场、侧滑返回(预测性返回)都由系统负责,网页里不做页面动画。
// 返回键:网页里有弹层、搜索框、非首个标签等处理器时交给最上面的处理器;否则原生直接关掉这个页面。
import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { call, inApp, on } from "./bridge";

export type Route =
  | { name: "chat"; params: { id: string; title?: string; focus?: string } }
  | { name: "search"; params?: { dialogId?: string; title?: string } }
  | { name: "random"; params: { label?: string | null; roots?: string | null } }
  | { name: "settings"; params: { section: string } };

interface NavState {
  push: (r: Route) => void;
  /** 关掉当前页面(首页上无效)。 */
  pop: () => void;
}

const NavCtx = createContext<NavState | null>(null);
const ActiveCtx = createContext(true);

export function useNav(): NavState {
  const v = useContext(NavCtx);
  if (!v) throw new Error("useNav outside provider");
  return v;
}

/** 当前页面在前台(被别的页面盖住时为 false;回到这一页时变回 true)。 */
export function usePageActive(): boolean { return useContext(ActiveCtx); }

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

/**
 * [render] 只在浏览器预览里用:没有原生 Activity,用一个简单的页面栈代替(没有动画)。
 */
export function NavProvider({ children, render }: { children: ReactNode; render: (r: Route) => ReactNode }) {
  const [, force] = useState(0);
  const [active, setActive] = useState(true);
  const [mockStack, setMockStack] = useState<Route[]>([]);

  const push = useCallback((route: Route) => {
    if (inApp) call("nav.open", { route }).catch(() => {});
    else setMockStack((s) => [...s, route]);
  }, []);
  const pop = useCallback(() => {
    if (inApp) call("nav.close").catch(() => {});
    else setMockStack((s) => s.slice(0, -1));
  }, []);
  const mockRef = useRef(mockStack);
  mockRef.current = mockStack;

  useEffect(() => {
    notify = () => force((n) => n + 1);
    const offs = [
      on("back", () => {
        const h = handlers[handlers.length - 1];
        if (h) h.fn();
        else if (!inApp && mockRef.current.length) pop();
      }),
      on("pageActive", (v: boolean) => setActive(v)),
    ];
    // 浏览器里开发:Esc = 返回
    const key = (e: KeyboardEvent) => { if (!inApp && e.key === "Escape") window.__lb.emit("back", null); };
    window.addEventListener("keydown", key);
    return () => { offs.forEach((off) => off()); window.removeEventListener("keydown", key); };
  }, [pop]);

  // 有处理器时返回键交给网页;否则系统返回直接关掉这个页面(系统的预测性返回动画)
  const wantsBack = handlers.length > 0;
  useEffect(() => { if (inApp) call("app.back", { enabled: wantsBack }).catch(() => {}); }, [wantsBack]);

  const value = useMemo(() => ({ push, pop }), [push, pop]);
  return (
    <NavCtx.Provider value={value}>
      <ActiveCtx.Provider value={active && mockStack.length === 0}>
        <div className="absolute inset-0" inert={mockStack.length > 0 || undefined}>{children}</div>
      </ActiveCtx.Provider>
      {mockStack.map((r, i) => (
        <ActiveCtx.Provider key={i} value={i === mockStack.length - 1}>
          <div className="absolute inset-0 bg-background" style={{ zIndex: i + 1 }}>{render(r)}</div>
        </ActiveCtx.Provider>
      ))}
    </NavCtx.Provider>
  );
}
