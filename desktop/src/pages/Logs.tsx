import { useCallback, useEffect, useRef, useState } from "react";
import { bridge } from "../bridge";
import { Btn, Card, Switch } from "../components";
import { useApp } from "../store";

export function Logs() {
  const { config } = useApp();
  const [text, setText] = useState("");
  const [err, setErr] = useState<string | null>(null);
  const [auto, setAuto] = useState(true);
  const ref = useRef<HTMLPreElement>(null);

  const load = useCallback(async () => {
    try { setText(await bridge.readLog(300)); setErr(null); } catch (e) { setErr(String(e)); }
  }, []);
  useEffect(() => { load(); }, [load]);
  useEffect(() => {
    if (!auto) return;
    const t = window.setInterval(load, 3000);
    return () => window.clearInterval(t);
  }, [auto, load]);
  useEffect(() => { if (ref.current) ref.current.scrollTop = ref.current.scrollHeight; }, [text]);

  return (
    <Card icon="filetext" title="服务日志"
          extra={<>
            <label className="inline-switch">自动刷新 <Switch checked={auto} onChange={setAuto} /></label>
            <Btn icon="refresh" onClick={load}>刷新</Btn>
            <Btn icon="folderopen" onClick={() => bridge.openPath(config.dataDir + "\\logs")}>打开日志文件夹</Btn>
          </>}>
      {err ? <div className="log-empty">{err}</div> : <pre className="log" ref={ref}>{text || "(还没有日志)"}</pre>}
    </Card>
  );
}
