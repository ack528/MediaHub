import { memo, useLayoutEffect, useRef, useState } from "react";
import { DiceIcon, FolderIcon, GearIcon, TabBar, type TabBarItem } from "../components/TabBar";
import { useApp } from "../lib/app";
import { haptic } from "../lib/bridge";
import { useBackHandler } from "../lib/nav";
import { FoldersScreen } from "./Folders";
import { RandomScreen } from "./Random";
import { SettingsHome } from "./settings/SettingsHome";

const TABS: TabBarItem[] = [
  { id: "folders", label: "文件夹", icon: FolderIcon },
  { id: "random", label: "随机", icon: DiceIcon },
  { id: "settings", label: "设置", icon: GearIcon },
];

/**
 * 切换标签的转场:新页从切换方向轻轻滑入、淡入,盖在不动的旧页上,放完再把旧页藏起来。
 * 只动一个页面(旧页静止不用重画),省一半绘制,导航的玻璃镜片也就不会因为主线程 / GPU 忙而卡。
 */
const SHIFT = 28;
const IN = { duration: 240, easing: "cubic-bezier(0.05, 0.7, 0.1, 1)" };

// 三个标签页单独 memo:切换标签时 Home 重新渲染,但页面本身不用跟着重新渲染(随机页有一大格图片)
const FoldersPage = memo(FoldersScreen);
const RandomPage = memo(function RandomPage() { return <RandomScreen label={null} roots={null} asTab />; });
const SettingsPage = memo(SettingsHome);

/**
 * 首页:三个标签共用一页,底部是悬浮的液态玻璃导航([TabBar],照 IT 之家)。
 * 每个标签的内容一直挂着(只是隐藏),切回来时滚动位置还在;非首个标签按返回键回到「文件夹」。
 */
export function Home() {
  const { settings } = useApp();
  const [tab, setTab] = useState<string>("folders");
  const [visited, setVisited] = useState<Set<string>>(() => new Set(["folders"]));
  // 正在退出的旧标签(转场期间还要显示)和转场方向
  const [leaving, setLeaving] = useState<string | null>(null);
  const dir = useRef(1);
  const pages = useRef<Record<string, HTMLDivElement | null>>({});
  const running = useRef<Animation[]>([]);
  const tabRef = useRef(tab);
  tabRef.current = tab;

  const go = (id: string) => {
    if (id === tab) return;
    // 上一次转场还没放完:直接跳到结尾(已经结束 / 取消的不能再 finish,否则会把它的终点样式重新套上去)
    running.current.forEach((a) => { if (a.playState === "running" || a.pending) a.finish(); });
    dir.current = TABS.findIndex((t) => t.id === id) > TABS.findIndex((t) => t.id === tab) ? 1 : -1;
    setVisited((v) => (v.has(id) ? v : new Set(v).add(id)));
    setLeaving(settings.motion === "off" ? null : tab);
    setTab(id);
  };
  useBackHandler(tab !== "folders", () => go("folders"));

  const select = (id: string) => {
    if (id === tab) return;
    haptic("Tick");
    go(id);
  };

  // 新标签挂上 / 显示出来之后、第一帧之前开始转场(只动 transform / opacity,在合成线程跑)
  useLayoutEffect(() => {
    if (!leaving) return;
    const out = pages.current[leaving], inn = pages.current[tab];
    if (!out || !inn) { setLeaving(null); return; }
    const d = dir.current;
    inn.style.zIndex = "1";
    const b = inn.animate([{ transform: `translateX(${d * SHIFT}px)`, opacity: 0 }, { transform: "none", opacity: 1 }], { ...IN, fill: "backwards" });
    running.current = [b];
    const done = leaving;
    b.finished.catch(() => {}).then(() => {
      inn.style.zIndex = "";
      // 快速来回切换时旧页可能已经又是当前标签了,那就不藏
      if (tabRef.current !== done) out.style.visibility = "hidden";
      setLeaving((l) => (l === done ? null : l));
    });
  }, [tab, leaving]);

  return (
    <div className="absolute inset-0">
      {TABS.map((t) => (
        <div
          key={t.id} ref={(el) => { pages.current[t.id] = el; }}
          // isolate:每页自己的层叠上下文 —— 否则旧页吸顶顶栏的 z-20 会压在正在淡入的新页上面,转场结束时才突然换掉(闪一下)
          className="absolute inset-0 isolate bg-background"
          style={{ visibility: tab === t.id || leaving === t.id ? "visible" : "hidden" }}
          inert={tab !== t.id || undefined}
        >
          {visited.has(t.id) && (t.id === "folders" ? <FoldersPage /> : t.id === "random" ? <RandomPage /> : <SettingsPage />)}
        </div>
      ))}
      <TabBar items={TABS} value={tab} onChange={select} />
    </div>
  );
}

/** 首页各标签页底部要留出的空间(悬浮标签栏 + 导航栏)。 */
export const TAB_BAR_SPACE = "calc(var(--sab) + 88px)";
