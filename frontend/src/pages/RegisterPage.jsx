import { useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { register, setRole, setToken } from "../api";

export default function RegisterPage() {
  const navigate = useNavigate();
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [email, setEmail] = useState("");
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);

  async function submit(e) {
    e.preventDefault();
    setError("");
    if (!username || !password) return setError("请输入用户名和密码");
    setLoading(true);
    try {
      const resp = await register(username, password, email);
      setToken(resp.token);
      setRole(resp.role || "USER");
      localStorage.setItem("ai_sport_username", username);
      navigate("/dashboard", { replace: true });
    } catch (err) {
      setError(err.message || "注册失败");
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className="centered">
      <form className="card" onSubmit={submit}>
        <h1>注册</h1>
        <label htmlFor="reg-username">用户名</label>
        <input id="reg-username" name="username" autoComplete="username" value={username} onChange={(e) => setUsername(e.target.value)} />
        <label htmlFor="reg-password">密码</label>
        <input id="reg-password" name="password" type="password" autoComplete="new-password" value={password} onChange={(e) => setPassword(e.target.value)} />
        <label htmlFor="reg-email">邮箱（可选）</label>
        <input id="reg-email" name="email" autoComplete="email" value={email} onChange={(e) => setEmail(e.target.value)} />
        {error && <p className="error">{error}</p>}
        <button disabled={loading}>{loading ? "注册中..." : "注册并登录"}</button>
        <p>已有账号？<Link to="/login">去登录</Link></p>
      </form>
    </div>
  );
}
