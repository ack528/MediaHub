import { Button, Calendar, Chip, Label, Modal, Sheet, Slider, Spinner, ToggleButton, ToggleButtonGroup, toast } from "@heroui/react";
import { ArrowUpArrowDown, Bookmark, Calendar as CalendarIcon, ClockArrowRotateLeft, LayoutCellsLarge, LayoutColumns, LayoutList, Magnifier, Sliders } from "@gravity-ui/icons";
import { Fragment, useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState, type Key } from "react";
import { api, BridgeError, call, errorText, haptic, on, query } from "../lib/bridge";
import { useApp } from "../lib/app";
import { aspectOf, dayKey, dayLabel, formatDuration, formatShortTime, formatSize, stripExt } from "../lib/media";
import { useBackHandler, useNav } from "../lib/nav";
import type { Dialog, DialogView, HistoryResp, Item } from "../lib/types";
import { PageHeader } from "../components/PageHeader";
import { StateView } from "../components/List";
import { MediaTile, Thumb, useLongPress } from "../components/Thumb";
import { dialogCache } from "./Folders";
import { HistorySheet } from "./ChatHistory";

/** 0 时间线 / 1 网格 / 2 瀑布流 */
type Mode = 0 | 1 | 2;
interface Opts { sort: string; asc: boolean; mode: Mode; types: string[]; columns: number }

const SORTS = [["taken", "时间"], ["name", "名称"], ["size", "大小"], ["type", "类型"]] as const;
const TYPES = [["photo", "照片"], ["video", "视频"], ["gif", "动图"]] as const;
const MODES: { id: Mode; label: string; icon: typeof LayoutList }[] = [
  { id: 0, label: "时间线", icon: LayoutList },
  { id: 1, label: "网格", icon: LayoutCellsLarge },
  { id: 2, label: "瀑布流", icon: LayoutColumns },
];
const PAGE = 60;
const dpr = () => window.devicePixelRatio || 1;

function historyPath(id: string, o: Pick<Opts, "sort" | "asc" | "types">, extra: Record<string, string | number | null | undefined>) {
  return `/api/v1/dialogs/${id}/history` + query({ sort: o.sort, dir: o.asc ? "asc" : "desc", types: o.types.join(","), limit: PAGE, ...extra });
}

/** 一个文件夹里的图片和视频:三种视图共用同一个排序方向,打开时回到上次的位置(和服务器同步)。 */
export function ChatScreen({ id, titleHint }: { id: string; titleHint?: string }) {
  const { settings: s, session, boot } = useApp();
  const nav = useNav();
  const [dialog, setDialog] = useState<Dialog | null>(() => dialogCache.get(id) ?? null);
  useEffect(() => { if (!dialog) api<Dialog>(`/api/v1/dialogs/${id}`).then(setDialog).catch(() => {}); }, [id, dialog]);

  // ---- 视图设置(打开时用设置里的默认值,然后被记住的浏览记录覆盖)
  const [opts, setOpts] = useState<Opts>(() => {
    let sort = session.local ? "taken" : "name";
    if (s.defaultSort !== "auto") sort = s.defaultSort;
    let asc = sort === "name" || sort === "type";
    if (s.defaultSortDir === "asc") asc = true;
    if (s.defaultSortDir === "desc") asc = false;
    if (session.local && s.defaultSort === "auto" && s.defaultSortDir === "auto") asc = false;
    return { sort, asc, mode: 0, types: ["photo", "video", "gif"], columns: Math.min(6, Math.max(2, s.defaultColumns)) };
  });
  const optsRef = useRef(opts);
  optsRef.current = opts;

  // ---- 分页数据(双向:围绕某一条取一页,向前 / 向后都能继续翻)
  const [items, setItems] = useState<Item[]>([]);
  const [prev, setPrev] = useState<string | null>(null);
  const [next, setNext] = useState<string | null>(null);
  const [total, setTotal] = useState(0);
  const [status, setStatus] = useState<"loading" | "ready" | "error">("loading");
  const [error, setError] = useState<string | null>(null);
  const [edgeBusy, setEdgeBusy] = useState<"" | "prev" | "next">("");
  const gen = useRef(0);
  const restored = useRef(false);
  /** 下一次布局后要滚到的条目(恢复位置 / 往前加载后保持位置 / 跳转)。 */
  const pendingScroll = useRef<{ id: string; offset: number } | null>(null);
  const itemsRef = useRef(items);
  itemsRef.current = items;

  const scroller = useRef<HTMLDivElement>(null);
  const header = useRef<HTMLDivElement>(null);
  const headerBottom = () => header.current?.getBoundingClientRect().bottom ?? 0;

  const reload = useCallback(async (anchor: string | null, offset = 0, o: Opts = optsRef.current) => {
    const my = ++gen.current;
    restored.current = false;
    setStatus("loading"); setError(null);
    try {
      let r: HistoryResp;
      try {
        r = await api<HistoryResp>(historyPath(id, o, { around: anchor }), { cache: true });
      } catch (e) {
        // 上次停留的条目已不存在(被删除 / 移走):从头开始
        if (anchor && e instanceof BridgeError && e.http === 404) r = await api<HistoryResp>(historyPath(id, o, {}), { cache: true });
        else throw e;
      }
      if (my !== gen.current) return;
      pendingScroll.current = anchor && r.items.some((x) => x.id === anchor) ? { id: anchor, offset } : null;
      setItems(r.items); setPrev(r.prevCursor ?? null); setNext(r.nextCursor ?? null); setTotal(r.total ?? r.items.length);
      setStatus("ready");
      if (!pendingScroll.current) { scroller.current?.scrollTo({ top: 0 }); restored.current = true; }
    } catch (e) {
      if (my !== gen.current) return;
      setError(errorText(e)); setStatus("error");
    }
  }, [id]);

  // 打开:取浏览记录(本机和服务器谁新用谁),然后按记录加载
  const [ready, setReady] = useState(false);
  useEffect(() => {
    let alive = true;
    (async () => {
      let anchor: string | null = null, offset = 0;
      if (s.rememberPosition) {
        const r = await call<{ view: DialogView | null; from: string }>("view.load", { dialogId: id }).catch(() => null);
        const v = r?.view;
        if (v) {
          const o: Opts = {
            sort: v.sort, asc: v.grid ? v.ascGrid : (v.ascChat ?? v.ascGrid), mode: !v.grid ? 0 : v.feed ? 2 : 1,
            types: v.types?.length ? v.types : ["photo", "video", "gif"], columns: Math.min(6, Math.max(2, v.columns || 3)),
          };
          optsRef.current = o; setOpts(o);
          anchor = v.itemId ?? null; offset = v.offsetPx ?? 0;
        }
        if (r?.from) toast(`已接着「${r.from}」上次的浏览位置`);
      }
      if (!alive) return;
      setReady(true);
      reload(anchor, offset);
    })();
    return () => { alive = false; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id]);

  // ---- 当前位置:屏幕顶端(顶栏下面)的第一项
  const topItem = useCallback((): { id: string; offset: number } | null => {
    const sc = scroller.current;
    if (!sc) return null;
    const y = headerBottom() + 2;
    const rect = sc.getBoundingClientRect();
    for (const x of [rect.left + 12, rect.left + rect.width / 2, rect.right - 12]) {
      for (const el of document.elementsFromPoint(x, y)) {
        const host = (el as HTMLElement).closest?.("[data-item]") as HTMLElement | null;
        if (host && sc.contains(host)) return { id: host.dataset.item!, offset: Math.round((headerBottom() - host.getBoundingClientRect().top) * dpr()) };
      }
    }
    return null;
  }, []);

  const scrollToItem = useCallback((target: { id: string; offset: number }, smooth = false) => {
    const sc = scroller.current;
    const el = sc?.querySelector<HTMLElement>(`[data-item="${CSS.escape(target.id)}"]`);
    if (!sc || !el) return false;
    const delta = el.getBoundingClientRect().top - headerBottom() + target.offset / dpr();
    sc.scrollBy({ top: delta, behavior: smooth ? "smooth" : "auto" });
    return true;
  }, []);

  useLayoutEffect(() => {
    const p = pendingScroll.current;
    if (!p) return;
    pendingScroll.current = null;
    scrollToItem(p);
    restored.current = true;
  }, [items, opts.mode, opts.columns, scrollToItem]);

  // ---- 保存位置(去抖;离开页面时立即保存)
  const snapshot = useCallback((): DialogView => {
    const o = optsRef.current;
    const t = topItem();
    const it = t ? itemsRef.current.find((x) => x.id === t.id) : undefined;
    return {
      itemId: t?.id ?? null, itemName: it?.name ?? "", offsetPx: t?.offset ?? 0, sort: o.sort, ascChat: o.asc, ascGrid: o.asc,
      grid: o.mode !== 0, feed: o.mode === 2, types: o.types, columns: o.columns, device: boot.model,
    };
  }, [topItem, boot.model]);
  const saveTimer = useRef(0);
  const saveSoon = useCallback(() => {
    if (!restored.current) return;
    clearTimeout(saveTimer.current);
    saveTimer.current = window.setTimeout(() => call("view.save", { dialogId: id, view: snapshot(), now: false }).catch(() => {}), 600);
  }, [id, snapshot]);
  useEffect(() => () => {
    clearTimeout(saveTimer.current);
    if (restored.current && itemsRef.current.length) call("view.save", { dialogId: id, view: snapshot(), now: true }).catch(() => {});
  }, [id, snapshot]);

  // ---- 向前 / 向后加载
  const loadNext = useCallback(async () => {
    if (!next || edgeBusy) return;
    const my = gen.current;
    setEdgeBusy("next");
    try {
      const r = await api<HistoryResp>(historyPath(id, optsRef.current, { cursor: next }), { cache: true });
      if (my !== gen.current) return;
      setItems((cur) => { const known = new Set(cur.map((x) => x.id)); return [...cur, ...r.items.filter((x) => !known.has(x.id))]; });
      setNext(r.nextCursor ?? null);
    } catch (e) { toast.danger(errorText(e)); }
    finally { setEdgeBusy(""); }
  }, [id, next, edgeBusy]);

  const loadPrev = useCallback(async () => {
    if (!prev || edgeBusy) return;
    const my = gen.current;
    setEdgeBusy("prev");
    try {
      const r = await api<HistoryResp>(historyPath(id, optsRef.current, { cursor: prev }), { cache: true });
      if (my !== gen.current) return;
      pendingScroll.current = topItem(); // 往前插入后,屏幕上的内容保持不动
      setItems((cur) => { const known = new Set(cur.map((x) => x.id)); return [...r.items.filter((x) => !known.has(x.id)), ...cur]; });
      setPrev(r.prevCursor ?? null);
    } catch (e) { toast.danger(errorText(e)); }
    finally { setEdgeBusy(""); }
  }, [id, prev, edgeBusy, topItem]);

  // 两端的哨兵进入视野(提前 1500px)就加载
  const topSentinel = useRef<HTMLDivElement>(null);
  const bottomSentinel = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const sc = scroller.current;
    if (!sc || status !== "ready") return;
    const io = new IntersectionObserver((es) => {
      for (const e of es) {
        if (!e.isIntersecting || !restored.current) continue;
        if (e.target === bottomSentinel.current) loadNext();
        if (e.target === topSentinel.current) loadPrev();
      }
    }, { root: sc, rootMargin: "1500px 0px" });
    if (topSentinel.current) io.observe(topSentinel.current);
    if (bottomSentinel.current) io.observe(bottomSentinel.current);
    return () => io.disconnect();
  }, [status, loadNext, loadPrev, items.length]);

  const onScroll = useRef(0);
  const handleScroll = () => {
    if (onScroll.current) return;
    onScroll.current = requestAnimationFrame(() => { onScroll.current = 0; saveSoon(); });
  };

  // ---- 修改视图:排序 / 方向 / 类型变了要重新取(回到开头),视图 / 列数只换布局(保持在当前这一条)
  const change = (patch: Partial<Opts>) => {
    const o = { ...optsRef.current, ...patch };
    const refetch = o.sort !== optsRef.current.sort || o.asc !== optsRef.current.asc || o.types.join() !== optsRef.current.types.join();
    const keep = topItem();
    optsRef.current = o;
    setOpts(o);
    if (refetch) reload(null, 0, o);
    else if (keep) pendingScroll.current = keep;
    restored.current = true;
    saveSoon();
  };

  // ---- 打开查看器 / 从查看器返回后定位到最后看的那一项
  const openViewer = (item: Item) => {
    const list = itemsRef.current;
    const o = optsRef.current;
    call("viewer.open", {
      items: list, index: Math.max(0, list.findIndex((x) => x.id === item.id)),
      source: { kind: "dialog", dialogId: id, sort: o.sort, dir: o.asc ? "asc" : "desc", types: o.types.join(",") },
    }).catch((e) => toast.danger(errorText(e)));
  };
  useEffect(() => on("viewerClosed", (d: { origin: string; itemId: string }) => {
    if (d.origin !== id || !d.itemId) return;
    if (!scrollToItem({ id: d.itemId, offset: 0 })) reload(d.itemId);
  }), [id, scrollToItem, reload]);
  // 文件夹内搜索选中了一条:围绕它重新加载
  useEffect(() => on("jumpTo", (d: { dialogId: string; itemId: string }) => { if (d.dialogId === id) reload(d.itemId); }), [id, reload]);

  // ---- 跳到某一天
  const jumpToDate = async (date: string) => {
    try {
      const r = await api<HistoryResp>(historyPath(id, optsRef.current, { aroundDate: date }).replace(`limit=${PAGE}`, "limit=2"));
      const it = r.items.length >= 2 ? r.items[1] : r.items[0];
      if (!it) { toast("这个日期附近没有文件"); return; }
      reload(it.id);
    } catch (e) { toast.danger(errorText(e)); }
  };

  const [sheet, setSheet] = useState(false);
  const [dateOpen, setDateOpen] = useState(false);
  const [historyOpen, setHistoryOpen] = useState(false);
  useBackHandler(sheet, () => setSheet(false));
  useBackHandler(dateOpen, () => setDateOpen(false));

  const counts = dialog?.counts;
  const subtitle = counts
    ? [counts.photo && `${counts.photo} 张照片`, counts.video && `${counts.video} 个视频`, counts.gif && `${counts.gif} 个动图`].filter(Boolean).join(" · ")
    : `${total} 项`;
  const nextMode = MODES[(opts.mode + 1) % 3];

  return (
    <div ref={scroller} className="page" style={{ overflowAnchor: "none" }} onScroll={handleScroll}>
      <div ref={header} className="sticky top-0 z-20">
        <PageHeader
          title={dialog?.title ?? titleHint ?? "文件夹"}
          subtitle={subtitle}
          onBack={nav.pop}
          actions={
            <>
              <Button isIconOnly variant="ghost" aria-label="在文件夹中搜索" onPress={() => nav.push({ name: "search", params: { dialogId: id, title: dialog?.title ?? titleHint } })}>
                <Magnifier className="size-5" />
              </Button>
              <Button isIconOnly variant="ghost" aria-label={`切换到${nextMode.label}`} onPress={() => { haptic("Tick"); change({ mode: nextMode.id }); }}>
                <nextMode.icon className="size-5" />
              </Button>
              <Button isIconOnly variant="ghost" aria-label="视图与排序" onPress={() => setSheet(true)}>
                <Sliders className="size-5" />
              </Button>
            </>
          }
        />
      </div>

      {!ready || (status === "loading" && items.length === 0) ? (
        <StateView loading />
      ) : status === "error" && items.length === 0 ? (
        <StateView error={error} onRetry={() => reload(null)} />
      ) : items.length === 0 ? (
        <StateView empty="这里没有符合条件的文件" />
      ) : (
        <div className="pb-safe">
          <div ref={topSentinel} className="h-px" />
          {prev && <div className="grid h-14 place-items-center">{edgeBusy === "prev" && <Spinner size="sm" />}</div>}
          {opts.mode === 1 ? (
            <GridView items={items} columns={opts.columns} onOpen={openViewer} onLong={(it) => { toast("已在时间线中定位到这一条"); change({ mode: 0 }); pendingScroll.current = { id: it.id, offset: 0 }; }} />
          ) : opts.mode === 2 ? (
            <MasonryView items={items} columns={opts.columns} onOpen={openViewer} />
          ) : (
            <TimelineView items={items} dated={opts.sort === "taken" && s.showDatePills} onOpen={openViewer} />
          )}
          <div ref={bottomSentinel} className="h-px" />
          <div className="grid h-16 place-items-center text-xs text-muted">
            {edgeBusy === "next" ? <Spinner size="sm" /> : !next && `共 ${total} 项`}
          </div>
        </div>
      )}

      {status === "loading" && items.length > 0 && (
        <div className="pointer-events-none fixed inset-x-0 top-[calc(var(--sat)+64px)] z-30 flex justify-center">
          <Chip variant="secondary" className="shadow-overlay"><Spinner size="sm" />加载中</Chip>
        </div>
      )}

      <OptionsSheet
        open={sheet} onOpenChange={setSheet} opts={opts} change={change}
        onDate={() => { setSheet(false); setDateOpen(true); }}
        onHistory={() => { setSheet(false); setHistoryOpen(true); }}
        onTop={() => { setSheet(false); reload(null); }}
      />
      <DateModal open={dateOpen} onOpenChange={setDateOpen} onPick={(d) => { setDateOpen(false); jumpToDate(d); }} />
      <HistorySheet
        open={historyOpen} onOpenChange={setHistoryOpen} dialogId={id} snapshot={snapshot}
        onRestore={(v) => {
          const o: Opts = { sort: v.sort, asc: v.ascGrid, mode: !v.grid ? 0 : v.feed ? 2 : 1, types: v.types, columns: Math.min(6, Math.max(2, v.columns || 3)) };
          optsRef.current = o; setOpts(o);
          reload(v.itemId ?? null, v.offsetPx ?? 0, o);
        }}
      />
    </div>
  );
}

// ---------------------------------------------------------------- 三种视图

function GridView({ items, columns, onOpen, onLong }: { items: Item[]; columns: number; onOpen: (i: Item) => void; onLong: (i: Item) => void }) {
  const { settings: s } = useApp();
  return (
    <div className="grid" style={{ gridTemplateColumns: `repeat(${columns}, minmax(0, 1fr))`, gap: s.gridSpacing }}>
      {items.map((it) => (
        <MediaTile
          key={it.id} item={it} width={s.gridThumbWidth} showName={s.showVideoName} showDuration={s.showDuration}
          className="aspect-square" onOpen={() => onOpen(it)} onLongPress={() => onLong(it)}
        />
      ))}
    </div>
  );
}

/** 瀑布流:按原比例的卡片,依次放进当前最矮的一列(顺序从上到下大致保持)。 */
function MasonryView({ items, columns, onOpen }: { items: Item[]; columns: number; onOpen: (i: Item) => void }) {
  const { settings: s } = useApp();
  const n = columns >= 5 ? 3 : 2;
  const cols = useMemo(() => {
    const out: Item[][] = Array.from({ length: n }, () => []);
    const h = new Array(n).fill(0);
    for (const it of items) {
      let k = 0;
      for (let i = 1; i < n; i++) if (h[i] < h[k] - 0.01) k = i;
      out[k].push(it);
      h[k] += 1 / Math.min(2, Math.max(0.5, aspectOf(it))) + 0.18;
    }
    return out;
  }, [items, n]);
  return (
    <div className="flex items-start gap-3 px-4 pt-1">
      {cols.map((col, i) => (
        <div key={i} className="flex min-w-0 flex-1 flex-col gap-3">
          {col.map((it) => <FeedCard key={it.id} item={it} width={s.gridThumbWidth} onOpen={() => onOpen(it)} />)}
        </div>
      ))}
    </div>
  );
}

function FeedCard({ item, width, onOpen }: { item: Item; width: number; onOpen: () => void }) {
  const ratio = Math.min(2, Math.max(0.5, aspectOf(item)));
  return (
    <button type="button" data-item={item.id} onClick={onOpen} className="tile rounded-3xl bg-surface p-2 text-left shadow-surface transition-transform active:scale-[0.98]" style={{ containIntrinsicSize: `auto ${Math.round(180 / ratio) + 56}px` }}>
      <div className="relative overflow-hidden rounded-2xl" style={{ aspectRatio: String(ratio) }}>
        <Thumb item={item} width={width} className="absolute inset-0" />
        {item.type === "video" && (
          <span className="absolute right-1.5 bottom-1.5 rounded-full bg-black/55 px-2 py-0.5 text-[11px] text-white">{formatDuration(item.durationMs) || "视频"}</span>
        )}
      </div>
      <div className="px-1.5 pt-2 pb-1">
        <p className="line-clamp-2 text-sm leading-snug font-medium">{stripExt(item.name)}</p>
        <p className="mt-1 text-xs text-muted tabular-nums">{formatShortTime(item.takenAt)}{item.size ? ` · ${formatSize(item.size)}` : ""}</p>
      </div>
    </button>
  );
}

/** 时间线:一列大图,按天分组(按时间排序且开了日期分隔时),每张图下面是文件名、时间和大小。 */
function TimelineView({ items, dated, onOpen }: { items: Item[]; dated: boolean; onOpen: (i: Item) => void }) {
  const { settings: s } = useApp();
  return (
    <div className="mx-auto flex max-w-xl flex-col gap-4 px-4 pt-1">
      {items.map((it, i) => {
        const showDay = dated && (i === 0 || dayKey(items[i - 1].takenAt) !== dayKey(it.takenAt));
        return (
          <Fragment key={it.id}>
            {showDay && (
              <div className="sticky top-[calc(var(--sat)+60px)] z-10 flex justify-center py-1">
                <Chip size="sm" variant="secondary" className="shadow-surface backdrop-blur">{dayLabel(it.takenAt)}</Chip>
              </div>
            )}
            <TimelineCard item={it} width={s.chatImageWidth} onOpen={() => onOpen(it)} />
          </Fragment>
        );
      })}
    </div>
  );
}

/** 时间线卡片:白色大圆角卡片,上方文件名和时间,中间是圆角图片,下方大小 / 时长。 */
function TimelineCard({ item, width, onOpen }: { item: Item; width: number; onOpen: () => void }) {
  const ratio = Math.min(2.2, Math.max(0.6, aspectOf(item)));
  const press = useLongPress(onOpen);
  const meta = [item.type === "video" ? formatDuration(item.durationMs) : item.type === "gif" ? "动图" : "", formatSize(item.size)].filter(Boolean).join(" · ");
  return (
    <article data-item={item.id} className="tile rounded-3xl bg-surface p-3 shadow-surface" style={{ containIntrinsicSize: `auto ${Math.round(340 / ratio) + 76}px` }}>
      <div className="flex items-baseline gap-2 px-1 pb-2.5">
        <p className="min-w-0 flex-1 truncate text-sm font-medium">{item.name}</p>
        <span className="shrink-0 text-xs text-muted tabular-nums">{formatShortTime(item.takenAt)}</span>
      </div>
      <button type="button" className="relative block w-full overflow-hidden rounded-2xl active:opacity-90" style={{ aspectRatio: String(ratio) }} {...press}>
        <Thumb item={item} width={width} className="absolute inset-0" />
        {item.type === "video" && (
          <span className="absolute inset-0 grid place-items-center">
            <span className="grid size-14 place-items-center rounded-full bg-black/40 text-white backdrop-blur-sm">
              <svg viewBox="0 0 24 24" className="ml-1 size-7 fill-current"><path d="M8 5.5v13l11-6.5z" /></svg>
            </span>
          </span>
        )}
      </button>
      {meta && <p className="px-1 pt-2 text-xs text-muted tabular-nums">{meta}</p>}
    </article>
  );
}

// ---------------------------------------------------------------- 视图与排序

function OptionsSheet({
  open, onOpenChange, opts, change, onDate, onHistory, onTop,
}: {
  open: boolean; onOpenChange: (v: boolean) => void; opts: Opts; change: (p: Partial<Opts>) => void;
  onDate: () => void; onHistory: () => void; onTop: () => void;
}) {
  const single = (k: Set<Key>) => [...k][0];
  return (
    <Sheet isOpen={open} onOpenChange={onOpenChange}>
      <Sheet.Backdrop>
        <Sheet.Content className="mx-auto max-w-lg">
          <Sheet.Dialog>
            <Sheet.Handle />
            <Sheet.Header>
              <Sheet.Heading>视图与排序</Sheet.Heading>
            </Sheet.Header>
            <Sheet.Body className="flex flex-col gap-5 pb-safe">
              <Field label="视图">
                <ToggleButtonGroup fullWidth selectionMode="single" disallowEmptySelection selectedKeys={new Set([String(opts.mode)])}
                  onSelectionChange={(k) => { haptic("Tick"); change({ mode: Number(single(k)) as Mode }); }}>
                  {MODES.map((m, i) => (
                    <ToggleButton key={m.id} id={String(m.id)}>
                      {i > 0 && <ToggleButtonGroup.Separator />}
                      <m.icon className="size-4" />{m.label}
                    </ToggleButton>
                  ))}
                </ToggleButtonGroup>
              </Field>
              <Field label="排序">
                <ToggleButtonGroup fullWidth selectionMode="single" disallowEmptySelection selectedKeys={new Set([opts.sort])}
                  onSelectionChange={(k) => { const sort = String(single(k)); haptic("Tick"); change({ sort, asc: sort === "name" || sort === "type" }); }}>
                  {SORTS.map(([k, l], i) => (
                    <ToggleButton key={k} id={k}>{i > 0 && <ToggleButtonGroup.Separator />}{l}</ToggleButton>
                  ))}
                </ToggleButtonGroup>
              </Field>
              <Field label="方向">
                <ToggleButtonGroup fullWidth selectionMode="single" disallowEmptySelection selectedKeys={new Set([opts.asc ? "asc" : "desc"])}
                  onSelectionChange={(k) => change({ asc: single(k) === "asc" })}>
                  <ToggleButton id="asc"><ArrowUpArrowDown className="size-4" />{opts.sort === "taken" ? "旧 → 新" : opts.sort === "size" ? "小 → 大" : "A → Z"}</ToggleButton>
                  <ToggleButton id="desc"><ToggleButtonGroup.Separator /><ArrowUpArrowDown className="size-4 rotate-180" />{opts.sort === "taken" ? "新 → 旧" : opts.sort === "size" ? "大 → 小" : "Z → A"}</ToggleButton>
                </ToggleButtonGroup>
              </Field>
              <Field label="显示">
                <ToggleButtonGroup fullWidth selectionMode="multiple" selectedKeys={new Set(opts.types)}
                  onSelectionChange={(k) => { const t = TYPES.map(([x]) => x).filter((x) => k.has(x)); if (t.length) change({ types: t }); }}>
                  {TYPES.map(([k, l], i) => (
                    <ToggleButton key={k} id={k}>{i > 0 && <ToggleButtonGroup.Separator />}{l}</ToggleButton>
                  ))}
                </ToggleButtonGroup>
              </Field>
              {opts.mode !== 0 && (
                <Slider minValue={2} maxValue={6} step={1} value={opts.columns} onChange={(v) => change({ columns: Number(v) })}>
                  <div className="flex items-center justify-between"><Label>每行列数</Label><Slider.Output /></div>
                  <Slider.Track>
                    <Slider.Fill />
                    <Slider.Thumb />
                  </Slider.Track>
                </Slider>
              )}
              <div className="grid grid-cols-3 gap-2">
                <Button variant="secondary" className="h-auto flex-col gap-1 py-3" onPress={onDate}><CalendarIcon className="size-5" />跳到日期</Button>
                <Button variant="secondary" className="h-auto flex-col gap-1 py-3" onPress={onHistory}><ClockArrowRotateLeft className="size-5" />浏览历史</Button>
                <Button variant="secondary" className="h-auto flex-col gap-1 py-3" onPress={onTop}><Bookmark className="size-5" />回到开头</Button>
              </div>
            </Sheet.Body>
          </Sheet.Dialog>
        </Sheet.Content>
      </Sheet.Backdrop>
    </Sheet>
  );
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="flex flex-col gap-2">
      <span className="text-xs font-medium text-muted">{label}</span>
      {children}
    </div>
  );
}

function DateModal({ open, onOpenChange, onPick }: { open: boolean; onOpenChange: (v: boolean) => void; onPick: (d: string) => void }) {
  return (
    <Modal.Backdrop isOpen={open} onOpenChange={onOpenChange}>
      <Modal.Container placement="center">
        <Modal.Dialog className="w-auto">
          <Modal.Header><Modal.Heading>跳到日期</Modal.Heading></Modal.Header>
          <Modal.Body className="flex justify-center">
            <Calendar aria-label="选择日期" onChange={(d) => d && onPick(d.toString())}>
              <Calendar.Header>
                <Calendar.YearPickerTrigger>
                  <Calendar.YearPickerTriggerHeading />
                  <Calendar.YearPickerTriggerIndicator />
                </Calendar.YearPickerTrigger>
                <Calendar.NavButton slot="previous" />
                <Calendar.NavButton slot="next" />
              </Calendar.Header>
              <Calendar.Grid>
                <Calendar.GridHeader>{(day) => <Calendar.HeaderCell>{day}</Calendar.HeaderCell>}</Calendar.GridHeader>
                <Calendar.GridBody>{(date) => <Calendar.Cell date={date} />}</Calendar.GridBody>
              </Calendar.Grid>
            </Calendar>
          </Modal.Body>
          <Modal.Footer><Button variant="tertiary" slot="close">取消</Button></Modal.Footer>
        </Modal.Dialog>
      </Modal.Container>
    </Modal.Backdrop>
  );
}
