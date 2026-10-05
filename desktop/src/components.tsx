import { useEffect, useState, type ReactNode } from "react";
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
