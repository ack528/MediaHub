// 点缀色:图标底色用 HeroUI 的语义柔和色(浅色 / 深色主题自动适配),头像占位用彩色渐变(参考 HeroUI 官方示例的头像组)。

export type Tone = "accent" | "success" | "warning" | "danger" | "violet" | "cyan" | "pink" | "neutral";

/** 圆形图标底:柔和底色 + 同色系图标。 */
export const TONE_BADGE: Record<Tone, string> = {
  accent: "bg-accent-soft text-accent",
  success: "bg-success-soft text-success",
  warning: "bg-warning-soft text-warning",
  danger: "bg-danger-soft text-danger",
  violet: "bg-violet-500/15 text-violet-600 dark:text-violet-300",
  cyan: "bg-cyan-500/15 text-cyan-600 dark:text-cyan-300",
  pink: "bg-pink-500/15 text-pink-600 dark:text-pink-300",
  neutral: "bg-default text-default-foreground",
};

const GRADIENTS = [
  "from-sky-400 to-indigo-500",
  "from-emerald-400 to-teal-500",
  "from-violet-400 to-fuchsia-500",
  "from-amber-400 to-orange-500",
  "from-rose-400 to-pink-500",
  "from-cyan-400 to-blue-500",
];

/** 按名字固定挑一个渐变(同一个文件夹每次颜色一样)。 */
export function gradientFor(key: string): string {
  let h = 0;
  for (const c of key) h = (h * 31 + c.charCodeAt(0)) >>> 0;
  return `bg-gradient-to-br ${GRADIENTS[h % GRADIENTS.length]}`;
}
