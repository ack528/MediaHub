import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { bridge } from "./bridge";
import { applyChanges } from "./configMerge";
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
  /** 需要重启服务才生效的设置(热更新之后服务端告诉的);空 = 没有或服务端太旧不能热更新(整体按需要重启处理) */
  restartKeys: string[];
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
  const [restartKeys, setRestartKeys] = useState<string[]>([]);
  const [toasts, setToasts] = useState<Toast[]>([]);
  const cfgRef = useRef(config);
  const saveTimer = useRef<number | undefined>(undefined);
  const savedRef = useRef(config);
  const saveQueue = useRef<Promise<void>>(Promise.resolve());
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
        savedRef.current = merged;
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
    saveTimer.current = window.setTimeout(() => {
      saveQueue.current = saveQueue.current.then(async () => {
        const base = savedRef.current;
        const snapshot = cfgRef.current;
        const latest = mergeConfig(await bridge.readConfig());
        const merged = applyChanges(base, snapshot, latest) as Config;
        await bridge.writeConfig(merged);
        savedRef.current = merged;
        const current = applyChanges(snapshot, cfgRef.current, merged) as Config;
        cfgRef.current = current;
        setConfig(current);
        if (!runningRef.current) { toast("已保存"); return; }
        // 服务在运行:让它重新读取配置,能立刻生效的(穿透 / 更新 / 调度 / 日志级别 / 数字开关类)马上生效,
        // 只有真正需要的才提示重启。服务端太旧没有这个接口时,退回"整体需要重启"
        try {
          const r = await bridge.admin<{ applied: string[]; restartRequired: string[] }>("POST", "/api/v1/admin/config/reload", undefined, 15000);
          if (r.restartRequired.length > 0) {
            setRestartKeys((k) => [...new Set([...k, ...r.restartRequired])]);
            setRestartPending(true);
            toast(r.applied.length ? `已保存,${r.applied.join("、")} 已立即生效` : "已保存,重启服务后生效");
          } else {
            toast(r.applied.length ? `已保存并立即生效:${r.applied.join("、")}` : "已保存");
          }
        } catch {
          setRestartKeys([]);
          setRestartPending(true);
          toast("已保存");
        }
      }).catch((e) => { toast("保存失败:" + String(e), "err"); });
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
    await run("restart", async () => { await bridge.stop(); await bridge.start(); setRestartPending(false); setRestartKeys([]); }, "服务已重启");
  }, [run]);
  const rescan = useCallback((rootId?: number) => run("rescan", () => bridge.rescan(rootId), rootId ? "已开始重新扫描这个盘" : "已开始重新扫描"), [run]);

  const value = useMemo<AppCtx>(
    () => ({ config, loaded, env, svc, ips, busy, restartPending, restartKeys, toasts, update, start, stop, restart, rescan, refresh, toast }),
    [config, loaded, env, svc, ips, busy, restartPending, restartKeys, toasts, update, start, stop, restart, rescan, refresh, toast],
  );
  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}
