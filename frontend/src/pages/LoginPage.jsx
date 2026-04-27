import { useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { login, setRole, setToken } from "../api";

export default function LoginPage() {
  const navigate = useNavigate();
  const [username, setUsername] = useState("admin");
  const [password, setPassword] = useState("123456");
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);

  async function submit(e) {
    e.preventDefault();
    setError("");
    setLoading(true);
    try {
      const resp = await login(username, password);
      setToken(resp.token);
      setRole(resp.role || "USER");
      localStorage.setItem("ai_sport_username", resp.username || username);
      navigate("/dashboard", { replace: true });
    } catch (err) {
      setError(err.message || "登录失败");
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className="centered">
      <form className="card" onSubmit={submit}>
        <h1>登录</h1>
        <label htmlFor="login-username">用户名</label>
        <input
          id="login-username"
          name="username"
          autoComplete="username"
          value={username}
          onChange={(e) => setUsername(e.target.value)}
        />
        <label htmlFor="login-password">密码</label>
        <input
          id="login-password"
          name="password"
          type="password"
          autoComplete="current-password"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
        />
        {error && <p className="error">{error}</p>}
        <button disabled={loading}>{loading ? "登录中..." : "登录"}</button>
        <p>
          没有账号？<Link to="/register">去注册</Link>
        </p>
      </form>
    </div>
  );
}
