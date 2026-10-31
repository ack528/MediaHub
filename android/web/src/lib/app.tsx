// 全局状态:启动信息、登录状态、设置、服务端能力、安全区、主题
import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { call, inApp, on } from "./bridge";
import { setMediaEnv } from "./media";
import type { BootInfo, Insets, ServerCaps, SessionState, Settings } from "./types";

interface AppState {
  boot: BootInfo;
  session: SessionState;
  settings: Settings;
  caps: ServerCaps;
  dark: boolean;
  route: string;
  notice: string;
  /** 合并修改设置(立即生效,原生负责保存)。 */
  update: (patch: Partial<Settings>) => void;
  setSettings: (s: Settings) => void;
  refreshCaps: () => void;
}

const Ctx = createContext<AppState | null>(null);

export function useApp(): AppState {
  const v = useContext(Ctx);
  if (!v) throw new Error("useApp outside provider");
  return v;
}

export function useSettings(): [Settings, (patch: Partial<Settings>) => void] {
  const a = useApp();
  return [a.settings, a.update];
}

function applyInsets(i: Insets | null) {
  if (!i) return;
  const r = document.documentElement.style;
  r.setProperty("--sat", `${i.top}px`);
  r.setProperty("--sab", `${i.bottom}px`);
  r.setProperty("--sal", `${i.left}px`);
  r.setProperty("--sar", `${i.right}px`);
}

export function AppProvider({ boot, children }: { boot: BootInfo; children: ReactNode }) {
  const [session, setSession] = useState(boot.session);
  const [settings, setSettings] = useState(boot.settings);
  const [caps, setCaps] = useState(boot.caps);
  const [route, setRoute] = useState(boot.route);
  const [notice, setNotice] = useState(boot.notice);
  const [systemDark, setSystemDark] = useState(() => window.matchMedia?.("(prefers-color-scheme: dark)").matches ?? boot.systemDark);
  const settingsRef = useRef(settings);
  settingsRef.current = settings;

  useEffect(() => {
    applyInsets(boot.insets);
    const offs = [
      on("session", (s: SessionState) => setSession(s)),
      on("settings", (s: Settings) => setSettings(s)),
      on("route", (r: string) => setRoute(r)),
      on("notice", (n: string) => setNotice(n)),
      on("insets", (i: Insets) => applyInsets(i)),
    ];
    const mq = window.matchMedia?.("(prefers-color-scheme: dark)");
    const onMq = (e: MediaQueryListEvent) => setSystemDark(e.matches);
    mq?.addEventListener("change", onMq);
    return () => { offs.forEach((o) => o()); mq?.removeEventListener("change", onMq); };
  }, [boot.insets]);

  const refreshCaps = useCallback(() => {
    if (!session.loggedIn || session.local) return;
    call<ServerCaps>("caps").then(setCaps).catch(() => {});
  }, [session.loggedIn, session.local]);

  // 登录 / 切换服务器后重新取服务端能力(缩略图是否用 WebP 等)
  useEffect(() => { refreshCaps(); }, [session.activeId, session.baseUrl, refreshCaps]);

  useEffect(() => {
    setMediaEnv({ caps, local: session.local, renderHeic: settings.heicMode !== "native", mock: !inApp });
  }, [caps, session.local, settings.heicMode]);

  const dark = settings.theme === "dark" || (settings.theme === "system" && systemDark);
  useEffect(() => {
    const el = document.documentElement;
    el.classList.toggle("dark", dark);
    el.classList.toggle("light", !dark);
    el.dataset.theme = dark ? "dark" : "light";
    if (inApp) call("app.bars", { light: !dark }).catch(() => {});
  }, [dark]);

  const update = useCallback((patch: Partial<Settings>) => {
    setSettings((s) => ({ ...s, ...patch }) as Settings);
    call<Settings>("settings.update", { patch }).then(setSettings).catch(() => {});
  }, []);

  const value = useMemo<AppState>(
    () => ({ boot, session, settings, caps, dark, route, notice, update, setSettings, refreshCaps }),
    [boot, session, settings, caps, dark, route, notice, update, refreshCaps],
  );
  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}
