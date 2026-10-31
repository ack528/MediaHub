import { Alert, AlertDialog, Button, Card, Chip, Description, Input, Label, Separator, TextField } from "@heroui/react";
import { ChevronRight, Picture, Server } from "@gravity-ui/icons";
import { useEffect, useState } from "react";
import { call, errorText } from "../lib/bridge";
import { useApp } from "../lib/app";
import type { ServerEntry } from "../lib/types";

interface Probe { url: string; fp: string; trusted: boolean }
interface Info { name: string; version: string }

/** 登录:先输入服务器地址(HTTPS 时核对证书指纹),再输入账号密码;也可以只浏览手机上的本地媒体。 */
export function Onboarding() {
  const { session, notice } = useApp();
  const [address, setAddress] = useState("");
  const [info, setInfo] = useState<Info | null>(null);
  const [url, setUrl] = useState("");
  const [fp, setFp] = useState("");
  const [pendingFp, setPendingFp] = useState<string | null>(null);
  const [user, setUser] = useState("");
  const [pass, setPass] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => { call<string>("session.lastAddress").then((a) => a && setAddress(a)).catch(() => {}); }, []);

  const verify = async (u: string) => {
    try { setInfo(await call<Info>("session.info", { url: u })); setUrl(u); }
    catch (e) { setError(errorText(e)); }
  };

  const connect = async () => {
    setBusy(true); setError(null);
    try {
      const p = await call<Probe>("session.probe", { address });
      if (p.fp && !p.trusted) { setPendingFp(p.fp); setUrl(p.url); return; }
      setFp(p.fp);
      await verify(p.url);
    } catch (e) { setError(errorText(e)); }
    finally { setBusy(false); }
  };

  const trust = async () => {
    const f = pendingFp!;
    setPendingFp(null); setBusy(true);
    try { await call("session.trust", { fp: f }); setFp(f); await verify(url); }
    finally { setBusy(false); }
  };

  const login = async () => {
    setBusy(true); setError(null);
    try { await call("session.login", { url, address, user, pass, fp: fp || null }); setPass(""); }
    catch (e) { setError(errorText(e)); }
    finally { setBusy(false); }
  };

  const local = async () => {
    setBusy(true); setError(null);
    try { await call("session.local"); } catch (e) { setError(errorText(e)); } finally { setBusy(false); }
  };

  const pick = async (e: ServerEntry) => {
    setError(null);
    if (!e.loggedIn) setAddress(e.address || e.baseUrl);
    await call("session.switch", { id: e.id }).catch((x) => setError(errorText(x)));
  };

  return (
    <div className="page pt-safe pb-safe">
      <div className="mx-auto flex min-h-full max-w-md flex-col gap-6 px-5 py-10">
        <div className="flex flex-col items-center gap-3 pt-6 text-center">
          <div className="grid size-16 place-items-center rounded-full bg-gradient-to-br from-violet-500 to-accent text-white shadow-overlay">
            <Picture className="size-8" />
          </div>
          <div>
            <h1 className="text-2xl font-semibold">本地浏览</h1>
            <p className="mt-1 text-sm text-muted">连接到你自己的媒体服务器</p>
          </div>
        </div>

        {notice && (
          <Alert status="accent">
            <Alert.Indicator />
            <Alert.Content><Alert.Description>{notice}</Alert.Description></Alert.Content>
          </Alert>
        )}

        {!info && session.servers.length > 0 && (
          <Card className="rounded-3xl p-1.5">
            <p className="px-3 pt-2 pb-1 text-xs text-muted">已保存的服务器</p>
            <Card.Content className="gap-0 p-0">
              {session.servers.map((e) => (
                <div key={e.id}>
                  <button type="button" onClick={() => pick(e)} className="flex w-full items-center gap-3 rounded-2xl px-3 py-2.5 text-left active:bg-default">
                    <span className="grid size-9 place-items-center rounded-full bg-accent-soft text-accent"><Server className="size-4" /></span>
                    <span className="min-w-0 flex-1">
                      <span className="block truncate text-sm font-medium">{e.name}</span>
                      <span className="block truncate text-xs text-muted">{e.host}</span>
                    </span>
                    {e.kind !== "local" && (
                      <Chip size="sm" variant="soft" color={e.loggedIn ? "success" : "warning"}>
                        {e.loggedIn ? (e.user ? `已登录 · ${e.user}` : "已登录") : "需要登录"}
                      </Chip>
                    )}
                    <ChevronRight className="size-4 text-muted" />
                  </button>
                </div>
              ))}
            </Card.Content>
          </Card>
        )}

        <Card className="rounded-3xl p-6">
          {!info ? (
            <div className="flex flex-col items-center gap-4 text-center">
              <span className="grid size-12 place-items-center rounded-full bg-accent-soft text-accent"><Server className="size-5" /></span>
              <div>
                <h2 className="font-semibold">连接服务器</h2>
                <p className="mt-1 text-sm text-muted">输入 MediaHub 服务器地址,默认端口 8480、加密传输</p>
              </div>
              <TextField className="w-full text-left" value={address} onChange={(v) => { setAddress(v); setError(null); }} isDisabled={busy}>
                <Label>服务器地址</Label>
                <Input placeholder="192.168.1.20:8480" inputMode="url" autoCapitalize="off" autoCorrect="off" onKeyDown={(e) => { if (e.key === "Enter" && address.trim()) connect(); }} />
              </TextField>
              <Button fullWidth isDisabled={!address.trim()} isPending={busy} onPress={connect}>连接</Button>
              <div className="flex w-full items-center gap-3 text-xs text-muted"><Separator className="flex-1" />或<Separator className="flex-1" /></div>
              <Button fullWidth variant="tertiary" isDisabled={busy} onPress={local}><Picture className="size-4" />读取本地媒体(不连接服务器)</Button>
            </div>
          ) : (
            <div className="flex flex-col items-center gap-4 text-center">
              <span className="grid size-12 place-items-center rounded-full bg-success-soft text-success"><Server className="size-5" /></span>
              <div>
                <h2 className="font-semibold">{info.name}</h2>
                <p className="mt-1 text-sm text-muted">v{info.version} · {url.replace(/^https?:\/\//, "")}</p>
              </div>
              <TextField className="w-full text-left" value={user} onChange={(v) => { setUser(v); setError(null); }} isDisabled={busy} autoFocus>
                <Label>账号</Label>
                <Input autoCapitalize="off" autoCorrect="off" autoComplete="username" />
              </TextField>
              <TextField className="w-full text-left" value={pass} onChange={(v) => { setPass(v); setError(null); }} isDisabled={busy} type="password">
                <Label>密码</Label>
                <Input autoComplete="current-password" onKeyDown={(e) => { if (e.key === "Enter" && user.trim() && pass) login(); }} />
                <Description>密码只用来换取登录令牌,不会保存在手机上</Description>
              </TextField>
              <Button fullWidth isDisabled={!user.trim() || !pass} isPending={busy} onPress={login}>登录</Button>
              <Button fullWidth variant="tertiary" isDisabled={busy} onPress={() => { setInfo(null); setError(null); }}>更换服务器</Button>
            </div>
          )}
        </Card>

        {error && (
          <Alert status="danger">
            <Alert.Indicator />
            <Alert.Content><Alert.Description>{error}</Alert.Description></Alert.Content>
          </Alert>
        )}

        <p className="mt-auto text-center text-xs text-muted">本应用只读访问你的媒体,不会修改服务器上的文件。</p>
      </div>

      <AlertDialog.Backdrop isOpen={pendingFp != null} onOpenChange={(v) => { if (!v) setPendingFp(null); }}>
        <AlertDialog.Container placement="center">
          <AlertDialog.Dialog className="sm:max-w-sm">
            <AlertDialog.Header>
              <AlertDialog.Icon status="warning" />
              <AlertDialog.Heading>确认服务器证书</AlertDialog.Heading>
            </AlertDialog.Header>
            <AlertDialog.Body>
              <p className="text-sm text-muted">第一次连接这台服务器(或它的证书变了)。请在电脑的 MediaHub 管理程序「网络」页核对下面的指纹,一致才信任。</p>
              <p className="mt-3 text-xs text-muted">SHA-256 指纹</p>
              <code className="selectable mt-1 block rounded-xl bg-surface-secondary p-3 font-mono text-xs leading-relaxed break-all">{pendingFp}</code>
            </AlertDialog.Body>
            <AlertDialog.Footer>
              <Button variant="tertiary" slot="close">取消</Button>
              <Button onPress={trust}>信任并连接</Button>
            </AlertDialog.Footer>
          </AlertDialog.Dialog>
        </AlertDialog.Container>
      </AlertDialog.Backdrop>
    </div>
  );
}
