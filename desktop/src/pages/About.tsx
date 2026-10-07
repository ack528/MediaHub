import { useCallback, useEffect, useState } from "react";
import { bridge } from "../bridge";
import { Badge, Btn, Card, Row } from "../components";
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
      {st?.notes && state === "available" && <Row icon="filetext" title="更新内容" desc={<span style={{ whiteSpace: "pre-wrap" }}>{st.notes}</span>} />}
    </Card>
  );
}

/** 解析随程序打包的 CHANGELOG.md:取「## 服务端 / 管理程序」下每个「### 版本」及其「- 条目」(新 → 旧)。 */
function parseChangelog(text: string, section: string): { title: string; items: string[] }[] {
  const out: { title: string; items: string[] }[] = [];
  let inSection = false;
  for (const raw of text.split(/\r?\n/)) {
    const l = raw.trimEnd();
    if (l.startsWith("## ")) inSection = l.slice(3).includes(section);
    else if (inSection && l.startsWith("### ")) out.push({ title: l.slice(4).trim(), items: [] });
    else if (inSection && l.startsWith("- ") && out.length) out[out.length - 1].items.push(l.slice(2).trim());
  }
  return out;
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
             desc={<ul style={{ margin: "4px 0 0", paddingLeft: 18 }}>{e.items.map((t) => <li key={t}>{t}</li>)}</ul>} />
      ))}
    </Card>
  );
}

export function About() {
  const { env, config, svc } = useApp();
  const open = (p?: string) => p && bridge.openPath(p);
  return (
    <>
    <Update />
    <Changelog />
    <Card icon="info" title="关于 MediaHub">
      <Row icon="server" title="MediaHub 管理程序" desc={`v${env?.appVersion ?? "?"} · 服务端 ${svc?.info?.version ?? "未运行"} · Tauri 2`} />
      <Row icon="folder" title="安装目录" desc={env?.root ?? "—"}><Btn icon="folderopen" onClick={() => open(env?.root)}>打开</Btn></Row>
      <Row icon="filetext" title="配置文件" desc={env?.configPath ?? "—"}><Btn icon="folderopen" onClick={() => open(env?.configPath?.replace(/[\\/][^\\/]+$/, ""))}>打开所在文件夹</Btn></Row>
      <Row icon="database" title="数据目录" desc={config.dataDir}><Btn icon="folderopen" onClick={() => open(config.dataDir)}>打开</Btn></Row>
      <Row icon="zap" title="服务程序" desc={env?.serverExe ?? "—"} />
      <Row icon="shield" title="使用说明" desc="本程序只管理服务端:修改配置、启动/停止服务、管理账号、查看日志。关闭窗口会最小化到托盘,服务继续运行;手机访问的是服务端本身。" />
    </Card>
    </>
  );
}
