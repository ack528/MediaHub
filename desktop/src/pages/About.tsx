import { bridge } from "../bridge";
import { Btn, Card, Row } from "../components";
import { useApp } from "../store";

export function About() {
  const { env, config, svc } = useApp();
  const open = (p?: string) => p && bridge.openPath(p);
  return (
    <Card icon="info" title="关于 MediaHub">
      <Row icon="server" title="MediaHub 管理程序" desc={`v${env?.appVersion ?? "?"} · 服务端 ${svc?.info?.version ?? "未运行"} · Tauri 2`} />
      <Row icon="folder" title="安装目录" desc={env?.root ?? "—"}><Btn icon="folderopen" onClick={() => open(env?.root)}>打开</Btn></Row>
      <Row icon="filetext" title="配置文件" desc={env?.configPath ?? "—"}><Btn icon="folderopen" onClick={() => open(env?.configPath?.replace(/[\\/][^\\/]+$/, ""))}>打开所在文件夹</Btn></Row>
      <Row icon="database" title="数据目录" desc={config.dataDir}><Btn icon="folderopen" onClick={() => open(config.dataDir)}>打开</Btn></Row>
      <Row icon="zap" title="服务程序" desc={env?.serverExe ?? "—"} />
      <Row icon="shield" title="使用说明" desc="本程序只管理服务端:修改配置、启动/停止服务、管理账号、查看日志。关闭窗口会最小化到托盘,服务继续运行;手机访问的是服务端本身。" />
    </Card>
  );
}
