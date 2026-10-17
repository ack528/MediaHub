import { useCallback, useEffect, useState } from "react";
import { bridge } from "../bridge";
import { Badge, Btn, Card, ItemRow, Row } from "../components";
import { itemsOf } from "../schema";
import { useApp } from "../store";
import { CopyBtn } from "./Overview";

// 内网穿透:配置(frp / 自定义命令)、运行状态、链路探测、针对当前配置的建议。
// 服务端托管穿透客户端进程(掉线自动重连),这里只是配置和观察的界面。

interface Probe { at: number; ok: boolean; connectMs: number; ttfbMs: number; err?: string }
interface TunnelStatus {
  enabled: boolean; type: string; state: string; message?: string; exe?: string; pid?: number; startedAt?: number;
  restarts: number; publicAddr?: string; probes: Probe[]; logs?: string[];
}
const TSTATE: Record<string, [string, "gray" | "green" | "blue" | "orange" | "red"]> = {
  disabled: ["未启用", "gray"], missing: ["找不到程序", "red"], stopped: ["已停止", "gray"], starting: ["启动中", "blue"],
  connecting: ["连接中", "orange"], running: ["运行中", "green"], backoff: ["重连中", "orange"], error: ["出错", "red"],
};

export function Tunnel() {
  const { config, svc, toast } = useApp();
  const t = config.tunnel;
  const [st, setSt] = useState<TunnelStatus | null>(null);
  const [busy, setBusy] = useState(false);
  const load = useCallback(async () => {
    try { setSt(await bridge.admin<TunnelStatus>("GET", "/api/v1/admin/tunnel?logs=1")); } catch { setSt(null); }
  }, []);
  useEffect(() => { load(); const id = setInterval(load, 2500); return () => clearInterval(id); }, [load]);
  const act = async (a: "start" | "stop" | "restart" | "probe") => {
    setBusy(true);
    try {
      const r = await bridge.admin<any>("POST", `/api/v1/admin/tunnel/${a}`, undefined, 30000);
      if (a === "probe") toast(r?.ok ? `链路正常:连接 ${r.connectMs} ms,首字节 ${r.ttfbMs} ms` : `探测失败:${r?.err ?? "未知原因"}`, r?.ok ? "ok" : "err");
      else toast(a === "start" ? "已启动" : a === "stop" ? "已停止" : "正在重启穿透进程");
    } catch (e) { toast(String(e), "err"); }
    setBusy(false); load();
  };
  const probes = st?.probes ?? [];
  const okP = probes.filter((p) => p.ok);
  const avgTtfb = okP.length ? Math.round(okP.reduce((s, p) => s + p.ttfbMs, 0) / okP.length) : 0;
  const lossPct = probes.length ? Math.round(((probes.length - okP.length) * 100) / probes.length) : 0;
  const [label, tone] = TSTATE[st?.state ?? "disabled"] ?? [st?.state ?? "", "gray" as const];
  const uptime = st?.startedAt ? Math.max(0, Math.floor(Date.now() / 1000 - st.startedAt)) : 0;
  const frp = t.type === "frp";
  const all = itemsOf("network", "内网穿透");

  // 针对当前配置和探测结果的建议
  const tips: { tone: "green" | "orange" | "red"; text: string }[] = [];
  if (t.enabled) {
    if (!t.publicAddr) tips.push({ tone: "orange", text: "还没填「外网地址」:填了才能探测链路延迟,手机也能同时记下内网和外网两个地址(在家自动走内网直连)。" });
    if (frp && t.protocol === "tcp" && probes.length >= 3 && (avgTtfb > 800 || lossPct >= 20)) tips.push({ tone: "orange", text: `最近探测首字节平均 ${avgTtfb} ms、失败 ${lossPct}%:链路延迟高 / 丢包多。如果穿透服务器支持,把传输协议改成 quic(或 kcp)通常会明显更流畅。` });
    if (frp && t.poolCount < 3) tips.push({ tone: "orange", text: "连接池太小:滑动网格时大量缩略图请求每个都要多等一个来回。建议 5 ~ 20。" });
    if (frp && (t.encryption || t.compression)) tips.push({ tone: "orange", text: "隧道内的加密 / 压缩建议关闭:MediaHub 已经是 HTTPS,图片视频也已经压缩,开着只耗 CPU(高码率视频时可能成为瓶颈)。" });
    if (frp && t.proxyType === "http" && config.tls.enabled) tips.push({ tone: "red", text: "代理类型 http 只能转发明文 HTTP,而 MediaHub 的加密传输是开着的 —— 手机会连不上。请改成 tcp 或 https,或者关闭加密传输(不推荐)。" });
    if (frp && t.proxyType === "tcp" && t.remotePort === 0) tips.push({ tone: "orange", text: "远程端口是 0:需要由穿透服务器分配 / 服务商指定,手机里填的端口要和服务商给的一致。" });
    if (st?.state === "missing") tips.push({ tone: "red", text: "找不到穿透客户端程序。服务端自带 frpc,通常是被杀毒软件(Windows Defender 常把 frpc 当成风险工具)拦截或删除了:把服务的数据目录下的 tunnel\\bin 文件夹加入排除项后重启服务即可;也可以自己放一个 frpc.exe 并在下面填路径。" });
    if (probes.length >= 3 && lossPct === 0 && avgTtfb > 0 && avgTtfb <= 400) tips.push({ tone: "green", text: `链路状态良好(首字节平均 ${avgTtfb} ms,无失败)。画面不流畅多半是穿透服务器的带宽不够:在手机「设置 → 网络测速」测一下稳定下载速度,视频码率要低于它的 70%。` });
  }
  return (
    <>
      <Card icon="link" title="内网穿透" count={all.filter((i) => i.id.startsWith("tun-")).length}>
        {all.filter((i) => i.id.startsWith("tun-")).map((i) => <ItemRow key={i.id} item={i} />)}
      </Card>

      <Card icon="gauge" title="运行状态"
            extra={<div className="btn-group">
              <Btn icon="play" disabled={busy || !svc?.running || !st?.enabled} onClick={() => act("start")}>启动</Btn>
              <Btn icon="stop" disabled={busy || !svc?.running || !st?.enabled} onClick={() => act("stop")}>停止</Btn>
              <Btn icon="refresh" disabled={busy || !svc?.running || !st?.enabled} onClick={() => act("restart")}>重启</Btn>
              <Btn kind="primary" icon="gauge" disabled={busy || !svc?.running || !st?.enabled} onClick={() => act("probe")}>立即探测</Btn>
            </div>}>
        {!svc?.running && <Row icon="alert" title="服务没有运行" desc="启动服务后才能看到穿透状态。" />}
        {svc?.running && !st?.enabled && <Row icon="info" title="服务端没有启用内网穿透" desc="在上面打开「启用内网穿透」,填好配置后保存并重启服务。" />}
        {st?.enabled && (
          <>
            <Row icon="zap" title="状态" desc={st.message || "—"}><Badge tone={tone}>{label}</Badge></Row>
            <Row icon="cpu" title="穿透进程" desc={<>{st.exe || "—"}{st.pid ? <> · pid {st.pid}</> : null}{uptime ? <> · 已运行 {Math.floor(uptime / 60)} 分钟</> : null} · 累计重启 {st.restarts} 次</>} />
            <Row icon="globe" title="外网地址" desc={st.publicAddr ? <>手机里填这个地址:<code>{st.publicAddr}</code></> : "没有填写"}>
              {st.publicAddr ? <CopyBtn text={st.publicAddr} /> : null}
            </Row>
            <Row icon="clock" title="链路探测" desc={probes.length ? `最近 ${probes.length} 次:成功 ${okP.length} · 失败 ${probes.length - okP.length} · 首字节平均 ${avgTtfb} ms` : "还没有探测记录(每 30 秒一次,或点「立即探测」)"}>
              {probes.length > 0 && <Badge tone={lossPct === 0 ? "green" : lossPct < 30 ? "orange" : "red"}>{probes[probes.length - 1].ok ? `${probes[probes.length - 1].connectMs} / ${probes[probes.length - 1].ttfbMs} ms` : "失败"}</Badge>}
            </Row>
            {probes.length > 0 && (
              <Row icon="info" title="最近的探测(连接 / 首字节,毫秒)" block desc={<div style={{ display: "flex", flexWrap: "wrap", gap: 6, marginTop: 6 }}>
                {probes.slice(-20).map((p) => (
                  <span key={p.at} title={p.err ?? ""} style={{ padding: "1px 8px", borderRadius: 6, background: p.ok ? "#f6ffed" : "#fff1f0", border: "1px solid " + (p.ok ? "#b7eb8f" : "#ffa39e"), fontSize: 12 }}>
                    {p.ok ? `${p.connectMs}/${p.ttfbMs}` : "×"}
                  </span>
                ))}
              </div>} />
            )}
          </>
        )}
      </Card>

      {tips.length > 0 && (
        <Card icon="zap" title="针对当前配置的建议" count={tips.length}>
          {tips.map((x, i) => <Row key={i} icon={x.tone === "green" ? "check" : "alert"} title={x.text}><Badge tone={x.tone}>{x.tone === "green" ? "正常" : x.tone === "red" ? "需要处理" : "建议"}</Badge></Row>)}
        </Card>
      )}

      {frp ? (
        <Card icon="server" title="frp 配置" count={all.filter((i) => i.id.startsWith("frp-")).length}>
          {all.filter((i) => i.id.startsWith("frp-")).map((i) => <ItemRow key={i.id} item={i} />)}
        </Card>
      ) : (
        <Card icon="wrench" title="自定义命令" count={all.filter((i) => i.id.startsWith("tc-")).length}>
          {all.filter((i) => i.id.startsWith("tc-")).map((i) => <ItemRow key={i.id} item={i} />)}
        </Card>
      )}

      <Card icon="filetext" title="日志(最近 100 行)" extra={<Btn icon="refresh" onClick={load}>刷新</Btn>}>
        <div style={{ padding: "10px 20px" }}>
          {st?.logs && st.logs.length > 0
            ? <pre className="code" style={{ maxHeight: 280, overflow: "auto", margin: 0, whiteSpace: "pre-wrap" }}>{st.logs.slice(-100).join("\n")}</pre>
            : <div className="log-empty">还没有日志。穿透进程的输出会显示在这里(令牌已自动隐藏)。</div>}
        </div>
      </Card>

      <Card icon="info" title="怎么让经过穿透的访问更流畅">
        <Row icon="drive" title="在家用内网直连" desc="手机和服务器在同一个局域网时,不要走穿透(多绕一大圈,还占穿透服务器的带宽)。手机端 3.1.0 起会自动在内网地址和外网地址之间切换:在家走内网直连,出门自动切回外网。" />
        <Row icon="gauge" title="先弄清瓶颈在哪" desc="手机「设置 → 网络测速」测下载稳定速度和延迟。画面卡顿多半是穿透服务器的带宽小于视频码率:带宽的 70% 应该大于视频码率。带宽不够就在播放器里选低一点的转码档位(0.5M ~ 6M),或者打开手机「设置 → 视频播放 → 自动画质」。" />
        <Row icon="zap" title="协议和连接池" desc="丢包多 / 延迟高的链路用 quic 或 kcp;连接池 5 ~ 20 省掉每个新请求的建连来回;以视频为主且丢包多时可以关掉连接多路复用,让更多条 TCP 并行。改完在「运行状态」里看探测的首字节耗时有没有降下来。" />
        <Row icon="globe" title="有公网 IPv6 就直连" desc="很多宽带有公网 IPv6:给这台电脑配个 DDNS(AAAA 记录)并在路由器防火墙放行端口,手机在有 IPv6 的网络下可以直连,完全不经过穿透服务器。服务端已经会把本机的 IPv6 地址告诉登录过的手机。" />
        <Row icon="film" title="少传字节" desc="3.0.0 起已经内置:转码码率只有 6M 及以下、H.265 转码(同画质省 30% ~ 50%)、小图用 WebP 缩略图(几十 KB)、JSON 压缩。穿透带宽小的时候,这些比调穿透参数管用得多。" />
      </Card>
    </>
  );
}
