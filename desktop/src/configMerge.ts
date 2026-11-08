// Apply only fields changed in this UI to the latest on-disk document. This
// preserves edits made by another client while the management window is open.
export function applyChanges(base: any, desired: any, latest: any): any {
  if (JSON.stringify(base) === JSON.stringify(desired)) return latest;
  if (base && desired && latest && typeof base === "object" && typeof desired === "object" && typeof latest === "object" && !Array.isArray(base) && !Array.isArray(desired) && !Array.isArray(latest)) {
    const out = { ...latest };
    for (const key of Object.keys(desired)) out[key] = applyChanges(base[key], desired[key], latest[key]);
    return out;
  }
  return desired;
}
