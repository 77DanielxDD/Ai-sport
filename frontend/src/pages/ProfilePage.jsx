import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { clearToken, deleteCurrentUserAccount, getCurrentUser, setRole, setToken, updateCurrentUserProfile } from "../api";

export default function ProfilePage() {
  const navigate = useNavigate();
  const [username, setUsername] = useState("");
  const [role, setRoleText] = useState("USER");
  const roleLabel = role === "ADMIN" ? "管理员" : "普通用户";
  const [currentPassword, setCurrentPassword] = useState("");
  const [newPassword, setNewPassword] = useState("");
  const [message, setMessage] = useState("");
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);

  const [deletePassword, setDeletePassword] = useState("");
  const [deleting, setDeleting] = useState(false);

  useEffect(() => {
    getCurrentUser()
      .then((u) => {
        setUsername(u.username || "");
        setRoleText(u.role || "USER");
      })
      .catch(() => {
        setError("加载个人信息失败");
      });
  }, []);

  async function submit(e) {
    e.preventDefault();
    setError("");
    setMessage("");

    if (!currentPassword) {
      setError("请输入当前密码");
      return;
    }
    if (!username) {
      setError("用户名不能为空");
      return;
    }

    setLoading(true);
    try {
      const resp = await updateCurrentUserProfile({ username, currentPassword, newPassword });
      setToken(resp.token);
      setRole(resp.role || "USER");
      localStorage.setItem("ai_sport_username", resp.username || username);
      setRoleText(resp.role || "USER");
      setCurrentPassword("");
      setNewPassword("");
      setMessage("个人信息更新成功，请继续使用新用户名登录。");
    } catch (e1) {
      setError(e1?.body?.error || e1.message || "更新失败");
    } finally {
      setLoading(false);
    }
  }

  async function deleteAccount() {
    const ok = window.confirm("确认注销账号吗？该账号下所有视频、任务和报告都会删除，且不可恢复。");
    if (!ok) return;
    if (!deletePassword) {
      setError("注销账号前，请输入当前密码");
      return;
    }

    setDeleting(true);
    setError("");
    try {
      await deleteCurrentUserAccount(deletePassword);
      clearToken();
      navigate("/login", { replace: true });
    } catch (e1) {
      setError(e1?.body?.error || e1.message || "注销账号失败");
    } finally {
      setDeleting(false);
    }
  }

  return (
    <div className="card">
      <h1>个人中心</h1>
      <p>当前角色：{roleLabel}</p>
      {message && <p>{message}</p>}
      {error && <p className="error">{error}</p>}

      <form onSubmit={submit}>
        <label htmlFor="profile-username">用户名</label>
        <input
          id="profile-username"
          name="username"
          autoComplete="username"
          value={username}
          onChange={(e) => setUsername(e.target.value)}
        />

        <label htmlFor="profile-current-password">当前密码（必填）</label>
        <input
          id="profile-current-password"
          name="currentPassword"
          type="password"
          autoComplete="current-password"
          value={currentPassword}
          onChange={(e) => setCurrentPassword(e.target.value)}
        />

        <label htmlFor="profile-new-password">新密码（选填）</label>
        <input
          id="profile-new-password"
          name="newPassword"
          type="password"
          autoComplete="new-password"
          value={newPassword}
          onChange={(e) => setNewPassword(e.target.value)}
        />

        <button disabled={loading}>{loading ? "保存中..." : "保存修改"}</button>
      </form>

      <hr style={{ margin: "20px 0", border: "none", borderTop: "1px solid #dbe3ef" }} />
      <h3>注销账号</h3>
      <p className="error" style={{ fontWeight: 500 }}>
        注销后将永久删除你的账号、视频、任务和报告数据，且无法恢复。
      </p>
      <label htmlFor="profile-delete-password">请输入当前密码确认注销</label>
      <input
        id="profile-delete-password"
        name="deletePassword"
        type="password"
        autoComplete="current-password"
        value={deletePassword}
        onChange={(e) => setDeletePassword(e.target.value)}
      />
      <button className="danger-btn" disabled={deleting} onClick={deleteAccount} style={{ width: "auto" }}>
        {deleting ? "注销中..." : "注销账号"}
      </button>
    </div>
  );
}
