// 连接质量:每 2 秒量一次手机到服务器的往返(原生 net.ping,在保活连接上量),同时读服务器那边看到的穿透状态 / 延迟(/server/link)。
// 只有页面在前台、需要显示的时候才量(enabled),离开就停,不白白占用穿透服务器的流量。
import { useEffect, useMemo, useRef, useState } from "react";
import { api, call } from "./bridge";

export interface PingInfo { ms: number; protocol: string; tls: string; lan: boolean; local?: boolean }
export interface LinkSample { at: number; ms: number | null }
export interface LatStat { last: number; avg: number; min: number; max: number; jitter: number; lossPct: number; n: number }

/** 服务器说的:传输协议能力 + 穿透状态和它测到的延迟(没有穿透时 tunnel = null)。 */
export interface ServerLink {
  transport: { http1: boolean; http2: boolean; http3: boolean; quic: boolean; udp: boolean; tunnelProtocol: string; tunnelUDP: boolean };
  tunnel: null | {
    enabled: boolean; type: string; state: string; message?: string; restarts: number;
    latency: { chain: { last: number; avg: number; lossPct: number; n: number }; server: { last: number; avg: number; lossPct: number; n: number }; protocol: string; udp: boolean };
  };
}

const KEEP = 60;

export function statOf(samples: LinkSample[]): LatStat {
  const ok = samples.filter((s) => s.ms != null).map((s) => s.ms as number);
  const n = samples.length;
  if (!ok.length) return { last: -1, avg: 0, min: 0, max: 0, jitter: 0, lossPct: n ? 100 : 0, n };
  let jit = 0;
  for (let i = 1; i < ok.length; i++) jit += Math.abs(ok[i] - ok[i - 1]);
  const last = samples[n - 1].ms;
  return {
    last: last ?? -1, avg: ok.reduce((a, b) => a + b, 0) / ok.length, min: Math.min(...ok), max: Math.max(...ok),
    jitter: ok.length > 1 ? jit / (ok.length - 1) : 0, lossPct: ((n - ok.length) * 100) / n, n,
  };
}

/** 延迟的颜色档位:≤ 80 ms 流畅,≤ 200 ms 一般,再高有明显延迟感。 */
export function latTone(ms: number | null | undefined): "success" | "warning" | "danger" | "default" {
  return ms == null || ms < 0 ? "default" : ms <= 80 ? "success" : ms <= 200 ? "warning" : "danger";
}

export function useLinkQuality(enabled: boolean, withServer = true) {
  const [samples, setSamples] = useState<LinkSample[]>([]);
  const [info, setInfo] = useState<PingInfo | null>(null);
  const [server, setServer] = useState<ServerLink | null>(null);
  const [local, setLocal] = useState(false);
  const tick = useRef(0);

  useEffect(() => {
    if (!enabled) return;
    let alive = true;
    const run = async () => {
      const n = tick.current++;
      try {
        const r = await call<PingInfo>("net.ping");
        if (!alive) return;
        if (r.local) { setLocal(true); return; }
        setLocal(false);
        setInfo(r);
        setSamples((s) => [...s, { at: Date.now(), ms: r.ms }].slice(-KEEP));
      } catch {
        if (alive) setSamples((s) => [...s, { at: Date.now(), ms: null }].slice(-KEEP));
      }
      // 服务器那边的状态不用那么勤:每 3 次(约 6 秒)取一次;服务端看到有人在取就每 2 秒采样一次
      if (withServer && n % 3 === 0) api<ServerLink>("/api/v1/server/link?watch=1").then((l) => alive && setServer(l)).catch(() => {});
    };
    run();
    const id = setInterval(run, 2000);
    return () => { alive = false; clearInterval(id); };
  }, [enabled, withServer]);

  const stat = useMemo(() => statOf(samples), [samples]);
  return { samples, stat, info, server, local };
}
