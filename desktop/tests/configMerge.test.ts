import { strict as assert } from "node:assert";
import { test } from "node:test";
import { applyChanges } from "../src/configMerge.ts";

test("a stale window preserves external FRP changes while saving another setting", () => {
  const base = { tunnel: { enabled: false, port: 0 }, video: { quality: 1 } };
  const local = { tunnel: { enabled: false, port: 0 }, video: { quality: 2 } };
  const disk = { tunnel: { enabled: true, port: 27673 }, video: { quality: 1 }, custom: 5 };
  assert.deepEqual(applyChanges(base, local, disk), { tunnel: { enabled: true, port: 27673 }, video: { quality: 2 }, custom: 5 });
});
test("edits made while a save is pending are retained", () => {
  const snapshot = { a: 1, b: 1 };
  assert.deepEqual(applyChanges(snapshot, { a: 2, b: 1 }, { a: 1, b: 3 }), { a: 2, b: 3 });
});
