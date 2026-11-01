import { useState } from "react";
import { Play } from "@gravity-ui/icons";
import { fileUrl, formatDuration, stripExt, thumbUrl } from "../lib/media";
import { thumbHashUrl } from "../lib/thumbhash";
import type { Item } from "../lib/types";

/** 缩略图:ThumbHash 模糊底图先显示,图片到了淡入;WebP 缩略图失败时退回原图。 */
export function Thumb({ item, width, className = "", fit = "cover" }: { item: Item; width: number; className?: string; fit?: "cover" | "contain" }) {
  const [src, setSrc] = useState(() => thumbUrl(item, width));
  const [loaded, setLoaded] = useState(false);
  const [failed, setFailed] = useState(false);
  const ph = thumbHashUrl(item.thumbhash);
  return (
    <div
      className={`${className.includes("absolute") ? "" : "relative"} overflow-hidden bg-surface-tertiary ${className}`}
      style={ph ? { backgroundImage: `url(${ph})`, backgroundSize: "cover", backgroundPosition: "center" } : undefined}
    >
      {!failed && (
        <img
          src={src}
          alt=""
          loading="lazy"
          decoding="async"
          draggable={false}
          className={`absolute inset-0 size-full ${fit === "cover" ? "object-cover" : "object-contain"} ${loaded ? "img-fade" : "opacity-0"}`}
          onLoad={() => setLoaded(true)}
          onError={() => {
            if (src.includes("fmt=webp")) setSrc(fileUrl(item));
            else setFailed(true);
          }}
        />
      )}
      {failed && <div className="absolute inset-0 grid place-items-center text-xs text-muted">{item.problem ?? "无法显示"}</div>}
    </div>
  );
}

/** 网格里的一格:缩略图 + 视频时长 / 动图标记 + 文件名。 */
export function MediaTile({
  item, width, showName, showDuration, onOpen, onLongPress, className = "", style,
}: {
  item: Item;
  width: number;
  showName: boolean;
  showDuration: boolean;
  onOpen: () => void;
  onLongPress?: () => void;
  className?: string;
  style?: React.CSSProperties;
}) {
  const press = useLongPress(onOpen, onLongPress);
  const isVideo = item.type === "video";
  return (
    <button
      type="button"
      data-item={item.id}
      data-hero
      className={`tile group relative block w-full overflow-hidden text-left outline-none focus-visible:ring-2 focus-visible:ring-accent ${className}`}
      style={style}
      {...press}
    >
      <Thumb item={item} width={width} className="size-full transition-transform duration-150 group-active:scale-[0.97]" />
      {(isVideo || item.type === "gif") && (
        <span className="pointer-events-none absolute top-1.5 right-1.5 flex items-center gap-1 rounded-full bg-black/55 px-1.5 py-0.5 text-[11px] font-medium text-white backdrop-blur-sm">
          {isVideo ? (
            <>
              <Play className="size-3" />
              {showDuration && formatDuration(item.durationMs)}
            </>
          ) : "GIF"}
        </span>
      )}
      {showName && (
        <span className="pointer-events-none absolute inset-x-0 bottom-0 truncate bg-gradient-to-t from-black/70 to-transparent px-1.5 pt-4 pb-1 text-[11px] text-white">
          {stripExt(item.name)}
        </span>
      )}
      {(item.flags?.corrupt || item.flags?.truncated) && (
        <span className="pointer-events-none absolute top-1.5 left-1.5 rounded-full bg-danger px-1.5 py-0.5 text-[10px] text-danger-foreground">
          {item.flags.corrupt ? "损坏" : "不完整"}
        </span>
      )}
    </button>
  );
}

/** 点按 / 长按(500ms)区分;长按时阻止随后的点击。 */
export function useLongPress(onPress: () => void, onLong?: () => void) {
  const [state] = useState(() => ({ timer: 0 as number | 0, long: false, x: 0, y: 0 }));
  const clear = () => { if (state.timer) { clearTimeout(state.timer); state.timer = 0; } };
  return {
    onPointerDown: (e: React.PointerEvent) => {
      state.long = false; state.x = e.clientX; state.y = e.clientY;
      if (onLong) state.timer = window.setTimeout(() => { state.long = true; state.timer = 0; onLong(); }, 500);
    },
    onPointerMove: (e: React.PointerEvent) => { if (Math.abs(e.clientX - state.x) + Math.abs(e.clientY - state.y) > 10) clear(); },
    onPointerUp: clear,
    onPointerCancel: clear,
    onContextMenu: (e: React.MouseEvent) => e.preventDefault(),
    onClick: () => { if (!state.long) onPress(); state.long = false; },
  };
}
