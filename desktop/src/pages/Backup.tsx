import { useCallback, useEffect, useState } from "react";
import { bridge } from "../bridge";
import { Badge, Btn, Card, Row, fmtBytes } from "../components";
import { useApp } from "../store";

// 数据库备份:账号、登录令牌、播放进度、浏览位置、压缩队列都在一个数据库文件里。
// 服务每天自动备份一份(保留最近 7 份);这里可以立即备份,或者选一份还原(还原要重启服务,当前的库会留底)。

interface BackupInfo { name: string; size: number; created: number }
const fmtTime = (sec: number) => new Date(sec * 1000).toLocaleString("zh-CN", { hour12: false });

export function Backup() {
  const { svc, toast, restart } = useApp();
  const [list, setList] = useState<BackupInfo[]>([]);
  const [dir, setDir] = useState("");
  const [busy, setBusy] = useState(false);
  const load = useCallback(async () => {
    try {
      const r = await bridge.admin<{ backups: BackupInfo[]; dir: string }>("GET", "/api/v1/admin/backup");
      setList(r.backups ?? []); setDir(r.dir ?? "");
    } catch { setList([]); }
  }, []);
  useEffect(() => { load(); }, [load, svc?.running]);

  const run = async () => {
    setBusy(true);
    try { const r = await bridge.admin<{ name: string }>("POST", "/api/v1/admin/backup/run", undefined, 120000); toast(`已备份:${r.name}`); }
    catch (e) { toast(String(e), "err"); }
    setBusy(false); load();
  };
  const restore = async (b: BackupInfo) => {
    if (!window.confirm(`用 ${fmtTime(b.created)} 的备份还原数据库?\n\n还原后:账号、播放进度、浏览位置、压缩队列都回到那个时间点(媒体文件本身不受影响)。\n现在的数据库会改名留底(hub.sqlite.before-restore)。\n需要重启服务才生效,确定后会自动重启。`)) return;
    setBusy(true);
    try {
      await bridge.admin("POST", "/api/v1/admin/backup/restore", { name: b.name });
      toast("已登记还原,正在重启服务…");
      await restart();
    } catch (e) { toast(String(e), "err"); }
    setBusy(false); load();
  };

  return (
    <Card icon="database" title="数据库备份" count={list.length}
          extra={<Btn kind="primary" icon="database" disabled={busy || !svc?.running} onClick={run}>立即备份</Btn>}>
      <Row icon="info" title="自动备份" desc={`服务每天自动备份一份(在线备份,不影响使用),保留最近 7 份。备份在 ${dir || "数据目录\\backup"}。`} />
      {list.length === 0 && <Row icon="alert" title={svc?.running ? "还没有备份" : "服务没有运行"} desc={svc?.running ? "服务启动 2 分钟后会自动做第一份,也可以点右上角「立即备份」。" : "启动服务后才能备份 / 还原。"} />}
      {list.map((b, i) => (
        <Row key={b.name} icon="database" title={fmtTime(b.created)} desc={`${b.name} · ${fmtBytes(b.size)}`}>
          {i === 0 && <Badge tone="green">最新</Badge>}
          <Btn disabled={busy || !svc?.running} onClick={() => restore(b)}>还原</Btn>
        </Row>
      ))}
    </Card>
  );
}
