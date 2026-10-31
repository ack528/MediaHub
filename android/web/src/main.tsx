import "./index.css";
import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { call, inApp } from "./lib/bridge";
import type { BootInfo } from "./lib/types";
import { App } from "./App";

async function start() {
  if (!inApp) (await import("./lib/mock")).installBrowserMock();
  const root = createRoot(document.getElementById("root")!);
  try {
    const boot = await call<BootInfo>("boot");
    root.render(<StrictMode><App boot={boot} /></StrictMode>);
  } catch (e) {
    root.render(<div className="p-6 text-danger">启动失败:{String((e as Error)?.message ?? e)}</div>);
  }
}

start();
