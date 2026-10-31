import { Button, Spinner, Tabs, toast } from "@heroui/react";
import { Shuffle } from "@gravity-ui/icons";
import { useCallback, useEffect, useRef, useState, type Key } from "react";
import { api, BridgeError, call, errorText, haptic, on, query } from "../lib/bridge";
import { useApp } from "../lib/app";
import { useNav } from "../lib/nav";
import type { HistoryResp, Item } from "../lib/types";
import { PageHeader } from "../components/PageHeader";
import { StateView } from "../components/List";
import { MediaTile } from "../components/Thumb";
import { TAB_BAR_SPACE } from "./Home";

/** 服务端每个范围 × 类型预先准备了 12 份随机序列,种子 1..12 就是用哪一份;换一批 = 换成另一份。 */
const SLOTS = 12;
function newSeed(cur: number): number {
  const slot = cur <= 0 ? -1 : (cur - 1) % SLOTS;
  let n = Math.floor(Math.random() * (SLOTS - (slot >= 0 ? 1 : 0)));
  if (slot >= 0 && n >= slot) n++;
  return n + 1;
}

const TYPE_TABS = [["0", "全部", "photo,video,gif"], ["1", "图片", "photo,gif"], ["2", "视频", "video"]] as const;

/** 随机浏览:从整个库(或一个盘符)里随机取图片 / 视频,滚到底再取一批;同一个种子的序列固定,点「换一批」才换。 */
export function RandomScreen({ label, roots, asTab }: { label: string | null; roots: string | null; asTab?: boolean }) {
  const { settings: s, update } = useApp();
  const nav = useNav();
  const tab = String(Math.min(2, Math.max(0, s.randomType)));
  const types = TYPE_TABS[Number(tab)][2];
  const seed = s.randomSeed;
  const [items, setItems] = useState<Item[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const batch = useRef(0);
  const gen = useRef(0);
  const busy = useRef(false);
  const scroller = useRef<HTMLDivElement>(null);
  const bottom = useRef<HTMLDivElement>(null);
  const itemsRef = useRef(items);
  itemsRef.current = items;

  useEffect(() => { if (!seed) update({ randomSeed: newSeed(0) }); }, [seed, update]);

  const more = useCallback(async (replace: boolean) => {
    if (!seed) return;
    if (!replace && busy.current) return;
    const my = replace ? ++gen.current : gen.current;
    busy.current = true;
    setLoading(true); setError(null);
    try {
      const b = replace ? 0 : batch.current;
      const r = await api<HistoryResp>("/api/v1/random" + query({ types, limit: 60, roots, seed, batch: b }));
      if (my !== gen.current) return;
      batch.current = b + 1;
      setItems((cur) => {
        const known = new Set(replace ? [] : cur.map((x) => x.id));
        const fresh = r.items.filter((x) => !known.has(x.id) && known.add(x.id));
        return replace ? fresh : [...cur, ...fresh];
      });
      if (replace) scroller.current?.scrollTo({ top: 0 });
    } catch (e) {
      if (my === gen.current) setError(e instanceof BridgeError && e.http === 404 ? "服务端版本太旧,不支持随机浏览,请更新服务端" : errorText(e));
    } finally {
      if (my === gen.current) { setLoading(false); busy.current = false; }
    }
  }, [types, roots, seed]);

  useEffect(() => { more(true); }, [more]);

  useEffect(() => {
    if (!bottom.current || items.length === 0) return;
    const io = new IntersectionObserver(([e]) => { if (e.isIntersecting) more(false); }, { root: scroller.current, rootMargin: "1200px 0px" });
    io.observe(bottom.current);
    return () => io.disconnect();
  }, [items.length, more]);

  // 从查看器返回:并入查看器里多取的几批,定位到最后看的那一项
  useEffect(() => on("viewerClosed", (d: { origin: string; itemId: string; items: Item[] | null }) => {
    if (d.origin !== "random") return;
    if (d.items && d.items.length > itemsRef.current.length) {
      setItems((cur) => { const known = new Set(cur.map((x) => x.id)); return [...cur, ...d.items!.filter((x) => !known.has(x.id))]; });
      batch.current = Math.max(batch.current, Math.ceil(d.items.length / 60));
    }
    requestAnimationFrame(() => scroller.current?.querySelector(`[data-item="${CSS.escape(d.itemId)}"]`)?.scrollIntoView({ block: "center" }));
  }), []);

  const open = (it: Item) => call("viewer.open", {
    items, index: Math.max(0, items.findIndex((x) => x.id === it.id)),
    source: { kind: "random", types, roots, seed, batch: batch.current },
  }).catch((e) => toast.danger(errorText(e)));

  return (
    <div ref={scroller} className="page" style={asTab ? { paddingBottom: TAB_BAR_SPACE } : undefined}>
      <PageHeader
        large={asTab}
        title={label ? `随机浏览 · ${label}` : "随机浏览"}
        subtitle={`第 ${seed || "-"} 组随机序列`}
        onBack={asTab ? undefined : nav.pop}
        actions={
          <Button variant="secondary" size="sm" onPress={() => { haptic("Click"); update({ randomSeed: newSeed(seed) }); }}>
            <Shuffle className="size-4" />换一批
          </Button>
        }
      >
        <div className="px-4 pb-2">
          <Tabs selectedKey={tab} onSelectionChange={(k: Key) => { haptic("Tick"); update({ randomType: Number(k) }); }}>
          <Tabs.ListContainer>
            <Tabs.List aria-label="类型">
              {TYPE_TABS.map(([id, l]) => <Tabs.Tab key={id} id={id}>{l}<Tabs.Indicator /></Tabs.Tab>)}
            </Tabs.List>
          </Tabs.ListContainer>
        </Tabs>
        </div>
      </PageHeader>
      {items.length === 0 ? (
        <StateView loading={loading || !seed} error={error} onRetry={() => more(true)} empty="没有可显示的文件" />
      ) : (
        <>
          <div className="grid grid-cols-3" style={{ gap: s.gridSpacing }}>
            {items.map((it) => (
              <MediaTile key={it.id} item={it} width={s.gridThumbWidth} showName={s.showVideoName} showDuration={s.showDuration} className="aspect-square" onOpen={() => open(it)} />
            ))}
          </div>
          <div ref={bottom} className="grid h-16 place-items-center pb-safe">
            {loading ? <Spinner size="sm" /> : error ? <Button size="sm" variant="tertiary" onPress={() => more(false)}>加载失败,重试</Button> : null}
          </div>
        </>
      )}
    </div>
  );
}
