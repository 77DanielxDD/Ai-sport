import { useEffect, useState } from "react";
import {
  adminCreateUser,
  adminDeleteUser,
  adminOverview,
  adminSetUserEnabled,
  adminTasks,
  adminUsers,
  adminVideos,
  getRole,
  runStorageCleanup,
} from "../api";
import StatusPill from "../components/StatusPill";
import { exerciseTypeLabel } from "../utils/exerciseType";

export default function AdminPage() {
  const role = getRole();
  const roleLabel = role === "ADMIN" ? "管理员" : "普通用户";
  const [overview, setOverview] = useState(null);
  const [users, setUsers] = useState([]);
  const [videos, setVideos] = useState([]);
  const [tasks, setTasks] = useState([]);
  const [error, setError] = useState("");
  const [updatingUser, setUpdatingUser] = useState(null);
  const [deletingUser, setDeletingUser] = useState(null);

  const [newUsername, setNewUsername] = useState("");
  const [newPassword, setNewPassword] = useState("");
  const [newEmail, setNewEmail] = useState("");
  const [newRole, setNewRole] = useState("USER");
  const [newEnabled, setNewEnabled] = useState(true);
  const [creating, setCreating] = useState(false);
  const [cleanupMessage, setCleanupMessage] = useState("");

  async function loadAll() {
    setError("");
    try {
      const [o, u, v, t] = await Promise.all([adminOverview(), adminUsers(), adminVideos(), adminTasks()]);
      setOverview(o);
      setUsers(u?.items || []);
      setVideos(v?.items || []);
      setTasks(t?.items || []);
    } catch (e) {
      setError(e?.body?.error || e.message || "加载管理员数据失败");
    }
  }

  useEffect(() => {
    loadAll();
  }, []);

  async function toggleUserEnabled(u) {
    const next = !(u.enabled === true);
    setUpdatingUser(u.id);
    setError("");
    try {
      await adminSetUserEnabled(u.id, next);
      await loadAll();
    } catch (e) {
      setError(e?.body?.error || e.message || "更新用户状态失败");
    } finally {
      setUpdatingUser(null);
    }
  }

  async function createUser(e) {
    e.preventDefault();
    setError("");
    if (!newUsername || !newPassword) {
      setError("请输入用户名和密码");
      return;
    }

    setCreating(true);
    try {
      await adminCreateUser({
        username: newUsername,
        password: newPassword,
        email: newEmail || null,
        role: newRole,
        enabled: newEnabled,
      });
      setNewUsername("");
      setNewPassword("");
      setNewEmail("");
      setNewRole("USER");
      setNewEnabled(true);
      await loadAll();
    } catch (e1) {
      setError(e1?.body?.error || e1.message || "新增用户失败");
    } finally {
      setCreating(false);
    }
  }

  async function removeUser(u) {
    const ok = window.confirm(`确认删除用户 ${u.username} 吗？该用户的视频与任务将一并删除。`);
    if (!ok) return;
    setDeletingUser(u.id);
    setError("");
    try {
      await adminDeleteUser(u.id);
      await loadAll();
    } catch (e1) {
      setError(e1?.body?.error || e1.message || "删除用户失败");
    } finally {
      setDeletingUser(null);
    }
  }

  async function runCleanupNow() {
    setError("");
    setCleanupMessage("");
    try {
      const resp = await runStorageCleanup();
      setCleanupMessage(
        `清理完成：候选 ${resp.candidates ?? 0} 条，删除 ${resp.deletedVideos ?? 0} 条，失败 ${resp.failedVideos ?? 0} 条`,
      );
      await loadAll();
    } catch (e1) {
      setError(e1?.body?.error || e1.message || "执行清理失败");
    }
  }

  return (
    <div>
      <h1>管理员控制台</h1>
      {role !== "ADMIN" && <p className="error">当前账号角色为“{roleLabel}”，若接口返回 403 请切换管理员账号。</p>}
      {error && <p className="error">{error}</p>}

      <div className="grid2">
        <div className="card">
          <h3>用户概况</h3>
          <ul>
            <li>总用户：{overview?.users?.total ?? 0}</li>
            <li>启用：{overview?.users?.enabled ?? 0}</li>
            <li>禁用：{overview?.users?.disabled ?? 0}</li>
            <li>管理员：{overview?.users?.admins ?? 0}</li>
          </ul>
        </div>
        <div className="card">
          <h3>业务概况</h3>
          <ul>
            <li>视频总数：{overview?.videos?.total ?? 0}</li>
            <li>任务总数：{overview?.tasks?.total ?? 0}</li>
          </ul>
          <div className="inline-actions">
            <button onClick={loadAll}>刷新数据</button>
            <button className="danger-btn" onClick={runCleanupNow}>
              执行存储清理
            </button>
          </div>
          {cleanupMessage && <p>{cleanupMessage}</p>}
        </div>
      </div>

      <div className="card">
        <h3>新增用户</h3>
        <form onSubmit={createUser}>
          <div className="grid2">
            <div>
              <label htmlFor="admin-new-username">用户名</label>
              <input id="admin-new-username" name="username" value={newUsername} onChange={(e) => setNewUsername(e.target.value)} />
            </div>
            <div>
              <label htmlFor="admin-new-password">密码</label>
              <input
                id="admin-new-password"
                name="password"
                type="password"
                value={newPassword}
                onChange={(e) => setNewPassword(e.target.value)}
              />
            </div>
          </div>
          <div className="grid2">
            <div>
              <label htmlFor="admin-new-email">邮箱（可选）</label>
              <input id="admin-new-email" name="email" value={newEmail} onChange={(e) => setNewEmail(e.target.value)} />
            </div>
            <div>
              <label htmlFor="admin-new-role">角色</label>
              <select id="admin-new-role" name="role" value={newRole} onChange={(e) => setNewRole(e.target.value)}>
                <option value="USER">普通用户</option>
                <option value="ADMIN">管理员</option>
              </select>
            </div>
          </div>
          <label>
            <input
              type="checkbox"
              name="enabled"
              checked={newEnabled}
              onChange={(e) => setNewEnabled(e.target.checked)}
              style={{ width: "auto", marginRight: 8 }}
            />
            创建后立即启用
          </label>
          <div style={{ marginTop: 8 }}>
            <button disabled={creating}>{creating ? "创建中..." : "创建用户"}</button>
          </div>
        </form>
      </div>

      <div className="card">
        <h3>用户管理（最近 100）</h3>
        <table>
          <thead>
            <tr>
              <th>ID</th>
              <th>用户名</th>
              <th>角色</th>
              <th>启用状态</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            {users.length === 0 ? (
              <tr>
                <td colSpan="5">暂无用户数据</td>
              </tr>
            ) : (
              users.map((u) => (
                <tr key={u.id}>
                  <td>{u.id}</td>
                  <td>{u.username}</td>
                  <td>{u.role === "ADMIN" ? "管理员" : "普通用户"}</td>
                  <td>
                    {u.enabled ? <span className="status-pill status-completed">启用</span> : <span className="status-pill status-failed">禁用</span>}
                  </td>
                  <td>
                    <div className="inline-actions">
                      <button disabled={updatingUser === u.id} onClick={() => toggleUserEnabled(u)}>
                        {updatingUser === u.id ? "处理中..." : u.enabled ? "禁用" : "启用"}
                      </button>
                      <button className="danger-btn" disabled={deletingUser === u.id} onClick={() => removeUser(u)}>
                        {deletingUser === u.id ? "删除中..." : "删除"}
                      </button>
                    </div>
                  </td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>

      <div className="card">
        <h3>视频总览（最近 100）</h3>
        <table>
          <thead>
            <tr>
              <th>ID</th>
              <th>用户</th>
              <th>动作</th>
              <th>状态</th>
              <th>上传时间</th>
            </tr>
          </thead>
          <tbody>
            {videos.length === 0 ? (
              <tr>
                <td colSpan="5">暂无视频数据</td>
              </tr>
            ) : (
              videos.map((v) => (
                <tr key={v.id}>
                  <td>{v.id}</td>
                  <td>{v.username || "-"}</td>
                  <td>{v.exerciseType ? exerciseTypeLabel(v.exerciseType) : "-"}</td>
                  <td>
                    <StatusPill status={v.status || "UNKNOWN"} />
                  </td>
                  <td>{v.uploadedAt || "-"}</td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>

      <div className="card">
        <h3>任务总览（最近 100）</h3>
        <table>
          <thead>
            <tr>
              <th>任务ID</th>
              <th>视频ID</th>
              <th>状态</th>
              <th>重试次数</th>
              <th>入队时间</th>
            </tr>
          </thead>
          <tbody>
            {tasks.length === 0 ? (
              <tr>
                <td colSpan="5">暂无任务数据</td>
              </tr>
            ) : (
              tasks.map((t) => (
                <tr key={t.id}>
                  <td>{t.id}</td>
                  <td>{t.videoId}</td>
                  <td>
                    <StatusPill status={t.status || "UNKNOWN"} />
                  </td>
                  <td>{t.attempt}</td>
                  <td>{t.queuedAt || "-"}</td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>
    </div>
  );
}
