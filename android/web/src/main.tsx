import "./index.css";
import { StrictMode, useEffect, useState } from "react";
import { createRoot } from "react-dom/client";
import { Button, Spinner } from "@heroui/react";
import { call, errorText, inApp } from "./lib/bridge";
import type { BootInfo } from "./lib/types";
import { App } from "./App";

/** 启动:先显示加载圈,取到原生的启动信息再进入界面;超时 / 出错时显示原因和重试,不会停在白屏。 */
function Boot() {
  const [boot, setBoot] = useState<BootInfo | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [tries, setTries] = useState(0);

  useEffect(() => {
    let alive = true;
    setError(null);
    const timer = setTimeout(() => { if (alive) setError("启动超时:原生端没有响应"); }, 15000);
    call<BootInfo>("boot")
      .then((b) => { if (alive) setBoot(b); })
      .catch((e) => { if (alive) setError(errorText(e)); })
      .finally(() => clearTimeout(timer));
    return () => { alive = false; clearTimeout(timer); };
  }, [tries]);

  if (boot) return <App boot={boot} />;
  return (
    <div className="grid h-full place-items-center p-6">
      {error ? (
        <div className="flex max-w-80 flex-col items-center gap-3 text-center">
          <p className="text-sm text-danger">启动失败:{error}</p>
          <Button variant="secondary" onPress={() => setTries((n) => n + 1)}>重试</Button>
        </div>
      ) : <Spinner size="lg" />}
    </div>
  );
}

/** 渲染期间未捕获的错误:显示出来(并写进原生日志),而不是留一张白屏。 */
function showFatal(msg: string) {
  console.error(msg);
  const el = document.getElementById("root");
  if (el && !el.childElementCount) el.innerHTML = `<pre style="padding:24px;white-space:pre-wrap;color:#d33;font-size:12px">${msg.replace(/</g, "&lt;")}</pre>`;
}
window.addEventListener("error", (e) => showFatal(`${e.message}\n${e.filename}:${e.lineno}`));
window.addEventListener("unhandledrejection", (e) => showFatal(String(e.reason?.stack ?? e.reason)));

async function start() {
  if (!inApp) (await import("./lib/mock")).installBrowserMock();
  createRoot(document.getElementById("root")!).render(<StrictMode><Boot /></StrictMode>);
}

start();
