import { openUrl } from "@tauri-apps/plugin-opener";
import { useCallback, useEffect, useState, type ReactNode } from "react";
import { bridge } from "../bridge";
import { Badge, Btn, Card, Row } from "../components";
import { Backup } from "./Backup";
import { useApp } from "../store";
import type { UpdateStatus } from "../types";
import changelogText from "../changelog.md?raw";

const STATE_TEXT: Record<string, [string, "gray" | "green" | "blue" | "orange" | "red"]> = {
  uptodate: ["已是最新", "green"], available: ["有新版本", "orange"], checking: ["检查中", "blue"], downloading: ["下载中", "blue"],
  installing: ["安装中", "blue"], error: ["出错", "red"], idle: ["未检查", "gray"], disabled: ["未启用", "gray"], offline: ["服务未运行", "gray"],
};

/** 软件更新:服务端每 10 分钟自动检查 GitHub Releases,发现新版本后自动下载、安装并重启服务;这里显示状态,也可以手动检查 / 立即更新。 */
function Update() {
  const { toast } = useApp();
  const [st, setSt] = useState<UpdateStatus | null>(null);
  const load = useCallback(async () => { try { setSt(await bridge.updateStatus()); } catch { /* 服务没运行 */ } }, []);
  useEffect(() => {
    load();
    const t = setInterval(load, 2500);
    return () => clearInterval(t);
  }, [load]);
  const act = async (kind: "check" | "apply") => {
    try { await bridge.updateAction(kind); toast(kind === "check" ? "已开始检查" : "已开始更新,完成后服务会自动重启"); setTimeout(load, 600); }
    catch (e) { toast(String(e), "err"); }
  };
  const state = st?.state ?? "offline";
  const [label, tone] = STATE_TEXT[state] ?? [state, "gray"];
  const busy = state === "checking" || state === "downloading" || state === "installing";
  return (
    <Card icon="refresh" title="软件更新">
      <Row icon="server" title="服务端版本" desc={<>
        当前 v{st?.current ?? "?"}{st?.latest && st.latest !== st.current ? <> · 最新 v{st.latest}</> : null}
        {st?.checkedAt && !st.checkedAt.startsWith("0001") ? <> · 上次检查 {new Date(st.checkedAt).toLocaleTimeString("zh-CN", { hour12: false })}</> : null}
        {st?.message ? <> · {st.message}</> : null}
        {state === "downloading" && <> · {st?.progress ?? 0}%</>}
      </>}>
        <div className="btn-group">
          <Badge tone={tone}>{label}</Badge>
          <Btn icon="refresh" disabled={busy || state === "offline"} onClick={() => act("check")}>检查更新</Btn>
          <Btn kind="primary" icon="zap" disabled={busy || state !== "available" || st?.installable === false} onClick={() => act("apply")}>立即更新</Btn>
        </div>
      </Row>
      <Row icon="clock" title="自动更新" desc={`服务端每 10 分钟检查一次 GitHub Releases(ack528/MediaHub),发现新版本后自动下载、校验、安装并重启服务;新版本起不来会自动回退。${st?.auto === false ? "当前配置里关闭了自动更新(config.json → update.enabled)。" : ""}${st?.installable === false ? "当前不是便携版目录布局,只检查、不自动安装。" : ""}`} />
      <Row icon="drive" title="手机端安装包" desc={!st?.app || st.app.state === "disabled"
        ? "没有缓存(config.json → update.cacheApp 为 false)。手机检查更新时只能自己连 GitHub。"
        : st.app.ready ? `已缓存 v${st.app.latest}${st.app.size ? `(${(st.app.size / 1048576).toFixed(1)} MB)` : ""}:手机「检查更新」直接从这台电脑下载,走局域网 / 内网穿透,不用连 GitHub。`
        : st.app.state === "downloading" ? `正在后台下载 v${st.app.latest ?? ""} · ${st.app.progress ?? 0}%(下好之前手机会自己从 GitHub 下载)`
        : `还没有缓存${st.app.message ? ":" + st.app.message : ""}。检查更新时会顺便下载最新的手机端安装包。`} />
      {st?.notes && state === "available" && <Row icon="filetext" title="更新内容" desc={<span style={{ whiteSpace: "pre-wrap" }}>{st.notes}</span>} />}
    </Card>
  );
}

interface Src { name: string; url: string; ok: boolean; ms: number; code?: number; err?: string }

/** 下载源与 DNS:服务端更新时同时探测直连和内置镜像(谁先响应用谁);这里现在就测一遍,看每个源的延迟和是否真的能下载。 */
function Mirrors() {
  const { toast } = useApp();
  const [res, setRes] = useState<{ sources: Src[]; doh: string[] } | null>(null);
  const [busy, setBusy] = useState(false);
  const test = async () => {
    setBusy(true);
    try { setRes(await bridge.admin("GET", "/api/v1/admin/update/mirrors", undefined, 30000)); }
    catch (e) { toast(String(e), "err"); }
    setBusy(false);
  };
  const tone = (s: Src): "green" | "orange" | "red" => (!s.ok ? "red" : s.ms < 600 ? "green" : s.ms < 1800 ? "orange" : "red");
  return (
    <Card icon="globe" title="更新下载源与 DNS" extra={<Btn icon="refresh" disabled={busy} onClick={test}>{busy ? "测试中…" : "测试全部下载源"}</Btn>}>
      <Row icon="link" title="直连 + 8 个内置镜像"
           desc="检查更新 / 下载更新包时同时探测直连和所有镜像,谁先响应用谁,失败再换下一个;镜像只搬运文件,更新包的 SHA-256 必须和直连取到的校验和一致才会安装。可在 config.json 的 update.mirrors 里增删镜像(填 [] 表示不用镜像),update.mirror 是你自己指定、排最前面的镜像。" />
      <Row icon="server" title="域名解析" desc={`先并发问 DoH 服务器(${res?.doh?.join("、") ?? "阿里 DNS、DNSPod、Cloudflare、Google、AdGuard、dns.sb"}),哪个先答用哪个,系统 DNS 兜底;同一个域名有多个 IP 时选 TCP 连接最快的。`} />
      {res?.sources.map((s) => (
        <Row key={s.name} icon="globe" title={s.name} desc={s.ok ? `HTTP ${s.code ?? 200}` : s.err || "不通"}>
          <Badge tone={tone(s)}>{s.ok ? `${s.ms} ms` : "不通"}</Badge>
        </Row>
      ))}
    </Card>
  );
}

/** 解析随程序打包的 CHANGELOG.md:取「## 服务端 / 管理程序」下每个「### 版本」及其「- 条目」(新 → 旧)。 */
interface LogItem { text: string; sub: boolean }
function parseChangelog(text: string, section: string): { title: string; items: LogItem[] }[] {
  const out: { title: string; items: LogItem[] }[] = [];
  let inSection = false;
  for (const raw of text.split(/\r?\n/)) {
    const l = raw.trimEnd();
    if (l.startsWith("## ")) inSection = l.slice(3).includes(section);
    else if (inSection && l.startsWith("### ")) out.push({ title: l.slice(4).trim(), items: [] });
    else if (inSection && out.length) {
      // 顶层「- 」和缩进的子条目「  - 」都认(以前缩进的子条目整行丢失)
      const m = /^(\s*)[-*]\s+(.*)$/.exec(l);
      if (m) out[out.length - 1].items.push({ text: m[2].trim(), sub: m[1].length >= 2 });
    }
  }
  return out;
}

/** 行内标记:**加粗** 和 `代码` 渲染成真正的格式,不再把星号 / 反引号原样显示出来。 */
function inlineMd(s: string): ReactNode[] {
  const parts: ReactNode[] = [];
  const re = /\*\*(.+?)\*\*|`([^`]+)`/g;
  let last = 0, m: RegExpExecArray | null, k = 0;
  while ((m = re.exec(s))) {
    if (m.index > last) parts.push(s.slice(last, m.index));
    parts.push(m[1] !== undefined ? <strong key={k++}>{m[1]}</strong> : <code key={k++}>{m[2]}</code>);
    last = m.index + m[0].length;
  }
  if (last < s.length) parts.push(s.slice(last));
  return parts;
}

/** 更新说明:当前版本的更新内容 + 全部历史版本(点开看)。 */
function Changelog() {
  const [all, setAll] = useState(false);
  const entries = parseChangelog(changelogText, "服务端");
  const shown = all ? entries : entries.slice(0, 1);
  return (
    <Card icon="filetext" title="更新说明" extra={entries.length > 1 ? <Btn onClick={() => setAll(!all)}>{all ? "收起历史版本" : `查看历史版本(${entries.length - 1})`}</Btn> : undefined}>
      {entries.length === 0 && <Row icon="info" title="没有找到更新说明" />}
      {shown.map((e, i) => (
        <Row key={e.title} icon="tag" title={e.title + (i === 0 && !all ? "(当前版本)" : "")}
             desc={<ul style={{ margin: "4px 0 0", paddingLeft: 18 }}>{e.items.map((t, n) => <li key={n} style={t.sub ? { marginLeft: 16, listStyleType: "circle" } : undefined}>{inlineMd(t.text)}</li>)}</ul>} />
      ))}
    </Card>
  );
}

export function About() {
  const { env, config, svc, toast } = useApp();
  const open = (p?: string) => p && bridge.openPath(p);
  return (
    <>
    <Update />
    <Mirrors />
    <Backup />
    <Changelog />
    <Card icon="info" title="关于 MediaHub">
      <Row icon="server" title="MediaHub 管理程序" desc={`v${env?.appVersion ?? "?"} · 服务端 ${svc?.info?.version ?? "未运行"} · Tauri 2`} />
      <Row icon="globe" title="项目主页" desc="github.com/ack528/MediaHub"><Btn icon="link" onClick={() => openUrl("https://github.com/ack528/MediaHub").catch(e => toast(String(e), "err"))}>在浏览器打开</Btn></Row>
      <Row icon="folder" title="安装目录" desc={env?.root ?? "—"}><Btn icon="folderopen" onClick={() => open(env?.root)}>打开</Btn></Row>
      <Row icon="filetext" title="配置文件" desc={env?.configPath ?? "—"}><Btn icon="folderopen" onClick={() => open(env?.configPath?.replace(/[\\/][^\\/]+$/, ""))}>打开所在文件夹</Btn></Row>
      <Row icon="database" title="数据目录" desc={config.dataDir}><Btn icon="folderopen" onClick={() => open(config.dataDir)}>打开</Btn></Row>
      <Row icon="zap" title="服务程序" desc={env?.serverExe ?? "—"} />
      <Row icon="shield" title="使用说明" desc="本程序只管理服务端:修改配置、启动/停止服务、管理账号、查看日志。关闭窗口会最小化到托盘,服务继续运行;手机访问的是服务端本身。" />
    </Card>
    </>
  );
}
