// 20 秒以内的短视频:列表里滑到屏幕附近就让原生把整个文件预加载进视频缓存,点开直接从本地播放。
// 原生那边(VideoCache.preloadShort)还会按设置(总是 / 仅 Wi-Fi / 关闭)、去重、限制同时下载数;这里只负责挑出"屏幕附近的短视频"。
import { useEffect, type RefObject } from "react";
import { call, inApp } from "./bridge";
import { useApp } from "./app";
import { runWhenCalm } from "./hero";
import type { Item } from "./types";

const MAX_BYTES = 60 * 1024 * 1024;
const sent = new Set<string>();

/** 时长上限(毫秒)来自设置 → 视频播放 → 短视频预加载时长,默认 20 秒。 */
export function isShortVideo(it: Item, maxMs = 20_000): boolean {
  const size = it.size ?? 0;
  return it.type === "video" && !!it.durationMs && it.durationMs > 0 && it.durationMs <= maxMs && size > 0 && size <= MAX_BYTES &&
    !it.flags?.corrupt && !it.flags?.truncated;
}

/** scroller 里上下各一屏范围内的短视频交给原生预加载:列表变化后、滚动停下 400ms 后各检查一次。 */
export function useShortVideoPreload(scroller: RefObject<HTMLElement | null>, items: Item[]) {
  const maxMs = (useApp().settings.shortVideoSeconds ?? 20) * 1000;
  useEffect(() => {
    const sc = scroller.current;
    const isShort = (it: Item) => isShortVideo(it, maxMs);
    if (!sc || !inApp || !items.some(isShort)) return;
    const byId = new Map(items.map((x) => [x.id, x]));
    const check = () => {
      const r = sc.getBoundingClientRect();
      const top = r.top - r.height, bottom = r.bottom + r.height;
      const pick: Item[] = [];
      for (const el of sc.querySelectorAll<HTMLElement>("[data-item]")) {
        const it = byId.get(el.dataset.item ?? "");
        if (!it || sent.has(it.id) || !isShort(it)) continue;
        const b = el.getBoundingClientRect();
        if (b.bottom > top && b.top < bottom) { pick.push(it); sent.add(it.id); }
      }
      if (pick.length) runWhenCalm(() => call("video.preload", { items: pick }).catch(() => { pick.forEach((it) => sent.delete(it.id)); }));
    };
    let t = window.setTimeout(check, 600);
    const onScroll = () => { clearTimeout(t); t = window.setTimeout(check, 400); };
    sc.addEventListener("scroll", onScroll, { passive: true });
    return () => { clearTimeout(t); sc.removeEventListener("scroll", onScroll); };
  }, [scroller, items, maxMs]);
}
