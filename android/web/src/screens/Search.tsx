import { SearchField, Spinner, Tabs, toast } from "@heroui/react";
import { Magnifier } from "@gravity-ui/icons";
import { useCallback, useEffect, useRef, useState, type Key } from "react";
import { api, call, errorText, on, query } from "../lib/bridge";
import { useShortVideoPreload } from "../lib/preload";
import { useApp } from "../lib/app";
import { useNav } from "../lib/nav";
import type { HistoryResp, Item } from "../lib/types";
import { PageHeader } from "../components/PageHeader";
import { StateView } from "../components/List";
import { MediaTile } from "../components/Thumb";
import { adaptiveColumns, useViewportWidth } from "../components/Windowed";
import { heroArgs } from "../lib/hero";

const TYPE_TABS = [["all", "全部", "photo,video,gif"], ["photo", "照片", "photo,gif"], ["video", "视频", "video"]] as const;

/**
 * 按文件名搜索。dialogId 不为空 = 只在这个文件夹里搜:点结果回到文件夹并定位到那一条(长按直接打开);
 * 全局搜索点结果直接打开查看器。
 */
export function SearchScreen({ dialogId, dialogTitle }: { dialogId?: string; dialogTitle?: string }) {
  const { settings: s } = useApp();
  const nav = useNav();
  const [q, setQ] = useState("");
  const [tab, setTab] = useState<string>("all");
  const [items, setItems] = useState<Item[]>([]);
  const [next, setNext] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [searched, setSearched] = useState("");
  const gen = useRef(0);
  const types = TYPE_TABS.find((t) => t[0] === tab)![2];
  const cols = adaptiveColumns(3, useViewportWidth()); // 横屏 / 平板按宽度加列

  const run = useCallback(async (text: string, cursor: string | null) => {
    const my = cursor ? gen.current : ++gen.current;
    if (!text.trim()) { setItems([]); setNext(null); setSearched(""); return; }
    setLoading(true); setError(null);
    try {
      const r = await api<HistoryResp>("/api/v1/search" + query({ q: text.trim(), types, limit: 60, dialog: dialogId, cursor }));
      if (my !== gen.current) return;
      setItems((cur) => (cursor ? [...cur, ...r.items] : r.items));
      setNext(r.nextCursor ?? null);
      setSearched(text.trim());
    } catch (e) {
      if (my === gen.current) setError(errorText(e));
    } finally {
      if (my === gen.current) setLoading(false);
    }
  }, [types, dialogId]);

  // 输入停顿 350ms 后搜索;换类型立即重搜
  useEffect(() => {
    const t = setTimeout(() => run(q, null), 350);
    return () => clearTimeout(t);
  }, [q, run]);

  const bottom = useRef<HTMLDivElement>(null);
  const scroller = useRef<HTMLDivElement>(null);
  useShortVideoPreload(scroller, items); // 屏幕附近 20 秒以内的短视频预加载,点开直接播
  useEffect(() => {
    if (!next || !bottom.current) return;
    const io = new IntersectionObserver(([e]) => { if (e.isIntersecting && !loading) run(searched, next); }, { root: scroller.current, rootMargin: "1200px 0px" });
    io.observe(bottom.current);
    return () => io.disconnect();
  }, [next, loading, run, searched]);

  const st = useRef({ items, searched, next });
  st.current = { items, searched, next };
  useEffect(() => on("mediaDeleted", (ids: string[]) => { const gone = new Set(ids); setItems((cur) => cur.filter((x) => !gone.has(x.id))); }), []);
  const open = (it: Item) => {
    const { items, searched, next } = st.current; // 读最新值:列表里的格子不随每次渲染重建(见 MediaTile 的 memo)
    call("viewer.open", {
      items, index: Math.max(0, items.findIndex((x) => x.id === it.id)), ...heroArgs(it.id),
      source: { kind: "search", q: searched, types, dialog: dialogId ?? null, cursor: next },
    }).catch((e) => toast.danger(errorText(e)));
  };

  const pick = (it: Item) => {
    if (!dialogId) return open(it);
    // 文件夹页在另一个页面(Activity)里:广播给它,再关掉搜索页
    call("app.broadcast", { name: "jumpTo", data: { dialogId, itemId: it.id } }).catch(() => {});
    nav.pop();
  };

  return (
    <div ref={scroller} className="page">
      <PageHeader title={dialogId ? `在「${dialogTitle ?? "文件夹"}」中搜索` : "搜索"} onBack={nav.pop}>
        <div className="px-4 pb-2">
          <SearchField value={q} onChange={setQ} aria-label="搜索文件名" autoFocus>
            <SearchField.Group>
              <SearchField.SearchIcon />
              <SearchField.Input placeholder="按文件名搜索" />
              <SearchField.ClearButton />
            </SearchField.Group>
          </SearchField>
        </div>
        <div className="px-4 pb-2">
          <Tabs selectedKey={tab} onSelectionChange={(k: Key) => setTab(String(k))}>
          <Tabs.ListContainer>
            <Tabs.List aria-label="类型">
              {TYPE_TABS.map(([id, label]) => (
                <Tabs.Tab key={id} id={id}>{label}<Tabs.Indicator /></Tabs.Tab>
              ))}
            </Tabs.List>
          </Tabs.ListContainer>
        </Tabs>
        </div>
      </PageHeader>

      {items.length === 0 ? (
        <StateView
          loading={loading}
          error={error}
          onRetry={() => run(q, null)}
          icon={<Magnifier className="size-8" />}
          empty={searched ? `没有找到「${searched}」` : dialogId ? "输入文件名,点结果回到文件夹里的那一条(长按直接打开)" : "输入文件名的一部分"}
        />
      ) : (
        <div className="pb-safe">
          <p className="px-4 py-2 text-xs text-muted">{next ? `已显示 ${items.length} 项` : `共 ${items.length} 项`}</p>
          <div className="grid" style={{ gap: s.gridSpacing, gridTemplateColumns: `repeat(${cols}, minmax(0, 1fr))` }}>
            {items.map((it) => (
              <MediaTile key={it.id} item={it} width={s.gridThumbWidth} showName showDuration={s.showDuration} className="aspect-square"
                onOpen={() => pick(it)} onLongPress={dialogId ? () => open(it) : undefined} />
            ))}
          </div>
          <div ref={bottom} className="grid h-16 place-items-center">{loading && <Spinner size="sm" />}</div>
        </div>
      )}
    </div>
  );
}
