import { Button } from "@heroui/react";
import { ArrowLeft } from "@gravity-ui/icons";
import type { ReactNode } from "react";

/**
 * 页面顶栏:吸顶、和页面一样的实色背景(不用 backdrop-filter:标签切换淡入时 WebView 画不出模糊,顶栏会先消失再闪出来)。左侧返回,中间标题 / 副标题,右侧操作按钮。
 * large = 首页的大标题样式(没有返回键)。
 */
export function PageHeader({
  title, subtitle, onBack, actions, large, children, leading,
}: {
  title: ReactNode;
  subtitle?: ReactNode;
  onBack?: () => void;
  actions?: ReactNode;
  large?: boolean;
  /** 顶栏下方附加的一行(标签栏、搜索框…) */
  children?: ReactNode;
  leading?: ReactNode;
}) {
  return (
    <header className="sticky top-0 z-20 bg-background pt-safe">
      <div className={`flex items-center gap-1 px-2 ${large ? "h-16" : "h-14"}`}>
        {onBack && (
          <Button isIconOnly variant="ghost" aria-label="返回" onPress={onBack}>
            <ArrowLeft className="size-5" />
          </Button>
        )}
        {leading}
        <div className={`min-w-0 flex-1 ${onBack || leading ? "px-1" : "px-3"}`}>
          <h1 className={`truncate font-semibold text-foreground ${large ? "text-2xl" : "text-base leading-tight"}`}>{title}</h1>
          {subtitle && <p className="truncate text-xs text-muted">{subtitle}</p>}
        </div>
        {actions && <div className="flex shrink-0 items-center gap-0.5">{actions}</div>}
      </div>
      {children}
    </header>
  );
}

export function HeaderAction({ label, onPress, children }: { label: string; onPress: () => void; children: ReactNode }) {
  return (
    <Button isIconOnly variant="ghost" aria-label={label} onPress={onPress}>
      {children}
    </Button>
  );
}
