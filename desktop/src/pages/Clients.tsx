import { useCallback, useEffect, useState } from "react";
import { bridge } from "../bridge";
import { Badge, Btn, Card, ItemRow, Row, fmtBytes } from "../components";
import { itemsOf } from "../schema";
import { useApp } from "../store";

// 客户端连接:谁连着、在做什么、速度多少,以及服务端的调度级别(有客户端时后台任务让路)。
// 每 2 秒轮询 GET /api/v1/admin/clients;设备名 / App 版本来自登录时的设备名和 User-Agent。

interface ClientView {
  id: string; user: string; device: string; ua: string; ip: string;
  route: "lan" | "tunnel" | "wan" | string;
  state: "playing" | "transfer" | "browsing" | "idle" | "offline" | string;
  online: boolean; firstSeen: number; lastSeen: number;
  requests: number; errors: number; bytesOut: number; bytesIn: number; rateBps: number; activeStreams: number;
  nowKind?: string; nowName?: string; nowDetail?: string; nowMediaId?: number;
}
interface BgView { name: string; state: string; note?: string }
interface LoginLock { remainingSec: number; failures: number; lockSec: number; recent: { at: number; ip: string; user: string; device: string }[] }
interface ClientsResp {
  clients: ClientView[]; online: number; rateBps: number; bytesOut: number;
  governor: { enabled: boolean; level: "idle" | "active" | "streaming" | string; activeWindowSec: number; idleForSec: number; streams: number; throttledMs: number; yields: number; pauseCompress: boolean };
  background: BgView[];
  loginLock?: LoginLock;
}

type Tone = "gray" | "green" | "blue" | "orange" | "red";
const ROUTE: Record<string, [string, Tone]> = { lan: ["内网直连", "green"], tunnel: ["内网穿透", "blue"], wan: ["公网", "orange"] };
const STATE: Record<string, [string, Tone]> = {
  playing: ["播放中", "green"], transfer: ["传输中", "green"], browsing: ["浏览中", "blue"], idle: ["空闲", "gray"], offline: ["离线", "gray"],
};
const LEVEL: Record<string, [string, Tone, string]> = {
  idle: ["空闲", "green", "没有客户端连接:后台任务(压缩、重复检测、扫描、封面预生成)全速运行"],
  active: ["有客户端", "blue", "有客户端在浏览:后台任务降速让路,视频压缩已暂停"],
  streaming: ["正在传输", "orange", "有客户端在播放 / 下载:后台任务只用很少的资源,视频压缩已暂停"],
};
const BG: Record<string, [string, Tone]> = {
  running: ["运行中", "green"], paused: ["已暂停", "orange"], throttled: ["已降速", "blue"], idle: ["空闲", "gray"], queued: ["排队", "gray"],
};

const fmtRate = (bps: number) => (bps >= 1_000_000 ? (bps / 1_000_000).toFixed(1) + " MB/s" : bps >= 1000 ? Math.round(bps / 1000) + " KB/s" : bps > 0 ? bps + " B/s" : "—");
const fmtAgo = (sec: number) => {
  if (!sec) return "—";
  const d = Math.max(0, Math.floor(Date.now() / 1000 - sec));
  if (d < 5) return "刚刚";
  if (d < 60) return d + " 秒前";
  if (d < 3600) return Math.floor(d / 60) + " 分钟前";
  if (d < 86400) return Math.floor(d / 3600) + " 小时前";
  return Math.floor(d / 86400) + " 天前";
};
const fmtTime = (sec: number) => (sec ? new Date(sec * 1000).toLocaleString("zh-CN", { hour12: false }) : "—");
// LocalBrowse/3.1.0 (Android 17; OPPO OPD2515) → "LocalBrowse 3.1.0 · Android 17"
const shortUA = (ua: string) => {
  const m = /^LocalBrowse\/([\w.\-]+)(?: \(([^)]*)\))?/.exec(ua);
  if (!m) return ua || "—";
  const os = m[2]?.split(";")[0]?.trim();
  return `手机端 ${m[1]}${os ? " · " + os : ""}`;
};

export function Clients({ tab }: { tab: string }) {
  const { svc, toast } = useApp();
  const [r, setR] = useState<ClientsResp | null>(null);
  const [err, setErr] = useState("");
  const load = useCallback(async () => {
    try { setR(await bridge.admin<ClientsResp>("GET", "/api/v1/admin/clients")); setErr(""); } catch (e) { setErr(String(e)); setR(null); }
  }, []);
  useEffect(() => {
    if (tab !== "连接状态") return;
    load();
    const id = setInterval(load, 2000);
    return () => clearInterval(id);
  }, [load, tab]);

  if (tab === "调度设置") {
    return (
      <Card icon="zap" title="客户端优先调度" count={itemsOf("clients", "调度设置").length}>
        {itemsOf("clients", "调度设置").map((i) => <ItemRow key={i.id} item={i} />)}
      </Card>
    );
  }

  const kick = async (c: ClientView) => {
    if (!window.confirm(`让「${c.device || "未命名设备"}」(账号 ${c.user})退出登录?\n\n它的登录令牌会立即失效,下次请求会回到登录界面;其它设备不受影响。`)) return;
    try {
      const k = await bridge.admin<{ revoked: number }>("POST", "/api/v1/admin/clients/kick", { id: c.id });
      toast(k?.revoked ? "已让该设备退出登录" : "该设备已经不在登录列表里");
    } catch (e) { toast(String(e), "err"); }
    load();
  };

  if (!svc?.running) return <Card icon="link" title="客户端连接"><Row icon="alert" title="服务没有运行" desc="启动服务后才能看到连接的客户端。" /></Card>;
  if (!r) return <Card icon="link" title="客户端连接"><Row icon="alert" title={err ? "读取失败" : "正在读取…"} desc={err || undefined} /></Card>;

  const g = r.governor;
  const [lvLabel, lvTone, lvDesc] = LEVEL[g.level] ?? [g.level, "gray" as Tone, ""];
  const clients = [...r.clients].sort((a, b) => Number(b.online) - Number(a.online) || b.rateBps - a.rateBps || b.lastSeen - a.lastSeen);

  return (
    <>
      <Card icon="gauge" title="概况">
        <Row icon="link" title="在线客户端" desc={`${r.clients.length} 台登录过的设备,其中 ${r.online} 台最近 ${g.activeWindowSec} 秒内有请求`}>
          <Badge tone={r.online > 0 ? "green" : "gray"}>{r.online} 台在线</Badge>
        </Row>
        <Row icon="zap" title="当前总速度" desc={`服务启动以来共发送 ${fmtBytes(r.bytesOut)};正在进行的流 ${g.streams} 条`}>
          <Badge tone={r.rateBps > 0 ? "green" : "gray"}>{fmtRate(r.rateBps)}</Badge>
        </Row>
        <Row icon="cpu" title="后台任务调度" desc={g.enabled ? lvDesc : "「有客户端连接时让路」已关闭:后台任务一直全速运行(在「调度设置」里打开)"}>
          <Badge tone={g.enabled ? lvTone : "gray"}>{g.enabled ? lvLabel : "未启用"}</Badge>
        </Row>
        {g.enabled && (
          <Row icon="clock" title="让路统计" desc={`距离上次客户端请求 ${g.idleForSec} 秒${g.level === "idle" ? "" : `;${g.activeWindowSec} 秒内没有新请求就会恢复全速`} · 后台任务累计让出 ${(g.throttledMs / 1000).toFixed(0)} 秒、${g.yields} 次`} />
        )}
      </Card>

      {r.loginLock && (
        <Card icon="shield" title="登录保护"
              extra={r.loginLock.remainingSec > 0 ? <Btn icon="refresh" onClick={async () => { try { await bridge.admin("POST", "/api/v1/admin/login-lock/clear"); toast("已解除锁定"); } catch (e) { toast(String(e), "err"); } load(); }}>解除锁定</Btn> : undefined}>
          <Row icon="key" title="全局锁定" desc={r.loginLock.lockSec > 0
            ? `任何设备登录失败后,所有设备 ${r.loginLock.lockSec} 秒内都不能再验证密码(已登录的设备不受影响)。累计失败 ${r.loginLock.failures} 次。`
            : "没有开启(账号安全 → 登录里设置锁定秒数)。"}>
            <Badge tone={r.loginLock.remainingSec > 0 ? "red" : "green"}>{r.loginLock.remainingSec > 0 ? `锁定中 · 还剩 ${r.loginLock.remainingSec} 秒` : "正常"}</Badge>
          </Row>
          {(r.loginLock.recent ?? []).slice().reverse().slice(0, 8).map((f) => (
            <Row key={f.at + f.ip + f.user} icon="alert" title={`${fmtTime(f.at)} 登录失败`} desc={`账号「${f.user || "—"}」· 设备「${f.device || "—"}」· 来源 ${f.ip}`} />
          ))}
        </Card>
      )}

      <Card icon="link" title="客户端" count={clients.length}>
        {clients.length === 0 && <Row icon="info" title="还没有设备登录过" desc="手机登录后会出现在这里。" />}
        {clients.map((c) => {
          const [rl, rt] = ROUTE[c.route] ?? [c.route || "—", "gray" as Tone];
          const [sl, st] = STATE[c.state] ?? [c.state, "gray" as Tone];
          return (
            <Row key={c.id} icon="server" block
              title={<>{c.device || "未命名设备"} <span style={{ color: "var(--muted, #8c8c8c)", fontWeight: 400, fontSize: 13 }}>· 账号 {c.user}</span></>}
              desc={
                <div style={{ display: "grid", gap: 3, marginTop: 4 }}>
                  <div>{c.online && c.nowDetail ? <b>{c.nowDetail}{c.nowName ? `:${c.nowName}` : ""}</b> : c.online ? "在线,暂时没有操作" : `离线 · 最后一次请求 ${fmtAgo(c.lastSeen)}`}</div>
                  <div style={{ fontSize: 12, opacity: 0.8 }}>
                    {c.ip || "—"} · {shortUA(c.ua)}
                  </div>
                  <div style={{ fontSize: 12, opacity: 0.8 }}>
                    速度 {fmtRate(c.rateBps)} · 已发送 {fmtBytes(c.bytesOut)}{c.bytesIn > 0 ? ` · 已上传 ${fmtBytes(c.bytesIn)}` : ""} · 请求 {c.requests} 次{c.errors > 0 ? `(失败 ${c.errors})` : ""}{c.activeStreams > 0 ? ` · ${c.activeStreams} 条流` : ""}
                  </div>
                  <div style={{ fontSize: 12, opacity: 0.8 }}>首次出现 {fmtTime(c.firstSeen)} · 最近 {fmtAgo(c.lastSeen)}</div>
                </div>
              }>
              <div style={{ display: "flex", gap: 8, alignItems: "center", flexWrap: "wrap", justifyContent: "flex-end" }}>
                <Badge tone={rt}>{rl}</Badge>
                <Badge tone={st}>{sl}</Badge>
                <Btn icon="stop" onClick={() => kick(c)}>退出登录</Btn>
              </div>
            </Row>
          );
        })}
      </Card>

      <Card icon="cpu" title="后台任务" count={r.background.length}>
        {r.background.length === 0 && <Row icon="info" title="没有后台任务" />}
        {r.background.map((b) => {
          const [bl, bt] = BG[b.state] ?? [b.state, "gray" as Tone];
          return <Row key={b.name} icon="zap" title={b.name} desc={b.note}><Badge tone={bt}>{bl}</Badge></Row>;
        })}
      </Card>
    </>
  );
}
