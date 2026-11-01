import { Tabs } from "@heroui/react";
import { Folder, Gear, Shuffle } from "@gravity-ui/icons";
import { useState, type Key } from "react";
import { haptic } from "../lib/bridge";
import { useBackHandler } from "../lib/nav";
import { FoldersScreen } from "./Folders";
import { RandomScreen } from "./Random";
import { SettingsHome } from "./settings/SettingsHome";

const TABS = [
  { id: "folders", label: "文件夹", icon: Folder },
  { id: "random", label: "随机", icon: Shuffle },
  { id: "settings", label: "设置", icon: Gear },
] as const;

/**
 * 首页:三个标签共用一页,底部是悬浮的 HeroUI Tabs(主样式,滑动的填充指示块)。
 * 每个标签的内容一直挂着(只是隐藏),切回来时滚动位置还在;非首个标签按返回键回到「文件夹」。
 */
export function Home() {
  const [tab, setTab] = useState<string>("folders");
  const [visited, setVisited] = useState<Set<string>>(() => new Set(["folders"]));
  useBackHandler(tab !== "folders", () => setTab("folders"));

  const select = (k: Key) => {
    const id = String(k);
    if (id === tab) return;
    haptic("Tick");
    setVisited((v) => new Set(v).add(id));
    setTab(id);
  };

  return (
    <div className="absolute inset-0">
      {TABS.map((t) => (
        <div key={t.id} className={`absolute inset-0 ${tab === t.id && visited.size > 1 ? "tab-in" : ""}`} style={{ visibility: tab === t.id ? "visible" : "hidden" }} inert={tab !== t.id || undefined}>
          {visited.has(t.id) && (t.id === "folders" ? <FoldersScreen /> : t.id === "random" ? <RandomScreen label={null} roots={null} asTab /> : <SettingsHome />)}
        </div>
      ))}
      <nav className="pointer-events-none absolute inset-x-0 bottom-0 z-30 flex justify-center pb-safe">
        <Tabs selectedKey={tab} onSelectionChange={select} className="pointer-events-auto mb-3 w-auto">
          <Tabs.ListContainer>
            <Tabs.List aria-label="主导航" className="shadow-overlay">
              {TABS.map((t) => (
                <Tabs.Tab key={t.id} id={t.id} className="h-12 min-w-20 flex-col gap-0.5 px-4 text-[11px] font-medium data-[selected=true]:text-accent">
                  <t.icon className="size-5" />
                  {t.label}
                  <Tabs.Indicator />
                </Tabs.Tab>
              ))}
            </Tabs.List>
          </Tabs.ListContainer>
        </Tabs>
      </nav>
    </div>
  );
}

/** 首页各标签页底部要留出的空间(悬浮标签栏 + 导航栏)。 */
export const TAB_BAR_SPACE = "calc(var(--sab) + 88px)";
