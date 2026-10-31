export type OptionValue = string | number | boolean;

export type SettingRow =
  | { type: "header"; title: string }
  | { type: "switch"; key: string; title: string; desc?: string }
  | { type: "choice"; key: string; title: string; desc?: string; options: [OptionValue, string][]; requires?: string };
