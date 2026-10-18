import { useCallback, useEffect, useRef, useState } from "react";
import { bridge } from "../bridge";
import { Badge, Btn, Card, NumberField, Progress, Row, SelectField, Switch, fmtBytes } from "../components";
import { ItemRow } from "../components";
import { itemsOf } from "../schema";
import { useApp } from "../store";

// 存储优化:重复文件检测、视频批量压缩(Intel QSV)、回收区。
// 写入边界:服务端只在各盘的 .mediahub 里写东西 —— 压缩输出写到 .mediahub\optimize,
// "删除重复文件"和"替换成压缩版"时原文件是被移进 .mediahub\trash(可以还原);只有在「回收区」里点「永久删除」才真正删除。

interface DdStatus {
  state: string; phase: string; done: number; total: number; message?: string;
  startedAt: number; finishedAt: number; exact: number; image: number; video: number; wasted: number;
}
interface DdItem {
  id: number; name: string; dir: string; root: string; type: string; size: number; w: number; h: number; durMs: number; mtime: number; codec?: string; keep: boolean;
}
interface DdGroup { id: number; kind: string; wasted: number; items: DdItem[] }
interface Job {
  id: number; mediaId: number; state: string; codec: string; kbps: number; preset: string; origSize: number; newSize: number;
  progress: number; err?: string; name: string; dir: string; root: string; path?: string;
}
interface TrashBatch { rootId: number; root: string; batch: string; reason: string; files: number; bytes: number; created: number }

const fmtDur = (ms: number) => {
  const s = Math.round(ms / 1000);
  return s >= 3600 ? `${Math.floor(s / 3600)}:${String(Math.floor((s % 3600) / 60)).padStart(2, "0")}:${String(s % 60).padStart(2, "0")}` : `${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")}`;
};
const fmtTime = (sec: number) => (sec ? new Date(sec * 1000).toLocaleString("zh-CN", { hour12: false }) : "—");

// ------------------------------------------------------------------ 重复文件

const KINDS: { id: string; label: string; hint: string }[] = [
  { id: "exact", label: "完全相同", hint: "内容一模一样(SHA-256 相同),不管文件名和放在哪个文件夹" },
  { id: "image", label: "相似图片", hint: "缩放、重新压缩后看起来一样的图片(感知哈希)" },
  { id: "video", label: "相似视频", hint: "重新编码、转分辨率后看起来一样的视频(时长相近 + 三个位置的画面相同)" },
];
const STRICT = [{ value: "2", label: "严格(几乎一模一样)" }, { value: "5", label: "标准(推荐)" }, { value: "8", label: "宽松(可能有误判)" }];

function Dedupe() {
  const { toast } = useApp();
  const [st, setSt] = useState<DdStatus | null>(null);
  const [kind, setKind] = useState("exact");
  const [groups, setGroups] = useState<DdGroup[]>([]);
  const [total, setTotal] = useState(0);
  const [sel, setSel] = useState<Set<number>>(new Set());
  const [thr, setThr] = useState("5");
  const [busy, setBusy] = useState(false);
  const selInit = useRef(new Set<number>());

  const loadStatus = useCallback(async () => {
    try { setSt(await bridge.admin<DdStatus>("GET", "/api/v1/admin/dedupe/status")); } catch { /* 服务没运行 */ }
  }, []);
  const loadGroups = useCallback(async (k: string, offset: number) => {
    try {
      const r = await bridge.admin<{ total: number; groups: DdGroup[] }>("GET", `/api/v1/admin/dedupe/groups?kind=${k}&offset=${offset}&limit=20`);
      setTotal(r.total);
      setGroups((old) => (offset === 0 ? r.groups : [...old, ...r.groups]));
      // 默认选中每组"不推荐保留"的文件
      setSel((old) => {
        const n = new Set(old);
        for (const g of r.groups) for (const it of g.items) if (!it.keep && !selInit.current.has(it.id)) { n.add(it.id); selInit.current.add(it.id); }
        return n;
      });
    } catch (e) { toast(String(e), "err"); }
  }, [toast]);

  useEffect(() => { loadStatus(); }, [loadStatus]);
  useEffect(() => {
    if (st?.state !== "running") return;
    const t = setInterval(loadStatus, 1000);
    return () => clearInterval(t);
  }, [st?.state, loadStatus]);
  const finished = st?.finishedAt ?? 0;
  useEffect(() => { selInit.current = new Set(); setSel(new Set()); setGroups([]); if (st && st.state !== "running" && st.finishedAt) loadGroups(kind, 0); }, [kind, finished]); // eslint-disable-line react-hooks/exhaustive-deps

  const start = async () => {
    try { await bridge.admin("POST", "/api/v1/admin/dedupe/scan", { threshold: Number(thr) }); toast("已开始检测,可以先去做别的"); setTimeout(loadStatus, 400); }
    catch (e) { toast(String(e), "err"); }
  };
  const cancel = async () => { try { await bridge.admin("POST", "/api/v1/admin/dedupe/cancel"); } catch (e) { toast(String(e), "err"); } };
  const toggle = (id: number) => setSel((o) => { const n = new Set(o); if (n.has(id)) n.delete(id); else n.add(id); return n; });
  const picked = groups.flatMap((g) => g.items).filter((i) => sel.has(i.id));
  const pickedBytes = picked.reduce((s, i) => s + i.size, 0);
  const trash = async () => {
    if (picked.length === 0) return;
    if (!window.confirm(`把选中的 ${picked.length} 个文件(${fmtBytes(pickedBytes)})移进回收区?\n\n文件不会被删除,放在各盘的 .mediahub\\trash 里,可以在「回收区」还原。确认没问题后再在回收区里永久删除才会释放空间。`)) return;
    setBusy(true);
    try {
      const r = await bridge.admin<{ moved: number; freed: number; failed: string[] }>("POST", "/api/v1/admin/dedupe/trash", { ids: picked.map((i) => i.id) }, 120000);
      toast(`已移入回收区 ${r.moved} 个文件(${fmtBytes(r.freed)})${r.failed?.length ? `,${r.failed.length} 个失败:${r.failed[0]}` : ""}`, r.failed?.length ? "err" : "ok");
      selInit.current = new Set(); setSel(new Set());
      await loadStatus();
      await loadGroups(kind, 0);
    } catch (e) { toast(String(e), "err"); }
    setBusy(false);
  };

  const running = st?.state === "running";
  const pct = st && st.total > 0 ? Math.min(100, (st.done * 100) / st.total) : 0;
  const hint = KINDS.find((k) => k.id === kind)?.hint;
  return (
    <>
      <Card icon="search" title="检测重复文件">
        <Row icon="gauge" title="相似度" desc="只影响「相似图片 / 相似视频」。严格 = 只找几乎一模一样的;宽松 = 能找到更多,但可能把不同的图当成相似">
          <SelectField value={thr} options={STRICT} onChange={setThr} />
        </Row>
        <Row icon="zap" title={running ? `正在检测:${st?.phase ?? ""}` : "开始检测"}
             desc={running ? `${st?.done ?? 0} / ${st?.total ?? 0}` : st?.state === "done" ? `上次完成于 ${fmtTime(st.finishedAt)}。指纹存在数据库里,没变的文件下次不会重算,第二次检测会快很多` : st?.state === "error" ? `出错:${st.message}` : st?.state === "cancelled" ? "上次检测已取消" : "会读取每个重复候选文件的内容计算指纹(全文件 SHA-256 只对「大小和头尾都相同」的文件做);图片 / 视频用 ffmpeg 取一帧算感知哈希。大库第一次要几分钟到几十分钟,不影响手机浏览。"}>
          <div className="btn-group">
            {running ? <Btn icon="stop" onClick={cancel}>取消</Btn> : <Btn kind="primary" icon="play" onClick={start}>开始检测</Btn>}
          </div>
        </Row>
        {running && <Row icon="clock" title="进度"><div style={{ width: 360 }}><Progress value={pct} max={100} /></div></Row>}
        {st && st.finishedAt > 0 && !running && (
          <Row icon="database" title="检测结果" desc={`完全相同 ${st.exact} 组 · 相似图片 ${st.image} 组 · 相似视频 ${st.video} 组 · 多余的副本共 ${fmtBytes(st.wasted)}`} />
        )}
      </Card>

      {st && st.finishedAt > 0 && !running && (
        <Card icon="copy" title="重复文件" count={total}
              extra={<div className="btn-group">
                {KINDS.map((k) => <Btn key={k.id} kind={kind === k.id ? "primary" : "default"} onClick={() => setKind(k.id)}>{k.label}{st[k.id as "exact" | "image" | "video"] ? ` ${st[k.id as "exact" | "image" | "video"]}` : ""}</Btn>)}
              </div>}>
          <Row icon="info" title={hint ?? ""} desc="每组第一个(带「推荐保留」)是分辨率最高 / 文件最大 / 最早的;默认勾选的是多余的副本。每组至少要保留一个。">
            <div className="btn-group">
              <Btn kind="danger" icon="trash" disabled={busy || picked.length === 0} onClick={trash}>{busy ? "处理中…" : `移入回收区(${picked.length} 个 · ${fmtBytes(pickedBytes)})`}</Btn>
            </div>
          </Row>
          {groups.length === 0 && <Row icon="check" title="没有找到这一类的重复文件" />}
          {groups.map((g) => (
            <div key={g.id} style={{ borderTop: "1px solid var(--border)", padding: "10px 20px" }}>
              <div style={{ color: "var(--text-2)", fontSize: 12.5, marginBottom: 6 }}>{g.items.length} 个文件 · 多余 {fmtBytes(g.wasted)}</div>
              {g.items.map((it) => (
                <label key={it.id} style={{ display: "grid", gridTemplateColumns: "24px 1fr 110px 100px 70px 90px", gap: 10, alignItems: "center", padding: "3px 0", cursor: "pointer" }}>
                  <input type="checkbox" checked={sel.has(it.id)} onChange={() => toggle(it.id)} />
                  <span style={{ minWidth: 0 }}>
                    <span style={{ fontWeight: it.keep ? 700 : 400 }}>{it.name}</span> {it.keep && <Badge tone="green">推荐保留</Badge>}
                    <div style={{ color: "var(--text-2)", fontSize: 12, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>{it.root} · {it.dir || "(根目录)"}</div>
                  </span>
                  <span>{it.w && it.h ? `${it.w}×${it.h}` : "—"}</span>
                  <span>{fmtBytes(it.size)}</span>
                  <span>{it.durMs ? fmtDur(it.durMs) : ""}</span>
                  <span style={{ color: "var(--text-2)", fontSize: 12 }}>{it.codec ?? ""}</span>
                </label>
              ))}
            </div>
          ))}
          {groups.length < total && <Row icon="plus" title={`已显示 ${groups.length} / ${total} 组`}><Btn onClick={() => loadGroups(kind, groups.length)}>加载更多</Btn></Row>}
        </Card>
      )}
    </>
  );
}

// ------------------------------------------------------------------ 视频压缩

const STATE_TEXT: Record<string, [string, "gray" | "green" | "blue" | "orange" | "red"]> = {
  queued: ["排队中", "gray"], running: ["压缩中", "blue"], done: ["已完成,待替换", "orange"], failed: ["失败", "red"], cancelled: ["已取消", "gray"], applied: ["已替换", "green"],
};
const PRESETS = [
  { value: "veryfast", label: "最快(体积略大)" }, { value: "faster", label: "较快" }, { value: "fast", label: "快" },
  { value: "medium", label: "标准(推荐)" }, { value: "slow", label: "慢(体积更小)" }, { value: "slower", label: "更慢" }, { value: "veryslow", label: "最慢(体积最小)" },
];

function Compress() {
  const { toast } = useApp();
  const [caps, setCaps] = useState<{ qsvH264: boolean; qsvH265: boolean; probing: boolean; paused: boolean } | null>(null);
  const [codec, setCodec] = useState("h265");
  const [mode, setMode] = useState("percent");
  const [value, setValue] = useState(40);
  const [preset, setPreset] = useState("medium");
  const [minMB, setMinMB] = useState(500);
  const [skip265, setSkip265] = useState(true);
  const [nameLike, setNameLike] = useState("");
  const [plan, setPlan] = useState<{ count: number; origBytes: number; estBytes: number; skipped?: string[] } | null>(null);
  const [jobs, setJobs] = useState<Job[]>([]);
  const [counts, setCounts] = useState<Record<string, number>>({});
  const [saved, setSaved] = useState(0);
  const [pick, setPick] = useState<Set<number>>(new Set());
  const [busy, setBusy] = useState(false);
  // 队列很长(几千个排队)时,失败的任务排在后面看不到:可以只看失败的;顶部汇总失败原因
  const [onlyFailed, setOnlyFailed] = useState(false);
  const [reasons, setReasons] = useState<{ reason: string; count: number }[]>([]);

  const load = useCallback(async () => {
    try {
      const r = await bridge.admin<{ jobs: Job[]; counts: Record<string, number>; savedBytes: number; failReasons?: { reason: string; count: number }[] }>("GET", "/api/v1/admin/compress/jobs?limit=100" + (onlyFailed ? "&state=failed" : ""));
      setJobs(r.jobs ?? []); setCounts(r.counts ?? {}); setSaved(r.savedBytes ?? 0); setReasons(r.failReasons ?? []);
      setCaps(await bridge.admin("GET", "/api/v1/admin/compress/caps"));
    } catch { /* 服务没运行 */ }
  }, [onlyFailed]);
  const retryFailed = async () => {
    setBusy(true);
    try {
      const r = await bridge.admin<{ requeued: number }>("POST", "/api/v1/admin/compress/retry", {});
      toast(`已把 ${r.requeued} 个失败的任务重新排队(现在的编码会依次尝试 QSV → 简化 QSV → 软件编码)`);
    } catch (e) { toast(String(e), "err"); }
    setBusy(false); load();
  };
  useEffect(() => { load(); const t = setInterval(load, 2000); return () => clearInterval(t); }, [load]);

  const body = (dryRun: boolean) => ({
    codec, mode, value, preset, dryRun,
    filter: { minSizeMB: minMB, notCodec: skip265 ? "hevc" : "", nameLike: nameLike.trim() },
  });
  const estimate = async () => {
    setBusy(true);
    try { setPlan(await bridge.admin("POST", "/api/v1/admin/compress/enqueue", body(true), 60000)); } catch (e) { toast(String(e), "err"); }
    setBusy(false);
  };
  const enqueue = async () => {
    setBusy(true);
    try {
      const r = await bridge.admin<{ count: number }>("POST", "/api/v1/admin/compress/enqueue", body(false), 60000);
      toast(r.count > 0 ? `已加入队列 ${r.count} 个视频` : "没有符合条件的视频(可能都已经在队列里)", r.count > 0 ? "ok" : "err");
      setPlan(null); load();
    } catch (e) { toast(String(e), "err"); }
    setBusy(false);
  };
  const act = async (path: string, ids: number[], extra: object = {}) => {
    try { return await bridge.admin<any>("POST", `/api/v1/admin/compress/${path}`, { ids, ...extra }, 120000); } catch (e) { toast(String(e), "err"); return null; }
  };
  const done = jobs.filter((j) => j.state === "done");
  const apply = async (purge: boolean) => {
    const ids = [...pick].filter((id) => done.some((j) => j.id === id));
    if (ids.length === 0) { toast("先勾选要替换的「已完成」任务", "err"); return; }
    const sv = done.filter((j) => ids.includes(j.id)).reduce((s, j) => s + j.origSize - j.newSize, 0);
    const msg = purge
      ? `用压缩版替换 ${ids.length} 个视频,并【永久删除】原文件(省出约 ${fmtBytes(sv)})。\n\n这一步没有后悔药,确定吗?`
      : `用压缩版替换 ${ids.length} 个视频(省出约 ${fmtBytes(sv)})。\n\n原文件会被移进回收区(可以还原)。要真正释放空间,之后在「回收区」里永久删除。\n注意:被替换的视频在手机上的播放进度会重置。`;
    if (!window.confirm(msg)) return;
    if (purge && !window.confirm("再确认一次:原文件将被永久删除,无法恢复。")) return;
    setBusy(true);
    const r = await act("apply", ids, { purge });
    if (r) toast(`已替换 ${r.applied} 个,省下 ${fmtBytes(r.saved)}${r.failed?.length ? `;${r.failed.length} 个失败:${r.failed[0]}` : ""}`, r.failed?.length ? "err" : "ok");
    setPick(new Set()); setBusy(false); load();
  };
  // 一键清除队列:取消正在压缩的、删掉排队 / 失败 / 已取消的任务;「已完成待替换」的单独再问一次(清掉会删除它们的压缩版,原视频不受影响)
  const clearQueue = async () => {
    const waiting = (counts.queued ?? 0) + (counts.running ?? 0) + (counts.failed ?? 0) + (counts.cancelled ?? 0);
    const doneN = counts.done ?? 0;
    if (!window.confirm(`清除压缩队列?\n\n将取消正在压缩的任务,并删除 ${waiting} 个排队 / 失败 / 已取消的任务。原视频不受任何影响。`)) return;
    let includeDone = false;
    if (doneN > 0) includeDone = window.confirm(`还有 ${doneN} 个「已完成、待替换」的压缩版。\n\n要连它们一起清除吗?(会删除这些压缩版文件,以后需要重新压缩;原视频不受影响)\n\n确定 = 一起清除,取消 = 保留它们。`);
    setBusy(true);
    const r = await act("clear", [], { includeDone });
    if (r) { toast(`已清除 ${r.removed} 个任务${r.cancelledRunning ? ",并停止了正在压缩的" : ""}`); setPick(new Set()); }
    setBusy(false); load();
  };
  const qsv = codec === "h265" ? caps?.qsvH265 : caps?.qsvH264;
  const togglePick = (id: number) => setPick((o) => { const n = new Set(o); if (n.has(id)) n.delete(id); else n.add(id); return n; });
  return (
    <>
      <Card icon="check" title="压缩完成后" count={itemsOf("optimize", "视频压缩").length}>
        {itemsOf("optimize", "视频压缩").map((i) => <ItemRow key={i.id} item={i} />)}
      </Card>
      <Card icon="cpu" title="压缩设置">
        <Row icon="zap" title="硬件编码" desc={caps == null ? "读取中…" : caps.probing ? "服务端正在检测 Intel QSV…" : qsv ? `Intel QSV(${codec === "h265" ? "HEVC" : "H.264"})可用:用核显硬件编码,速度快、CPU 占用低` : `没有检测到 Intel QSV ${codec === "h265" ? "HEVC" : "H.264"} 硬件编码:会用 CPU 软件编码(画质更好但慢得多)。需要 Intel 核显 + 驱动,config.json 里 video.hwaccel = "qsv"`}>
          <Badge tone={caps == null || caps.probing ? "gray" : qsv ? "green" : "orange"}>{caps == null || caps.probing ? "检测中" : qsv ? "QSV 可用" : "软件编码"}</Badge>
        </Row>
        <Row icon="film" title="编码" desc="H.265 同样画质比 H.264 小 30% ~ 50%,压缩存储首选;H.264 兼容性最好">
          <SelectField value={codec} options={[{ value: "h265", label: "H.265 / HEVC(推荐)" }, { value: "h264", label: "H.264 / AVC" }]} onChange={setCodec} />
        </Row>
        <Row icon="gauge" title="码率" desc={mode === "percent" ? "目标视频码率 = 原码率 × 这个百分比(不低于 300 kbps)。40% 通常画质损失很小、体积约一半" : "所有视频都压到这个固定的视频码率(kbps);比原码率还高的视频会被跳过"}>
          <div className="btn-group">
            <SelectField value={mode} options={[{ value: "percent", label: "原码率的百分比" }, { value: "abs", label: "固定码率(kbps)" }]} onChange={(v) => { setMode(v); setValue(v === "percent" ? 40 : 2500); }} />
            <NumberField value={value} onChange={setValue} unit={mode === "percent" ? "%" : "kbps"} min={mode === "percent" ? 10 : 200} max={mode === "percent" ? 90 : 60000} />
          </div>
        </Row>
        <Row icon="clock" title="编码预设" desc="越慢体积越小、画质越好,耗时越长。QSV 硬编下「标准」就很快">
          <SelectField value={preset} options={PRESETS} onChange={setPreset} />
        </Row>
        <Row icon="folder" title="选哪些视频" desc="按条件从整个媒体库里挑:小于这个大小的不压;已经是 H.265 的通常压不小,默认跳过">
          <div className="btn-group">
            <NumberField value={minMB} onChange={setMinMB} unit="MB 以上" min={0} max={1000000} />
            <Switch checked={skip265} onChange={setSkip265} /><span style={{ color: "var(--text-2)" }}>跳过 H.265</span>
          </div>
        </Row>
        <Row icon="search" title="文件名包含" desc="留空 = 不限。可以先填一个关键词(例如某个系列)小范围试一下效果">
          <input value={nameLike} onChange={(e) => setNameLike(e.target.value)} placeholder="例如:S01" style={{ width: 200, padding: "6px 10px", border: "1px solid var(--border-2)", borderRadius: 8 }} />
        </Row>
        <Row icon="database" title="预估与加入队列"
             desc={plan ? `符合条件 ${plan.count} 个视频 · 原大小 ${fmtBytes(plan.origBytes)} → 预计 ${fmtBytes(plan.estBytes)},可省约 ${fmtBytes(Math.max(0, plan.origBytes - plan.estBytes))}${plan.skipped?.length ? `(另有 ${plan.skipped.length} 个被跳过:${plan.skipped[0]})` : ""}` : "先点「预估」看看能省多少空间(不会开始压缩)。压缩只写到 .mediahub\\optimize,原文件完全不动,你确认后才替换。"}>
          <div className="btn-group">
            <Btn icon="gauge" disabled={busy} onClick={estimate}>预估节省</Btn>
            <Btn kind="primary" icon="play" disabled={busy || (plan != null && plan.count === 0)} onClick={enqueue}>加入压缩队列</Btn>
          </div>
        </Row>
      </Card>

      <Card icon="film" title="压缩队列" count={jobs.length}
            extra={<div className="btn-group">
              {caps?.paused ? <Btn icon="play" onClick={async () => { await act("resume", []); load(); }}>继续</Btn> : <Btn icon="stop" onClick={async () => { await act("pause", []); load(); }}>暂停</Btn>}
              <Btn icon="trash" onClick={async () => { const ids = jobs.filter((j) => ["failed", "cancelled"].includes(j.state)).map((j) => j.id); if (ids.length) { await act("remove", ids); load(); } }}>清除失败 / 取消的</Btn>
              <Btn kind="danger" icon="trash" disabled={busy || jobs.length === 0} onClick={clearQueue}>一键清除队列</Btn>
            </div>}>
        <Row icon={caps?.paused ? "alert" : "info"} title={`${caps?.paused ? "【已暂停】" : ""}排队 ${counts.queued ?? 0} · 压缩中 ${counts.running ?? 0} · 待替换 ${counts.done ?? 0} · 已替换 ${counts.applied ?? 0} · 失败 ${counts.failed ?? 0}`}
             desc={`累计已省下 ${fmtBytes(saved)}(含待替换的)。同一时间只压缩一个;有人正在看转码视频时会自动让一让。`}>
          <div className="btn-group">
            <Btn icon="check" disabled={busy || done.length === 0} onClick={() => setPick(new Set(done.map((j) => j.id)))}>全选待替换</Btn>
            <Btn kind="primary" icon="zap" disabled={busy || pick.size === 0} onClick={() => apply(false)}>替换选中(原文件进回收区)</Btn>
            <Btn kind="danger" icon="trash" disabled={busy || pick.size === 0} onClick={() => apply(true)}>替换并永久删除原文件</Btn>
          </div>
        </Row>
        {(counts.failed ?? 0) > 0 && (
          <Row icon="alert" block title={`失败 ${counts.failed} 个 —— 主要原因`}
               desc={<div style={{ display: "grid", gap: 2, marginTop: 4 }}>{reasons.map((x) => <div key={x.reason}><b>{x.count}</b> × {x.reason}</div>)}</div>}>
            <div className="btn-group">
              <Btn icon="refresh" disabled={busy} onClick={retryFailed}>重试全部失败项</Btn>
              <Btn icon="search" onClick={() => setOnlyFailed((v) => !v)}>{onlyFailed ? "显示全部" : "只看失败的"}</Btn>
            </div>
          </Row>
        )}
        {jobs.length === 0 && <Row icon="check" title="队列是空的" />}
        {jobs.map((j) => {
          const [label, tone] = STATE_TEXT[j.state] ?? [j.state, "gray" as const];
          return (
            <div key={j.id} style={{ display: "grid", gridTemplateColumns: "24px 1fr 130px 210px 200px", gap: 12, alignItems: "center", padding: "8px 20px", borderTop: "1px solid var(--border)" }}>
              {j.state === "done" ? <input type="checkbox" checked={pick.has(j.id)} onChange={() => togglePick(j.id)} /> : <span />}
              <span style={{ minWidth: 0 }}>
                <div style={{ overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>{j.name || `媒体 ${j.mediaId}`}</div>
                <div style={{ color: "var(--text-2)", fontSize: 12, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>{j.root} · {j.dir || "(根目录)"} · {j.codec === "h265" ? "H.265" : "H.264"} {j.kbps} kbps</div>
                {j.err && <div style={{ color: j.err.startsWith("⏸") ? "var(--gray, #8c8c8c)" : "var(--red)", fontSize: 12 }}>{j.err}</div>}
              </span>
              <span><Badge tone={tone}>{label}</Badge></span>
              <span>
                {j.state === "running" && <Progress value={Math.round(j.progress * 100)} max={100} />}
                {j.state === "running" && <div style={{ color: "var(--text-2)", fontSize: 12 }}>{Math.round(j.progress * 100)}%</div>}
                {(j.state === "done" || j.state === "applied") && <span>{fmtBytes(j.origSize)} → {fmtBytes(j.newSize)} <span style={{ color: "var(--green)" }}>(省 {j.origSize ? Math.round(((j.origSize - j.newSize) * 100) / j.origSize) : 0}%)</span></span>}
              </span>
              <span className="btn-group">
                {j.path && <Btn icon="folderopen" onClick={async () => { try { await bridge.revealPath(j.path!); } catch (e) { toast(String(e), "err"); } }}>定位</Btn>}
                {(j.state === "queued" || j.state === "running") && <Btn onClick={async () => { await act("cancel", [j.id]); load(); }}>取消</Btn>}
                {["done", "failed", "cancelled"].includes(j.state) && <Btn onClick={async () => { await act("remove", [j.id]); load(); }}>移除</Btn>}
              </span>
            </div>
          );
        })}
      </Card>
    </>
  );
}

// ------------------------------------------------------------------ 回收区

function Trash() {
  const { toast } = useApp();
  const [list, setList] = useState<TrashBatch[]>([]);
  const [busy, setBusy] = useState(false);
  const load = useCallback(async () => {
    try { setList((await bridge.admin<{ batches: TrashBatch[] }>("GET", "/api/v1/admin/trash")).batches ?? []); } catch { /* 服务没运行 */ }
  }, []);
  useEffect(() => { load(); }, [load]);
  const total = list.reduce((s, b) => s + b.bytes, 0);
  const run = async (what: "restore" | "purge", bs: TrashBatch[]) => {
    const size = bs.reduce((s, b) => s + b.bytes, 0);
    if (what === "purge" && !window.confirm(`永久删除 ${bs.length} 个批次(${fmtBytes(size)})?\n\n这是真正的删除,文件无法恢复。`)) return;
    setBusy(true);
    try {
      let n = 0;
      for (const b of bs) {
        const r = await bridge.admin<any>("POST", `/api/v1/admin/trash/${what}`, { rootId: b.rootId, batches: [b.batch] }, 120000);
        n += what === "restore" ? r.restored : r.purged;
      }
      toast(what === "restore" ? `已还原 ${n} 个文件` : `已永久删除 ${n} 个批次`);
    } catch (e) { toast(String(e), "err"); }
    setBusy(false); load();
  };
  return (
    <Card icon="trash" title="回收区" count={list.length}
          extra={<Btn kind="danger" icon="trash" disabled={busy || list.length === 0} onClick={() => run("purge", list)}>清空全部({fmtBytes(total)})</Btn>}>
      <Row icon="info" title="被移进回收区的文件" desc="删除重复文件、用压缩版替换原文件时,原文件不会被直接删除,而是移到各盘的 .mediahub\trash 里。在这里可以还原;确认没问题后再永久删除,才会真正释放磁盘空间。" />
      {list.length === 0 && <Row icon="check" title="回收区是空的" />}
      {list.map((b) => (
        <Row key={b.rootId + b.batch} icon="folder" title={`${b.root} · ${b.batch}`}
             desc={`${b.reason || "—"} · ${b.files} 个文件 · ${fmtBytes(b.bytes)} · ${fmtTime(b.created)}`}>
          <div className="btn-group">
            <Btn icon="refresh" disabled={busy} onClick={() => run("restore", [b])}>还原</Btn>
            <Btn kind="danger" icon="trash" disabled={busy} onClick={() => run("purge", [b])}>永久删除</Btn>
          </div>
        </Row>
      ))}
    </Card>
  );
}

export function Optimize({ tab }: { tab: string }) {
  if (tab === "视频压缩") return <Compress />;
  if (tab === "回收区") return <Trash />;
  return <Dedupe />;
}
