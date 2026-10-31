import { Button, Chip, Sheet, Spinner, toast } from "@heroui/react";
import { Bookmark, TrashBin } from "@gravity-ui/icons";
import { useCallback, useEffect, useState } from "react";
import { api, call, errorText, query } from "../lib/bridge";
import { formatDateTime, typesLabel } from "../lib/media";
import { useBackHandler } from "../lib/nav";
import type { BrowseHistoryResult, DialogView, HistoryResp } from "../lib/types";

const SORT_NAME: Record<string, string> = { taken: "按时间", name: "按名称", size: "按大小", type: "按类型" };

/** 浏览历史:自动 / 手动保存的浏览位置,点一条回到那里(排序、视图、过滤一起恢复)。 */
export function HistorySheet({
  open, onOpenChange, dialogId, snapshot, onRestore,
}: {
  open: boolean;
  onOpenChange: (v: boolean) => void;
  dialogId: string;
  snapshot: () => DialogView;
  onRestore: (v: DialogView) => void;
}) {
  const [data, setData] = useState<BrowseHistoryResult | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  useBackHandler(open, () => onOpenChange(false));

  const load = useCallback(() => {
    setError(null);
    call<BrowseHistoryResult>("view.history", { dialogId }).then(setData).catch((e) => setError(errorText(e)));
  }, [dialogId]);
  useEffect(() => { if (open) load(); }, [open, load]);

  const save = async () => {
    setBusy(true);
    try { await call("view.checkpoint", { dialogId, view: snapshot() }); toast.success("已保存当前位置"); load(); }
    catch (e) { toast.danger(errorText(e)); }
    finally { setBusy(false); }
  };

  const remove = async (at?: number) => {
    try { await call("view.deleteHistory", { dialogId, at: at ?? null }); load(); }
    catch (e) { toast.danger(errorText(e)); }
  };

  const restore = async (v: DialogView) => {
    if (!v.itemId) return;
    try {
      // 先确认那一条还在(文件可能已删除、移动或不在筛选范围里)
      const r = await api<HistoryResp>(`/api/v1/dialogs/${dialogId}/history` + query({ sort: v.sort, dir: v.ascGrid ? "asc" : "desc", types: v.types.join(","), limit: 3, around: v.itemId }));
      if (!r.items.some((x) => x.id === v.itemId)) throw new Error("文件已删除、移动或不在筛选范围中,可删除这条历史");
      onOpenChange(false);
      onRestore(v);
    } catch (e) { toast.danger(errorText(e)); }
  };

  const entries = data?.entries ?? [];
  return (
    <Sheet isOpen={open} onOpenChange={onOpenChange}>
      <Sheet.Backdrop>
        <Sheet.Content className="mx-auto max-h-[85vh] max-w-lg">
          <Sheet.Dialog>
            <Sheet.Handle />
            <Sheet.Header>
              <Sheet.Heading>浏览历史</Sheet.Heading>
            </Sheet.Header>
            <Sheet.Body className="flex flex-col gap-3 overflow-y-auto pb-safe">
              <Button fullWidth variant="secondary" isPending={busy} onPress={save}><Bookmark className="size-4" />保存当前位置</Button>
              {!data && !error && <div className="grid h-24 place-items-center"><Spinner /></div>}
              {error && <p className="text-sm text-danger">{error}</p>}
              {data && entries.length === 0 && <p className="py-6 text-center text-sm text-muted">还没有浏览历史</p>}
              {entries.length > 0 && (
                <div className="overflow-hidden rounded-2xl bg-surface-secondary">
                  {entries.map((e, i) => (
                    <div key={e.savedAt} className={`flex items-center gap-2 px-3 py-2.5 ${i ? "border-t border-separator" : ""}`}>
                      <button type="button" className="min-w-0 flex-1 text-left" onClick={() => restore(e.view)}>
                        <p className="truncate text-sm">{e.view.itemName || "(未知文件)"}</p>
                        <p className="mt-0.5 flex flex-wrap items-center gap-1 text-xs text-muted">
                          <span className="tabular-nums">{formatDateTime(e.savedAt)}</span>
                          {e.view.device && <Chip size="sm" variant="soft">{e.view.device}</Chip>}
                          <span>{SORT_NAME[e.view.sort] ?? e.view.sort} · {e.view.grid ? (e.view.feed ? "瀑布流" : "网格") : "时间线"} · {typesLabel(e.view.types)}</span>
                        </p>
                      </button>
                      <Button isIconOnly size="sm" variant="ghost" aria-label="删除这条历史" onPress={() => remove(e.savedAt)}>
                        <TrashBin className="size-4 text-danger" />
                      </Button>
                    </div>
                  ))}
                </div>
              )}
              {entries.length > 0 && <Button variant="danger-soft" fullWidth onPress={() => remove()}>清除全部历史</Button>}
              {data?.config && !data.config.enabled && <p className="text-xs text-muted">服务器已关闭自动记录浏览历史,只保留手动保存的位置。</p>}
            </Sheet.Body>
          </Sheet.Dialog>
        </Sheet.Content>
      </Sheet.Backdrop>
    </Sheet>
  );
}
