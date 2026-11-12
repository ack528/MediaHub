import { AlertDialog, Button, Description, Label, ListBox, Select, Switch } from "@heroui/react";
import { useState, type ReactNode } from "react";
import { errorText, haptic } from "../../lib/bridge";
import { useSettings } from "../../lib/app";
import { ListGroup, ListRow } from "../../components/List";
import type { OptionValue, SettingRow } from "./types";
import type { Tone } from "../../lib/tones";
import { toast } from "@heroui/react";

/** 只属于旧版 Compose 界面的设置(Activity 转场、聊天气泡…):新界面里不显示,值原样保留。「液态玻璃」现在控制首页底部导航,又显示出来了。 */
const OBSOLETE = new Set(["imageDiskCacheMb", "videoCacheMb", "motion", "fineMotion", "avatarShape", "chatBackground", "swipeGroups", "pauseThumbsWhenFast", "preloadCount", "imageMemPercent", "hardwareBitmaps", "crossfade"]);

/** 把设置清单按小节标题分成若干卡片渲染。 */
export function SchemaRows({ rows, hide = [] }: { rows: SettingRow[]; hide?: string[] }) {
  const groups: { title?: string; rows: SettingRow[] }[] = [];
  for (const r of rows) {
    if (r.type === "header") groups.push({ title: r.title, rows: [] });
    else if (!OBSOLETE.has(r.key) && !hide.includes(r.key)) {
      if (!groups.length) groups.push({ rows: [] });
      groups[groups.length - 1].rows.push(r);
    }
  }
  return (
    <>
      {groups.filter((g) => g.rows.length).map((g, i) => (
        <ListGroup key={i} title={g.title}>
          {g.rows.map((r) => (r.type === "switch" ? <SwitchRow key={r.key} row={r} /> : r.type === "choice" ? <ChoiceRow key={r.key} row={r} /> : null))}
        </ListGroup>
      ))}
    </>
  );
}

export function SwitchRow({ row }: { row: Extract<SettingRow, { type: "switch" }> }) {
  const [s, update] = useSettings();
  return (
    <div className="px-3 py-2.5">
      <Switch className="w-full" isSelected={!!s[row.key]} onChange={(v) => { haptic("Click"); update({ [row.key]: v }); }}>
        <Switch.Content className="w-full justify-between">
          <Label className="text-sm font-medium">{row.title}</Label>
          <Switch.Control className="shrink-0">
            <Switch.Thumb />
          </Switch.Control>
        </Switch.Content>
        {row.desc && <Description className="ps-0 pe-14 text-xs leading-relaxed">{row.desc}</Description>}
      </Switch>
    </div>
  );
}

export function ChoiceRow({ row }: { row: Extract<SettingRow, { type: "choice" }> }) {
  const [s, update] = useSettings();
  const cur = s[row.key] as OptionValue;
  const idx = row.options.findIndex(([v]) => v === cur);
  return (
    <ChoiceField
      title={row.title} desc={row.desc} options={row.options.map(([, l]) => l)}
      selected={idx >= 0 ? idx : null} fallback={String(cur ?? "")}
      onSelect={(i) => { haptic("Tick"); update({ [row.key]: row.options[i][0] }); }}
    />
  );
}

/**
 * 一行带下拉选择:和其它行一样,左边标题 / 说明,右边是当前值(弱化色)+ 箭头;点值弹出 HeroUI Select 的列表。
 * 触发器去掉输入框的底色和阴影,不在卡片里再套一个白框。
 */
export function ChoiceField({ title, desc, options, selected, fallback, onSelect }: {
  title: string; desc?: string; options: string[]; selected: number | null; fallback?: string; onSelect: (i: number) => void;
}) {
  return (
    <Select value={selected == null ? null : String(selected)} onChange={(k) => { if (k != null) onSelect(Number(k)); }} placeholder={fallback} aria-label={title}
      className="w-full gap-0 rounded-2xl px-3 py-2.5">
      <div className="flex items-center gap-3">
        <Label className="min-w-0 flex-1 text-sm font-medium text-foreground">{title}</Label>
        <Select.Trigger className="h-auto min-h-0 w-auto max-w-[55%] gap-1 border-0 bg-transparent p-0 text-sm text-muted shadow-none data-[hovered=true]:bg-transparent data-[pressed=true]:opacity-70">
          <Select.Value className="min-w-0 truncate text-sm text-muted" />
          <Select.Indicator className="static size-4 shrink-0 translate-y-0 text-muted" />
        </Select.Trigger>
      </div>
      {desc && <Description className="mt-0.5 pe-6 text-xs leading-relaxed">{desc}</Description>}
      <Select.Popover className="min-w-56">
        <ListBox>
          {options.map((l, i) => (
            <ListBox.Item key={i} id={String(i)} textValue={l}>
              {l}
              <ListBox.ItemIndicator />
            </ListBox.Item>
          ))}
        </ListBox>
      </Select.Popover>
    </Select>
  );
}

/** 操作行:可选二次确认(危险操作红色)。action 抛错时提示。 */
export function ActionRow({ title, description, confirm, danger, icon, tone, action, end }: {
  title: string; description?: ReactNode; confirm?: string; danger?: boolean; icon?: ReactNode; tone?: Tone; end?: ReactNode; action: () => unknown | Promise<unknown>;
}) {
  const [ask, setAsk] = useState(false);
  const [busy, setBusy] = useState(false);
  const run = async () => {
    setBusy(true);
    try { await action(); } catch (e) { toast.danger(errorText(e)); } finally { setBusy(false); }
  };
  return (
    <>
      <ListRow icon={icon} tone={tone} title={title} description={description} danger={danger} disabled={busy} end={end}
        onPress={() => (confirm ? setAsk(true) : run())} chevron={false} />
      {confirm && (
        <AlertDialog.Backdrop isOpen={ask} onOpenChange={setAsk}>
          <AlertDialog.Container placement="center">
            <AlertDialog.Dialog className="sm:max-w-sm">
              <AlertDialog.Header>
                {danger && <AlertDialog.Icon status="danger" />}
                <AlertDialog.Heading>{title}</AlertDialog.Heading>
              </AlertDialog.Header>
              <AlertDialog.Body><p className="text-sm text-muted">{confirm}</p></AlertDialog.Body>
              <AlertDialog.Footer>
                <Button variant="tertiary" slot="close">取消</Button>
                <Button variant={danger ? "danger" : "primary"} onPress={() => { setAsk(false); run(); }}>确定</Button>
              </AlertDialog.Footer>
            </AlertDialog.Dialog>
          </AlertDialog.Container>
        </AlertDialog.Backdrop>
      )}
    </>
  );
}

export function InfoRow({ title, value }: { title: string; value: ReactNode }) {
  return (
    <div className="flex items-start gap-4 px-3 py-2.5">
      <span className="shrink-0 text-sm font-medium">{title}</span>
      <span className="selectable min-w-0 flex-1 text-right text-sm break-all text-muted">{value}</span>
    </div>
  );
}

export function Note({ children }: { children: ReactNode }) {
  return <p className="px-6 text-xs leading-relaxed text-muted">{children}</p>;
}
