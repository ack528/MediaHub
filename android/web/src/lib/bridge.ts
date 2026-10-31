// 与原生的桥(WebBridge.kt):LB.call(id, method, argsJson) → window.__lb.resolve(id, ok, data);原生事件 → window.__lb.emit(name, data)

type Listener = (data: any) => void;

interface NativeBridge { call(id: string, method: string, args: string): void }

declare global {
  interface Window {
    LB?: NativeBridge;
    __lb: { resolve(id: string, ok: boolean, data: unknown): void; emit(name: string, data: unknown): void };
  }
}

export class BridgeError extends Error {
  http?: number;
  code?: string;
  constructor(message: string, http?: number, code?: string) {
    super(message);
    this.http = http;
    this.code = code;
  }
}

const pending = new Map<string, { resolve: (v: any) => void; reject: (e: BridgeError) => void }>();
const listeners = new Map<string, Set<Listener>>();
let seq = 0;

window.__lb = {
  resolve(id, ok, data) {
    const p = pending.get(id);
    if (!p) return;
    pending.delete(id);
    if (ok) p.resolve(data);
    else {
      const d = (data ?? {}) as { message?: string; http?: number; code?: string };
      p.reject(new BridgeError(d.message ?? "出错了", d.http, d.code));
    }
  },
  emit(name, data) {
    listeners.get(name)?.forEach((l) => {
      try { l(data); } catch (e) { console.error(e); }
    });
  },
};

/** 是否运行在 App 里(浏览器里开发时用模拟数据)。 */
export const inApp = typeof window.LB !== "undefined";

let mock: ((method: string, args: any) => Promise<any>) | null = null;
export function installMock(fn: (method: string, args: any) => Promise<any>) { mock = fn; }

export function call<T = any>(method: string, args: Record<string, unknown> = {}): Promise<T> {
  if (!window.LB) {
    if (mock) return mock(method, args).catch((e) => { throw e instanceof BridgeError ? e : new BridgeError(String(e?.message ?? e)); });
    return Promise.reject(new BridgeError("不在 App 中运行"));
  }
  const id = `c${++seq}`;
  return new Promise<T>((resolve, reject) => {
    pending.set(id, { resolve, reject });
    window.LB!.call(id, method, JSON.stringify(args));
  });
}

export function on(name: string, fn: Listener): () => void {
  let set = listeners.get(name);
  if (!set) listeners.set(name, (set = new Set()));
  set.add(fn);
  return () => { set!.delete(fn); };
}

/** 模拟原生推送(浏览器开发用)。 */
export function emitLocal(name: string, data?: unknown) { window.__lb.emit(name, data); }

// ---- 常用封装 ----

export interface HttpOpts { method?: "GET" | "POST" | "PUT" | "DELETE"; body?: unknown; cache?: boolean }

/** 发往当前服务器 / 本地媒体的接口请求(path 以 /api/v1 开头)。 */
export function api<T = any>(path: string, opts: HttpOpts = {}): Promise<T> {
  return call<T>("http", { path, method: opts.method ?? "GET", body: opts.body ?? null, cache: !!opts.cache });
}

/** 磁盘缓存里上次的响应(没有就是 null)。 */
export function peek<T = any>(path: string): Promise<T | null> { return call<T | null>("peek", { path }); }

export function haptic(kind: "Tick" | "Click" | "Long" | "Confirm" | "Reject" = "Tick") { if (inApp) call("app.haptic", { kind }).catch(() => {}); }

export function query(params: Record<string, string | number | boolean | null | undefined>): string {
  const q = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) if (v !== null && v !== undefined && v !== "") q.set(k, String(v));
  const s = q.toString();
  return s ? `?${s}` : "";
}

export function errorText(e: unknown): string {
  if (e instanceof BridgeError) return e.message;
  if (e instanceof Error) return e.message;
  return String(e);
}
