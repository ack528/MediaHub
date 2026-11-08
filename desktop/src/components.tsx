import { useEffect, useRef, useState, type ReactNode, type RefObject } from "react";
import { bridge } from "./bridge";
import { Icon } from "./icons";
import type { Item } from "./schema";
import { useApp } from "./store";

// ------------------------------------------------------------ 基础控件

export function Switch({ checked, onChange }: { checked: boolean; onChange: (v: boolean) => void }) {
  return (
    <button type="button" role="switch" aria-checked={checked} className={"switch" + (checked ? " on" : "")} onClick={() => onChange(!checked)}>
      <span className="knob" />
    </button>
  );
}

export function NumberField({ value, onChange, unit, min, max }: { value: number; onChange: (v: number) => void; unit?: string; min?: number; max?: number }) {
  const [text, setText] = useState(String(value));
  useEffect(() => setText(String(value)), [value]);
  const commit = (s: string) => {
    let n = Number(s);
    if (!Number.isFinite(n) || s.trim() === "") { setText(String(value)); return; }
    if (min !== undefined) n = Math.max(min, n);
    if (max !== undefined) n = Math.min(max, n);
    setText(String(n));
    if (n !== value) onChange(n);
  };
  return (
    <div className="numfield">
      <input value={text} inputMode="numeric" onChange={(e) => setText(e.target.value)} onBlur={(e) => commit(e.target.value)}
             onKeyDown={(e) => { if (e.key === "Enter") (e.target as HTMLInputElement).blur(); }} />
      {unit && <span className="unit">{unit}</span>}
    </div>
  );
}

export function TextField({ value, onChange, placeholder, secret, path, width }: { value: string; onChange: (v: string) => void; placeholder?: string; secret?: boolean; path?: boolean; width?: number }) {
  const [text, setText] = useState(value);
  const [show, setShow] = useState(false);
  useEffect(() => setText(value), [value]);
  return (
    <div className="textfield" style={{ width: width ?? 320 }}>
      <input value={text} placeholder={placeholder} type={secret && !show ? "password" : "text"} spellCheck={false}
             onChange={(e) => setText(e.target.value)}
             onBlur={() => text !== value && onChange(text)}
             onKeyDown={(e) => { if (e.key === "Enter") (e.target as HTMLInputElement).blur(); }} />
      {secret && <button type="button" className="mini" onClick={() => setShow(!show)}>{show ? "隐藏" : "显示"}</button>}
      {path && (
        <button type="button" className="mini" onClick={async () => { const p = await bridge.pickFolder(); if (p) { setText(p); onChange(p); } }}>浏览…</button>
      )}
    </div>
  );
}

export function SelectField({ value, options, onChange }: { value: string; options: { value: string; label: string }[]; onChange: (v: string) => void }) {
  return (
    <select className="selectfield" value={value} onChange={(e) => onChange(e.target.value)}>
      {options.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
    </select>
  );
}

export function TagsField({ value, onChange, placeholder }: { value: string[]; onChange: (v: string[]) => void; placeholder?: string }) {
  const [text, setText] = useState("");
  const add = () => {
    const t = text.trim();
    if (t && !value.some((x) => x.toLowerCase() === t.toLowerCase())) onChange([...value, t]);
    setText("");
  };
  return (
    <div className="tags">
      {value.map((t) => (
        <span className="tag" key={t}>{t}<button type="button" aria-label="删除" onClick={() => onChange(value.filter((x) => x !== t))}>×</button></span>
      ))}
      <input value={text} placeholder={placeholder} onChange={(e) => setText(e.target.value)}
             onKeyDown={(e) => { if (e.key === "Enter") add(); }} onBlur={add} />
    </div>
  );
}

export function Btn({ children, onClick, kind = "default", disabled, icon }: { children: ReactNode; onClick?: () => void; kind?: "default" | "primary" | "danger"; disabled?: boolean; icon?: string }) {
  return (
    <button type="button" className={"btn " + kind} onClick={onClick} disabled={disabled}>
      {icon && <Icon name={icon} size={16} />}{children}
    </button>
  );
}

export function Progress({ value, max, warn }: { value: number; max: number; warn?: boolean }) {
  const pct = max > 0 ? Math.min(100, (value / max) * 100) : 0;
  return <div className="progress"><div className={"bar" + (warn || pct > 90 ? " warn" : "")} style={{ width: Math.max(pct, value > 0 ? 2 : 0) + "%" }} /></div>;
}

export function Badge({ children, tone = "gray" }: { children: ReactNode; tone?: "gray" | "green" | "red" | "orange" | "blue" }) {
  return <span className={"badge " + tone}>{children}</span>;
}

// ------------------------------------------------------------ 卡片与设置行

export function Card({ icon, title, count, extra, children }: { icon?: string; title: string; count?: number; extra?: ReactNode; children: ReactNode }) {
  return (
    <section className="card">
      <header className="card-head">
        {icon && <span className="ico"><Icon name={icon} size={24} /></span>}
        <h2>{title}</h2>
        <span className="grow" />
        {extra}
        {count !== undefined && <span className="count">{count} 项</span>}
      </header>
      {children}
    </section>
  );
}

export function Row({ icon, title, desc, children, block }: { icon?: string; title: ReactNode; desc?: ReactNode; children?: ReactNode; block?: boolean }) {
  return (
    <div className={"row" + (block ? " block" : "")}>
      <span className="row-ico">{icon && <Icon name={icon} size={26} />}</span>
      <div className="row-main">
        <div className="row-title">{title}</div>
        {desc && <div className="row-desc">{desc}</div>}
      </div>
      {children && <div className="row-ctl">{children}</div>}
    </div>
  );
}

/** 按 schema 里的类型渲染一个设置项。 */
export function ItemRow({ item }: { item: Item }) {
  const { config, update } = useApp();
  const v = item.read(config);
  const set = (nv: any) => update((c) => item.write(c, nv));
  let ctl: ReactNode = null;
  switch (item.kind) {
    case "switch": ctl = <Switch checked={!!v} onChange={set} />; break;
    case "number": ctl = <NumberField value={Number(v)} onChange={set} unit={item.unit} min={item.min} max={item.max} />; break;
    case "select": ctl = <SelectField value={String(v)} options={item.options ?? []} onChange={set} />; break;
    case "text": ctl = <TextField value={String(v ?? "")} onChange={set} placeholder={item.placeholder} />; break;
    case "secret": ctl = <TextField value={String(v ?? "")} onChange={set} placeholder={item.placeholder} secret />; break;
    case "path": ctl = <TextField value={String(v ?? "")} onChange={set} placeholder={item.placeholder} path width={380} />; break;
    case "tags": return (
      <Row icon={item.icon} title={item.title} desc={item.desc} block>
        <TagsField value={v as string[]} onChange={set} placeholder={item.placeholder} />
      </Row>
    );
  }
  return <Row icon={item.icon} title={item.title} desc={item.desc}>{ctl}</Row>;
}

export function Tabs({ tabs, active, onChange }: { tabs: string[]; active: string; onChange: (t: string) => void }) {
  return (
    <nav className="tabs">
      {tabs.map((t) => (
        <button key={t} type="button" className={t === active ? "active" : ""} onClick={() => onChange(t)}>{t}</button>
      ))}
    </nav>
  );
}

// ------------------------------------------------------------ 格式化

export function fmtBytes(b: number) {
  if (b >= 2 ** 40) return (b / 2 ** 40).toFixed(2) + " TB";
  if (b >= 2 ** 30) return (b / 2 ** 30).toFixed(1) + " GB";
  if (b >= 2 ** 20) return Math.round(b / 2 ** 20) + " MB";
  if (b >= 2 ** 10) return Math.round(b / 2 ** 10) + " KB";
  return b + " B";
}

export function fmtUptime(sec: number) {
  const d = Math.floor(sec / 86400), h = Math.floor((sec % 86400) / 3600), m = Math.floor((sec % 3600) / 60);
  return d > 0 ? `${d} 天 ${h} 小时` : h > 0 ? `${h} 小时 ${m} 分钟` : `${m} 分钟`;
}

export const fmtNum = (n: number) => n.toLocaleString("zh-CN");

// ------------------------------------------------------------ 实时延迟

export interface LatSample { at: number; chainMs: number; serverMs: number }
export interface LatStats { last: number; avg: number; min: number; max: number; jitter: number; p95: number; lossPct: number; n: number }

/** 延迟的颜色档位:≤ 80 ms 流畅,≤ 200 ms 一般,再高明显有延迟感。 */
export function latTone(ms: number): "green" | "orange" | "red" | "gray" {
  return ms < 0 ? "gray" : ms <= 80 ? "green" : ms <= 200 ? "orange" : "red";
}
const LAT_COLOR = { green: "#52c41a", orange: "#fa8c16", red: "#f5222d", gray: "#8c8c8c" } as const;

/** 容器的真实宽度(像素):图表按它画,不用 preserveAspectRatio="none" 拉伸(拉伸会让文字变形、圆点变椭圆)。 */
function useWidth(initial = 600): [RefObject<HTMLDivElement | null>, number] {
  const ref = useRef<HTMLDivElement>(null);
  const [w, setW] = useState(initial);
  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    const ro = new ResizeObserver(() => setW(Math.max(120, Math.round(el.clientWidth))));
    ro.observe(el);
    setW(Math.max(120, Math.round(el.clientWidth)));
    return () => ro.disconnect();
  }, []);
  return [ref, w];
}

/** 延迟柱状图:每次采样一根蓝色柱子(整条链路经穿透访问服务器的往返);失败的采样在底部画红色短柱。按容器真实宽度画,不拉伸。 */
export function LatencyChart({ samples, height = 120 }: { samples: LatSample[]; height?: number }) {
  const [ref, W] = useWidth();
  const H = height, L = 34, R = 8, T = 8, B = 8; // 左边留给纵轴刻度
  const vals = samples.map((s) => s.chainMs).filter((v) => v >= 0);
  const top = Math.max(100, Math.ceil((Math.max(0, ...vals) * 1.15) / 50) * 50);
  const slots = Math.max(samples.length, 30); // 采样不够时右对齐,柱子宽度不会忽大忽小
  const slot = (W - L - R) / slots;
  const bw = Math.max(2, slot * 0.68);
  const x0 = (i: number) => L + (slots - samples.length + i) * slot + (slot - bw) / 2;
  const hOf = (v: number) => Math.max(2, (Math.min(v, top) * (H - T - B)) / top);
  return (
    <div ref={ref} style={{ width: "100%" }}>
      <svg width={W} height={H} style={{ display: "block", overflow: "visible" }} role="img" aria-label="延迟柱状图">
        {[0, 0.5, 1].map((f) => {
          const yy = T + f * (H - T - B), val = Math.round(top * (1 - f));
          return (
            <g key={f}>
              <line x1={L} x2={W - R} y1={yy} y2={yy} stroke="currentColor" strokeOpacity={f === 1 ? 0.25 : 0.1} />
              <text x={L - 6} y={yy + 3.5} textAnchor="end" fontSize={11} fill="currentColor" fillOpacity={0.5}>{val}</text>
            </g>
          );
        })}
        {samples.map((s, i) => s.chainMs >= 0
          ? <rect key={s.at} x={x0(i)} y={H - B - hOf(s.chainMs)} width={bw} height={hOf(s.chainMs)} rx={Math.min(2, bw / 2)} fill="#1677ff"><title>{`${s.chainMs} ms`}</title></rect>
          : <rect key={s.at} x={x0(i)} y={H - B - 10} width={bw} height={10} rx={Math.min(2, bw / 2)} fill="#f5222d"><title>失败</title></rect>)}
      </svg>
    </div>
  );
}

/** 一格统计数字(大号数字 + 小标题)。 */
export function LatTile({ label, value, unit = "ms", tone }: { label: string; value: string; unit?: string; tone?: "green" | "orange" | "red" | "gray" }) {
  return (
    <div style={{ flex: "1 1 88px", minWidth: 88, padding: "8px 10px", borderRadius: 10, background: "var(--bg-soft, rgba(127,127,127,.08))" }}>
      <div style={{ fontSize: 12, opacity: 0.6 }}>{label}</div>
      <div style={{ fontSize: 22, fontWeight: 600, lineHeight: 1.25, color: tone ? LAT_COLOR[tone] : undefined, fontVariantNumeric: "tabular-nums" }}>
        {value}<span style={{ fontSize: 12, fontWeight: 400, opacity: 0.6, marginLeft: 2 }}>{value === "—" ? "" : unit}</span>
      </div>
    </div>
  );
}
