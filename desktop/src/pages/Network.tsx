import { Card, ItemRow, Row } from "../components";
import { itemsOf } from "../schema";
import { useApp } from "../store";
import { CopyBtn } from "./Overview";

export function Network() {
  const { ips, config } = useApp();
  const port = Number(config.listen.slice(config.listen.lastIndexOf(":") + 1)) || 8480;
  const items = itemsOf("network", "监听与访问");
  const fw = `netsh advfirewall firewall add rule name="MediaHub" dir=in action=allow protocol=TCP localport=${port} profile=private`;
  return (
    <>
      <Card icon="globe" title="监听与访问" count={items.length}>
        {items.map((i) => <ItemRow key={i.id} item={i} />)}
      </Card>
      <Card icon="link" title="访问地址">
        {ips.map((i) => (
          <Row key={i.ip} icon="globe" title={`http://${i.ip}:${port}`} desc={i.name}>
            <CopyBtn text={`http://${i.ip}:${port}`} />
          </Row>
        ))}
        <Row icon="shield" title="Windows 防火墙" desc={<>首次让手机访问时,需要放行端口。以管理员身份运行 PowerShell 执行(只对“专用网络”生效):<pre className="code">{fw}</pre></>}>
          <CopyBtn text={fw} />
        </Row>
      </Card>
    </>
  );
}
