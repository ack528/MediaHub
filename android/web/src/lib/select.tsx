import { createContext, useContext } from "react";

/** 文件夹页的多选(删除):on = 正在选择,ids = 已选的条目。三种视图的每一格都读它画选中标记。 */
export interface Selection { on: boolean; ids: ReadonlySet<string> }
export const SelectionContext = createContext<Selection>({ on: false, ids: new Set() });

/** 选择模式下叠在缩略图上的圆形勾选标记(不在选择模式时不画)。 */
export function SelectMark({ id }: { id: string }) {
  const { on, ids } = useContext(SelectionContext);
  if (!on) return null;
  const picked = ids.has(id);
  return (
    <span className={`pointer-events-none absolute inset-0 ${picked ? "bg-accent/25 ring-2 ring-inset ring-accent" : "bg-black/10"}`}>
      <span className={`absolute top-1.5 left-1.5 grid size-5 place-items-center rounded-full border-2 text-[11px] leading-none font-bold ${picked ? "border-accent bg-accent text-white" : "border-white bg-black/30 text-transparent"}`}>✓</span>
    </span>
  );
}
