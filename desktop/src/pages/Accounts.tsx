import { useCallback, useEffect, useState } from "react";
import { bridge } from "../bridge";
import { Btn, Card, ItemRow, Row } from "../components";
import { itemsOf } from "../schema";
import { useApp } from "../store";
import type { UserInfo } from "../types";

export function Accounts({ tab }: { tab: string }) {
  const { toast } = useApp();
  const [users, setUsers] = useState<UserInfo[]>([]);
  const [loadErr, setLoadErr] = useState<string | null>(null);
  const [name, setName] = useState("");
  const [pw, setPw] = useState("");
  const [resetFor, setResetFor] = useState<string | null>(null);
  const [newPw, setNewPw] = useState("");
  // 可见范围:哪个账号正在编辑、全部盘符、勾选的盘符(一个都不勾 = 不限制)
  const [rootsFor, setRootsFor] = useState<string | null>(null);
  const [allRoots, setAllRoots] = useState<{ id: number; label: string; path: string }[]>([]);
  const [picked, setPicked] = useState<number[]>([]);

  const load = useCallback(async () => {
    try { setUsers(await bridge.listUsers()); setLoadErr(null); } catch (e) { setLoadErr(String(e)); }
  }, []);
  useEffect(() => { if (tab === "账号") load(); }, [tab, load]);

  if (tab === "登录") {
    const items = itemsOf("accounts", "登录");
    return <Card icon="shield" title="登录" count={items.length}>{items.map((i) => <ItemRow key={i.id} item={i} />)}</Card>;
  }

  const add = async () => {
    try { await bridge.addUser(name.trim(), pw); toast("账号已创建"); setName(""); setPw(""); load(); } catch (e) { toast(String(e), "err"); }
  };
  return (
    <>
      <Card icon="users" title="账号" count={users.length}>
        {loadErr && <Row icon="alert" title="读取账号失败" desc={loadErr} />}
        {!loadErr && users.length === 0 && <Row icon="users" title="还没有账号" desc="创建一个账号,用它在手机应用里登录。" />}
        {users.map((u) => (
          <div key={u.name}>
            <Row icon="key" title={u.name} desc={`创建于 ${new Date(u.created * 1000).toLocaleDateString("zh-CN")}`}>
              {!u.isAdmin && <Btn onClick={async () => {
                if (rootsFor === u.name) { setRootsFor(null); return; }
                try {
                  const r = await bridge.admin("GET", `/api/v1/admin/user-roots?user=${encodeURIComponent(u.name)}`) as { roots: { id: number; label: string; path: string }[]; visible: number[] };
                  setAllRoots(r.roots); setPicked(r.visible); setRootsFor(u.name);
                } catch (e) { toast(String(e), "err"); }
              }}>可见范围</Btn>}
              <Btn onClick={() => { setResetFor(resetFor === u.name ? null : u.name); setNewPw(""); }}>重置密码</Btn>
              <Btn kind="danger" icon="trash" onClick={async () => {
                if (!window.confirm(`删除账号 ${u.name}?该账号在手机上的登录会立即失效。`)) return;
                try { await bridge.deleteUser(u.name); toast("已删除"); load(); } catch (e) { toast(String(e), "err"); }
              }}>删除</Btn>
            </Row>
            {rootsFor === u.name && (
              <div className="inline-form" style={{ flexWrap: "wrap" }}>
                <span>只能看这些盘符(都不勾 = 不限制):</span>
                {allRoots.map((r) => (
                  <label key={r.id} style={{ marginRight: 12 }}>
                    <input type="checkbox" checked={picked.includes(r.id)}
                      onChange={(e) => setPicked(e.target.checked ? [...picked, r.id] : picked.filter((x) => x !== r.id))} /> {r.label || r.path}
                  </label>
                ))}
                <Btn kind="primary" onClick={async () => {
                  try { await bridge.admin("POST", "/api/v1/admin/user-roots", { user: u.name, roots: picked }); toast("可见范围已保存"); setRootsFor(null); } catch (e) { toast(String(e), "err"); }
                }}>保存</Btn>
              </div>
            )}
            {resetFor === u.name && (
              <div className="inline-form">
                <input type="password" placeholder="新密码(至少 4 位)" value={newPw} onChange={(e) => setNewPw(e.target.value)} />
                <Btn kind="primary" disabled={newPw.length < 4} onClick={async () => {
                  try { await bridge.setPassword(u.name, newPw); toast("密码已更新"); setResetFor(null); } catch (e) { toast(String(e), "err"); }
                }}>确定</Btn>
              </div>
            )}
          </div>
        ))}
        <div className="inline-form add">
          <input placeholder="新账号名" value={name} onChange={(e) => setName(e.target.value)} />
          <input type="password" placeholder="密码(至少 4 位)" value={pw} onChange={(e) => setPw(e.target.value)} />
          <Btn kind="primary" icon="plus" disabled={!name.trim() || pw.length < 4} onClick={add}>添加账号</Btn>
        </div>
      </Card>
    </>
  );
}
