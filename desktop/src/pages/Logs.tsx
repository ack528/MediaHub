import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { bridge } from "../bridge";
import { Badge, Btn, Card, ItemRow, Row, SelectField, Switch, fmtBytes } from "../components";
import { itemsOf } from "../schema";
import type { CrashFile } from "../types";
import { useApp } from "../store";

const LEVELS = [
  { value: "", label: "全部" },
  { value: "info", label: "信息及以上" },
  { value: "warn", label: "仅警告和错误" },
  { value: "error", label: "仅错误" },
];

function lineClass(l: string) {
  if (l.includes(" level=ERROR")) return "lv-error";
  if (l.includes(" level=WARN")) return "lv-warn";
  if (l.includes(" level=DEBUG")) return "lv-debug";
  return undefined;
}

function LogView() {
  const { config } = useApp();
  const [text, setText] = useState("");
  const [err, setErr] = useState<string | null>(null);
  const [auto, setAuto] = useState(true);
  const [level, setLevel] = useState("");
  const [q, setQ] = useState("");
  const ref = useRef<HTMLPreElement>(null);
  const stick = useRef(true);

  const load = useCallback(async () => {
    try { setText(await bridge.readLog(1000, level)); setErr(null); } catch (e) { setErr(String(e)); }
  }, [level]);
  useEffect(() => { load(); }, [load]);
  useEffect(() => {
    if (!auto) return;
    const t = window.setInterval(load, 3000);
    return () => window.clearInterval(t);
  }, [auto, load]);

  const lines = useMemo(() => {
    const all = text ? text.split("\n") : [];
    const s = q.trim().toLowerCase();
    return s ? all.filter((l) => l.toLowerCase().includes(s)) : all;
  }, [text, q]);

  // 用户停在底部时才自动跟随最新日志;往上翻看时不打断
  useEffect(() => {
    const el = ref.current;
    if (el && stick.current) el.scrollTop = el.scrollHeight;
  }, [lines]);

  return (
    <Card icon="filetext" title="服务日志"
          extra={<>
            <label className="inline-switch">自动刷新 <Switch checked={auto} onChange={setAuto} /></label>
            <Btn icon="refresh" onClick={load}>刷新</Btn>
            <Btn icon="folderopen" onClick={() => bridge.openPath(config.dataDir + "\\logs")}>打开日志文件夹</Btn>
          </>}>
      <div className="log-tools">
        <SelectField value={level} options={LEVELS} onChange={setLevel} />
        <input type="search" placeholder="在日志中搜索" value={q} onChange={(e) => setQ(e.target.value)} />
        <span className="grow" />
        <span className="count">{lines.length} 行</span>
      </div>
      {err ? <div className="log-empty">{err}</div> : (
        <pre className="log" ref={ref} onScroll={(e) => {
          const el = e.currentTarget;
          stick.current = el.scrollHeight - el.scrollTop - el.clientHeight < 40;
        }}>
          {lines.length === 0 ? "(没有符合条件的日志)" : lines.map((l, i) => <div key={i} className={lineClass(l)}>{l}</div>)}
        </pre>
      )}
    </Card>
  );
}

function fmtTime(sec: number) {
  return new Date(sec * 1000).toLocaleString("zh-CN", { hour12: false });
}

function LogSettings() {
  const { config, toast } = useApp();
  const [crashes, setCrashes] = useState<CrashFile[]>([]);
  const [busy, setBusy] = useState(false);
  const items = itemsOf("logs", "日志设置");
  const dir = config.dataDir + "\\logs";

  const loadCrashes = useCallback(async () => { try { setCrashes(await bridge.listCrashes()); } catch { /* 没有日志目录 */ } }, []);
  useEffect(() => { loadCrashes(); }, [loadCrashes]);

  const exportZip = async () => {
    setBusy(true);
    try {
      const p = await bridge.exportLogs();
      toast("已导出");
      await bridge.openPath(p.slice(0, p.lastIndexOf("\\")));
    } catch (e) { toast(String(e), "err"); }
    setBusy(false);
  };

  return (
    <>
      <Card icon="filetext" title="日志设置" count={items.length}>
        {items.map((i) => <ItemRow key={i.id} item={i} />)}
      </Card>
      <Card icon="shield" title="导出与崩溃报告">
        <Row icon="copy" title="导出日志包" desc="把全部日志、崩溃报告和运行环境打成一个 zip(不含密码、登录令牌和 API 密钥),出问题时发给开发者。服务没有运行也能导出。">
          <Btn kind="primary" icon="copy" disabled={busy} onClick={exportZip}>{busy ? "打包中…" : "导出"}</Btn>
        </Row>
        <Row icon="alert" title="崩溃报告"
             desc={crashes.length === 0 ? "没有崩溃记录。服务内部出现严重错误时会自动写入一份报告(含堆栈和当时的日志),服务本身继续运行。" : `共 ${crashes.length} 份,最近的在最上面。点“打开”查看。`}>
          <Btn icon="folderopen" onClick={() => bridge.openPath(dir)}>打开日志文件夹</Btn>
        </Row>
        {crashes.map((c) => (
          <Row key={c.name} icon="filetext" title={c.name} desc={`${fmtTime(c.time)} · ${fmtBytes(c.size)}`}>
            <Badge tone="red">崩溃</Badge>
            <Btn onClick={() => bridge.openPath(dir + "\\" + c.name)}>打开</Btn>
          </Row>
        ))}
      </Card>
    </>
  );
}

export function Logs({ tab }: { tab: string }) {
  return tab === "日志设置" ? <LogSettings /> : <LogView />;
}
