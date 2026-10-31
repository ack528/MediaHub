// 页面栈 + 返回键:系统返回(原生 BackHandler → "back" 事件)先关最上层的弹层,再退回上一页。
import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from "react";
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

export function NavProvider({ children, render }: { children: ReactNode; render: (r: Route) => ReactNode }) {
  const [stack, setStack] = useState<Entry[]>([]);
  const seq = useRef(0);
  const [, force] = useState(0);
  const stackRef = useRef(stack);
  stackRef.current = stack;

  const push = useCallback((route: Route) => setStack((s) => [...s, { key: ++seq.current, route }]), []);
  const pop = useCallback(() => setStack((s) => s.slice(0, -1)), []);
  const reset = useCallback(() => setStack([]), []);

  // 原生返回键:有处理器 → 交给最上面的;否则退一页
  useEffect(() => {
    notify = () => force((n) => n + 1);
    const off = on("back", () => {
      const h = handlers[handlers.length - 1];
      if (h) h.fn();
      else if (stackRef.current.length > 0) pop();
    });
    // 浏览器里开发:Esc = 返回
    const key = (e: KeyboardEvent) => { if (!inApp && e.key === "Escape") window.__lb.emit("back", null); };
    window.addEventListener("keydown", key);
    return () => { off(); window.removeEventListener("keydown", key); };
  }, [pop]);

  const wantsBack = handlers.length > 0 || stack.length > 0;
  useEffect(() => { if (inApp) call("app.back", { enabled: wantsBack }).catch(() => {}); }, [wantsBack]);

  const value = useMemo(() => ({ stack, push, pop, reset }), [stack, push, pop, reset]);
  return (
    <NavCtx.Provider value={value}>
      <EntryCtx.Provider value={{ key: 0, active: stack.length === 0 }}>
        <div className="absolute inset-0 isolate" inert={stack.length > 0 || undefined} aria-hidden={stack.length > 0 || undefined}>{children}</div>
      </EntryCtx.Provider>
      {stack.map((e, i) => (
        <EntryCtx.Provider key={e.key} value={{ key: e.key, active: i === stack.length - 1 }}>
          <div className="page-enter absolute inset-0 isolate bg-background" style={{ zIndex: i + 1 }} inert={i < stack.length - 1 || undefined} aria-hidden={i < stack.length - 1 || undefined}>
            {render(e.route)}
          </div>
        </EntryCtx.Provider>
      ))}
    </NavCtx.Provider>
  );
}
