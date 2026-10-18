import { Component, type ReactNode } from "react";

// 错误边界:页面渲染出错时显示错误信息和「重试 / 复制」,而不是整个管理程序白屏。
export class ErrorBoundary extends Component<{ children: ReactNode; label?: string }, { err: Error | null }> {
  state = { err: null as Error | null };
  static getDerivedStateFromError(err: Error) { return { err }; }
  componentDidCatch(err: Error, info: { componentStack?: string | null }) {
    console.error("界面渲染出错", err, info.componentStack);
  }
  render() {
    const { err } = this.state;
    if (!err) return this.props.children;
    const text = `${this.props.label ?? "界面"} 出错:${err.message}\n${err.stack ?? ""}`;
    return (
      <div style={{ padding: 24, maxWidth: 820 }}>
        <h2 style={{ margin: "0 0 8px" }}>这个页面出错了</h2>
        <p style={{ color: "var(--text-2)", margin: "0 0 12px" }}>其它页面不受影响。把下面的信息复制下来发给开发者,会很快修好。</p>
        <pre className="code" style={{ whiteSpace: "pre-wrap", maxHeight: 260, overflow: "auto" }}>{text}</pre>
        <div style={{ display: "flex", gap: 8, marginTop: 12 }}>
          <button type="button" className="btn primary" onClick={() => this.setState({ err: null })}>重试</button>
          <button type="button" className="btn" onClick={() => navigator.clipboard?.writeText(text)}>复制错误信息</button>
        </div>
      </div>
    );
  }
}
