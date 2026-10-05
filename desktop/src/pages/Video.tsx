import { Card, ItemRow } from "../components";
import { itemsOf } from "../schema";

const META: Record<string, { icon: string; title: string }> = {
  转码: { icon: "film", title: "转码" },
  Jellyfin: { icon: "link", title: "Jellyfin" },
  工具路径: { icon: "wrench", title: "工具路径" },
};

export function Video({ tab }: { tab: string }) {
  const items = itemsOf("video", tab);
  const m = META[tab] ?? META["转码"];
  return <Card icon={m.icon} title={m.title} count={items.length}>{items.map((i) => <ItemRow key={i.id} item={i} />)}</Card>;
}
