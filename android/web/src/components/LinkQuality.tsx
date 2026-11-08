import { Chip } from "@heroui/react";
import { useEffect, useRef, useState } from "react";
import { useApp } from "../lib/app";
import { latTone, useLinkQuality, type LinkSample } from "../lib/link";
import { usePageActive } from "../lib/nav";
import { ListGroup } from "./List";

const TONE_COLOR = { success: "var(--success)", warning: "var(--warning)", danger: "var(--danger)", default: "var(--muted)" } as const;

const hasValue = (s: LinkSample[]) => s.some((x) => x.ms != null);

/** 延迟柱状图:每次测量一根蓝色柱子,失败的画红色短柱。按容器真实宽度画(不拉伸,文字不会变形)。 */
function Spark({ samples, height = 104 }: { samples: LinkSample[]; height?: number }) {
  const ref = useRef<HTMLDivElement>(null);
  const [W, setW] = useState(320);
  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    const ro = new ResizeObserver(() => setW(Math.max(120, Math.round(el.clientWidth))));
    ro.observe(el);
    setW(Math.max(120, Math.round(el.clientWidth)));
    return () => ro.disconnect();
  }, []);
  const H = height, L = 30, R = 4, T = 8, B = 8;
  const vals = samples.map((s) => s.ms).filter((v): v is number => v != null);
  const top = Math.max(100, Math.ceil((Math.max(0, ...vals) * 1.15) / 50) * 50);
  const slots = Math.max(samples.length, 30); // 采样不够时右对齐,柱子宽度不会忽大忽小
  const slot = (W - L - R) / slots;
  const bw = Math.max(2, slot * 0.68);
  const x0 = (i: number) => L + (slots - samples.length + i) * slot + (slot - bw) / 2;
  const hOf = (v: number) => Math.max(2, (Math.min(v, top) * (H - T - B)) / top);
  return (
    <div ref={ref} className="w-full text-foreground">
      <svg width={W} height={H} className="block overflow-visible" role="img" aria-label="延迟柱状图">
        {[0, 0.5, 1].map((f) => {
          const yy = T + f * (H - T - B);
          return (
            <g key={f}>
              <line x1={L} x2={W - R} y1={yy} y2={yy} stroke="currentColor" strokeOpacity={f === 1 ? 0.25 : 0.1} />
              <text x={L - 5} y={yy + 3.5} textAnchor="end" fontSize={10} fill="currentColor" fillOpacity={0.5}>{Math.round(top * (1 - f))}</text>
            </g>
          );
        })}
        {samples.map((s, i) => s.ms != null
          ? <rect key={s.at} x={x0(i)} y={H - B - hOf(s.ms)} width={bw} height={hOf(s.ms)} rx={Math.min(2, bw / 2)} fill="var(--accent)" />
          : <rect key={s.at} x={x0(i)} y={H - B - 10} width={bw} height={10} rx={Math.min(2, bw / 2)} fill="var(--danger)" />)}
      </svg>
    </div>
  );
}

function Tile({ label, value, unit = "ms", tone, small }: { label: string; value: string; unit?: string; tone?: keyof typeof TONE_COLOR; small?: boolean }) {
  return (
    <div className="rounded-2xl bg-surface-secondary px-3 py-2">
      <div className="text-xs text-muted">{label}</div>
      <div className={`whitespace-nowrap leading-tight font-semibold tabular-nums ${small ? "text-base" : "text-lg"}`} style={tone ? { color: TONE_COLOR[tone] } : undefined}>
        {value}{value !== "—" && unit && <span className="ml-0.5 text-xs font-normal text-muted">{unit}</span>}
      </div>
    </div>
  );
}

const PROTO: Record<string, string> = { h2: "HTTP/2", "http/1.1": "HTTP/1.1", h3: "HTTP/3" };
const TUN_STATE: Record<string, string> = { running: "运行中", starting: "启动中", connecting: "连接中", backoff: "重连中", stopped: "已停止", missing: "找不到程序", error: "出错", disabled: "未启用" };

/** 设置 → 网络里的"连接质量":每 2 秒量一次手机到服务器的往返,加上服务器那边的穿透状态 / 延迟和当前用的传输协议。 */
export function LinkQuality() {
  const { session } = useApp();
  const active = usePageActive();
  const { samples, stat, info, server, local } = useLinkQuality(active && !session.local);
  if (session.local || local) return null;
  const f = (v: number) => (stat.n === 0 || v < 0 ? "—" : String(Math.round(v)));
  const tun = server?.tunnel;
  const line = info?.lan ? "局域网直连" : tun?.enabled ? "经内网穿透" : "服务器地址";
  return (
    <ListGroup title="连接质量" footer="每 2 秒测一次手机到服务器的往返;页面离开就停止。柱子:蓝色 = 往返时间,红色短柱 = 这次没成功。">
      <div className="flex flex-col gap-3 p-3">
        <div className="flex items-center gap-2">
          <span className="text-3xl font-semibold tabular-nums" style={{ color: TONE_COLOR[latTone(stat.last)] }}>{f(stat.last)}</span>
          <span className="text-sm text-muted">{stat.n === 0 || stat.last < 0 ? "" : "ms"}</span>
          <span className="flex-1" />
          <Chip size="sm" variant="soft" color={info?.lan ? "success" : "accent"}>{line}</Chip>
        </div>
        <Spark samples={samples} />
        <div className="grid grid-cols-3 gap-2">
          <Tile label="平均" value={f(stat.avg)} tone={stat.n && hasValue(samples) ? latTone(stat.avg) : undefined} />
          <Tile label="最低 / 最高" value={hasValue(samples) ? `${Math.round(stat.min)}/${Math.round(stat.max)}` : "—"} />
          <Tile label="抖动" value={f(stat.jitter)} />
          <Tile label="丢包" value={stat.n ? stat.lossPct.toFixed(0) : "—"} unit="%" tone={stat.lossPct === 0 ? "success" : stat.lossPct < 10 ? "warning" : "danger"} />
          <Tile label="协议" value={info ? PROTO[info.protocol] ?? info.protocol : "—"} unit="" small />
          <Tile label="线路传输" value={tun?.latency.udp ? `UDP·${tun.latency.protocol}` : "TCP"} unit="" small />
        </div>
        {tun && (
          <div className="rounded-2xl bg-surface-secondary px-3 py-2 text-sm">
            <div className="flex items-center gap-2">
              <span className="font-medium">内网穿透</span>
              <Chip size="sm" variant="soft" color={tun.state === "running" ? "success" : ["connecting", "starting", "backoff"].includes(tun.state) ? "warning" : "danger"}>
                {TUN_STATE[tun.state] ?? tun.state}
              </Chip>
              <span className="flex-1" />
              <span className="text-xs text-muted">{tun.type === "frp" ? "frp" : "自定义"} · {tun.latency.protocol}{tun.latency.udp ? "(UDP)" : ""}</span>
            </div>
            <div className="mt-1 text-xs text-muted tabular-nums">
              服务器测到的整条链路 {tun.latency.chain.n && tun.latency.chain.last >= 0 ? `${Math.round(tun.latency.chain.last)} ms(平均 ${Math.round(tun.latency.chain.avg)} ms)` : "—"}
              {" · "}到穿透服务器 {tun.latency.server.n && tun.latency.server.last >= 0 ? `${Math.round(tun.latency.server.last)} ms` : "—"}
              {tun.restarts > 0 ? ` · 已重连 ${tun.restarts} 次` : ""}
            </div>
          </div>
        )}
      </div>
    </ListGroup>
  );
}

/** 设置首页个人卡片上的实时延迟小标签(设置标签在前台时每 2 秒测一次,不取服务器那边的数据)。 */
export function LatencyChip() {
  const { session } = useApp();
  const active = usePageActive();
  const { stat } = useLinkQuality(active && !session.local, false);
  if (session.local || stat.n === 0) return null;
  const down = stat.last < 0;
  return (
    <Chip size="sm" variant="soft" className="shrink-0 tabular-nums" color={down ? "danger" : latTone(stat.last)}>
      {down ? "无响应" : `${Math.round(stat.last)} ms`}
    </Chip>
  );
}
