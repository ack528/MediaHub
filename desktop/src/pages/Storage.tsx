import { Badge, Card, ItemRow, Progress, Row, fmtBytes } from "../components";
import { itemsOf } from "../schema";
import { useApp } from "../store";

export function Storage({ tab }: { tab: string }) {
  const { svc } = useApp();
  const items = itemsOf("storage", tab);
  const cache = svc?.status?.cache ?? [];
  return (
    <>
      <Card icon={tab === "图片" ? "image" : "database"} title={tab === "图片" ? "图片" : "缓存设置"} count={items.length}>
        {items.map((i) => <ItemRow key={i.id} item={i} />)}
      </Card>
      {tab === "缓存" && (
        <Card icon="drive" title="各盘缓存占用" count={cache.length}>
          {!svc?.running && <Row icon="clock" title="服务未运行" desc="启动后显示每个媒体盘上的缓存占用。" />}
          {svc?.running && cache.length === 0 && <Row icon="drive" title="暂无缓存" desc="打开视频文件夹后会开始生成封面。" />}
          {cache.map((c) => (
            <Row key={c.rootId} icon="drive" title={c.dir}
                 desc={<>
                   已用 {fmtBytes(c.usedBytes)} / 配额 {fmtBytes(c.quotaBytes)} · 盘剩余 {c.driveFreeGB.toFixed(1)} GB
                   {c.reason && <div className="warn-text">已改用备用目录:{c.reason}</div>}
                   <Progress value={c.usedBytes} max={c.quotaBytes} />
                 </>}>
              <Badge tone={c.mode === "on_drive" ? "green" : "orange"}>{c.mode === "on_drive" ? "盘内缓存" : "备用目录"}</Badge>
            </Row>
          ))}
        </Card>
      )}
    </>
  );
}
