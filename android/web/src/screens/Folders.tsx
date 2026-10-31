import { Avatar, Button, Card, Tabs } from "@heroui/react";
import { ArrowsRotateRight, Magnifier, Shuffle } from "@gravity-ui/icons";
import { useCallback, useEffect, useMemo, useRef, useState, type Key } from "react";
import { api, call, errorText, haptic, peek, query } from "../lib/bridge";
import { useApp } from "../lib/app";
import { dialogCover, formatShortTime, mediaCount, rootLabel } from "../lib/media";
import { useNav, usePageActive } from "../lib/nav";
import type { Dialog, DialogsResp } from "../lib/types";
import { PageHeader } from "../components/PageHeader";
import { StateView } from "../components/List";
import { TAB_BAR_SPACE } from "./Home";

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

  const roots = label === "all" ? null : [...new Set(all.filter((d) => rootLabel(d) === label).map((d) => d.rootId))].join(",");

  return (
    <div className="page" style={{ paddingBottom: TAB_BAR_SPACE }}>
      <PageHeader
        large
        title="本地浏览"
        subtitle={session.local ? "本地媒体" : session.servers.find((s) => s.id === session.activeId)?.name}
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

      <div className="flex flex-col gap-4 px-4 pt-2">
        <Card className="flex-row items-center gap-3 rounded-3xl">
          <span className="grid size-10 shrink-0 place-items-center rounded-full bg-accent-soft text-accent"><Shuffle className="size-5" /></span>
          <span className="min-w-0 flex-1">
            <span className="block text-sm font-medium">{label === "all" ? "随机浏览" : `随机浏览 · ${label}`}</span>
            <span className="block truncate text-xs text-muted">{label === "all" ? "从所有盘符里随机取图片和视频" : `只随机 ${label} 里的图片和视频`}</span>
          </span>
          <Button size="sm" variant="secondary" onPress={() => nav.push({ name: "random", params: { label: label === "all" ? null : label, roots } })}>开始</Button>
        </Card>

        {shown.length === 0 ? (
          <StateView loading={loading} error={error} empty="没有可显示的文件夹" onRetry={load} />
        ) : (
          <div className="grid grid-cols-2 gap-3">
            {shown.map((d) => (
              <FolderCard key={d.id} d={d} recent={recent[d.id]} onOpen={() => nav.push({ name: "chat", params: { id: d.id, title: d.title } })} />
            ))}
          </div>
        )}
        {error && shown.length > 0 && <p className="px-2 text-center text-xs text-danger">刷新失败:{error}</p>}
      </div>
    </div>
  );
}

/** 文件夹卡片:封面、名称、数量,底部一行是最近浏览的文件(或路径)。 */
function FolderCard({ d, recent, onOpen }: { d: Dialog; recent?: string; onOpen: () => void }) {
  const { settings } = useApp();
  const cover = settings.avatarCover ? dialogCover(d) : undefined;
  const sub = settings.dialogSubtitle === "path" ? d.pathDisplay : settings.dialogSubtitle === "none" ? null : recent ?? "暂无浏览记录";
  return (
    <button type="button" onClick={onOpen}
      className="tile flex min-w-0 flex-col items-start gap-3 rounded-3xl bg-surface p-4 text-left shadow-surface transition-transform active:scale-[0.98]"
      style={{ containIntrinsicSize: "auto 170px" }}>
      <Avatar className="size-14 rounded-2xl">
        {cover && <Avatar.Image src={cover} alt="" loading="lazy" className="object-cover" />}
        <Avatar.Fallback className="rounded-2xl text-lg">{d.title.slice(0, 1)}</Avatar.Fallback>
      </Avatar>
      <span className="w-full min-w-0">
        <span className="block truncate font-medium">{d.title}</span>
        <span className="block text-sm text-muted tabular-nums">{mediaCount(d)} 项{d.last?.takenAt ? ` · ${formatShortTime(d.last.takenAt)}` : ""}</span>
      </span>
      {sub && (
        <span className="flex w-full min-w-0 items-center gap-1.5 text-xs text-muted">
          <span className="size-3.5 shrink-0 rounded-full bg-gradient-to-br from-accent to-accent-soft" />
          <span className="truncate">{sub}</span>
        </span>
      )}
    </button>
  );
}
