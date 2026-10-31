// ThumbHash(https://github.com/evanw/thumbhash,MIT)解码:服务端给的约 25 字节 → 模糊小图,缩略图到之前先显示。
// 逻辑与原生 ThumbHash.kt 相同,结果画到 canvas 转成 data URL 并缓存。

const cache = new Map<string, string>();

function decode(hash: Uint8Array): { w: number; h: number; rgba: Uint8ClampedArray } | null {
  if (hash.length < 5) return null;
  const header24 = hash[0] | (hash[1] << 8) | (hash[2] << 16);
  const header16 = hash[3] | (hash[4] << 8);
  const lDc = (header24 & 63) / 63;
  const pDc = ((header24 >> 6) & 63) / 31.5 - 1;
  const qDc = ((header24 >> 12) & 63) / 31.5 - 1;
  const lScale = ((header24 >> 18) & 31) / 31;
  const hasAlpha = header24 >> 23 !== 0;
  const pScale = ((header16 >> 3) & 63) / 63;
  const qScale = ((header16 >> 9) & 63) / 63;
  const isLandscape = header16 >> 15 !== 0;
  const lx = Math.max(3, isLandscape ? (hasAlpha ? 5 : 7) : header16 & 7);
  const ly = Math.max(3, isLandscape ? header16 & 7 : hasAlpha ? 5 : 7);
  if (hasAlpha && hash.length < 6) return null;
  const acStart = hasAlpha ? 6 : 5;
  let acIndex = 0;
  const channel = (nx: number, ny: number, scale: number): number[] | null => {
    const out: number[] = [];
    for (let cy = 0; cy < ny; cy++) {
      for (let cx = cy ? 0 : 1; cx * ny < nx * (ny - cy); cx++) {
        const idx = acStart + (acIndex >> 1);
        if (idx >= hash.length) return null;
        out.push((((hash[idx] >> ((acIndex & 1) << 2)) & 15) / 7.5 - 1) * scale);
        acIndex++;
      }
    }
    return out;
  };
  const lAc = channel(lx, ly, lScale);
  const pAc = channel(3, 3, pScale * 1.25);
  const qAc = channel(3, 3, qScale * 1.25);
  if (!lAc || !pAc || !qAc) return null;

  const ratio = lx / ly;
  const w = Math.max(1, Math.round(ratio > 1 ? 32 : 32 * ratio));
  const h = Math.max(1, Math.round(ratio > 1 ? 32 / ratio : 32));
  const rgba = new Uint8ClampedArray(w * h * 4);
  const fx = new Float32Array(Math.max(lx, 3));
  const fy = new Float32Array(Math.max(ly, 3));
  for (let y = 0, i = 0; y < h; y++) {
    for (let cy = 0; cy < fy.length; cy++) fy[cy] = Math.cos((Math.PI / h) * (y + 0.5) * cy);
    for (let x = 0; x < w; x++, i += 4) {
      for (let cx = 0; cx < fx.length; cx++) fx[cx] = Math.cos((Math.PI / w) * (x + 0.5) * cx);
      let l = lDc, p = pDc, q = qDc;
      for (let cy = 0, j = 0; cy < ly; cy++) {
        const fy2 = fy[cy] * 2;
        for (let cx = cy ? 0 : 1; cx * ly < lx * (ly - cy); cx++, j++) l += lAc[j] * fx[cx] * fy2;
      }
      for (let cy = 0, j = 0; cy < 3; cy++) {
        const fy2 = fy[cy] * 2;
        for (let cx = cy ? 0 : 1; cx < 3 - cy; cx++, j++) {
          const f = fx[cx] * fy2;
          p += pAc[j] * f;
          q += qAc[j] * f;
        }
      }
      const b = l - (2 / 3) * p;
      const r = (3 * l - b + q) / 2;
      const g = r - q;
      rgba[i] = 255 * r;
      rgba[i + 1] = 255 * g;
      rgba[i + 2] = 255 * b;
      rgba[i + 3] = 255;
    }
  }
  return { w, h, rgba };
}

/** 服务端返回标准 Base64;解不出来返回 undefined(调用方用纯色占位)。 */
export function thumbHashUrl(b64?: string | null): string | undefined {
  if (!b64) return undefined;
  const hit = cache.get(b64);
  if (hit) return hit;
  try {
    const bin = atob(b64);
    const bytes = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
    const d = decode(bytes);
    if (!d) return undefined;
    const canvas = document.createElement("canvas");
    canvas.width = d.w;
    canvas.height = d.h;
    const ctx = canvas.getContext("2d");
    if (!ctx) return undefined;
    ctx.putImageData(new ImageData(d.rgba as unknown as Uint8ClampedArray<ArrayBuffer>, d.w, d.h), 0, 0);
    const url = canvas.toDataURL();
    if (cache.size > 800) cache.delete(cache.keys().next().value!);
    cache.set(b64, url);
    return url;
  } catch {
    return undefined;
  }
}
