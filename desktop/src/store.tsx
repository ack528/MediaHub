import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { bridge } from "./bridge";
import { DEFAULT_CONFIG, mergeConfig, type Config, type Env, type LocalIp, type ServiceStatus } from "./types";

interface Toast { id: number; text: string; kind: "ok" | "err" }

interface AppCtx {
  config: Config;
  loaded: boolean;
  env: Env | null;
  svc: ServiceStatus | null;
  ips: LocalIp[];
  busy: string | null;
  restartPending: boolean;
  toasts: Toast[];
  update: (fn: (c: Config) => Config) => void;
  start: () => Promise<void>;
  stop: () => Promise<void>;
  restart: () => Promise<void>;
  rescan: (rootId?: number) => Promise<void>;
  refresh: () => Promise<void>;
  toast: (text: string, kind?: "ok" | "err") => void;
}

const Ctx = createContext<AppCtx>(null as unknown as AppCtx);
export const useApp = () => useContext(Ctx);

export function AppProvider({ children }: { children: ReactNode }) {
  const [config, setConfig] = useState<Config>(DEFAULT_CONFIG);
  const [loaded, setLoaded] = useState(false);
  const [env, setEnv] = useState<Env | null>(null);
  const [svc, setSvc] = useState<ServiceStatus | null>(null);
  const [ips, setIps] = useState<LocalIp[]>([]);
  const [busy, setBusy] = useState<string | null>(null);
  const [restartPending, setRestartPending] = useState(false);
  const [toasts, setToasts] = useState<Toast[]>([]);
  const cfgRef = useRef(config);
  const saveTimer = useRef<number | undefined>(undefined);
  const runningRef = useRef(false);

  const toast = useCallback((text: string, kind: "ok" | "err" = "ok") => {
    const id = Date.now() + Math.random();
    setToasts((t) => [...t, { id, text, kind }]);
    window.setTimeout(() => setToasts((t) => t.filter((x) => x.id !== id)), kind === "err" ? 5000 : 1800);
  }, []);

  const refresh = useCallback(async () => {
    try {
      const s = await bridge.serviceStatus();
      runningRef.current = s.running;
      setSvc(s);
    } catch (e) {
      toast(String(e), "err");
    }
  }, [toast]);

  useEffect(() => {
    (async () => {
      try {
        const [e, c, i] = await Promise.all([bridge.env(), bridge.readConfig(), bridge.localIps()]);
        setEnv(e); setIps(i);
        const merged = mergeConfig(c);
        cfgRef.current = merged;
        setConfig(merged);
      } catch (e) { toast("读取配置失败:" + String(e), "err"); }
      setLoaded(true);
      refresh();
    })();
    const t = window.setInterval(refresh, 2500);
    return () => window.clearInterval(t);
  }, [refresh, toast]);

  const update = useCallback((fn: (c: Config) => Config) => {
    const next = fn(cfgRef.current);
    cfgRef.current = next;
    setConfig(next);
    window.clearTimeout(saveTimer.current);
    saveTimer.current = window.setTimeout(async () => {
      try {
        await bridge.writeConfig(cfgRef.current);
        toast("已保存");
        if (runningRef.current) setRestartPending(true);
      } catch (e) { toast("保存失败:" + String(e), "err"); }
    }, 500);
  }, [toast]);

  const run = useCallback(async (name: string, fn: () => Promise<void>, okText: string) => {
    setBusy(name);
    try { await fn(); toast(okText); } catch (e) { toast(String(e), "err"); }
    setBusy(null);
    await refresh();
  }, [refresh, toast]);

  const start = useCallback(() => run("start", () => bridge.start(), "服务已启动"), [run]);
  const stop = useCallback(() => run("stop", () => bridge.stop(), "服务已停止"), [run]);
  const restart = useCallback(async () => {
    await run("restart", async () => { await bridge.stop(); await bridge.start(); setRestartPending(false); }, "服务已重启");
  }, [run]);
  const rescan = useCallback((rootId?: number) => run("rescan", () => bridge.rescan(rootId), rootId ? "已开始重新扫描这个盘" : "已开始重新扫描"), [run]);

  const value = useMemo<AppCtx>(
    () => ({ config, loaded, env, svc, ips, busy, restartPending, toasts, update, start, stop, restart, rescan, refresh, toast }),
    [config, loaded, env, svc, ips, busy, restartPending, toasts, update, start, stop, restart, rescan, refresh, toast],
  );
  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}
