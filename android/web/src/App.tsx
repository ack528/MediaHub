import { AlertDialog, Button, Toast, toast } from "@heroui/react";
import { useEffect, useRef, useState } from "react";
import { call, errorText, on } from "./lib/bridge";
import { AppProvider, useApp } from "./lib/app";
import { NavProvider, type Route } from "./lib/nav";
import "./lib/hero";
import type { BootInfo } from "./lib/types";
import { Onboarding } from "./screens/Onboarding";
import { Home } from "./screens/Home";
import { ChatScreen } from "./screens/Chat";
import { SearchScreen } from "./screens/Search";
import { RandomScreen } from "./screens/Random";
import { SettingsPage } from "./screens/settings/SettingsPage";

export function App({ boot }: { boot: BootInfo }) {
  return (
    <AppProvider boot={boot}>
      <Shell />
      <Toast.Provider placement="bottom" className="pb-safe" />
      <CrashNotice />
    </AppProvider>
  );
}

function renderRoute(r: Route) {
  switch (r.name) {
    case "chat": return <ChatScreen id={r.params.id} titleHint={r.params.title} />;
    case "search": return <SearchScreen dialogId={r.params?.dialogId} dialogTitle={r.params?.title} />;
    case "random": return <RandomScreen label={r.params.label ?? null} roots={r.params.roots ?? null} />;
    case "settings": return <SettingsPage section={r.params.section} />;
  }
}

function Shell() {
  const { boot, session, settings } = useApp();
  const firstView = useRef(session.loggedIn ? `home-${session.activeId}` : "onboarding");
  // 服务端太旧不支持同步:提示一次(只在首页提示)
  useEffect(() => boot.page ? undefined : on("syncUnsupported", () => toast.warning("服务端版本太旧,浏览位置和播放进度没有同步,请更新服务端")), [boot.page]);
  // 第一屏渲染好了:原生这时才画第一帧(新页面的系统转场滑进来的是完整的页面)
  useEffect(() => { const t = setTimeout(() => call("app.ready").catch(() => {}), 0); return () => clearTimeout(t); }, []);
  // 二级页面:这个 WebView 只显示一个页面(原生 Activity),登录失效时原生会关掉它
  if (boot.page) return <NavProvider render={renderRoute}><div className="absolute inset-0">{renderRoute(boot.page)}</div></NavProvider>;
  // 登录页 ↔ 首页:淡出再淡入 + 轻微放大(以前版本的 fade through;启动时的第一屏不做)
  const view = session.loggedIn ? `home-${session.activeId}` : "onboarding";
  if (view !== firstView.current) firstView.current = "";
  const fade = settings.motion === "off" || view === firstView.current ? "" : "fade-through";
  if (!session.loggedIn) return <div key={view} className={`absolute inset-0 ${fade}`}><Onboarding /></div>;
  // 切换服务器 = 重新挂载首页(页面里的数据都属于原来的服务器)
  return (
    <div key={view} className={`absolute inset-0 ${fade}`}>
      <NavProvider render={renderRoute}>
        <Home />
      </NavProvider>
    </div>
  );
}

/** 上次崩溃过:启动时提示,可分享 / 复制日志。 */
function CrashNotice() {
  const { boot } = useApp();
  const [open, setOpen] = useState(boot.hasCrash);
  const close = () => { setOpen(false); call("crash.dismiss").catch(() => {}); };
  return (
    <AlertDialog.Backdrop isOpen={open} onOpenChange={(v) => { if (!v) close(); }}>
      <AlertDialog.Container placement="center">
        <AlertDialog.Dialog className="sm:max-w-sm">
          <AlertDialog.Header>
            <AlertDialog.Icon status="danger" />
            <AlertDialog.Heading>上次异常退出了</AlertDialog.Heading>
          </AlertDialog.Header>
          <AlertDialog.Body>
            <p className="text-sm text-muted">崩溃信息已保存。把日志发给开发者就能定位问题。</p>
            {boot.crash && <pre className="selectable mt-3 max-h-40 overflow-auto rounded-xl bg-surface-secondary p-3 text-xs whitespace-pre-wrap">{boot.crash}</pre>}
          </AlertDialog.Body>
          <AlertDialog.Footer>
            <Button variant="tertiary" onPress={() => call<number>("log.copy").then((n) => toast.success(`已复制 ${n} 字`)).catch((e) => toast.danger(errorText(e)))}>复制</Button>
            <Button variant="tertiary" onPress={close}>忽略</Button>
            <Button onPress={() => { call("log.share").catch(() => {}); close(); }}>分享日志</Button>
          </AlertDialog.Footer>
        </AlertDialog.Dialog>
      </AlertDialog.Container>
    </AlertDialog.Backdrop>
  );
}
