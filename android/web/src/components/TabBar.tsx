import { Fragment, useCallback, useEffect, useLayoutEffect, useRef, type PointerEvent as ReactPointerEvent, type ReactNode } from "react";
import { haptic } from "../lib/bridge";

/**
 * 首页底部导航(照 IT 之家的液态玻璃导航):
 *  - 平时:玻璃胶囊,选中项下面一块浅灰的镜片;
 *  - 按下(不管按哪里):镜片跳到手指下、鼓成约 1.35 倍、变成透明的玻璃泡(边缘折射、彩色描边),整条导航微微撑大;
 *  - 拖动:玻璃泡跟着手指走,越快横向拉得越长;盖住的图标和文字变成强调色实心并略微放大;拖出两端有阻尼;
 *  - 松手:按位置(快甩时加上惯性)吸附到最近的一项,弹簧回弹、缩回浅灰镜片,这时才切换页面。
 * 颜色"染"过去的效果:两层一样的内容叠在一起,强调色那层只露出镜片那块,普通那层挖掉镜片那块。
 * 镜片和玻璃底是兄弟元素(不是父子):这样镜片折射的是背后的页面,而不只是导航自己。
 * 所有动画都是每帧直接改 transform / clip-path(弹簧物理),不触发 React 重渲染。
 */
export interface TabBarItem { id: string; label: string; icon: (filled: boolean) => ReactNode }

const PAD = 4; // 胶囊内边距(镜片离边缘)
const ITEM = 72; // 每一项的宽度:三项时导航只有内容那么宽,不铺满整行
const H = 56; // 导航高度
const GROW = 1.35; // 按下时镜片放大的倍数
const MAGNIFY = 1.12; // 按下时镜片里的图标放大
const BAR_RIM = 16, BAR_REFRACT = 30; // 玻璃底:边缘折射带宽 / 最大位移
const LENS_RIM = 20, LENS_REFRACT = 48; // 玻璃泡:折射更强
const DISPERSION = 0.12; // 色散:红 / 绿 / 蓝三个通道折射程度不同,边缘出现淡淡的彩边

/** 弹簧:x 朝 target 走,k 刚度,z 阻尼比(<1 有回弹)。 */
interface Spring { x: number; v: number; target: number; k: number; z: number }
const spring = (x: number, k: number, z: number): Spring => ({ x, v: 0, target: x, k, z });
function stepSpring(s: Spring, dt: number) {
  const c = 2 * s.z * Math.sqrt(s.k);
  for (let i = 0; i < 4; i++) { const h = dt / 4; s.v += (-s.k * (s.x - s.target) - c * s.v) * h; s.x += s.v * h; }
}
const settled = (s: Spring, eps: number) => Math.abs(s.x - s.target) < eps && Math.abs(s.v) < eps * 20;

export function TabBar({ items, value, onChange }: { items: TabBarItem[]; value: string; onChange: (id: string) => void }) {
  const n = items.length;
  const W = n * ITEM + PAD * 2;
  const lw0 = ITEM * GROW, lh0 = (H - PAD * 2) * GROW; // 镜片的布局尺寸 = 按下时的大小,平时缩小显示
  const index = Math.max(0, items.findIndex((t) => t.id === value));
  const centerOf = useCallback((i: number) => PAD + ITEM * (i + 0.5), []);

  const stage = useRef<HTMLDivElement>(null);
  const lens = useRef<HTMLDivElement>(null);
  const base = useRef<HTMLDivElement>(null);
  const tintClip = useRef<HTMLDivElement>(null);
  const tint = useRef<HTMLDivElement>(null);
  const ids = useRef({ bar: `lgb-${Math.random().toString(36).slice(2, 7)}`, lens: `lgl-${Math.random().toString(36).slice(2, 7)}` }).current;
  const maps = useRef({ bar: "", lens: "" });
  const lensFilter = useRef(`url(#${ids.lens}) saturate(1.5) brightness(1.05)`);
  if (!maps.current.bar) maps.current = { bar: displacementMap(W, H, BAR_RIM), lens: displacementMap(lw0, lh0, LENS_RIM) };

  // 物理状态:镜片中心 x、按下程度 p(0 平时 / 1 按下)、横向拉伸 st、整条导航的位移 shift
  const phys = useRef({
    x: spring(centerOf(index), 520, 0.72), p: spring(0, 520, 0.7), st: spring(0, 600, 0.6), shift: spring(0, 400, 0.7),
    raf: 0, last: 0, pressed: false, fv: 0, fx: 0, ft: 0, hover: index, t0: 0, x0: 0,
  });
  const indexRef = useRef(index);
  indexRef.current = index;

  /** 按当前物理状态摆好镜片、两层内容的裁剪、导航整体的形变。 */
  const paint = useCallback(() => {
    const ph = phys.current;
    if (!lens.current || !base.current || !tintClip.current || !tint.current || !stage.current) return;
    const p = Math.max(0, ph.p.x), stv = Math.max(-0.2, Math.min(0.6, ph.st.x));
    // 镜片显示尺寸:平时 ITEM × (H-2PAD),按下放大;拉伸时横向变长、纵向略扁
    const k = 1 / GROW + (1 - 1 / GROW) * p;
    const sx = k * (1 + stv), sy = k * (1 - stv * 0.35);
    const lw = lw0 * sx, lh = lh0 * sy;
    const cx = ph.x.x, cy = H / 2;
    const lx = cx - lw / 2, ly = cy - lh / 2, r = lh / 2;
    lens.current.style.transform = `translate3d(${cx - lw0 / 2}px,${cy - lh0 / 2}px,0) scale(${sx},${sy})`;
    lens.current.style.setProperty("--press", p.toFixed(3));
    // 镜片的折射滤镜(三个颜色通道分别折射)很费 GPU:只在按下 / 拖动时打开,平时就是一块浅灰
    const f = p > 0.02 ? lensFilter.current : "none";
    if (lens.current.style.backdropFilter !== f) { lens.current.style.backdropFilter = f; lens.current.style.setProperty("-webkit-backdrop-filter", f); }
    tintClip.current.style.clipPath = `inset(${ly}px ${W - lx - lw}px ${H - ly - lh}px ${lx}px round ${r}px)`;
    tint.current.style.transformOrigin = `${cx}px ${cy}px`;
    tint.current.style.transform = `scale(${1 + (MAGNIFY - 1) * p})`;
    const x2 = lx + lw, y2 = ly + lh;
    base.current.style.clipPath = `path(evenodd, "M-40 -40H${W + 40}V${H + 40}H-40Z M${lx + r} ${ly}H${x2 - r}A${r} ${r} 0 0 1 ${x2 - r} ${y2}H${lx + r}A${r} ${r} 0 0 1 ${lx + r} ${ly}Z")`;
    // 导航整体:按下撑大一点,拖出两端时朝那个方向鼓一下
    const bs = 1 + 0.035 * p;
    stage.current.style.transform = `translate3d(${ph.shift.x}px,0,0) scale(${bs + (Math.abs(ph.shift.x) / W) * 0.6},${bs})`;
  }, [W, lw0, lh0]);

  const loop = useCallback(() => {
    const ph = phys.current;
    if (ph.raf) return;
    ph.last = 0;
    const tick = (now: number) => {
      const dt = Math.min(0.032, ph.last ? (now - ph.last) / 1000 : 1 / 120);
      ph.last = now;
      // 拉伸跟着镜片的速度走(快甩时拉长),停下就弹回
      ph.st.target = Math.min(0.55, Math.abs(ph.x.v) / 2600);
      for (const s of [ph.x, ph.p, ph.st, ph.shift]) stepSpring(s, dt);
      paint();
      if (!ph.pressed && settled(ph.x, 0.2) && settled(ph.p, 0.002) && settled(ph.st, 0.002) && settled(ph.shift, 0.1)) {
        ph.x.x = ph.x.target; ph.p.x = ph.p.target; ph.st.x = 0; ph.shift.x = 0;
        ph.x.v = ph.p.v = ph.st.v = ph.shift.v = 0;
        paint(); ph.raf = 0; return;
      }
      ph.raf = requestAnimationFrame(tick);
    };
    ph.raf = requestAnimationFrame(tick);
  }, [paint]);
  useEffect(() => () => cancelAnimationFrame(phys.current.raf), []);

  useLayoutEffect(() => { paint(); }, [paint]);

  // 外部改了选中项(返回键回到第一个标签…):镜片滑过去
  useEffect(() => {
    const ph = phys.current;
    if (ph.pressed) return;
    ph.x.target = centerOf(index);
    ph.hover = index;
    if (matchMedia("(prefers-reduced-motion: reduce)").matches) { ph.x.x = ph.x.target; paint(); return; }
    loop();
  }, [index, centerOf, loop, paint]);

  /** 手指位置 → 镜片中心(两端外有阻尼,导航跟着往那边鼓)。 */
  const follow = (fx: number) => {
    const lo = centerOf(0), hi = centerOf(n - 1);
    const rub = (d: number) => (d * 0.35) / (1 + d / 120);
    const ph = phys.current;
    ph.x.target = fx < lo ? lo - rub(lo - fx) : fx > hi ? hi + rub(fx - hi) : fx;
    ph.shift.target = fx < lo ? -rub(lo - fx) * 0.25 : fx > hi ? rub(fx - hi) * 0.25 : 0;
  };
  const nearest = (x: number) => Math.max(0, Math.min(n - 1, Math.round((x - PAD) / ITEM - 0.5)));
  const local = (e: ReactPointerEvent) => e.clientX - (e.currentTarget as HTMLElement).getBoundingClientRect().left;

  const down = (e: ReactPointerEvent) => {
    if (e.button !== 0) return;
    (e.currentTarget as HTMLElement).setPointerCapture(e.pointerId);
    const ph = phys.current;
    const fx = local(e);
    ph.pressed = true; ph.fx = fx; ph.ft = e.timeStamp; ph.fv = 0; ph.t0 = e.timeStamp; ph.x0 = fx;
    ph.p.target = 1;
    ph.x.k = 160; ph.x.z = 0.78; // 按下:镜片从原来的位置滑到手指下(约 330ms,和 IT 之家点按一样);开始拖动后才紧跟手指
    follow(fx);
    ph.hover = nearest(fx);
    haptic("Tick");
    loop();
  };
  const move = (e: ReactPointerEvent) => {
    const ph = phys.current;
    if (!ph.pressed) return;
    const fx = local(e);
    const dt = Math.max(1, e.timeStamp - ph.ft);
    ph.fv = 0.6 * (((fx - ph.fx) / dt) * 1000) + 0.4 * ph.fv; // px/s
    ph.fx = fx; ph.ft = e.timeStamp;
    if (Math.abs(fx - ph.x0) > 8) { ph.x.k = 900; ph.x.z = 0.82; }
    follow(fx);
    const h = nearest(ph.x.target);
    if (h !== ph.hover) { ph.hover = h; haptic("Tick"); }
  };
  const up = (e: ReactPointerEvent, cancel = false) => {
    const ph = phys.current;
    if (!ph.pressed) return;
    ph.pressed = false;
    ph.p.target = 0;
    ph.shift.target = 0;
    // 松手:拖过的带一点回弹地吸附;没拖动(点按 / 长按)的继续按原来的速度滑过去
    if (Math.abs(ph.fx - ph.x0) > 8) { ph.x.k = 520; ph.x.z = 0.72; }
    // 按手指的位置选(轻点时镜片还没跟过去);快甩时顺着速度多走一段再选最近的一项
    const fling = e.timeStamp - ph.ft < 60 ? ph.fv * 0.12 : 0;
    const i = cancel ? indexRef.current : nearest(ph.x.target + fling);
    ph.x.target = centerOf(i);
    ph.hover = i;
    if (!cancel && items[i].id !== value) onChange(items[i].id);
    loop();
  };

  const row = (filled: boolean) => (
    <div className="flex h-full" style={{ padding: `0 ${PAD}px` }}>
      {items.map((t) => (
        <div key={t.id} className="flex flex-1 flex-col items-center justify-center gap-0.5">
          <span className="size-6">{t.icon(filled)}</span>
          <span className="text-[11px] leading-none font-medium">{t.label}</span>
        </div>
      ))}
    </div>
  );

  return (
    <nav className="pointer-events-none absolute inset-x-0 bottom-0 z-30 flex justify-center" style={{ paddingBottom: "calc(var(--sab) + 12px)" }}>
      {/* 液态玻璃:背后的内容先按位移图在边缘折射(像透镜一样往里弯),再轻微模糊、提高饱和度 */}
      <svg width="0" height="0" className="absolute" aria-hidden>
        {([["bar", W, H, BAR_REFRACT, false], ["lens", lw0, lh0, LENS_REFRACT, true]] as const).map(([k, w, h, scale, disperse]) => (
          <filter key={k} id={ids[k]} x="0" y="0" width={w} height={h} filterUnits="userSpaceOnUse" primitiveUnits="userSpaceOnUse" colorInterpolationFilters="sRGB">
            <feImage href={maps.current[k]} x="0" y="0" width={w} height={h} preserveAspectRatio="none" result="map" />
            {/* 玻璃泡:三个颜色通道分别折射(红最强、蓝最弱)再加回一起,边缘有彩边;玻璃底只折射一次(省 GPU) */}
            {!disperse && <feDisplacementMap in="SourceGraphic" in2="map" scale={scale} xChannelSelector="R" yChannelSelector="G" />}
            {disperse && ([["r", 1 + DISPERSION, "1 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 1 0"], ["g", 1, "0 0 0 0 0 0 1 0 0 0 0 0 0 0 0 0 0 0 1 0"], ["b", 1 - DISPERSION, "0 0 0 0 0 0 0 0 0 0 0 0 1 0 0 0 0 0 1 0"]] as const).map(([c, f, m]) => (
              <Fragment key={c}>
                <feDisplacementMap in="SourceGraphic" in2="map" scale={scale * f} xChannelSelector="R" yChannelSelector="G" result={`d${c}`} />
                <feColorMatrix in={`d${c}`} type="matrix" values={m} result={c} />
              </Fragment>
            ))}
            {disperse && <feComposite in="r" in2="g" operator="arithmetic" k2="1" k3="1" result="rg" />}
            {disperse && <feComposite in="rg" in2="b" operator="arithmetic" k2="1" k3="1" />}
          </filter>
        ))}
      </svg>
      <div
        role="tablist" aria-label="主导航"
        className="pointer-events-auto relative touch-none select-none"
        style={{ width: W, height: H }}
        onPointerDown={down} onPointerMove={move} onPointerUp={(e) => up(e)} onPointerCancel={(e) => up(e, true)}
      >
        <div ref={stage} className="absolute inset-0" style={{ transformOrigin: "50% 50%" }}>
          {/* backdrop-filter 必须直接写在元素上:WebView 里经 CSS 变量传进来的 url(#…) 会被丢掉,整个滤镜(连模糊)失效 */}
          <div className="tabbar-glass absolute inset-0 rounded-full" style={glassFilter(`url(#${ids.bar}) blur(4px) saturate(1.8) brightness(1.05)`)}>
            <div className="tabbar-sheen absolute inset-0 rounded-full" />
          </div>
          <div
            ref={lens} aria-hidden className="tabbar-lens absolute top-0 left-0 rounded-full"
            style={{ width: lw0, height: lh0, transformOrigin: "50% 50%" }}
          >
            <div className="tabbar-lens-fill absolute inset-0 rounded-full" />
            <div className="tabbar-lens-rim absolute inset-0 rounded-full" />
          </div>
          <div ref={base} aria-hidden className="pointer-events-none absolute inset-0 text-foreground">{row(false)}</div>
          <div ref={tintClip} aria-hidden className="pointer-events-none absolute inset-0">
            <div ref={tint} className="absolute inset-0 text-accent">{row(true)}</div>
          </div>
        </div>
        {/* 读屏 / 键盘用的标签按钮(手指操作由上面的指针事件处理) */}
        <div className="absolute inset-0 flex opacity-0" style={{ padding: `0 ${PAD}px` }}>
          {items.map((t) => (
            <button key={t.id} type="button" role="tab" aria-selected={t.id === value} aria-label={t.label} tabIndex={t.id === value ? 0 : -1}
              className="pointer-events-none flex-1" onClick={() => onChange(t.id)} />
          ))}
        </div>
      </div>
    </nav>
  );
}

const glassFilter = (f: string) => ({ backdropFilter: f, WebkitBackdropFilter: f });

/**
 * 液态玻璃的位移图(w×h 的胶囊):R / G 通道是 x / y 方向的位移(128 = 不动)。
 * 离边缘 rim 以内的像素从更靠里的位置取色,越靠边取得越靠里 —— 边缘的内容像被透镜放大、弯进来。
 */
function displacementMap(w: number, h: number, rim: number): string {
  const c = document.createElement("canvas");
  c.width = Math.round(w); c.height = Math.round(h);
  const ctx = c.getContext("2d")!;
  const img = ctx.createImageData(c.width, c.height);
  const r = h / 2, cy = h / 2;
  for (let y = 0; y < c.height; y++) {
    for (let x = 0; x < c.width; x++) {
      const px = x + 0.5, py = y + 0.5;
      const cx = Math.min(Math.max(px, r), w - r);
      let nx = px - cx, ny = py - cy;
      const len = Math.hypot(nx, ny) || 1;
      nx /= len; ny /= len;
      const d = r - Math.hypot(px - cx, py - cy);
      const t = Math.max(0, 1 - d / rim);
      const m = t * t * (3 - 2 * t); // smoothstep:边上最强,往里平滑消失
      const i = (y * c.width + x) * 4;
      img.data[i] = 128 - nx * m * 127;
      img.data[i + 1] = 128 - ny * m * 127;
      img.data[i + 2] = 128;
      img.data[i + 3] = 255;
    }
  }
  ctx.putImageData(img, 0, 0);
  return c.toDataURL();
}

// ---- 导航图标:线框(未选中)/ 实心带白色细节(选中),和 IT 之家一样的两种状态 ----

const svg = (children: ReactNode) => (
  <svg viewBox="0 0 24 24" width="100%" height="100%" fill="none" strokeWidth={1.8} strokeLinecap="round" strokeLinejoin="round">{children}</svg>
);

const FOLDER = "M3.5 7.5a2 2 0 0 1 2-2h3.7l2 2.2h7.3a2 2 0 0 1 2 2v7.8a2 2 0 0 1-2 2h-13a2 2 0 0 1-2-2z";
export const FolderIcon = (filled: boolean) => filled
  ? svg(<><path d={FOLDER} fill="currentColor" stroke="currentColor" /><path d="M4 10.4h16" stroke="var(--tabbar-cut)" strokeWidth={1.6} /></>)
  : svg(<><path d={FOLDER} stroke="currentColor" /><path d="M4 10.4h16" stroke="currentColor" strokeWidth={1.6} /></>);

const DICE = "M6 3.8h12A2.2 2.2 0 0 1 20.2 6v12a2.2 2.2 0 0 1-2.2 2.2H6A2.2 2.2 0 0 1 3.8 18V6A2.2 2.2 0 0 1 6 3.8z";
const PIPS = [[8.4, 8.4], [15.6, 8.4], [12, 12], [8.4, 15.6], [15.6, 15.6]];
export const DiceIcon = (filled: boolean) => svg(<>
  <path d={DICE} stroke="currentColor" fill={filled ? "currentColor" : "none"} />
  {PIPS.map(([x, y]) => <circle key={`${x}${y}`} cx={x} cy={y} r={1.35} fill={filled ? "var(--tabbar-cut)" : "currentColor"} />)}
</>);

/** 齿轮:8 个齿,外径 9.6 / 齿根 7.4。 */
const GEAR = (() => {
  const n = 8, ro = 9.6, ri = 7.4, half = Math.PI / n;
  const pt = (r: number, a: number) => `${(12 + r * Math.cos(a)).toFixed(2)} ${(12 + r * Math.sin(a)).toFixed(2)}`;
  let d = "";
  for (let i = 0; i < n; i++) {
    const a = (i * 2 * Math.PI) / n - Math.PI / 2;
    d += `${i ? "L" : "M"}${pt(ri, a - half * 0.95)}L${pt(ro, a - half * 0.5)}L${pt(ro, a + half * 0.5)}L${pt(ri, a + half * 0.95)}`;
  }
  return d + "Z";
})();
export const GearIcon = (filled: boolean) => svg(<>
  <path d={GEAR} stroke="currentColor" fill={filled ? "currentColor" : "none"} strokeWidth={1.6} />
  <circle cx={12} cy={12} r={2.9} stroke={filled ? "var(--tabbar-cut)" : "currentColor"} fill={filled ? "var(--tabbar-cut)" : "none"} strokeWidth={1.6} />
</>);
