import { Avatar, Button, Chip, Tabs } from "@heroui/react";
import { ArrowsRotateRight, Magnifier, Shuffle } from "@gravity-ui/icons";
import { useCallback, useEffect, useMemo, useRef, useState, type Key } from "react";
import { api, call, errorText, haptic, peek, query } from "../lib/bridge";
import { useApp } from "../lib/app";
import { dialogCover, formatShortTime, mediaCount, rootLabel } from "../lib/media";
import { useNav, usePageActive } from "../lib/nav";
import type { Dialog, DialogsResp } from "../lib/types";
import { PageHeader } from "../components/PageHeader";
import { StateView } from "../components/List";
import { Chunk, useChunks } from "../components/Windowed";
import { RowsSkeleton } from "../components/Skeleton";
import { TAB_BAR_SPACE } from "./Home";
import { gradientFor } from "../lib/tones";

const FIRST = "/api/v1/dialogs" + query({ group: "all", sort: "last", limit: 100 });

/** 文件夹在列表上的顺序(聊天页里不再使用,但搜索 / 随机按盘符过滤要用)。 */
export let dialogCache: Map<string, Dialog> = new Map();

/** 文件夹列表:一个母文件夹 = 一行。顶部标签按盘符过滤(全部 / C: / D: …),置顶「随机浏览」。 */
export function FoldersScreen() {
  const { settings, session } = useApp();
  const nav = useNav();
  const active = usePageActive();
  const [all, setAll] = useState<Dialog[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [recent, setRecent] = useState<Record<string, string>>({});
  const [label, setLabel] = useState<string>("all");
  const gen = useRef(0);

  const load = useCallback(async () => {
    const my = ++gen.current;
    setLoading(true); setError(null);
    try {
      // 先显示上次缓存的列表,再按页取最新的(每到一页就更新)
      const cached = await peek<DialogsResp>(FIRST).catch(() => null);
      if (cached && my === gen.current) setAll((cur) => (cur.length ? cur : cached.dialogs));
      const acc: Dialog[] = [];
      let cursor: string | null | undefined = null;
      do {
        const r: DialogsResp = await api<DialogsResp>("/api/v1/dialogs" + query({ group: "all", sort: "last", limit: 100, cursor }), { cache: true });
        if (my !== gen.current) return;
        acc.push(...r.dialogs);
        setAll([...acc]);
        cursor = r.nextCursor;
      } while (cursor);
      dialogCache = new Map(acc.map((d) => [d.id, d]));
    } catch (e) {
      if (my === gen.current) setError(errorText(e));
    } finally {
      if (my === gen.current) setLoading(false);
    }
  }, []);

  useEffect(() => { load(); }, [load, session.activeId]);
  const status = useServerStatus(session.local, active, session.activeId);
  // 回到这一页(从文件夹里返回)时刷新"最近浏览"
  useEffect(() => { if (active) call<Record<string, string>>("view.recent").then(setRecent).catch(() => {}); }, [active]);

  const labels = useMemo(() => [...new Set(all.map(rootLabel))].filter(Boolean).sort(), [all]);
  useEffect(() => { if (label !== "all" && !labels.includes(label)) setLabel("all"); }, [labels, label]);

  const shown = useMemo(() => {
    let list = label === "all" ? all : all.filter((d) => rootLabel(d) === label);
    if (settings.dialogMinCount > 0) list = list.filter((d) => mediaCount(d) >= settings.dialogMinCount);
    const cmp = (a: string, b: string) => a.localeCompare(b, "zh-CN", { numeric: true, sensitivity: "base" });
    switch (settings.dialogSort) {
      case "name": return [...list].sort((a, b) => cmp(a.title, b.title));
      case "path": return [...list].sort((a, b) => cmp(a.pathDisplay, b.pathDisplay));
      case "count": return [...list].sort((a, b) => mediaCount(b) - mediaCount(a));
      default: return list;
    }
  }, [all, label, settings.dialogMinCount, settings.dialogSort]);

  const chunks = useChunks(shown, 40);
  const roots = label === "all" ? null : [...new Set(all.filter((d) => rootLabel(d) === label).map((d) => d.rootId))].join(",");

  // 列表上左右滑动切换盘符(左滑下一个、右滑上一个);明显是横向的滑动才算,不影响上下滚动
  const listRef = useRef<HTMLDivElement>(null);
  const order = useMemo(() => ["all", ...labels], [labels]);
  const orderRef = useRef(order);
  orderRef.current = order;
  const labelRef = useRef(label);
  labelRef.current = label;
  /*
   * 以前只在抬手时判断一次(横向 ≥ 60px、横向 ≥ 纵向 1.6 倍、800ms 内),滑动过程中列表不动,稍微斜一点就被当成上下滚动 → 不灵敏。
   * 现在:
   *  - 列表声明 touch-action: pan-y,浏览器只接管上下滚动,横向的手指移动完整交给这里;
   *  - 移动 10px 内判定方向:横向只要比纵向大 10%(以前要 1.6 倍);判成横向后列表跟手移动(带阻尼),判成纵向就不再管;
   *  - 抬手:横向超过 48px,或者轻快一甩(超过 20px 且速度 > 0.25 px/ms)就切换;不够就弹回原位;
   *  - 已经是第一个 / 最后一个盘符:跟手的阻尼更大,抬手弹回并轻震提示。
   */
  useEffect(() => {
    const el = listRef.current;
    if (!el) return;
    let x0 = 0, y0 = 0, axis: "" | "x" | "y" = "", track = false, dx = 0;
    let samples: { x: number; t: number }[] = [];
    const ease = "cubic-bezier(0.05, 0.7, 0.1, 1)";
    const target = (d: number) => { const o = orderRef.current, j = o.indexOf(labelRef.current) + (d < 0 ? 1 : -1); return o.length >= 2 && j >= 0 && j < o.length ? o[j] : null; };
    const settle = (from: number) => {
      el.style.transform = ""; el.style.opacity = "";
      if (from) el.animate([{ transform: `translateX(${from}px)` }, { transform: "none" }], { duration: 260, easing: ease });
    };
    const start = (e: TouchEvent) => {
      track = e.touches.length === 1;
      if (!track) return;
      const t = e.touches[0];
      x0 = t.clientX; y0 = t.clientY; axis = ""; dx = 0;
      samples = [{ x: t.clientX, t: e.timeStamp }];
    };
    const move = (e: TouchEvent) => {
      if (!track) return;
      if (e.touches.length !== 1) { track = false; settle(dx * 0.5); return; }
      const t = e.touches[0];
      dx = t.clientX - x0;
      const dy = t.clientY - y0;
      if (!axis) {
        if (Math.abs(dx) < 10 && Math.abs(dy) < 10) return;
        axis = Math.abs(dx) > Math.abs(dy) * 1.1 ? "x" : "y";
      }
      if (axis !== "x") return;
      samples.push({ x: t.clientX, t: e.timeStamp });
      if (samples.length > 6) samples.shift();
      // 跟手:有下一个盘符时阻尼 0.5,到头了阻尼更大
      const k = target(dx) ? 0.5 : 0.18;
      el.style.transform = `translateX(${dx * k}px)`;
      el.style.opacity = String(1 - Math.min(0.35, Math.abs(dx) / 900));
    };
    const end = (e: TouchEvent) => {
      if (!track) return;
      track = false;
      if (axis !== "x") return;
      const last = samples[samples.length - 1], first = samples[0];
      const v = last && first && last.t > first.t ? (last.x - first.x) / (last.t - first.t) : 0; // px/ms
      const shown = dx * (target(dx) ? 0.5 : 0.18);
      const next = target(dx);
      const go = Math.abs(dx) > 48 || (Math.abs(dx) > 20 && Math.abs(v) > 0.25 && Math.sign(v) === Math.sign(dx));
      if (!go || !next) {
        if (go && !next) haptic("Reject"); // 已经是第一个 / 最后一个
        settle(shown);
        return;
      }
      haptic("Tick");
      setLabel(next);
      // 新盘符的列表从手指离开的位置继续滑入
      el.style.transform = ""; el.style.opacity = "";
      el.animate([{ transform: `translateX(${dx < 0 ? 40 : -40}px)`, opacity: 0.35 }, { transform: "none", opacity: 1 }], { duration: 240, easing: ease });
      void e;
    };
    const cancel = () => { if (track && axis === "x") settle(dx * 0.5); track = false; };
    el.addEventListener("touchstart", start, { passive: true });
    el.addEventListener("touchmove", move, { passive: true });
    el.addEventListener("touchend", end, { passive: true });
    el.addEventListener("touchcancel", cancel, { passive: true });
    return () => {
      el.removeEventListener("touchstart", start); el.removeEventListener("touchmove", move);
      el.removeEventListener("touchend", end); el.removeEventListener("touchcancel", cancel);
    };
  }, []);

  return (
    <div className="page" style={{ paddingBottom: TAB_BAR_SPACE }}>
      <PageHeader
        large
        title="本地浏览"
        subtitle={<StatusPill local={session.local} status={status} />}
        actions={
          <>
            <Button isIconOnly variant="ghost" aria-label="刷新" isPending={loading && all.length > 0} onPress={() => { haptic("Click"); load(); }}>
              <ArrowsRotateRight className="size-5" />
            </Button>
            <Button isIconOnly variant="ghost" aria-label="搜索" onPress={() => nav.push({ name: "search" })}>
              <Magnifier className="size-5" />
            </Button>
          </>
        }
      >
        {labels.length > 1 && (
          <div className="no-scrollbar overflow-x-auto px-4 pb-2">
            <Tabs selectedKey={label} onSelectionChange={(k: Key) => { haptic("Tick"); setLabel(String(k)); }} className="w-max min-w-full">
              <Tabs.ListContainer>
                <Tabs.List aria-label="盘符">
                  {["all", ...labels].map((l) => (
                    <Tabs.Tab key={l} id={l} className="px-4 whitespace-nowrap">
                      {l === "all" ? "全部" : l}
                      <Tabs.Indicator />
                    </Tabs.Tab>
                  ))}
                </Tabs.List>
              </Tabs.ListContainer>
            </Tabs>
          </div>
        )}
      </PageHeader>

      {/* 文件夹直接排在页面上(不包在卡片里),行之间是从文字开始的细分隔线 */}
      {/* 宽屏(横屏 / 平板)居中,行分两栏 */}
      <div ref={listRef} className="mx-auto flex min-h-[60vh] w-full max-w-5xl flex-col pt-1" style={{ touchAction: "pan-y" }}>
        {shown.length === 0 ? (
          loading && !error ? <RowsSkeleton /> : <StateView error={error} empty="没有可显示的文件夹" onRetry={load} />
        ) : (
          <div>
            {/* 置顶的特殊"会话":随机浏览(全部页随机所有盘符,盘符页只随机这个盘符) */}
            <button type="button" onClick={() => nav.push({ name: "random", params: { label: label === "all" ? null : label, roots } })}
              className="flex w-full items-center gap-3 px-4 text-left transition-colors active:bg-default">
              <span className="grid size-12 shrink-0 place-items-center rounded-xl bg-gradient-to-br from-violet-500 to-accent text-white shadow-surface"><Shuffle className="size-6" /></span>
              <span className="flex min-w-0 flex-1 flex-col gap-0.5 py-3">
                <span className="truncate font-medium">{label === "all" ? "随机浏览" : `随机浏览 · ${label}`}</span>
                <span className="truncate text-sm text-muted">{label === "all" ? "随机所有盘符里的图片或视频" : `只随机 ${label} 里的图片或视频`}</span>
              </span>
            </button>
            {chunks.map((c) => {
              const part = shown.slice(c.start, c.end);
              return (
                <Chunk key={c.key} ids={part.map((d) => d.id)} className="grid md:grid-cols-2 md:gap-x-4">
                  {part.map((d) => (
                    <DialogRow key={d.id} d={d} first={false} recent={recent[d.id]} onOpen={() => nav.push({ name: "chat", params: { id: d.id, title: d.title } })} />
                  ))}
                </Chunk>
              );
            })}
          </div>
        )}
        {error && shown.length > 0 && <p className="px-6 pt-3 text-center text-xs text-danger">刷新失败:{error}</p>}
      </div>
    </div>
  );
}

type Status = "checking" | "online" | "offline";

/**
 * 服务器真实的连接状态:进入页面时、每 20 秒(页面在前台时)请求一次 /api/v1/server/info。
 * 走和其它请求一样的线路(内网直连 / 原地址、证书指纹、令牌),请求成功 = 在线,连不上 / 超时 = 离线。
 */
function useServerStatus(local: boolean, active: boolean, activeId: string): Status {
  const [status, setStatus] = useState<Status>("checking");
  useEffect(() => { setStatus("checking"); }, [activeId]);
  useEffect(() => {
    if (local || !active) return;
    let alive = true;
    // 服务器回了 HTTP 错误(比如 401)也说明连得上;只有连不上 / 超时才算离线
    const check = () => api("/api/v1/server/info")
      .then(() => alive && setStatus("online"))
      .catch((e: unknown) => alive && setStatus((e as { http?: number })?.http ? "online" : "offline"));
    check();
    const t = setInterval(check, 20000);
    return () => { alive = false; clearInterval(t); };
  }, [local, active, activeId]);
  return status;
}

/** 首页标题下的状态胶囊:在线 / 离线 / 检测中;本地媒体模式显示「本地」。 */
function StatusPill({ local, status }: { local: boolean; status: Status }) {
  const [label, tone] = local ? ["本地", "bg-default text-default-foreground"]
    : status === "online" ? ["在线", "bg-success-soft text-success"]
    : status === "offline" ? ["离线", "bg-danger-soft text-danger"]
    : ["检测中", "bg-default text-muted"];
  const dot = local ? "bg-muted" : status === "online" ? "bg-success" : status === "offline" ? "bg-danger" : "bg-muted animate-pulse";
  return (
    <span className={`mt-1 inline-flex h-6 items-center gap-1.5 rounded-full px-2.5 text-xs font-medium ${tone}`}>
      <span className={`size-1.5 rounded-full ${dot}`} />
      {label}
    </span>
  );
}

/** 一个文件夹 = 一行"会话":封面头像、名称、最近一项的时间,第二行最近浏览的文件(或路径)和数量。 */
function DialogRow({ d, recent, first, onOpen }: { d: Dialog; recent?: string; first: boolean; onOpen: () => void }) {
  const { settings } = useApp();
  const cover = settings.avatarCover ? dialogCover(d) : undefined;
  const sub = settings.dialogSubtitle === "path" ? d.pathDisplay : settings.dialogSubtitle === "none" ? null : recent ?? "暂无浏览记录";
  return (
    <button type="button" onClick={onOpen}
      className="flex w-full items-center gap-3 px-4 text-left transition-colors active:bg-default">
      <Avatar className="size-12 shrink-0 rounded-xl">
        {cover && <Avatar.Image src={cover} alt="" loading="lazy" className="object-cover" />}
        <Avatar.Fallback className={`rounded-xl font-semibold text-white ${gradientFor(d.title)}`}>{d.title.slice(0, 1)}</Avatar.Fallback>
      </Avatar>
      <span className={`flex min-w-0 flex-1 flex-col gap-0.5 py-3 ${first ? "" : "border-t border-separator"}`}>
        <span className="flex items-baseline gap-2">
          <span className="min-w-0 flex-1 truncate font-medium">{d.title}</span>
          <span className="shrink-0 text-xs text-muted tabular-nums">{formatShortTime(d.last?.takenAt)}</span>
        </span>
        <span className="flex items-center gap-2">
          <span className="min-w-0 flex-1 truncate text-sm text-muted">{sub}</span>
          <Chip size="sm" variant="soft" color={recent ? "accent" : "default"} className="shrink-0 tabular-nums">{mediaCount(d)}</Chip>
        </span>
      </span>
    </button>
  );
}
