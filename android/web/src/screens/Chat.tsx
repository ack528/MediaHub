import { Button, Calendar, Chip, Modal, Sheet, Spinner, ToggleButton, ToggleButtonGroup, toast } from "@heroui/react";
import { ArrowUpArrowDown, Bookmark, Calendar as CalendarIcon, ClockArrowRotateLeft, LayoutCellsLarge, LayoutColumns, LayoutList, Magnifier, Sliders } from "@gravity-ui/icons";
import { Fragment, useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState, type Key } from "react";
import { api, BridgeError, call, errorText, haptic, on, query } from "../lib/bridge";
import { useShortVideoPreload } from "../lib/preload";
import { useApp } from "../lib/app";
import { aspectOf, dayKey, dayLabel, formatDuration, formatShortTime, stripExt } from "../lib/media";
import { heroArgs } from "../lib/hero";
import { useBackHandler, useNav } from "../lib/nav";
import type { Dialog, DialogView, HistoryResp, Item } from "../lib/types";
import { PageHeader } from "../components/PageHeader";
import { StateView } from "../components/List";
import { MediaTile, Thumb, useLongPress } from "../components/Thumb";
import { Chunk, adaptiveColumns, gridChunk, revealItem, useChunks, useViewportWidth } from "../components/Windowed";
import { DateScrubber, DayPill, createStore } from "../components/DateScrubber";
import { GridSkeleton, MasonrySkeleton, TimelineSkeleton } from "../components/Skeleton";
import { dialogCache } from "./Folders";
import { HistorySheet } from "./ChatHistory";
import { SelectMark, SelectionContext } from "../lib/select";

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
export function ChatScreen({ id, titleHint, focus }: { id: string; titleHint?: string; focus?: string }) {
  const { settings: s, session, boot, caps } = useApp();
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
  /** frac:按"这一格被遮住的比例"定位(切换视图 / 列数时用 —— 新旧视图里一格的高度不同,不能沿用像素偏移) */
  const pendingScroll = useRef<{ id: string; offset: number; frac?: number } | null>(null);
  const itemsRef = useRef(items);
  itemsRef.current = items;

  // ---- 多选删除(管理员账号):选中的文件由服务端移进回收区
  const [selecting, setSelecting] = useState(false);
  const [picked, setPicked] = useState<ReadonlySet<string>>(new Set());
  const selectingRef = useRef(false);
  selectingRef.current = selecting;
  const [confirmDel, setConfirmDel] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const selection = useMemo(() => ({ on: selecting, ids: picked }), [selecting, picked]);
  const toggle = useCallback((itemId: string) => {
    haptic("Tick");
    setPicked((cur) => { const n = new Set(cur); if (n.has(itemId)) n.delete(itemId); else n.add(itemId); return n; });
  }, []);
  const stopSelecting = () => { setSelecting(false); setPicked(new Set()); };
  useBackHandler(selecting, stopSelecting);
  const doDelete = async () => {
    setDeleting(true);
    try {
      const r = await call<{ deleted: number; failed: string[] }>("media.delete", { ids: [...picked] });
      toast(r.failed.length ? `已删除 ${r.deleted} 个,${r.failed.length} 个失败:${r.failed[0]}` : `已移到回收区(${r.deleted} 个)`);
      stopSelecting();
    } catch (e) { toast.danger(errorText(e)); }
    finally { setDeleting(false); setConfirmDel(false); }
  };
  // 文件被删除(这里选择删除 / 查看器里删除):从列表里去掉
  useEffect(() => on("mediaDeleted", (ids: string[]) => {
    const gone = new Set(ids);
    setItems((cur) => cur.filter((x) => !gone.has(x.id)));
    setTotal((t) => Math.max(0, t - ids.length));
  }), []);

  const scroller = useRef<HTMLDivElement>(null);
  useShortVideoPreload(scroller, items); // 屏幕附近 20 秒以内的短视频预加载,点开直接播
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
        if (r?.from && !focus) toast(`已接着「${r.from}」上次的浏览位置`);
      }
      // 从查看器「在文件夹中定位」打开:视图设置沿用记住的,但围绕那一项加载(不回到上次的位置)
      // 类型过滤要包含它(记住的过滤可能把这一类排除了,围绕它加载会找不到)
      if (focus) {
        anchor = focus; offset = 0;
        const o: Opts = { ...optsRef.current, types: ["photo", "video", "gif"] };
        optsRef.current = o; setOpts(o);
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

  const scrollToItem = useCallback(function go(target: { id: string; offset: number; frac?: number }, smooth = false, retry = true): boolean {
    // 它所在的块可能正是占位(分块渲染):这里多在 layout effect 里调用,不能同步渲染,先发起显示,找不到就下一帧再定位一次
    revealItem(target.id, false);
    const sc = scroller.current;
    const el = sc?.querySelector<HTMLElement>(`[data-item="${CSS.escape(target.id)}"]`);
    if (!el && sc && retry) { requestAnimationFrame(() => { go(target, smooth, false); }); return true; }
    if (!sc || !el) return false;
    const r = el.getBoundingClientRect();
    const delta = r.top - headerBottom() + (target.frac != null ? target.frac * r.height : target.offset / dpr());
    // 往前插入内容时浏览器的滚动锚定通常已经保持住位置了;再 scrollBy(哪怕 0)会打断正在进行的惯性滑动
    if (Math.abs(delta) >= 1) sc.scrollBy({ top: delta, behavior: smooth ? "smooth" : "auto" });
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

  // 屏幕顶端那一项的时间(浮动日期胶囊、日期滑条用;只在按时间排序时更新)
  const topTime = useMemo(() => createStore<number | null>(null), []);
  const timeOf = useMemo(() => new Map(items.map((x) => [x.id, x.takenAt ? Date.parse(x.takenAt) : NaN])), [items]);
  const lastTop = useRef<{ id: string; offset: number } | null>(null); // 转屏时用:变宽 / 变窄后滚回这一项
  const updateTop = useCallback(() => {
    if (window.innerWidth !== prevWidth.current) return; // 正在转屏:布局还没换好,这时的顶端项不对
    const t = topItem();
    lastTop.current = t;
    if (optsRef.current.sort !== "taken") { topTime.set(null); return; }
    const ms = t ? timeOf.get(t.id) : undefined;
    topTime.set(ms != null && isFinite(ms) ? ms : null);
  }, [topItem, timeOf, topTime]);
  useEffect(() => { if (status === "ready") requestAnimationFrame(updateTop); }, [status, items, opts.mode, opts.sort, updateTop]);

  const onScroll = useRef(0);
  const handleScroll = () => {
    if (onScroll.current) return;
    onScroll.current = requestAnimationFrame(() => { onScroll.current = 0; updateTop(); saveSoon(); });
  };

  // 日期滑条的范围:当前顺序里第一项和最后一项的时间(各取 1 条)
  const [range, setRange] = useState<{ a: number; b: number } | null>(null);
  const typesKey = opts.types.join(",");
  useEffect(() => {
    if (opts.sort !== "taken") { setRange(null); return; }
    let alive = true;
    const o = { sort: opts.sort, asc: opts.asc, types: typesKey.split(",") };
    Promise.all([
      api<HistoryResp>(historyPath(id, o, { limit: 1 }), { cache: true }),
      api<HistoryResp>(historyPath(id, { ...o, asc: !o.asc }, { limit: 1 }), { cache: true }),
    ]).then(([x, y]) => {
      const a = Date.parse(x.items[0]?.takenAt ?? ""), b = Date.parse(y.items[0]?.takenAt ?? "");
      if (alive) setRange(isFinite(a) && isFinite(b) && a !== b ? { a, b } : null);
    }).catch(() => { if (alive) setRange(null); });
    return () => { alive = false; };
  }, [id, opts.sort, opts.asc, typesKey]);

  // ---- 修改视图:排序 / 方向 / 类型变了要重新取(回到开头),视图 / 列数只换布局(保持在当前这一条)
  const change = (patch: Partial<Opts>) => {
    const o = { ...optsRef.current, ...patch };
    const refetch = o.sort !== optsRef.current.sort || o.asc !== optsRef.current.asc || o.types.join() !== optsRef.current.types.join();
    const keep = topItem();
    optsRef.current = o;
    setOpts(o);
    if (refetch) reload(null, 0, o);
    else if (keep) {
      // 换视图 / 列数:一格的高度变了(时间线的大图 → 网格的小格),按被遮住的比例换算,不能沿用像素偏移(会多跳好几行)
      const el = scroller.current?.querySelector<HTMLElement>(`[data-item="${CSS.escape(keep.id)}"]`);
      const h = el?.getBoundingClientRect().height ?? 0;
      pendingScroll.current = { id: keep.id, offset: 0, frac: h > 0 ? Math.min(0.5, Math.max(0, keep.offset / dpr() / h)) : 0 }; // 最多遮住一半:切换后刚才看的那一项一定看得见
    }
    restored.current = true;
    saveSoon();
  };

  // ---- 打开查看器 / 从查看器返回后定位到最后看的那一项
  const openViewer = (item: Item) => {
    if (selectingRef.current) { toggle(item.id); return; }
    const all = itemsRef.current;
    const o = optsRef.current;
    // 只把点开的这一项前后 300 项交给查看器(几千项整个序列化传过去,点开会卡一下);后面的由查看器按游标继续加载
    const at = Math.max(0, all.findIndex((x) => x.id === item.id));
    const from = Math.max(0, at - 300);
    const list = all.length > 600 ? all.slice(from, at + 301) : all;
    call("viewer.open", {
      items: list, index: Math.max(0, list.findIndex((x) => x.id === item.id)), ...heroArgs(item.id),
      source: { kind: "dialog", dialogId: id, sort: o.sort, dir: o.asc ? "asc" : "desc", types: o.types.join(",") },
    }).catch((e) => toast.danger(errorText(e)));
  };
  useEffect(() => on("viewerClosed", (d: { origin: string; itemId: string }) => {
    if (d.origin !== id || !d.itemId) return;
    // 看的过程中列表已经跟着滚到了那一项(返回动画落在那里);查看器里翻到了列表还没加载的部分时,围绕它重新加载
    if (!itemsRef.current.some((x) => x.id === d.itemId)) reload(d.itemId); // 分块渲染:列表里有但所在块是占位的也算"有"
  }), [id, reload]);
  // 查看器翻到了列表还没加载的部分:趁查看器还盖着,围绕那一项重新加载(返回时列表已经在那里,动画落在它上面)
  useEffect(() => on("viewerMissing", (itemId: string) => { if (itemId) reload(itemId); }), [reload]);
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
  const width = useViewportWidth();
  // 转屏 / 分屏改变宽度:列数和每格大小都变了,滚回转屏前顶端的那一项
  const prevWidth = useRef(width);
  useLayoutEffect(() => {
    if (prevWidth.current === width) return;
    prevWidth.current = width;
    if (lastTop.current && restored.current) scrollToItem(lastTop.current);
  }, [width, scrollToItem]);

  return (
    <SelectionContext.Provider value={selection}>
    <div ref={scroller} className="page" style={{ overflowAnchor: "none" }} onScroll={handleScroll}>
      <div ref={header} className="sticky top-0 z-20">
        <PageHeader
          title={selecting ? `已选 ${picked.size} 项` : dialog?.title ?? titleHint ?? "文件夹"}
          subtitle={selecting ? "点缩略图选择,再点一次取消" : subtitle}
          onBack={selecting ? stopSelecting : nav.pop}
          actions={selecting ? (
            <>
              <Button variant="ghost" onPress={() => setPicked(new Set(items.map((x) => x.id)))}>全选已加载</Button>
              <Button variant="ghost" onPress={stopSelecting}>取消</Button>
            </>
          ) : (
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
          )}
        />
      </div>

      {!ready || (status === "loading" && items.length === 0) ? (
        opts.mode === 1 ? <GridSkeleton columns={adaptiveColumns(opts.columns, width)} gap={s.gridSpacing} />
          : opts.mode === 2 ? <MasonrySkeleton columns={2} />
          : <TimelineSkeleton widthPct={Math.min(100, Math.max(40, Number(s.bubbleWidth ?? 72)))} />
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
            <MasonryView items={items} onOpen={openViewer} />
          ) : (
            <TimelineView items={items} dated={opts.sort === "taken" && s.showDatePills} onOpen={openViewer} />
          )}
          <div ref={bottomSentinel} className="h-px" />
          <div className="grid h-16 place-items-center text-xs text-muted">
            {edgeBusy === "next" ? <Spinner size="sm" /> : !next && `共 ${total} 项`}
          </div>
        </div>
      )}

      {status === "ready" && items.length > 0 && opts.mode === 0 && opts.sort === "taken" && s.showDatePills && <DayPill top={topTime} />}
      {status === "ready" && range && total >= 100 && <DateScrubber scroller={scroller} top={topTime} range={range} onJump={jumpToDate} />}

      {status === "loading" && items.length > 0 && (
        <div className="pointer-events-none fixed inset-x-0 top-[calc(var(--sat)+64px)] z-30 flex justify-center">
          <Chip variant="secondary" className="shadow-overlay"><Spinner size="sm" />加载中</Chip>
        </div>
      )}

      {selecting && (
        <div className="fixed inset-x-0 bottom-0 z-30 flex justify-center bg-background/90 px-4 pt-2 pb-safe backdrop-blur">
          <Button variant="danger" className="mb-2 w-full max-w-md" isDisabled={picked.size === 0} onPress={() => setConfirmDel(true)}>
            删除{picked.size ? `(${picked.size})` : ""}
          </Button>
        </div>
      )}
      <Modal.Backdrop isOpen={confirmDel} onOpenChange={(v) => { if (!deleting) setConfirmDel(v); }}>
        <Modal.Container placement="center">
          <Modal.Dialog className="w-80">
            <Modal.Header><Modal.Heading>删除 {picked.size} 个文件?</Modal.Heading></Modal.Header>
            <Modal.Body className="text-sm text-muted">文件会被移到服务器所在盘的回收区,之后可以在电脑上的管理程序里还原。</Modal.Body>
            <Modal.Footer>
              <Button variant="tertiary" slot="close" isDisabled={deleting}>取消</Button>
              <Button variant="danger" isPending={deleting} onPress={doDelete}>删除</Button>
            </Modal.Footer>
          </Modal.Dialog>
        </Modal.Container>
      </Modal.Backdrop>
      <OptionsSheet
        onSelect={caps.phoneDelete && !session.local ? () => { setSheet(false); setSelecting(true); } : undefined}
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
    </SelectionContext.Provider>
  );
}

// ---------------------------------------------------------------- 三种视图

/**
 * 网格:分块渲染(每块整数行),离屏的块换成同高占位。宽屏(横屏 / 平板)按宽度自动加列。
 * 原点前面最靠上的那块可能不满整行:把它的空位留在最上面一行的开头,下面的行都是满的(块和块之间不会出现缺口)。
 */
function GridView({ items, columns, onOpen, onLong }: { items: Item[]; columns: number; onOpen: (i: Item) => void; onLong: (i: Item) => void }) {
  const { settings: s } = useApp();
  const cols = adaptiveColumns(columns, useViewportWidth());
  const size = gridChunk(cols);
  const chunks = useChunks(items, size);
  const gap = s.gridSpacing;
  const tpl = `repeat(${cols}, minmax(0, 1fr))`;
  return (
    <div className="flex flex-col" style={{ gap }}>
      {chunks.map((c, ci) => {
        const part = items.slice(c.start, c.end);
        const lead = ci === 0 && chunks.length > 1 && part.length < size ? (cols - (part.length % cols)) % cols : 0;
        return (
          <Chunk key={c.key} ids={part.map((x) => x.id)} className="grid" style={{ gridTemplateColumns: tpl, gap }}>
            {part.map((it, i) => (
              <MediaTile
                key={it.id} item={it} width={s.gridThumbWidth} showName={s.showVideoName} showDuration={s.showDuration}
                className="aspect-square" style={i === 0 && lead ? { gridColumnStart: lead + 1 } : undefined}
                onOpen={() => onOpen(it)} onLongPress={() => onLong(it)}
              />
            ))}
          </Chunk>
        );
      })}
    </div>
  );
}

/** 瀑布流固定 2 列(手机竖屏);横屏 / 平板按宽度等比例加列(约每 420px 两列)。 */
const MASONRY_COLS = 2;

/** 瀑布流:按原比例的卡片,依次放进当前最矮的一列(顺序从上到下大致保持)。宽屏按宽度加列;每一列各自分块渲染。 */
function MasonryView({ items, onOpen }: { items: Item[]; onOpen: (i: Item) => void }) {
  const { settings: s } = useApp();
  const width = useViewportWidth();
  const n = width > 600 ? Math.min(6, Math.max(MASONRY_COLS, Math.round((MASONRY_COLS * width) / 420))) : MASONRY_COLS;
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
    <div className="flex items-start gap-1.5 px-1.5 pt-1">
      {cols.map((col, i) => <MasonryColumn key={i} items={col} width={s.gridThumbWidth} onOpen={onOpen} />)}
    </div>
  );
}

function MasonryColumn({ items, width, onOpen }: { items: Item[]; width: number; onOpen: (i: Item) => void }) {
  const chunks = useChunks(items, 16);
  return (
    <div className="flex min-w-0 flex-1 flex-col gap-1.5">
      {chunks.map((c) => {
        const part = items.slice(c.start, c.end);
        return (
          <Chunk key={c.key} ids={part.map((x) => x.id)} className="flex flex-col gap-1.5">
            {part.map((it) => <FeedCard key={it.id} item={it} width={width} onOpen={() => onOpen(it)} />)}
          </Chunk>
        );
      })}
    </div>
  );
}

/**
 * 瀑布流的一张(紧凑版):以前是白底卡片 + 8px 内边距 + 两行文件名 + 时间大小一行,文字区几乎和图一样高。
 * 现在图直接铺满(圆角 12px),只在下面留一行小字文件名;时间 / 时长放在图片角上的小胶囊里。
 */
function FeedCard({ item, width, onOpen }: { item: Item; width: number; onOpen: () => void }) {
  const ratio = Math.min(2, Math.max(0.5, aspectOf(item)));
  return (
    <button type="button" data-item={item.id} onClick={onOpen} className="block w-full text-left transition-transform active:scale-[0.98]">
      <div data-hero className="relative overflow-hidden rounded-xl bg-surface-tertiary" style={{ aspectRatio: String(ratio) }}>
        <Thumb item={item} width={width} className="absolute inset-0" />
        <SelectMark id={item.id} />
        <span className="pointer-events-none absolute right-1 bottom-1 rounded-full bg-black/55 px-1.5 py-0.5 text-[10px] leading-none text-white tabular-nums">
          {item.type === "video" ? formatDuration(item.durationMs) || "视频" : formatShortTime(item.takenAt)}
        </span>
      </div>
      <p className="truncate px-0.5 pt-0.5 pb-1 text-[11px] leading-tight text-muted">{stripExt(item.name)}</p>
    </button>
  );
}

/**
 * 时间线(原来的聊天流):每一项是一个没有外框的圆角媒体"气泡",宽度 / 间距跟设置里的气泡宽度、气泡间距;
 * 时间叠在图片右下角,文件名是图片下面一行小字。按时间排序时按天插入日期胶囊。
 */
function TimelineView({ items, dated, onOpen }: { items: Item[]; dated: boolean; onOpen: (i: Item) => void }) {
  const { settings: s } = useApp();
  const bubble = Math.min(100, Math.max(40, Number(s.bubbleWidth ?? 72)));
  const gap = Math.max(4, Number(s.chatSpacing ?? 3) * 2);
  const chunks = useChunks(items, 24);
  const showDay = (i: number) => dated && (i === 0 || dayKey(items[i - 1].takenAt) !== dayKey(items[i].takenAt));
  return (
    <div className="mx-auto flex max-w-xl flex-col px-3 pt-1" style={{ gap }}>
      {chunks.map((c) => {
        const part = items.slice(c.start, c.end);
        // 块的第一项有没有日期胶囊取决于前一块的最后一项(往前加载后可能变):变了就换键重新挂载,不沿用旧的高度
        return (
          <Chunk key={`${c.key}${showDay(c.start) ? "d" : ""}`} ids={part.map((x) => x.id)} className="flex flex-col" style={{ gap }}>
            {part.map((it, j) => (
              <Fragment key={it.id}>
                {/* 日期胶囊不再吸顶:当前这一天由页面顶部的浮动胶囊显示(DayPill) */}
                {showDay(c.start + j) && (
                  <div className="flex justify-center py-1.5">
                    <Chip size="sm" variant="soft" color="accent">{dayLabel(it.takenAt)}</Chip>
                  </div>
                )}
                <TimelineBubble item={it} width={s.chatImageWidth} widthPct={bubble} onOpen={() => onOpen(it)} />
              </Fragment>
            ))}
          </Chunk>
        );
      })}
    </div>
  );
}

function TimelineBubble({ item, width, widthPct, onOpen }: { item: Item; width: number; widthPct: number; onOpen: () => void }) {
  const ratio = Math.min(2.2, Math.max(0.6, aspectOf(item)));
  const press = useLongPress(onOpen);
  return (
    <div data-item={item.id} style={{ width: `${widthPct}%` }}>
      <button type="button" data-hero className="relative block w-full overflow-hidden rounded-2xl bg-surface-tertiary active:opacity-90" style={{ aspectRatio: String(ratio) }} {...press}>
        <Thumb item={item} width={width} className="absolute inset-0" />
        <SelectMark id={item.id} />
        {item.type === "video" && (
          <>
            <span className="absolute inset-0 grid place-items-center">
              <span className="grid size-12 place-items-center rounded-full bg-black/40 text-white backdrop-blur-sm">
                <svg viewBox="0 0 24 24" className="ml-0.5 size-6 fill-current"><path d="M8 5.5v13l11-6.5z" /></svg>
              </span>
            </span>
            {item.durationMs ? <span className="absolute top-2 left-2 rounded-full bg-black/45 px-1.5 py-0.5 text-[11px] text-white tabular-nums">{formatDuration(item.durationMs)}</span> : null}
          </>
        )}
        {item.type === "gif" && <span className="absolute top-2 left-2 rounded-full bg-black/45 px-1.5 py-0.5 text-[11px] text-white">GIF</span>}
        <span className="absolute right-2 bottom-2 rounded-full bg-black/45 px-1.5 py-0.5 text-[11px] text-white tabular-nums">{formatShortTime(item.takenAt)}</span>
      </button>
      <p className="truncate px-1 pt-1 text-xs text-muted">{item.name}</p>
    </div>
  );
}

// ---------------------------------------------------------------- 视图与排序

function OptionsSheet({
  open, onOpenChange, opts, change, onDate, onHistory, onTop, onSelect,
}: {
  onSelect?: () => void;
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
              {/* 每行数量:以前是滑块,面板本身能下拉关闭,拖滑块常被面板的手势抢走 → 改成直接点的分段按钮 */}
              {opts.mode === 1 && (
                <Field label="每行数量">
                  <ToggleButtonGroup fullWidth selectionMode="single" disallowEmptySelection
                    selectedKeys={new Set([String(opts.columns)])}
                    onSelectionChange={(k) => { haptic("Tick"); change({ columns: Number(single(k)) }); }}>
                    {[2, 3, 4, 5, 6].map((n, i) => (
                      <ToggleButton key={n} id={String(n)}>{i > 0 && <ToggleButtonGroup.Separator />}{n}</ToggleButton>
                    ))}
                  </ToggleButtonGroup>
                </Field>
              )}
              <div className={`grid gap-2 ${onSelect ? "grid-cols-4" : "grid-cols-3"}`}>
                {onSelect && <Button variant="secondary" className="h-auto flex-col gap-1 py-3" onPress={onSelect}><Bookmark className="size-5" />选择</Button>}
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
