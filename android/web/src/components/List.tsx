import { Button, EmptyState, Spinner } from "@heroui/react";
import { ChevronRight } from "@gravity-ui/icons";
import type { ReactNode } from "react";
import { TONE_BADGE, type Tone } from "../lib/tones";

/**
 * 一组行:白色大圆角卡片(HeroUI Surface 层级),组标题写在卡片里面的左上角(小号弱化色),
 * 行之间不画分隔线,靠间距分组 —— 和 HeroUI 官方示例的菜单卡片一样。
 */
export function ListGroup({ title, footer, children, className = "" }: { title?: ReactNode; footer?: ReactNode; children: ReactNode; className?: string }) {
  return (
    <section className={`mx-auto w-full max-w-2xl px-4 ${className}`}>
      <div className="rounded-3xl bg-surface p-1.5 shadow-surface">
        {title && <h2 className="px-3 pt-2 pb-1 text-xs text-muted">{title}</h2>}
        {children}
      </div>
      {footer && <p className="px-4 pt-2 text-xs leading-relaxed text-muted">{footer}</p>}
    </section>
  );
}

/** 列表行:图标 + 标题 / 说明 + 右侧内容;可点的行按下时整行变灰,带右箭头。tone = 图标放进柔和底色的圆里。 */
export function ListRow({
  icon, tone, title, description, end, onPress, danger, chevron = true, disabled, className = "",
}: {
  icon?: ReactNode;
  tone?: Tone;
  title: ReactNode;
  description?: ReactNode;
  end?: ReactNode;
  onPress?: () => void;
  danger?: boolean;
  chevron?: boolean;
  disabled?: boolean;
  className?: string;
}) {
  const body = (
    <>
      {icon && (tone
        ? <span className={`grid size-9 shrink-0 place-items-center rounded-full ${TONE_BADGE[danger ? "danger" : tone]}`}>{icon}</span>
        : <span className={`grid size-5 shrink-0 place-items-center self-start pt-0.5 ${danger ? "text-danger" : "text-muted"}`}>{icon}</span>)}
      <span className="min-w-0 flex-1">
        <span className={`block text-sm font-medium ${danger ? "text-danger" : "text-foreground"}`}>{title}</span>
        {description && <span className="mt-0.5 block text-xs leading-relaxed text-muted">{description}</span>}
      </span>
      {end && <span className="max-w-[45%] shrink-0 text-right text-sm text-muted">{end}</span>}
      {onPress && chevron && <ChevronRight className="size-4 shrink-0 text-muted" />}
    </>
  );
  const cls = `flex w-full items-center gap-3 rounded-2xl px-3 py-2.5 text-left ${className}`;
  if (!onPress) return <div className={cls}>{body}</div>;
  return (
    <button type="button" disabled={disabled} onClick={onPress} className={`${cls} transition-colors active:bg-default disabled:opacity-50`}>
      {body}
    </button>
  );
}

/** 卡片里的分隔(两组操作之间,例如「危险操作」前)。 */
export function ListDivider() {
  return <div className="mx-3 my-1 h-px bg-separator" />;
}

/** 加载中 / 出错 / 空:用 HeroUI 的 EmptyState 统一样式。 */
export function StateView({
  loading, error, empty, onRetry, icon, className = "",
}: {
  loading?: boolean;
  error?: string | null;
  empty?: ReactNode;
  onRetry?: () => void;
  icon?: ReactNode;
  className?: string;
}) {
  if (loading) return <div className={`grid min-h-64 place-items-center ${className}`}><Spinner size="lg" /></div>;
  if (error)
    return (
      <EmptyState className={`min-h-64 ${className}`}>
        <p className="max-w-80 text-center text-sm text-danger">{error}</p>
        {onRetry && <Button variant="secondary" size="sm" onPress={onRetry}>重试</Button>}
      </EmptyState>
    );
  return (
    <EmptyState className={`min-h-64 ${className}`}>
      {icon && <span className="grid size-14 place-items-center rounded-full bg-accent-soft text-accent">{icon}</span>}
      <p className="max-w-72 text-center text-sm text-muted">{empty ?? "这里什么也没有"}</p>
    </EmptyState>
  );
}
