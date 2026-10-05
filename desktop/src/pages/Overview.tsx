import { Badge, Btn, Card, Progress, Row, fmtNum, fmtUptime } from "../components";
import { Icon } from "../icons";
import { useApp } from "../store";

const fmtRate = (r?: number) => (r && r > 0 ? (r >= 100 ? Math.round(r).toLocaleString("zh-CN") : r.toFixed(1)) : "0");
const fmtEta = (sec: number) => (sec >= 3600 ? `${Math.floor(sec / 3600)} 小时 ${Math.floor((sec % 3600) / 60)} 分钟` : sec >= 60 ? `${Math.ceil(sec / 60)} 分钟` : `${sec} 秒`);

const KIND_LABEL: Record<string, string> = { denied: "没有权限", gone: "已不存在", name: "名称不合法", offline: "盘不可用", io: "读取出错", other: "其他" };

function portOf(listen: string) { return Number(listen.slice(listen.lastIndexOf(":") + 1)) || 8480; }

export function CopyBtn({ text }: { text: string }) {
  const { toast } = useApp();
  return (
    <button type="button" className="mini icon" title="复制" onClick={async () => { await navigator.clipboard.writeText(text); toast("已复制"); }}>
      <Icon name="copy" size={15} />
    </button>
  );
}

export function Overview() {
  const { svc, busy, start, stop, restart, rescan, ips, config, env } = useApp();
  const running = !!svc?.running;
  const st = svc?.status ?? null;
  const port = portOf(config.listen);
  const loopbackOnly = config.listen.startsWith("127.0.0.1");

  return (
    <>
      <Card icon="server" title="服务状态">
        <Row icon="zap" title={
          <span className="status-line"><i className={"dot " + (running ? "ok" : "off")} />{running ? "运行中" : "已停止"}</span>
        } desc={running
          ? `版本 ${svc?.info?.version ?? "?"} · 已运行 ${fmtUptime(st?.uptimeSec ?? 0)}${svc?.pids?.length ? ` · 进程 ${svc.pids.join(", ")}` : ""}`
          : "手机暂时无法访问。点击\"启动\"开始提供服务。"}>
          <div className="btn-group">
            {!running && <Btn kind="primary" icon="play" disabled={!!busy || !env?.serverExists} onClick={start}>{busy === "start" ? "启动中…" : "启动"}</Btn>}
            {running && <Btn icon="refresh" disabled={!!busy} onClick={restart}>{busy === "restart" ? "重启中…" : "重启"}</Btn>}
            {running && <Btn kind="danger" icon="stop" disabled={!!busy} onClick={stop}>{busy === "stop" ? "停止中…" : "停止"}</Btn>}
          </div>
        </Row>
        {env && !env.serverExists && (
          <Row icon="alert" title="找不到服务程序" desc={<>请先编译服务端:<code>{env.serverExe}</code></>}><Badge tone="red">缺失</Badge></Row>
        )}
        <Row icon="database" title="已索引" desc={running && st ? "索引在后台持续进行,新增文件会自动出现在手机上。" : "服务运行后显示。"}>
          <span className="stat">{running && st ? <><b>{fmtNum(st.media)}</b> 个媒体 · <b>{fmtNum(st.dialogs)}</b> 个文件夹</> : "—"}</span>
        </Row>
      </Card>

      <Card icon="link" title="手机连接地址">
        {loopbackOnly && <Row icon="alert" title="当前只监听本机" desc={'手机无法连接。到“网络”页把监听范围改成“局域网”。'}><Badge tone="orange">仅本机</Badge></Row>}
        {ips.length === 0 && <Row icon="globe" title="没有检测到局域网地址" desc="请确认电脑已连接到路由器。" />}
        {ips.map((i) => (
          <Row key={i.ip} icon="globe" title={`${i.ip}:${port}`} desc={`${i.name}${i.private ? "" : "(非私有地址)"} · 在手机应用的\"服务器地址\"里输入`}>
            <CopyBtn text={`${i.ip}:${port}`} />
          </Row>
        ))}
      </Card>

      <Card icon="folder" title="索引进度" extra={<Btn icon="refresh" disabled={!running || !!busy} onClick={rescan}>重新扫描</Btn>}>
        {!running && <Row icon="clock" title="服务未运行" desc="启动后显示每个媒体盘的扫描与元数据进度。" />}
        {running && st && st.index.length === 0 && <Row icon="folder" title="还没有配置媒体库" desc={'到“媒体库”页添加根目录。'} />}
        {running && st?.index.map((p) => {
          const busyState = p.state !== "idle";
          const scanning = p.state === "scanning";
          const enriching = p.state === "enriching";
          const total = p.enrichTotal && p.enrichTotal > 0 ? p.enrichTotal : Math.max(p.files, 1);
          return (
            <Row key={p.rootId} icon="drive" title={p.label}
                 desc={<>
                   {fmtNum(p.files)} 个文件 · {fmtNum(p.dirs)} 个文件夹
                   {scanning && <> · <b className="speed">{fmtRate(p.scanRate)} 文件/秒</b></>}
                   {enriching && <> · 已读元数据 {fmtNum(p.enriched)} / {fmtNum(total)} · <b className="speed">{fmtRate(p.enrichRate)} 个/秒</b>{p.etaSec ? <> · 预计还需 {fmtEta(p.etaSec)}</> : null}</>}
                   {scanning && p.resumed && <> · 接着上次中断的扫描继续</>}
                   {(p.skipped ?? 0) > 0 && <> · {p.skipped} 个目录已跳过</>}
                   {p.errors > 0 && <> · <span className="warn-text">{p.errors} 个目录读取失败</span></>}
                   {enriching && <Progress value={p.enriched} max={total} />}
                   {(p.failedDirs?.length ?? 0) > 0 && (
                     <details className="faildirs">
                       <summary>查看跳过 / 失败的目录({p.failedDirs!.length}{p.failedDirs!.length >= 50 ? "+" : ""})</summary>
                       <ul>
                         {p.failedDirs!.map((f) => (
                           <li key={f.path}>
                             <Badge tone={f.kind === "denied" || f.kind === "gone" || f.kind === "name" ? "gray" : "orange"}>{KIND_LABEL[f.kind] ?? "其他"}</Badge>
                             <code>{f.path}</code>
                             <span className="why">{f.reason}{f.code ? `(错误码 ${f.code})` : ""}</span>
                           </li>
                         ))}
                       </ul>
                       <div className="hint">“没有权限”“已不存在”是正常现象(系统保护目录、被删除的文件夹),已自动跳过;“名称不合法”是文件夹名以空格 / 点结尾或含 Windows 不允许的字符(多半是 Linux / Mac 创建的),已尝试扩展路径读取仍失败,改个名字即可;“读取出错”“盘不可用”需要留意:可能是硬盘坏道、移动硬盘断开,或网络盘掉线。</div>
                     </details>
                   )}
                 </>}>
              <Badge tone={busyState ? "blue" : "green"}>{scanning ? "扫描中" : enriching ? "读取元数据" : p.state === "error" ? "出错" : "已完成"}</Badge>
            </Row>
          );
        })}
      </Card>

      {st && st.warnings.length > 0 && (
        <Card icon="alert" title="需要注意">
          {st.warnings.map((w) => <Row key={w} icon="alert" title={w} />)}
        </Card>
      )}
    </>
  );
}
