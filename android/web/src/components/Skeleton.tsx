/** 首屏加载时的占位骨架(代替转圈):形状和真实内容一致,内容到了直接替换,不会跳。 */

const block = "bg-surface-tertiary animate-pulse motion-reduce:animate-none";

/** 网格:columns 列、rows 行的正方形格子。 */
export function GridSkeleton({ columns = 3, rows = 7, gap = 1 }: { columns?: number; rows?: number; gap?: number }) {
  return (
    <div aria-busy aria-label="加载中" className="grid" style={{ gridTemplateColumns: `repeat(${columns}, minmax(0, 1fr))`, gap }}>
      {Array.from({ length: columns * rows }, (_, i) => (
        <div key={i} className={`aspect-square ${block}`} style={{ animationDelay: `${(i % columns) * 60 + Math.floor(i / columns) * 40}ms` }} />
      ))}
    </div>
  );
}

/** 文件夹列表:头像 + 两行文字。 */
export function RowsSkeleton({ rows = 9 }: { rows?: number }) {
  return (
    <div aria-busy aria-label="加载中" className="flex flex-col">
      {Array.from({ length: rows }, (_, i) => (
        <div key={i} className="flex items-center gap-3 px-4 py-3">
          <div className={`size-12 shrink-0 rounded-xl ${block}`} style={{ animationDelay: `${i * 50}ms` }} />
          <div className="flex min-w-0 flex-1 flex-col gap-2">
            <div className={`h-3.5 rounded-full ${block}`} style={{ width: `${55 + ((i * 37) % 30)}%`, animationDelay: `${i * 50}ms` }} />
            <div className={`h-3 rounded-full ${block}`} style={{ width: `${35 + ((i * 23) % 25)}%`, animationDelay: `${i * 50 + 30}ms` }} />
          </div>
        </div>
      ))}
    </div>
  );
}

/** 时间线:几张不同比例的圆角大图 + 文件名。 */
export function TimelineSkeleton({ widthPct = 72 }: { widthPct?: number }) {
  const ratios = [1.33, 0.75, 1.78, 1, 1.33];
  return (
    <div aria-busy aria-label="加载中" className="mx-auto flex max-w-xl flex-col gap-4 px-3 pt-1">
      {ratios.map((r, i) => (
        <div key={i} style={{ width: `${widthPct}%` }}>
          <div className={`w-full rounded-2xl ${block}`} style={{ aspectRatio: String(r), animationDelay: `${i * 80}ms` }} />
          <div className={`mt-1.5 h-3 w-2/5 rounded-full ${block}`} />
        </div>
      ))}
    </div>
  );
}

/** 瀑布流:两列不同高度的卡片。 */
export function MasonrySkeleton({ columns = 2 }: { columns?: number }) {
  const hs = [[1.2, 0.8, 1.5], [0.9, 1.4, 1]];
  return (
    <div aria-busy aria-label="加载中" className="flex items-start gap-3 px-4 pt-1">
      {Array.from({ length: columns }, (_, c) => (
        <div key={c} className="flex min-w-0 flex-1 flex-col gap-3">
          {hs[c % 2].map((r, i) => (
            <div key={i} className="rounded-3xl bg-surface p-2 shadow-surface">
              <div className={`w-full rounded-2xl ${block}`} style={{ aspectRatio: String(1 / r), animationDelay: `${(c + i) * 80}ms` }} />
              <div className={`mt-2 h-3 w-3/4 rounded-full ${block}`} />
            </div>
          ))}
        </div>
      ))}
    </div>
  );
}
