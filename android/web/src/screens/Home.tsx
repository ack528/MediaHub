import { useState } from "react";
import { DiceIcon, FolderIcon, GearIcon, TabBar, type TabBarItem } from "../components/TabBar";
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
 * 首页:三个标签共用一页,底部是悬浮的毛玻璃导航([TabBar],照 IT 之家)。
 * 每个标签的内容一直挂着(只是隐藏),切回来时滚动位置还在;非首个标签按返回键回到「文件夹」。
 */
export function Home() {
  const [tab, setTab] = useState<string>("folders");
  const [visited, setVisited] = useState<Set<string>>(() => new Set(["folders"]));
  useBackHandler(tab !== "folders", () => setTab("folders"));

  const select = (id: string) => {
    if (id === tab) return;
    haptic("Tick");
    setVisited((v) => new Set(v).add(id));
    setTab(id);
  };

  return (
    <div className="absolute inset-0">
      {TABS.map((t) => (
        <div key={t.id} className="absolute inset-0" style={{ visibility: tab === t.id ? "visible" : "hidden" }} inert={tab !== t.id || undefined}>
          {visited.has(t.id) && (t.id === "folders" ? <FoldersScreen /> : t.id === "random" ? <RandomScreen label={null} roots={null} asTab /> : <SettingsHome />)}
        </div>
      ))}
      <TabBar items={TABS} value={tab} onChange={select} />
    </div>
  );
}

/** 首页各标签页底部要留出的空间(悬浮标签栏 + 导航栏)。 */
export const TAB_BAR_SPACE = "calc(var(--sab) + 88px)";
