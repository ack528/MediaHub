import { bridge } from "../bridge";
import { Btn, Card, ItemRow, Row, TextField } from "../components";
import { itemsOf } from "../schema";
import { useApp } from "../store";

function defaultLabel(path: string) {
  const m = /^([A-Za-z]:)[\\/]?$/.exec(path.trim());
  if (m) return m[1].toUpperCase();
  return path.replace(/[\\/]+$/, "").split(/[\\/]/).pop() || path;
}

export function Library({ tab }: { tab: string }) {
  const { config, update, toast } = useApp();

  if (tab === "根目录") {
    const add = async () => {
      const p = await bridge.pickFolder();
      if (!p) return;
      if (config.roots.some((r) => r.path.toLowerCase() === p.toLowerCase())) { toast("这个文件夹已经添加过了", "err"); return; }
      update((c) => ({ ...c, roots: [...c.roots, { path: p, label: defaultLabel(p) }] }));
    };
    return (
      <Card icon="folder" title="媒体库根目录" count={config.roots.length}
            extra={<Btn kind="primary" icon="plus" onClick={add}>添加文件夹</Btn>}>
        {config.roots.length === 0 && (
          <Row icon="folder" title="还没有添加媒体库" desc="直接选择存放图片和视频的盘符(例如 D:\)。盘符会成为手机上的一个分组标签,盘符下的每个母文件夹是一个群(群名就是文件夹名),母文件夹里无论嵌套多少层子文件夹,里面的媒体都会平铺进这个群。" />
        )}
        {config.roots.map((r, i) => (
          <Row key={r.path + i} icon="drive" title={r.path} desc="根目录(对服务端始终只读,只会在其下的 .mediahub 文件夹里写缓存)">
            <TextField value={r.label} width={140} placeholder="标签"
                       onChange={(v) => update((c) => ({ ...c, roots: c.roots.map((x, j) => (j === i ? { ...x, label: v } : x)) }))} />
            <Btn kind="danger" icon="trash" onClick={() => update((c) => ({ ...c, roots: c.roots.filter((_, j) => j !== i) }))}>移除</Btn>
          </Row>
        ))}
      </Card>
    );
  }

  const items = itemsOf("library", tab);
  return (
    <Card icon="tag" title="扫描与过滤" count={items.length}>
      {items.map((i) => <ItemRow key={i.id} item={i} />)}
    </Card>
  );
}
