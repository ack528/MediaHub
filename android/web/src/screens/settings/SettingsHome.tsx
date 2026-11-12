import { LatencyChip } from "../../components/LinkQuality";
import { Avatar, Card, Chip } from "@heroui/react";
import { CircleInfo, Cpu, Database, Display, FileText, Folder, Globe, LayoutCellsLarge, MagicWand, Picture, Play, Server, Sliders, Speedometer, Sun } from "@gravity-ui/icons";
import { useApp } from "../../lib/app";
import { gradientFor, type Tone } from "../../lib/tones";
import { useNav } from "../../lib/nav";
import { ListGroup, ListRow } from "../../components/List";
import { PageHeader } from "../../components/PageHeader";
import { TAB_BAR_SPACE } from "../Home";

export const SECTIONS: Record<string, { title: string; desc: string; icon: typeof Sun; tone: Tone }> = {
  appearance: { title: "外观", desc: "主题、文字大小、屏幕刷新率、震动、底部导航、隐私", icon: Sun, tone: "violet" },
  folders: { title: "文件夹列表", desc: "排序、隐藏小文件夹、头像与第二行内容", icon: Folder, tone: "accent" },
  browse: { title: "浏览", desc: "默认排序、网格间距与清晰度、时间线", icon: LayoutCellsLarge, tone: "success" },
  viewer: { title: "图片查看器", desc: "预加载、清晰度、缩放、幻灯片全屏提示", icon: Picture, tone: "warning" },
  image: { title: "图片", desc: "缓存大小、HEIC 显示方式", icon: Display, tone: "cyan" },
  video: { title: "视频播放", desc: "自动播放、循环、倍速、手势、字幕样式", icon: Play, tone: "danger" },
  codec: { title: "编解码器", desc: "硬解 / 软解、缓冲、转码、音轨字幕", icon: Cpu, tone: "accent" },
  enhance: { title: "画质增强(实验)", desc: "实时超分、补帧、SDR 转 HDR、硬件检测", icon: MagicWand, tone: "pink" },
  network: { title: "网络", desc: "内网直连、连接与读取超时", icon: Globe, tone: "cyan" },
  speedtest: { title: "网络测速", desc: "服务器延迟、下载 / 上传速度", icon: Speedometer, tone: "warning" },
  storage: { title: "存储与缓存", desc: "清理缓存和记录、随机序列、恢复默认设置", icon: Database, tone: "success" },
  account: { title: "服务器管理", desc: "已保存的服务器、切换、添加、退出登录", icon: Server, tone: "accent" },
  "server-config": { title: "服务器状态与配置", desc: "运行状态、媒体库、转码与后台参数", icon: Sliders, tone: "violet" },
  log: { title: "日志与诊断", desc: "日志级别、查看、复制、分享给开发者", icon: FileText, tone: "neutral" },
  about: { title: "关于", desc: "版本、检查更新、更新说明", icon: CircleInfo, tone: "accent" },
};

const GROUPS: [string, string[]][] = [
  ["界面与浏览", ["appearance", "folders", "browse", "viewer", "image"]],
  ["播放", ["video", "codec", "enhance"]],
  ["网络与存储", ["network", "speedtest", "storage"]],
  ["服务器与支持", ["account", "server-config", "log", "about"]],
];

/** 设置首页:当前服务器卡片 + 分组的分类列表。 */
export function SettingsHome() {
  const { session, boot } = useApp();
  const nav = useNav();
  const cur = session.servers.find((s) => s.id === session.activeId);
  return (
    <div className="page" style={{ paddingBottom: TAB_BAR_SPACE }}>
      <PageHeader large title="设置" />
      <div className="flex flex-col gap-5 pt-2">
        <div className="px-4">
          <Card className="rounded-3xl">
            <button type="button" className="flex w-full min-w-0 flex-col gap-3 text-left" onClick={() => nav.push({ name: "settings", params: { section: "account" } })}>
              <span className="flex min-w-0 items-center gap-3">
                <Avatar size="lg" className="shrink-0">
                  <Avatar.Fallback className={`${gradientFor(cur?.name ?? "本地")} font-semibold text-white`}>{(cur?.name ?? "本").slice(0, 1)}</Avatar.Fallback>
                </Avatar>
                <span className="min-w-0 flex-1">
                  <span className="block truncate font-semibold">{cur?.name ?? "本地媒体"}</span>
                  <span className="block truncate text-sm text-muted">{session.local ? "这部手机上的照片和视频" : cur?.user ? `@${cur.user}` : cur?.host}</span>
                </span>
                <LatencyChip />
                <Chip size="sm" variant="soft" className="shrink-0" color={session.local ? "default" : session.baseUrl.startsWith("https://") ? "success" : "warning"}>
                  {session.local ? "本地" : session.baseUrl.startsWith("https://") ? "已加密" : "未加密"}
                </Chip>
              </span>
              {!session.local && <span className="block min-w-0 truncate text-sm">{cur?.host}</span>}
              <span className="flex gap-4 text-sm text-muted">
                <span><b className="font-semibold text-foreground tabular-nums">{session.servers.length}</b> 个服务器</span>
                <span><b className="font-semibold text-foreground tabular-nums">{session.servers.filter((x) => x.loggedIn).length}</b> 个已登录</span>
                <span className="ml-auto text-accent">管理 ›</span>
              </span>
            </button>
          </Card>
        </div>
        {GROUPS.map(([title, g]) => (
          <ListGroup key={title} title={title}>
            {g.filter((id) => !(session.local && (id === "server-config" || id === "speedtest"))).map((id) => {
              const sec = SECTIONS[id];
              return <ListRow key={id} icon={<sec.icon className="size-[18px]" />} tone={sec.tone} title={sec.title} description={sec.desc} onPress={() => nav.push({ name: "settings", params: { section: id } })} />;
            })}
          </ListGroup>
        ))}
        <p className="pb-4 text-center text-xs text-muted">本地浏览 v{boot.version}</p>
      </div>
    </div>
  );
}
