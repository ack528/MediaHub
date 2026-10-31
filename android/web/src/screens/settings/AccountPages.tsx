import { Accordion, Alert, AlertDialog, Button, Chip, Input, Label, ListBox, Select, Switch, TextArea, TextField, toast } from "@heroui/react";
import { Plus } from "@gravity-ui/icons";
import { useCallback, useEffect, useState } from "react";
import { api, BridgeError, call, errorText } from "../../lib/bridge";
import { useApp } from "../../lib/app";
import { useBackHandler } from "../../lib/nav";
import type { ServerEntry } from "../../lib/types";
import { ListGroup, ListRow, StateView } from "../../components/List";
import { ActionRow, InfoRow, Note } from "./controls";

/** 服务器管理:已保存的服务器(展开:切换 / 重命名 / 退出登录 / 删除)、当前服务器信息。 */
export function AccountPage() {
  const { session } = useApp();
  const cur = session.servers.find((s) => s.id === session.activeId);
  return (
    <>
      <section className="px-4">
        <h2 className="px-3 pb-1.5 text-xs font-medium text-muted">已保存的服务器</h2>
        <Accordion variant="surface" className="rounded-2xl shadow-surface">
          {session.servers.map((e) => <ServerItem key={e.id} e={e} active={e.id === session.activeId} />)}
        </Accordion>
      </section>
      <ListGroup>
        <ActionRow icon={<Plus className="size-4" />} title="添加服务器" description="连接另一台服务器;现有的登录都保留,随时可以切换回来" action={() => call("session.deactivate")} />
      </ListGroup>
      {session.local ? (
        <ListGroup title="当前模式"><InfoRow title="模式" value="本地媒体(读取这部手机上的照片和视频)" /></ListGroup>
      ) : cur && (
        <>
          <ListGroup title="当前服务器">
            <InfoRow title="地址" value={session.baseUrl} />
            <InfoRow title="传输" value={session.baseUrl.startsWith("https://") ? "已加密(TLS)" : "未加密(HTTP),建议在服务端开启加密传输"} />
            {session.baseUrl.startsWith("https://") && <InfoRow title="证书指纹" value={<code className="font-mono text-xs">{session.pin || "未信任"}</code>} />}
          </ListGroup>
          <ListGroup title="账号">
            <ActionRow danger title="退出登录" description="清除这台服务器在这部手机上的登录令牌,服务器记录会保留" confirm="退出登录?" action={() => call("session.logout", { id: session.activeId })} />
          </ListGroup>
        </>
      )}
    </>
  );
}

function ServerItem({ e, active }: { e: ServerEntry; active: boolean }) {
  const [renaming, setRenaming] = useState(false);
  const [name, setName] = useState(e.name);
  const [del, setDel] = useState(false);
  const status = e.kind === "local" ? "本地媒体" : e.loggedIn ? `已登录${e.user ? `(${e.user})` : ""}${e.baseUrl.startsWith("https://") ? " · 加密" : " · 未加密"}` : "需要重新登录";
  const act = (m: string, args: Record<string, unknown> = {}) => call(m, { id: e.id, ...args }).catch((x) => toast.danger(errorText(x)));
  return (
    <Accordion.Item id={e.id}>
      <Accordion.Heading>
        <Accordion.Trigger className="gap-3">
          <span className="min-w-0 flex-1 text-left">
            <span className="flex items-center gap-2">
              <span className="truncate text-[15px] font-medium">{e.name}</span>
              {active && <Chip size="sm" color="accent" variant="soft">当前</Chip>}
            </span>
            <span className={`block truncate text-xs ${e.loggedIn ? "text-muted" : "text-warning"}`}>{e.host} · {status}</span>
          </span>
          <Accordion.Indicator />
        </Accordion.Trigger>
      </Accordion.Heading>
      <Accordion.Panel>
        <Accordion.Body className="flex flex-col gap-2">
          {renaming ? (
            <div className="flex flex-col gap-2">
              <TextField value={name} onChange={setName} autoFocus>
                <Label>服务器名称</Label>
                <Input onKeyDown={(k) => { if (k.key === "Enter") { act("session.rename", { name }); setRenaming(false); } }} />
              </TextField>
              <div className="flex gap-2">
                <Button size="sm" onPress={() => { act("session.rename", { name }); setRenaming(false); }}>保存</Button>
                <Button size="sm" variant="tertiary" onPress={() => { setName(e.name); setRenaming(false); }}>取消</Button>
              </div>
            </div>
          ) : (
            <div className="flex flex-wrap gap-2">
              {!active && <Button size="sm" onPress={() => act("session.switch")}>{e.loggedIn ? "切换到这里" : "去登录"}</Button>}
              <Button size="sm" variant="secondary" onPress={() => setRenaming(true)}>重命名</Button>
              {e.loggedIn && e.kind !== "local" && <Button size="sm" variant="secondary" onPress={() => act("session.logout")}>退出登录</Button>}
              <Button size="sm" variant="danger-soft" onPress={() => setDel(true)}>删除</Button>
            </div>
          )}
        </Accordion.Body>
      </Accordion.Panel>
      <AlertDialog.Backdrop isOpen={del} onOpenChange={setDel}>
        <AlertDialog.Container placement="center">
          <AlertDialog.Dialog className="sm:max-w-sm">
            <AlertDialog.Header><AlertDialog.Icon status="danger" /><AlertDialog.Heading>删除「{e.name}」?</AlertDialog.Heading></AlertDialog.Header>
            <AlertDialog.Body><p className="text-sm text-muted">只是从这部手机上移除这条记录和它的登录,服务器上的内容不受影响。</p></AlertDialog.Body>
            <AlertDialog.Footer>
              <Button variant="tertiary" slot="close">取消</Button>
              <Button variant="danger" onPress={() => { setDel(false); act("session.remove"); }}>删除</Button>
            </AlertDialog.Footer>
          </AlertDialog.Dialog>
        </AlertDialog.Container>
      </AlertDialog.Backdrop>
    </Accordion.Item>
  );
}

// ---------------------------------------------------------------- 服务器状态与配置

interface Field { key: string; group: string; title: string; kind: string; min?: number; max?: number; options?: string[]; value: unknown }
interface Config { revision: string; fields: Field[]; configPath?: string; dataDir?: string; restartRequired?: boolean }
type Status = Record<string, any>;

const OPTION_LABEL: Record<string, string> = {
  auto: "自动", ffmpeg: "内置 FFmpeg", qsv: "Intel QSV", none: "关闭", off: "关闭", exif: "使用内嵌缩略图", debug: "调试", info: "信息", warn: "警告", error: "错误",
};
const optLabel = (v: string) => OPTION_LABEL[v] ?? v;

/** 服务器状态与远程配置(需要管理员账号才能修改)。 */
export function ServerConfigPage() {
  const { session } = useApp();
  const [status, setStatus] = useState<Status | null>(null);
  const [config, setConfig] = useState<Config | null>(null);
  const [changes, setChanges] = useState<Record<string, unknown>>({});
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState("");
  const [editing, setEditing] = useState<Field | null>(null);
  const [confirm, setConfirm] = useState<"restart" | "purge" | null>(null);

  const refresh = useCallback(async () => {
    setBusy(true); setMessage("");
    try {
      setStatus(await api<Status>("/api/v1/manage/status"));
      setConfig(await api<Config>("/api/v1/manage/config").catch(() => null));
    } catch (e) {
      setMessage(e instanceof BridgeError && e.http === 404 ? "服务端需升级到支持远程配置的版本" : errorText(e));
    } finally { setBusy(false); }
  }, []);
  useEffect(() => { if (!session.local) refresh(); }, [refresh, session.local, session.activeId]);

  if (session.local) return <Note>本地媒体模式没有服务器配置。</Note>;

  const setField = (f: Field, v: unknown) => setChanges((c) => {
    const n = { ...c };
    if (JSON.stringify(v) === JSON.stringify(f.value)) delete n[f.key]; else n[f.key] = v;
    return n;
  });

  const save = async () => {
    if (!config) return;
    setBusy(true);
    try {
      await api("/api/v1/manage/config", { method: "PUT", body: { revision: config.revision, changes } });
      setChanges({}); setMessage("配置已保存,重启服务后生效"); await refresh();
    } catch (e) { setMessage(errorText(e)); } finally { setBusy(false); }
  };
  const post = async (path: string, ok: string) => {
    setBusy(true);
    try { await api(path, { method: "POST", body: {} }); setMessage(ok); } catch (e) { setMessage(errorText(e)); } finally { setBusy(false); }
  };

  const groups = new Map<string, Field[]>();
  config?.fields.forEach((f) => { if (!groups.has(f.group)) groups.set(f.group, []); groups.get(f.group)!.push(f); });
  const nChanges = Object.keys(changes).length;
  const jobs = status?.jobs ?? {};

  return (
    <>
      {!status && busy ? <StateView loading /> : status && (
        <ListGroup title="运行状态">
          <InfoRow title="服务版本" value={String(status.version ?? "")} />
          <InfoRow title="运行时间" value={`${Math.floor((status.uptimeSec ?? 0) / 60)} 分钟`} />
          <InfoRow title="媒体 / 群组" value={`${status.media ?? 0} / ${status.dialogs ?? 0}`} />
          <InfoRow title="存储优化" value={`排队 ${jobs.queued ?? 0} · 待替换 ${jobs.done ?? 0} · 失败 ${jobs.failed ?? 0}`} />
          {(status.index as any[] | undefined)?.map((p, i) => (
            <ListRow key={i} title={p.label ?? "媒体盘"} description={`${p.state ?? ""} · ${p.files ?? 0} 个文件${p.message ? ` · ${p.message}` : ""}`} />
          ))}
        </ListGroup>
      )}
      {status && status.canManage !== true && <Note>当前账号可查看状态。修改配置需管理员账号,初始管理员为服务器的首个账号。</Note>}
      {message && <div className="px-4"><Alert status="accent"><Alert.Indicator /><Alert.Content><Alert.Description>{message}</Alert.Description></Alert.Content></Alert></div>}
      <div className="flex flex-wrap gap-2 px-4">
        <Button size="sm" variant="secondary" isPending={busy} isDisabled={nChanges > 0} onPress={refresh}>刷新</Button>
        <Button size="sm" variant="secondary" isDisabled={busy} onPress={() => post("/api/v1/manage/rescan", "已请求重新扫描")}>重新扫描媒体库</Button>
        <Button size="sm" variant="secondary" isDisabled={busy || nChanges > 0 || status?.restartAvailable !== true} onPress={() => setConfirm("restart")}>重启服务器</Button>
      </div>

      {config && (
        <>
          <Note>配置保存到服务器磁盘,重启服务后生效。未保存 {nChanges} 项。</Note>
          {[...groups.entries()].map(([g, fields]) => (
            <ListGroup key={g} title={g}>
              {fields.map((f) => {
                const v = f.key in changes ? changes[f.key] : f.value;
                if (f.kind === "bool")
                  return (
                    <div key={f.key} className="px-3 py-2.5">
                      <Switch className="w-full" isSelected={v === true} isDisabled={busy}
                        onChange={(on) => { if (f.key === "optimize.purgeOriginal" && on) setConfirm("purge"); else setField(f, on); }}>
                        <Switch.Content className="w-full justify-between">
                          <Label className="text-sm font-medium">{f.title}</Label>
                          <Switch.Control><Switch.Thumb /></Switch.Control>
                        </Switch.Content>
                      </Switch>
                    </div>
                  );
                const label = f.kind === "roots" ? `${(v as unknown[] | null)?.length ?? 0} 个目录` : f.kind === "lines" ? `${(v as unknown[] | null)?.length ?? 0} 项`
                  : f.kind === "choice" ? optLabel(String(v ?? "")) : String(v ?? "") || "未限制";
                return <ListRow key={f.key} title={<span className={f.key in changes ? "text-accent" : ""}>{f.title}</span>} end={label} onPress={busy ? undefined : () => setEditing({ ...f, value: v })} />;
              })}
            </ListGroup>
          ))}
          <div className="flex gap-2 px-4">
            <Button fullWidth isDisabled={busy || nChanges === 0} onPress={save}>保存配置({nChanges} 项)</Button>
            {nChanges > 0 && <Button variant="tertiary" onPress={() => setChanges({})}>放弃修改</Button>}
          </div>
          <ListGroup title="配置位置">
            <ListRow title="实际配置文件" description={<span className="selectable break-all">{config.configPath}</span>} />
            <ListRow title="数据目录" description={<span className="selectable break-all">{config.dataDir}</span>} />
          </ListGroup>
        </>
      )}

      {editing && <FieldEditor field={editing} onClose={() => setEditing(null)} onSave={(v) => { setField(config!.fields.find((f) => f.key === editing.key)!, v); setEditing(null); }} />}

      <AlertDialog.Backdrop isOpen={confirm != null} onOpenChange={(o) => { if (!o) setConfirm(null); }}>
        <AlertDialog.Container placement="center">
          <AlertDialog.Dialog className="sm:max-w-sm">
            <AlertDialog.Header><AlertDialog.Icon status="warning" /><AlertDialog.Heading>{confirm === "purge" ? "永久删除原文件?" : "重启服务器?"}</AlertDialog.Heading></AlertDialog.Header>
            <AlertDialog.Body><p className="text-sm text-muted">{confirm === "purge" ? "启用后,压缩替换成功的原文件会被永久删除,不进入可恢复的保留流程。" : "保存的设置将在重启后生效,连接将暂时断开。"}</p></AlertDialog.Body>
            <AlertDialog.Footer>
              <Button variant="tertiary" slot="close">取消</Button>
              <Button variant="danger" onPress={() => {
                const c = confirm; setConfirm(null);
                if (c === "purge") setField(config!.fields.find((f) => f.key === "optimize.purgeOriginal")!, true);
                else post("/api/v1/manage/restart", "重启请求已提交,稍后点刷新重新连接");
              }}>确定</Button>
            </AlertDialog.Footer>
          </AlertDialog.Dialog>
        </AlertDialog.Container>
      </AlertDialog.Backdrop>
    </>
  );
}

function FieldEditor({ field, onClose, onSave }: { field: Field; onClose: () => void; onSave: (v: unknown) => void }) {
  useBackHandler(true, onClose);
  const init = field.kind === "roots" ? ((field.value as { path: string; label?: string }[] | null) ?? []).map((r) => `${r.path} | ${r.label ?? ""}`).join("\n")
    : field.kind === "lines" ? ((field.value as string[] | null) ?? []).join("\n") : String(field.value ?? "");
  const [text, setText] = useState(init);
  const [err, setErr] = useState("");
  const submit = () => {
    if (field.kind === "number") {
      const n = Number(text);
      if (!Number.isInteger(n) || n < (field.min ?? -Infinity) || n > (field.max ?? Infinity)) { setErr("请输入范围内的整数"); return; }
      onSave(n);
    } else if (field.kind === "roots") {
      onSave(text.split("\n").filter((l) => l.trim()).map((l) => { const [p, ...rest] = l.split("|"); return { path: p.trim(), label: rest.join("|").trim() }; }));
    } else if (field.kind === "lines") onSave(text.split("\n").map((l) => l.trim()).filter(Boolean));
    else onSave(text.trim());
  };
  return (
    <AlertDialog.Backdrop isOpen onOpenChange={(o) => { if (!o) onClose(); }}>
      <AlertDialog.Container placement="center">
        <AlertDialog.Dialog className="sm:max-w-md">
          <AlertDialog.Header><AlertDialog.Heading>{field.title}</AlertDialog.Heading></AlertDialog.Header>
          <AlertDialog.Body className="flex flex-col gap-2">
            {field.kind === "choice" ? (
              <Select value={text} onChange={(k) => k != null && setText(String(k))} aria-label={field.title}>
                <Select.Trigger><Select.Value /><Select.Indicator /></Select.Trigger>
                <Select.Popover>
                  <ListBox>{(field.options ?? []).map((o) => <ListBox.Item key={o} id={o} textValue={optLabel(o)}>{optLabel(o)}<ListBox.ItemIndicator /></ListBox.Item>)}</ListBox>
                </Select.Popover>
              </Select>
            ) : field.kind === "roots" || field.kind === "lines" ? (
              <TextField value={text} onChange={(v) => { setText(v); setErr(""); }} aria-label={field.title}>
                <TextArea rows={6} className="font-mono text-xs" />
              </TextField>
            ) : (
              <TextField value={text} onChange={(v) => { setText(v); setErr(""); }} aria-label={field.title} isInvalid={!!err}>
                <Input inputMode={field.kind === "number" ? "numeric" : undefined} />
              </TextField>
            )}
            {field.kind === "roots" && <p className="text-xs text-muted">每行一个目录,可在 | 后填写显示名称。例如:F:\视频 | 视频盘</p>}
            {field.kind === "lines" && <p className="text-xs text-muted">每行填写一个目录名</p>}
            {field.kind === "number" && <p className="text-xs text-muted">范围:{field.min} ~ {field.max}</p>}
            {err && <p className="text-xs text-danger">{err}</p>}
          </AlertDialog.Body>
          <AlertDialog.Footer>
            <Button variant="tertiary" slot="close">取消</Button>
            <Button onPress={submit}>确定</Button>
          </AlertDialog.Footer>
        </AlertDialog.Dialog>
      </AlertDialog.Container>
    </AlertDialog.Backdrop>
  );
}
