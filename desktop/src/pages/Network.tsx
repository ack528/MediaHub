import { useCallback, useEffect, useState } from "react";
import { bridge } from "../bridge";
import { Badge, Btn, Card, ItemRow, Row, Switch } from "../components";
import { itemsOf } from "../schema";
import { useApp } from "../store";
import { CopyBtn } from "./Overview";

function Listen() {
  const { ips, config, svc } = useApp();
  const tls = svc?.info?.tls;
  const https = config.tls.enabled;
  const port = Number(config.listen.slice(config.listen.lastIndexOf(":") + 1)) || 8480;
  const items = itemsOf("network", "监听与访问");
  return (
    <>
      <Card icon="globe" title="监听与访问" count={items.length}>
        {items.map((i) => <ItemRow key={i.id} item={i} />)}
      </Card>
      <Card icon="link" title="访问地址">
        {ips.map((i) => (
          <Row key={i.ip} icon="globe" title={`${https ? "https" : "http"}://${i.ip}:${port}`} desc={i.name}>
            <CopyBtn text={`${i.ip}:${port}`} />
          </Row>
        ))}
      </Card>
      <Card icon="shield" title="证书指纹(手机首次连接时核对)">
        {!https && <Row icon="alert" title="加密传输已关闭" desc="手机与服务器之间是明文 HTTP。建议在上面打开“加密传输”。"><Badge tone="orange">未加密</Badge></Row>}
        {https && !tls?.fingerprint && <Row icon="clock" title="服务未运行" desc="启动服务后显示证书指纹。" />}
        {https && tls?.fingerprint && (
          <Row icon="key" title="SHA-256 指纹"
               desc={<>手机第一次连接这台服务器时会显示一段指纹,和下面的逐段核对,一致再点“信任并连接”。以后手机只接受这张证书,不依赖 IP 地址。<pre className="code">{tls.fingerprint.replace(/(.{24})/g, "$1\n").trim()}</pre></>}>
            <CopyBtn text={tls.fingerprint} />
          </Row>
        )}
      </Card>
    </>
  );
}

function Startup() {
  const { config, toast } = useApp();
  const port = Number(config.listen.slice(config.listen.lastIndexOf(":") + 1)) || 8480;
  const items = itemsOf("network", "开机与防火墙");
  const [auto, setAuto] = useState(false);
  const [fw, setFw] = useState<boolean | null>(null);
  const [busy, setBusy] = useState(false);
  const cmd = `netsh advfirewall firewall add rule name="MediaHub" dir=in action=allow protocol=TCP localport=${port} profile=private`;

  const load = useCallback(async () => {
    try { setAuto(await bridge.autostartGet()); } catch { /* ignore */ }
    try { setFw(await bridge.firewallStatus()); } catch { setFw(null); }
  }, []);
  useEffect(() => { load(); }, [load]);

  const toggleAuto = async (v: boolean) => {
    try { await bridge.autostartSet(v); setAuto(v); toast(v ? "已设为开机自启(启动后最小化到托盘)" : "已取消开机自启"); }
    catch (e) { toast(String(e), "err"); }
  };
  const addFw = async () => {
    setBusy(true);
    try { await bridge.firewallAdd(port); toast("已放行端口 " + port); } catch (e) { toast(String(e), "err"); }
    setBusy(false);
    load();
  };

  return (
    <>
      <Card icon="play" title="开机与启动" count={items.length + 1}>
        <Row icon="zap" title="开机自启管理程序" desc="登录 Windows 后自动运行管理程序,窗口不弹出,只留在托盘。配合下面的开关,服务也会随之启动。">
          <Switch checked={auto} onChange={toggleAuto} />
        </Row>
        {items.map((i) => <ItemRow key={i.id} item={i} />)}
      </Card>
      <Card icon="shield" title="Windows 防火墙">
        <Row icon="shield" title="端口放行" desc={<>手机访问需要放行 TCP 端口 {port}(只对“专用网络”生效)。点“一键放行”会弹出管理员确认框。</>}>
          {fw === null ? <Badge>未检测</Badge> : fw ? <Badge tone="green">已放行</Badge> : <Badge tone="orange">未放行</Badge>}
          <Btn kind="primary" icon="shield" disabled={busy} onClick={addFw}>{busy ? "等待确认…" : fw ? "重新放行" : "一键放行"}</Btn>
        </Row>
        <Row icon="wrench" title="手动方式" desc={<>也可以以管理员身份运行 PowerShell 执行:<pre className="code">{cmd}</pre></>}>
          <CopyBtn text={cmd} />
        </Row>
      </Card>
    </>
  );
}

export function Network({ tab }: { tab: string }) {
  return tab === "开机与防火墙" ? <Startup /> : <Listen />;
}
