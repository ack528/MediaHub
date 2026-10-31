import { useNav } from "../../lib/nav";
import { PageHeader } from "../../components/PageHeader";
import { ListGroup, ListRow } from "../../components/List";
import { SETTING_PAGES } from "./schema";
import { SchemaRows, ActionRow, Note } from "./controls";
import { SECTIONS } from "./SettingsHome";
import { AccountPage, ServerConfigPage } from "./AccountPages";
import { AboutPage, LogPage, LogViewPage, NetworkPage, SpeedTestPage, StoragePage } from "./SystemPages";
import { CodecsInfoPage, EnhancePage, HardwarePage, LsfgPage, SlideshowSection } from "./MediaPages";
import { call } from "../../lib/bridge";

const EXTRA_TITLES: Record<string, string> = {
  "codecs-info": "本机解码器", "log-view": "应用日志", "enhance-hw": "硬件支持检测", lsfg: "LSFG 帧生成",
};

/** 设置的分类页:大部分是设置清单直接渲染,少数是自定义页面。 */
export function SettingsPage({ section }: { section: string }) {
  const nav = useNav();
  const title = EXTRA_TITLES[section] ?? SECTIONS[section]?.title ?? "设置";
  const open = (s: string) => nav.push({ name: "settings", params: { section: s } });
  return (
    <div className="page">
      <PageHeader title={title} onBack={nav.pop} />
      <div className="flex flex-col gap-5 pt-2 pb-[calc(var(--sab)+24px)]">
        <Body section={section} open={open} />
      </div>
    </div>
  );
}

function Body({ section, open }: { section: string; open: (s: string) => void }) {
  const rows = SETTING_PAGES[section];
  switch (section) {
    case "account": return <AccountPage />;
    case "server-config": return <ServerConfigPage />;
    case "network": return <NetworkPage />;
    case "speedtest": return <SpeedTestPage />;
    case "storage": return <StoragePage />;
    case "log": return <LogPage onView={() => open("log-view")} />;
    case "log-view": return <LogViewPage />;
    case "about": return <AboutPage />;
    case "enhance": return <EnhancePage open={open} />;
    case "enhance-hw": return <HardwarePage />;
    case "lsfg": return <LsfgPage />;
    case "codecs-info": return <CodecsInfoPage />;
    case "codec":
      return (
        <>
          <ListGroup><ListRow title="本机解码器" description="这台手机支持哪些格式、是不是硬件解码" onPress={() => open("codecs-info")} /></ListGroup>
          <SchemaRows rows={rows} />
        </>
      );
    case "viewer":
      return (
        <>
          <SchemaRows rows={rows} />
          <SlideshowSection />
        </>
      );
    case "image":
      return (
        <>
          <Note>缓存大小在重启应用后生效。</Note>
          <SchemaRows rows={rows} />
          <ListGroup><ActionRow title="立即重启应用" description="让上面的设置马上生效" action={() => call("app.restart")} /></ListGroup>
        </>
      );
    default:
      return rows ? <SchemaRows rows={rows} /> : <p className="px-6 text-sm text-muted">没有这个设置页</p>;
  }
}
