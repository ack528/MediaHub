import { useMemo, useState } from "react";
import { Btn, Card, ItemRow, Progress, Tabs, fmtBytes } from "./components";
import { Icon } from "./icons";
import { Accounts } from "./pages/Accounts";
import { About } from "./pages/About";
import { Library } from "./pages/Library";
import { Logs } from "./pages/Logs";
import { Network } from "./pages/Network";
import { Overview } from "./pages/Overview";
import { Storage } from "./pages/Storage";
import { Video } from "./pages/Video";
import { PAGES, countOf, searchItems, type PageId } from "./schema";
import { useApp } from "./store";

function Sidebar({ page, onPage }: { page: PageId; onPage: (p: PageId) => void }) {
  const { svc, env } = useApp();
  const cache = svc?.status?.cache ?? [];
  const used = cache.reduce((s, c) => s + c.usedBytes, 0);
  const quota = cache.reduce((s, c) => s + c.quotaBytes, 0);
  return (
    <aside className="sidebar">
      <div className="brand">
        <img src="/logo.png" alt="" />
        <div>
          <div className="brand-name">MediaHub</div>
          <div className="brand-ver">v{env?.appVersion ?? ""}</div>
        </div>
      </div>
      <nav className="nav">
        {PAGES.map((p) => (
          <button key={p.id} type="button" className={"nav-item" + (p.id === page ? " active" : "")} onClick={() => onPage(p.id)}>
            <Icon name={p.icon} size={22} /><span>{p.label}</span>
          </button>
        ))}
      </nav>
      <div className="storage-card">
        <Icon name="drive" size={26} />
        <div className="grow">
          <div className="storage-title">缓存占用</div>
          <div className="storage-sub">{svc?.running ? `${fmtBytes(used)} / ${fmtBytes(quota)}` : "服务未运行"}</div>
        </div>
        <Progress value={used} max={quota || 1} />
      </div>
    </aside>
  );
}

function PageBody({ page, tab }: { page: PageId; tab: string }) {
  switch (page) {
    case "overview": return <Overview />;
    case "library": return <Library tab={tab} />;
    case "storage": return <Storage tab={tab} />;
    case "video": return <Video tab={tab} />;
    case "accounts": return <Accounts tab={tab} />;
    case "network": return <Network />;
    case "logs": return <Logs />;
    case "about": return <About />;
  }
}

export default function App() {
  const { loaded, restartPending, svc, busy, restart, toasts, env } = useApp();
  const [page, setPage] = useState<PageId>("overview");
  const [tabs, setTabs] = useState<Partial<Record<PageId, string>>>({});
  const [q, setQ] = useState("");
  const def = PAGES.find((p) => p.id === page)!;
  const tab = tabs[page] ?? def.tabs[0];
  const results = useMemo(() => searchItems(q), [q]);
  const searching = q.trim() !== "";

  if (!loaded) return <div className="loading">正在读取配置…</div>;

  return (
    <div className="app">
      <Sidebar page={page} onPage={(p) => { setPage(p); setQ(""); }} />
      <main className="main">
        <header className="header">
          <div className="header-top">
            <span className="header-ico"><Icon name={def.icon} size={26} /></span>
            <h1>{searching ? "搜索结果" : def.label}</h1>
            <span className="grow" />
            <label className="search">
              <Icon name="search" size={20} />
              <input placeholder="搜索配置项" value={q} onChange={(e) => setQ(e.target.value)} />
            </label>
          </div>
          {!searching && (
            <div className="header-tabs">
              <Tabs tabs={def.tabs} active={tab} onChange={(t) => setTabs({ ...tabs, [page]: t })} />
              <span className="grow" />
              <span className="count-pill">{countOf(page, tab)} 项设置</span>
            </div>
          )}
          {searching && <div className="header-tabs"><span className="grow" /><span className="count-pill">{results.length} 项匹配</span></div>}
        </header>

        <div className="content">
          {restartPending && svc?.running && !searching && (
            <div className="banner">
              <Icon name="alert" size={20} /><span>设置已保存,重启服务后生效。</span>
              <span className="grow" />
              <Btn kind="primary" icon="refresh" disabled={!!busy} onClick={restart}>{busy === "restart" ? "重启中…" : "立即重启服务"}</Btn>
            </div>
          )}
          {env && !env.configExists && !searching && page !== "overview" && (
            <div className="banner info"><Icon name="info" size={20} /><span>还没有配置文件,修改任何设置后会自动创建。</span></div>
          )}
          {searching ? (
            results.length === 0
              ? <Card title="没有匹配的配置项" icon="search"><div className="log-empty">换个关键词试试,例如"缓存"、"端口"、"转码"。</div></Card>
              : <Card icon="search" title="搜索结果" count={results.length}>{results.map((i) => <ItemRow key={i.id} item={i} />)}</Card>
          ) : (
            <PageBody page={page} tab={tab} />
          )}
        </div>
      </main>

      <div className="toasts">
        {toasts.map((t) => <div key={t.id} className={"toast " + t.kind}>{t.text}</div>)}
      </div>
    </div>
  );
}
