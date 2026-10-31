import { Button, Chip, Disclosure, ProgressBar, Spinner, ToggleButton, ToggleButtonGroup, toast } from "@heroui/react";
import { ArrowDownToLine, ArrowsRotateRight, Copy, LogoGithub } from "@gravity-ui/icons";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { call, errorText, on } from "../../lib/bridge";
import { useApp } from "../../lib/app";
import { formatSize } from "../../lib/media";
import { ListGroup, ListRow } from "../../components/List";
import { SETTING_PAGES } from "./schema";
import { ActionRow, ChoiceField, InfoRow, Note, SchemaRows } from "./controls";

// ---------------------------------------------------------------- 网络

export function NetworkPage() {
  const { session, route } = useApp();
  const [probing, setProbing] = useState(false);
  const line = session.local ? "本地媒体(不连服务器)" : route ? `内网直连:${route.replace(/^https?:\/\//, "")}` : `服务器地址:${session.baseUrl.replace(/^https?:\/\//, "") || "未连接"}`;
  return (
    <>
      <ListGroup title="连接线路">
        <InfoRow title="当前线路" value={line} />
        <ListRow title="重新检测线路" description="马上重新探测服务器的内网 / IPv6 地址" end={probing ? <Spinner size="sm" /> : undefined} chevron={false}
          onPress={async () => {
            setProbing(true);
            try { const r = await call<string>("route.probe"); toast(r ? `已改走内网直连 ${r.replace(/^https?:\/\//, "")}` : "没有更快的内网线路,使用服务器地址"); }
            catch (e) { toast.danger(errorText(e)); } finally { setProbing(false); }
          }} />
      </ListGroup>
      <SchemaRows rows={SETTING_PAGES.network} />
      <Note>超时设置在重启应用后生效。</Note>
      <ListGroup><ActionRow title="立即重启应用" description="让超时设置马上生效" action={() => call("app.restart")} /></ListGroup>
    </>
  );
}

// ---------------------------------------------------------------- 网络测速

interface Flow { bytes: number; ms: number; avg: number; stable: number; peak: number }
interface Lat { avg: number; min: number; max: number; jitter: number; lost: number; samples: number }
const mbps = (m: number) => `${m.toFixed(1)} Mbps(${(m / 8).toFixed(2)} MB/s)`;

export function SpeedTestPage() {
  const { settings: s, update, session } = useApp();
  const secs = Math.max(15, s.speedSeconds);
  const [phase, setPhase] = useState("");
  const [latDone, setLatDone] = useState(0);
  const [lat, setLat] = useState<Lat | null>(null);
  const [live, setLive] = useState({ elapsed: 0, now: 0, avg: 0 });
  const [down, setDown] = useState<Flow | null>(null);
  const [up, setUp] = useState<Flow | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const running = ["latency", "download", "upload", "latencyDone", "downloadDone"].includes(phase);

  useEffect(() => on("speed", (d: any) => {
    switch (d.phase) {
      case "latency": setPhase("latency"); if (d.done) setLatDone(d.done); break;
      case "latencyDone": setLat(d); setPhase("download"); break;
      case "download": case "upload": setPhase(d.phase); setLive({ elapsed: d.elapsed, now: d.now, avg: d.avg }); break;
      case "downloadDone": setDown(d.result); setPhase("upload"); setLive({ elapsed: 0, now: 0, avg: 0 }); break;
      case "uploadDone": setUp(d.result); break;
      case "done": case "stopped": setPhase(d.phase); break;
      case "error": setErr(d.message); setPhase("error"); break;
    }
  }), []);
  useEffect(() => () => { call("speed.stop").catch(() => {}); }, []);

  if (session.local) return <Note>本地媒体模式没有服务器,不能测速。请先连接服务器。</Note>;

  const start = () => {
    if (running) { call("speed.stop"); return; }
    setErr(null); setLat(null); setDown(null); setUp(null); setLatDone(0); setLive({ elapsed: 0, now: 0, avg: 0 });
    setPhase("latency");
    call("speed.start", { seconds: secs, threads: s.speedThreads }).catch((e) => { setErr(errorText(e)); setPhase("error"); });
  };

  return (
    <>
      <ListGroup title="服务器"><InfoRow title="地址" value={session.baseUrl.replace(/^https?:\/\//, "")} /></ListGroup>
      <ListGroup title="测速设置">
        <ChoiceField title="测速时长" desc="下载和上传各测这么久;前几秒是 TCP 慢启动,「稳定速度」会去掉前 2 秒再算"
          options={["15 秒(最短)", "20 秒", "30 秒", "60 秒"]} selected={[15, 20, 30, 60].indexOf(secs)} onSelect={(i) => update({ speedSeconds: [15, 20, 30, 60][i] })} />
        <ChoiceField title="连接数" desc="播放视频通常是 1 ~ 2 个连接;经云中继 / 高延迟链路时,多连接能测出最大带宽"
          options={["单连接(最接近播放视频)", "4 连接(测最大带宽)"]} selected={s.speedThreads === 4 ? 1 : 0} onSelect={(i) => update({ speedThreads: i ? 4 : 1 })} />
      </ListGroup>
      <div className="px-4">
        <Button fullWidth size="lg" variant={running ? "danger-soft" : "primary"} onPress={start}>
          {running ? `停止(${phase === "latency" ? "延迟" : phase === "download" ? "下载" : "上传"})` : `开始测速(约 ${secs * 2 + 5} 秒)`}
        </Button>
        {err && <p className="mt-2 text-sm text-danger">{err}</p>}
      </div>
      {(lat || phase === "latency") && (
        <ListGroup title="延迟">
          {lat ? (
            <>
              <InfoRow title="平均 / 最小 / 最大" value={`${lat.avg.toFixed(0)} / ${lat.min} / ${lat.max} ms`} />
              <InfoRow title="抖动" value={`${lat.jitter.toFixed(1)} ms(越小越稳)`} />
              <InfoRow title="丢包" value={`${lat.lost} / ${lat.samples + lat.lost}`} />
            </>
          ) : <InfoRow title="正在测量…" value={`${latDone} / 12`} />}
        </ListGroup>
      )}
      {(phase === "download" || down) && <SpeedCard title="下载(服务器 → 手机)" live={phase === "download"} v={live} secs={secs} flow={down} />}
      {(phase === "upload" || up) && <SpeedCard title="上传(手机 → 服务器)" live={phase === "upload"} v={live} secs={secs} flow={up} />}
      <Note>测的是手机到服务器整条链路(含 Wi-Fi、路由器、云中继)的速度。视频能不能流畅播放主要看下载的「稳定速度」是否大于视频码率(1080p 约 8 ~ 20 Mbps,转码后 1.5 ~ 6 Mbps)。</Note>
    </>
  );
}

function SpeedCard({ title, live, v, secs, flow }: { title: string; live: boolean; v: { elapsed: number; now: number; avg: number }; secs: number; flow: Flow | null }) {
  return (
    <ListGroup title={title}>
      <div className="px-3 py-3">
        {live || !flow ? (
          <>
            <p className="text-3xl font-semibold text-accent tabular-nums">{v.now.toFixed(1)} <span className="text-base font-normal text-muted">Mbps</span></p>
            <p className="mt-1 text-xs text-muted tabular-nums">{(v.now / 8).toFixed(2)} MB/s · 平均 {v.avg.toFixed(1)} Mbps · 已测 {(v.elapsed / 1000).toFixed(0)} / {secs} 秒</p>
            <ProgressBar aria-label="测速进度" value={Math.min(100, (v.elapsed / (secs * 10)))} className="mt-3">
              <ProgressBar.Track><ProgressBar.Fill /></ProgressBar.Track>
            </ProgressBar>
          </>
        ) : (
          <>
            <p className="text-lg font-semibold text-accent">稳定速度 {mbps(flow.stable)}</p>
            <p className="mt-1 text-sm tabular-nums">全程平均 {mbps(flow.avg)}</p>
            <p className="text-sm tabular-nums">峰值 {mbps(flow.peak)}</p>
            <p className="mt-1 text-xs text-muted">共 {formatSize(flow.bytes)} · {(flow.ms / 1000).toFixed(1)} 秒</p>
          </>
        )}
      </div>
    </ListGroup>
  );
}

// ---------------------------------------------------------------- 存储与缓存

export function StoragePage() {
  const { session, update } = useApp();
  const [info, setInfo] = useState<{ images: number; playback: number } | null>(null);
  const reload = useCallback(() => { call("storage.info").then(setInfo).catch(() => {}); }, []);
  useEffect(reload, [reload]);
  const done = (msg: string) => () => { toast.success(msg); reload(); };
  return (
    <>
      <ListGroup title="缓存">
        <ActionRow title="清除图片缓存" description={info ? `当前占用 ${formatSize(info.images)}` : "计算中…"} confirm="删除手机里已缓存的缩略图和图片?之后需要重新从服务器下载。"
          action={() => call("storage.clearImages").then(done("图片缓存已清除"))} />
      </ListGroup>
      <ListGroup title="记录">
        <ActionRow title="清除浏览位置记录" description="所有文件夹下次打开都从开头开始(同时清除服务器上同步的记录)" confirm="清除所有文件夹的浏览位置?"
          action={() => call("storage.clearViews").then(done("浏览位置已清除"))} />
        <ActionRow title="清除播放进度记录" description={`所有视频下次都从头播放(同时清除服务器上的记录)${info ? ` · 本机 ${info.playback} 条` : ""}`} confirm="清除所有视频的播放进度?"
          action={() => call("storage.clearPlayback").then(done("播放进度已清除"))} />
      </ListGroup>
      {!session.local && (
        <ListGroup title="随机浏览">
          <ActionRow title="重新生成随机序列" description="让服务器清除所有历史随机序列并重新生成" confirm="清除服务器上所有的随机序列并重新生成?"
            action={() => call("random.reset").then(() => { update({ randomSeed: 0 }); toast.success("已让服务器重新生成随机序列"); })} />
        </ListGroup>
      )}
      <ListGroup title="设置">
        <ActionRow danger title="恢复默认设置" description="不影响登录状态和浏览记录" confirm="把所有设置恢复为默认值?"
          action={() => call("settings.reset").then(() => toast.success("已恢复默认设置"))} />
      </ListGroup>
    </>
  );
}

// ---------------------------------------------------------------- 日志

export function LogPage({ onView }: { onView: () => void }) {
  const [info, setInfo] = useState<{ size: number; crashes: string[] } | null>(null);
  const reload = useCallback(() => { call("log.info").then(setInfo).catch(() => {}); }, []);
  useEffect(reload, [reload]);
  return (
    <>
      <SchemaRows rows={SETTING_PAGES.log} />
      <ListGroup>
        <ListRow title="查看日志" description={info ? `已占用 ${formatSize(info.size)}` : undefined} onPress={onView} />
      </ListGroup>
      <ListGroup title="发给开发者">
        <ActionRow title="分享日志文件" description="打包成 zip:设备信息、应用日志、崩溃报告、系统日志" action={() => call("log.share")} />
        <ActionRow title="复制最近日志" description="设备信息 + 最近一次崩溃 + 最近的日志,可直接粘贴" action={() => call<number>("log.copy").then((n) => toast.success(`已复制 ${n} 字`))} />
      </ListGroup>
      <ListGroup title="崩溃报告">
        <InfoRow title="崩溃记录" value={!info ? "…" : info.crashes.length ? `${info.crashes.length} 份,最近一次:${info.crashes[info.crashes.length - 1]}` : "无"} />
      </ListGroup>
      <ListGroup title="清理">
        <ActionRow danger title="清除日志" description="删除应用日志和崩溃报告" confirm="清除所有日志和崩溃报告?" action={() => call("log.clear").then(() => { toast.success("日志已清除"); reload(); })} />
      </ListGroup>
    </>
  );
}

export function LogViewPage() {
  const [text, setText] = useState<string | null>(null);
  const [filter, setFilter] = useState<"all" | "warn">("all");
  const end = useRef<HTMLDivElement>(null);
  const load = useCallback(() => { setText(null); call<string>("log.tail", { max: 300_000 }).then(setText).catch((e) => setText(errorText(e))); }, []);
  useEffect(load, [load]);
  const lines = useMemo(() => {
    const all = (text ?? "").split("\n");
    return filter === "all" ? all : all.filter((l) => l.includes(" W/") || l.includes(" E/") || l.startsWith(" ") || l.startsWith("\tat "));
  }, [text, filter]);
  useEffect(() => { end.current?.scrollIntoView(); }, [lines]);
  return (
    <>
      <div className="flex items-center gap-2 px-4">
        <ToggleButtonGroup size="sm" selectionMode="single" disallowEmptySelection selectedKeys={new Set([filter])} onSelectionChange={(k) => setFilter([...k][0] as "all" | "warn")}>
          <ToggleButton id="all">全部</ToggleButton>
          <ToggleButton id="warn"><ToggleButtonGroup.Separator />仅警告和错误</ToggleButton>
        </ToggleButtonGroup>
        <Button size="sm" variant="ghost" isIconOnly aria-label="刷新" onPress={load}><ArrowsRotateRight className="size-4" /></Button>
        <span className="ml-auto text-xs text-muted">{lines.length} 行</span>
      </div>
      {text == null ? <div className="grid h-40 place-items-center"><Spinner /></div> : (
        <pre className="selectable mx-4 overflow-x-auto rounded-2xl bg-surface p-3 font-mono text-[10.5px] leading-[1.45] shadow-surface">
          {lines.map((l, i) => <div key={i} className={l.includes(" E/") ? "text-danger" : l.includes(" W/") ? "text-warning" : ""}>{l || " "}</div>)}
          <div ref={end} />
        </pre>
      )}
    </>
  );
}

// ---------------------------------------------------------------- 关于 / 更新

interface Check { current: string; version?: string; newer?: boolean; notes?: string; fromServer?: boolean; serverProgress?: number }
interface Pending { pending: string; downloading: boolean; progress: number; version: string; error: string }
interface Source { name: string; prefix: string; ok: boolean; ms: number; note: string; testing: boolean }

export function AboutPage() {
  const { boot, session, settings: s, update } = useApp();
  const [server, setServer] = useState("读取中…");
  const [check, setCheck] = useState<Check | null>(null);
  const [checking, setChecking] = useState(false);
  const [pending, setPending] = useState<Pending | null>(null);
  const [changelog, setChangelog] = useState<{ version: string; items: string[] }[]>([]);
  const [sources, setSources] = useState<Source[] | null>(null);

  useEffect(() => {
    if (session.local || !session.baseUrl) setServer("未连接");
    else call<{ name: string; version: string }>("session.info", { url: session.baseUrl }).then((i) => setServer(`${i.name} ${i.version}`)).catch(() => setServer("无法连接"));
    call("app.changelog").then(setChangelog).catch(() => {});
    const refresh = () => call<Pending>("update.pending").then(setPending).catch(() => {});
    refresh();
    const offs = [
      on("update", (d: { version: string; progress: number; error: string; done: boolean }) => {
        setPending((p) => ({ ...(p ?? { pending: "" }), downloading: d.progress >= 0, progress: d.progress, version: d.version, error: d.error } as Pending));
        if (d.done || d.progress < 0) refresh();
      }),
      on("updateSources", (l: Source[]) => setSources(l)),
    ];
    return () => offs.forEach((o) => o());
  }, [session.local, session.baseUrl]);

  const doCheck = async () => {
    setChecking(true);
    try { setCheck(await call<Check>("update.check")); }
    catch (e) { toast.danger(`检查失败:${errorText(e)}(GitHub 在手机网络下可能访问不了)`); }
    finally { setChecking(false); }
  };

  const install = () => call<boolean>("update.install").then((ok) => toast(ok ? "已调起安装器" : "请允许安装未知应用,返回后再次点安装")).catch((e) => toast.danger(errorText(e)));

  const sourceOptions = useMemo(() => [{ v: "", l: "自动(选最快的)" }, { v: "direct", l: "直连 GitHub" }, ...(sources ?? []).filter((x) => x.prefix && x.prefix !== "direct").map((x) => ({ v: x.prefix, l: x.name }))], [sources]);
  const checkText = !check ? `当前 v${boot.version} · 先问自己的服务器,同时从 GitHub 检查`
    : !check.version ? "最新发布里没有安装包"
    : check.newer ? `发现新版本 v${check.version}${check.fromServer ? "(服务器已下载好)" : check.serverProgress != null && check.serverProgress >= 0 ? `(服务器正在下载 ${check.serverProgress}%)` : ""}`
    : `已是最新版本(v${boot.version})`;

  return (
    <>
      <div className="flex flex-col items-center gap-1 py-4">
        <p className="text-xl font-semibold">本地浏览</p>
        <Chip variant="soft" color="accent">v{boot.version}</Chip>
      </div>
      <ListGroup>
        <InfoRow title="服务端" value={server} />
        <InfoRow title="系统" value={boot.android} />
        <InfoRow title="设备" value={boot.device} />
        <ListRow icon={<LogoGithub className="size-4" />} title="项目主页" description="github.com/ack528/MediaHub" onPress={() => call("app.open", { url: "https://github.com/ack528/MediaHub" })} />
      </ListGroup>

      <ListGroup title="软件更新">
        <ListRow title={checking ? "正在检查…" : "检查更新"} description={checkText} end={checking ? <Spinner size="sm" /> : undefined} chevron={false} onPress={checking ? undefined : doCheck} />
        {pending?.downloading && (
          <div className="px-3 py-2.5">
            <ProgressBar value={pending.progress} aria-label="下载进度">
              <div className="flex justify-between text-sm"><span>正在下载 v{pending.version}</span><ProgressBar.Output /></div>
              <ProgressBar.Track><ProgressBar.Fill /></ProgressBar.Track>
            </ProgressBar>
            <p className="mt-1 text-xs text-muted">离开此页面后会继续下载</p>
          </div>
        )}
        {pending?.error && <InfoRow title="下载状态" value={`${pending.error},已保留断点,可重试`} />}
        {pending?.pending && <ListRow icon={<ArrowDownToLine className="size-4" />} title={`安装已下载的 v${pending.pending}`} description="安装取消或失败后会保留,装上后自动清理" onPress={install} />}
        {check?.newer && pending?.pending !== check.version && !pending?.downloading && (
          <ListRow icon={<ArrowDownToLine className="size-4" />} title={`下载 v${check.version}`} description={`${check.fromServer ? "优先从服务器下载 · " : ""}支持断点续传,校验后保存在本地`}
            onPress={() => call("update.download").catch((e) => toast.danger(errorText(e)))} />
        )}
      </ListGroup>
      {check?.newer && check.notes && (
        <ListGroup title={`v${check.version} 更新内容`}>
          <p className="selectable px-3 py-2.5 text-sm leading-relaxed whitespace-pre-line text-muted">{check.notes}</p>
        </ListGroup>
      )}

      <ListGroup title="下载源" footer="检查更新 / 下载安装包时优先使用的源;自动 = 直连和内置镜像里实测最快的。">
        <ChoiceField title="优先使用" options={sourceOptions.map((o) => o.l)} selected={Math.max(0, sourceOptions.findIndex((o) => o.v === s.updateSource))}
          fallback={s.updateSource} onSelect={(i) => update({ updateSource: sourceOptions[i].v })} />
        <ListRow title="测试各下载源" description={sources ? undefined : "测一下直连和各个镜像现在的速度"} chevron={false}
          onPress={() => { setSources([]); call<Source[]>("update.sources").then(setSources).catch((e) => toast.danger(errorText(e))); }} />
        {sources?.map((x) => (
          <InfoRow key={x.prefix || x.name} title={x.name} value={x.testing ? "测试中…" : x.ok ? <span className={x.ms < 600 ? "text-success" : x.ms < 1800 ? "text-warning" : "text-danger"}>{x.ms} ms</span> : <span className="text-danger">{x.note || "不通"}</span>} />
        ))}
      </ListGroup>

      {changelog.length > 0 && (
        <section className="px-4">
          <h2 className="px-3 pb-1.5 text-xs font-medium text-muted">更新说明</h2>
          <div className="rounded-2xl bg-surface p-4 shadow-surface">
            <ChangeEntry e={changelog[0]} current />
            {changelog.length > 1 && (
              <Disclosure>
                <Disclosure.Heading>
                  <Disclosure.Trigger className="mt-3 flex w-full items-center justify-between text-sm text-accent">
                    查看历史版本({changelog.length - 1})<Disclosure.Indicator />
                  </Disclosure.Trigger>
                </Disclosure.Heading>
                <Disclosure.Content>
                  <Disclosure.Body className="flex flex-col gap-4 pt-3">
                    {changelog.slice(1).map((e) => <ChangeEntry key={e.version} e={e} />)}
                  </Disclosure.Body>
                </Disclosure.Content>
              </Disclosure>
            )}
          </div>
        </section>
      )}
      <div className="flex justify-center">
        <Button size="sm" variant="ghost" onPress={() => navigator.clipboard?.writeText(`本地浏览 v${boot.version} · ${boot.device} · ${boot.android}`).then(() => toast.success("已复制"))}>
          <Copy className="size-4" />复制版本信息
        </Button>
      </div>
    </>
  );
}

function ChangeEntry({ e, current }: { e: { version: string; items: string[] }; current?: boolean }) {
  return (
    <div>
      <p className="text-sm font-medium">{e.version}{current && <span className="ml-2 text-xs text-muted">当前版本</span>}</p>
      <ul className="mt-1.5 flex list-disc flex-col gap-1 pl-5 text-[13px] leading-relaxed text-muted">
        {e.items.map((l, i) => <li key={i}>{l}</li>)}
      </ul>
    </div>
  );
}
