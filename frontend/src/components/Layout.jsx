import { NavLink, Outlet, useNavigate } from "react-router-dom";
import { clearToken, getRole } from "../api";

const baseNavItems = [
  { to: "/dashboard", label: "系统概览", icon: "⌬" },
  { to: "/upload", label: "上传分析", icon: "↑" },
  { to: "/history", label: "视频历史", icon: "⊞" },
  { to: "/compare", label: "报告对比", icon: "⇄" },
  { to: "/qa", label: "智能问答", icon: "⚡" },
  { to: "/profile", label: "个人中心", icon: "◎" },
];

export default function Layout() {
  const navigate = useNavigate();
  const username = localStorage.getItem("ai_sport_username") || "当前用户";
  const role = getRole();
  const roleLabel = role === "ADMIN" ? "管理员" : "普通用户";
  const navItems = role === "ADMIN" ? [...baseNavItems, { to: "/admin", label: "管理员" }] : baseNavItems;

  function logout() {
    clearToken();
    navigate("/login", { replace: true });
  }

  return (
    <div className="app-shell">
      <aside className="sidebar">
        <div className="brand-block">
          <div className="brand-mark">AI</div>
          <div>
            <h2>AI Sport</h2>
            <p>训练分析实验室</p>
          </div>
        </div>

        <nav>
          {navItems.map((item) => (
            <NavLink
              key={item.to}
              to={item.to}
              className={({ isActive }) => `nav-item${isActive ? " active" : ""}`}
            >
              <span className="nav-icon">{item.icon}</span>{item.label}
            </NavLink>
          ))}
        </nav>

        <div className="sidebar-footer">
          <div className="sidebar-user">
            <div className="sidebar-avatar">{username.charAt(0).toUpperCase()}</div>
            <div className="sidebar-user-info">
              <div className="sidebar-user-name">{username}</div>
              <div className="sidebar-user-role">{roleLabel}</div>
            </div>
          </div>
          <button className="ghost" onClick={logout} style={{ marginTop: 6, fontSize: 12 }}>
            退出登录
          </button>
        </div>
      </aside>

      <main className="main-content">
        <Outlet />
      </main>
    </div>
  );
}
