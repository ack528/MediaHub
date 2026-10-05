// 通过 WebView2 远程调试端口(CDP)驱动真实的 Tauri 窗口,用于自动化验收。
// 用法:node testdata/scripts/cdp.mjs <端口> eval "<JS 表达式>"
//       node testdata/scripts/cdp.mjs <端口> shot <输出.png>
// 表达式可用 await;返回值以 JSON 打印。
import fs from "node:fs";

const [, , port, cmd, arg] = process.argv;
const targets = await (await fetch(`http://127.0.0.1:${port}/json`)).json();
const page = targets.find((t) => t.type === "page");
if (!page) { console.error("没有找到页面目标", targets); process.exit(2); }

const ws = new WebSocket(page.webSocketDebuggerUrl);
await new Promise((r, j) => { ws.onopen = r; ws.onerror = j; });
let id = 0;
const pending = new Map();
ws.onmessage = (m) => { const d = JSON.parse(m.data); if (d.id && pending.has(d.id)) { pending.get(d.id)(d); pending.delete(d.id); } };
const send = (method, params = {}) => new Promise((res) => { const i = ++id; pending.set(i, res); ws.send(JSON.stringify({ id: i, method, params })); });

if (cmd === "eval") {
  const r = await send("Runtime.evaluate", { expression: `(async()=>{ return JSON.stringify(await (${arg})) })()`, awaitPromise: true, returnByValue: true });
  if (r.result?.exceptionDetails) console.log("EXCEPTION", JSON.stringify(r.result.exceptionDetails.exception?.description ?? r.result.exceptionDetails));
  else console.log(r.result?.result?.value ?? "undefined");
} else if (cmd === "shot") {
  const r = await send("Page.captureScreenshot", { format: "png" });
  fs.writeFileSync(arg, Buffer.from(r.result.data, "base64"));
  console.log("saved", arg);
}
ws.close();
