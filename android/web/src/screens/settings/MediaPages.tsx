import { Alert, Button, Chip, ColorSwatchPicker, Input, Label, ProgressCircle, Slider, TextField, parseColor, toast } from "@heroui/react";
import { useEffect, useMemo, useState } from "react";
import { call, errorText, on } from "../../lib/bridge";
import { useApp } from "../../lib/app";
import { formatDateTime } from "../../lib/media";
import { ListGroup, ListRow, StateView } from "../../components/List";
import { SETTING_PAGES } from "./schema";
import { ActionRow, InfoRow, Note, SchemaRows } from "./controls";

// ---------------------------------------------------------------- 画质增强

interface Lsfg { supported: boolean; lib: boolean; dllInAssets: boolean; bundled: boolean; cacheReady: boolean; state: number; error: string }

export function EnhancePage({ open }: { open: (s: string) => void }) {
  const [lsfg, setLsfg] = useState<Lsfg | null>(null);
  useEffect(() => { call<Lsfg>("lsfg.status").then(setLsfg).catch(() => {}); }, []);
  const rows = useMemo(() => SETTING_PAGES.enhance.map((r) =>
    r.type === "choice" && r.requires === "lsfg" && lsfg && !lsfg.supported ? { ...r, options: [["off", "关闭(此设备不支持)"]] as [string, string][] } : r), [lsfg]);
  return (
    <>
      <Note>开启任意一项后,视频改由本应用自己的 OpenGL 渲染器显示(普通播放不受影响)。全部在手机 GPU 上实时处理,耗电和发热会增加;处理跟不上时会自动停用。播放时在「更多 → 画质增强」里也能快速切换。</Note>
      <ListGroup>
        <ListRow title="硬件支持检测" description="实测这部手机能不能流畅超分 / 补帧,并给出推荐设置" onPress={() => open("enhance-hw")} />
        <ListRow title="LSFG 帧生成" description="内置的 Lossless Scaling 帧生成:状态、着色器提取" onPress={() => open("lsfg")} />
      </ListGroup>
      {lsfg && !lsfg.supported && <div className="px-4"><Alert status="warning"><Alert.Indicator /><Alert.Content><Alert.Description>此设备不支持补帧(需要 arm64 处理器和 Vulkan)</Alert.Description></Alert.Content></Alert></div>}
      <SchemaRows rows={rows} />
    </>
  );
}

interface HwReport { time: number; lines: { title: string; level: number; text: string }[]; note: string; recUpscale: string; recFrc: string; lsfgReady?: boolean }
const UPSCALE: Record<string, string> = { fsr: "FSR", anime4k_s: "Anime4K 小模型", anime4k_m: "Anime4K 中模型", fsrcnnx_s: "FSRCNNX 小模型", fsrcnnx_m: "FSRCNNX 中模型" };
const FRC: Record<string, string> = { lsfg: "LSFG 标准", lsfg_low: "LSFG 低功耗" };
const LEVEL: Record<number, { label: string; color: "success" | "warning" | "danger" | "default" }> = {
  2: { label: "良好", color: "success" }, 1: { label: "勉强", color: "warning" }, 0: { label: "不支持", color: "danger" },
};

export function HardwarePage() {
  const { settings: s, update } = useApp();
  const [running, setRunning] = useState(false);
  const [step, setStep] = useState("");
  const report = useMemo<HwReport | null>(() => { try { return s.hwReport ? JSON.parse(s.hwReport) : null; } catch { return null; } }, [s.hwReport]);
  useEffect(() => on("hwProgress", (t: string) => setStep(t)), []);
  const run = async () => {
    setRunning(true); setStep("");
    try { await call("hw.probe"); } catch (e) { toast.danger(errorText(e)); } finally { setRunning(false); }
  };
  return (
    <>
      <Note>实际运行一遍超分着色器(540p→1080p),量出每帧耗时,再和 30 / 60fps 的帧间隔比较;补帧按前提条件和 GPU 型号判断。检测期间画面可能短暂卡顿。</Note>
      <div className="px-4">
        <Button fullWidth size="lg" isPending={running} onPress={run}>{running ? step || "准备中…" : report ? "重新检测" : "开始检测(约 5 ~ 15 秒)"}</Button>
      </div>
      {report && (
        <>
          <ListGroup title={`检测结果 · ${formatDateTime(report.time)}`}>
            {report.lines.map((l, i) => {
              const lv = LEVEL[l.level] ?? { label: "信息", color: "default" as const };
              return <ListRow key={i} title={<span className="flex items-center gap-2">{l.title}<Chip size="sm" variant="soft" color={lv.color}>{lv.label}</Chip></span>} description={l.text} />;
            })}
          </ListGroup>
          <ListGroup title="推荐">
            <p className="px-3 py-2.5 text-sm">{report.note}</p>
            <ActionRow title="应用推荐设置" description={`超分设为「${UPSCALE[report.recUpscale] ?? "关闭"}」、补帧设为「${FRC[report.recFrc] ?? "关闭"}」(其它设置不动)`}
              action={() => { update({ enhUpscale: report.recUpscale, enhFrc: report.recFrc }); toast.success("已应用推荐设置"); }} />
          </ListGroup>
        </>
      )}
    </>
  );
}

export function LsfgPage() {
  const { settings: s } = useApp();
  const [st, setSt] = useState<Lsfg | null>(null);
  useEffect(() => {
    const tick = () => call<Lsfg>("lsfg.status").then(setSt).catch(() => {});
    tick();
    const t = setInterval(tick, 1000);
    return () => clearInterval(t);
  }, []);
  const report = useMemo<HwReport | null>(() => { try { return s.hwReport ? JSON.parse(s.hwReport) : null; } catch { return null; } }, [s.hwReport]);
  if (!st) return <StateView loading />;
  const cache = st.state === 2 ? "已提取,可以使用" : st.state === 1 ? "正在提取(第一次约几秒到几十秒)…" : st.state === 3 ? `不可用:${st.error}` : "还没提取(应用启动时会自动提取)";
  return (
    <>
      <Note>LSFG = Lossless Scaling 的帧生成(基于开源的 lsfg-vk,Vulkan 计算着色器)。应用启动时会在后台提取 Lossless.dll 里的着色器并缓存,之后到「画质增强 → 补帧方式」选 LSFG 即可。</Note>
      <ListGroup title="状态">
        <InfoRow title="原生库" value={st.lib ? "已加载" : "没有加载(安装包没有编进 LSFG 原生部分,或设备架构不支持)"} />
        <InfoRow title="Lossless.dll" value={st.dllInAssets ? "已内置在安装包里" : st.bundled ? "应用私有目录里保存着一份,补帧可用" : st.cacheReady ? "着色器已提取过,补帧可用" : "安装包不含(版权原因不公开分发);用 tools\\setup-lsfg.ps1 自己打包"} />
        <InfoRow title="着色器缓存" value={<span className="inline-flex items-center gap-2">{st.state === 1 && <ProgressCircle isIndeterminate size="sm" aria-label="提取中" />}{cache}</span>} />
        <InfoRow title="硬件条件" value={report?.lsfgReady === true ? "满足:Android 10+、Vulkan、Adreno 7xx 及更新的 GPU" : report?.lsfgReady === false ? "不一定满足,详见「硬件支持检测」" : "还没检测"} />
      </ListGroup>
      <ListGroup title="操作">
        <ActionRow title="现在提取着色器" description="用于提取失败后重试(已经提取过的会跳过)" action={() => call("lsfg.prepare")} />
        <ActionRow title="重新提取着色器" description="清掉缓存后重新提取(换了 DLL 或提取出错时用)" confirm="清掉着色器缓存并重新提取?" action={() => call("lsfg.reset")} />
      </ListGroup>
      <Note>帧生成很费电、会发热;想省电用「LSFG 低功耗」。Lossless.dll 受版权保护,只给你自己用,请不要把带它的安装包发给别人。</Note>
    </>
  );
}

interface CodecGroup { label: string; mime: string; decoders: { name: string; hardware: boolean; detail: string }[] }

export function CodecsInfoPage() {
  const [list, setList] = useState<CodecGroup[] | null>(null);
  useEffect(() => { call<CodecGroup[]>("codecs").then(setList).catch(() => setList([])); }, []);
  if (!list) return <StateView loading />;
  return (
    <>
      <Note>没有解码器的格式手机无法直接播放(可由服务器转码)。</Note>
      {list.map((g) => (
        <ListGroup key={g.mime} title={<span className="flex items-center gap-2">{g.label}{g.decoders.length === 0 && <Chip size="sm" variant="soft" color="danger">不支持</Chip>}</span>}>
          {g.decoders.map((d) => (
            <ListRow key={d.name} title={<span className="flex items-center gap-2"><Chip size="sm" variant="soft" color={d.hardware ? "success" : "default"}>{d.hardware ? "硬件" : "软件"}</Chip><span className="font-mono text-[13px]">{d.name}</span></span>} description={d.detail || undefined} />
          ))}
        </ListGroup>
      ))}
    </>
  );
}

// ---------------------------------------------------------------- 幻灯片全屏提示(图片查看器页)

const PRESETS = ["#CCCCCC", "#FFFFFF", "#000000", "#4DA6FF", "#48C153", "#FF6B6B", "#FFB74D", "#B39DDB"];
const HEX = /^#[0-9A-Fa-f]{6}$/;

export function SlideshowSection() {
  const { settings: s, update } = useApp();
  const thickness = Number(s.slideshowBarThickness ?? 4);
  const color = String(s.slideshowBarColor ?? "#CCCCCC").toUpperCase();
  const [hex, setHex] = useState(color);
  useEffect(() => setHex(color), [color]);
  const pos = String(s.slideshowBarPosition ?? "top");
  const bar = pos === "left" || pos === "right"
    ? { top: 0, bottom: 0, [pos]: 0, width: thickness, background: `linear-gradient(to top, ${color} 72%, transparent 72%)` }
    : { left: 0, right: 0, [pos]: 0, height: thickness, background: `linear-gradient(to right, ${color} 72%, transparent 72%)` };
  return (
    <>
      <SchemaRows rows={SETTING_PAGES.slideshow} />
      <ListGroup title="进度条外观" footer="0.5–12 dp · 图片和视频幻灯片共用,保存后立即生效">
        <div className="flex flex-col gap-4 px-3 py-3">
          <div className="relative h-16 overflow-hidden rounded-xl bg-gradient-to-r from-neutral-800 to-neutral-200">
            <div className="absolute" style={bar} />
          </div>
          <Slider minValue={0.5} maxValue={12} step={0.5} value={thickness} onChangeEnd={(v) => update({ slideshowBarThickness: Number(v) })}>
            <div className="flex items-center justify-between"><Label>粗细</Label><Slider.Output /></div>
            <Slider.Track><Slider.Fill /><Slider.Thumb /></Slider.Track>
          </Slider>
          <div className="flex flex-col gap-2">
            <Label>颜色</Label>
            <ColorSwatchPicker value={HEX.test(color) ? parseColor(color) : undefined} onChange={(c) => update({ slideshowBarColor: c.toString("hex").toUpperCase() })}>
              {PRESETS.map((c) => (
                <ColorSwatchPicker.Item key={c} color={c}>
                  <ColorSwatchPicker.Swatch />
                  <ColorSwatchPicker.Indicator />
                </ColorSwatchPicker.Item>
              ))}
            </ColorSwatchPicker>
            <TextField value={hex} onChange={setHex} isInvalid={!HEX.test(hex)} aria-label="自定义颜色">
              <Input className="font-mono" placeholder="#4DA6FF" onBlur={() => { if (HEX.test(hex)) update({ slideshowBarColor: hex.toUpperCase() }); }} />
            </TextField>
          </div>
        </div>
        <ActionRow title="恢复全屏提示默认设置" description="顶部进度条 · 浅灰色 · 4 dp"
          action={() => update({ slideshowBarThickness: 4, slideshowBarColor: "#CCCCCC", slideshowBarPosition: "top", slideshowPhotoIndicator: "bar" })} />
      </ListGroup>
    </>
  );
}

