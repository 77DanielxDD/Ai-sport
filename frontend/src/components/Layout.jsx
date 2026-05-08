import { NavLink, Outlet, useNavigate } from "react-router-dom";
import { clearToken, getRole } from "../api";
import { useState } from "react";

const coreNavItems = [
  { to: "/dashboard", label: "📊 系统概览" },
  { to: "/upload", label: "📤 上传分析" },
  { to: "/history", label: "📋 视频历史" },
  { to: "/compare", label: "📈 报告对比" },
  { to: "/profile", label: "⚙️ 个人中心" },
];

export default function Layout() {
  const navigate = useNavigate();
  const username = localStorage.getItem("ai_sport_username") || "当前用户";
  const role = getRole();
  const roleLabel = role === "ADMIN" ? "管理员" : "普通用户";
  const [showMore, setShowMore] = useState(false);

  function logout() {
    clearToken();
    navigate("/login", { replace: true });
  }

  return (
    <div className="app-shell">
      <aside className="sidebar">
        <div className="brand-block">
          <div className="brand-mark">AS</div>
          <div>
            <h2>AI 运动分析</h2>
            <p>动作分析工作台</p>
          </div>
        </div>

        <nav>
          {coreNavItems.map((item) => (
            <NavLink
              key={item.to}
              to={item.to}
              className={({ isActive }) => `nav-item${isActive ? " active" : ""}`}
            >
              {item.label}
            </NavLink>
          ))}

          <div className="nav-divider" />
          <button type="button" className="nav-item nav-toggle" onClick={() => setShowMore((s) => !s)}>
            {showMore ? "收起导航" : "展开更多"}
          </button>
          {showMore && role === "ADMIN" && (
            <>
              <NavLink to="/experiments" className={({ isActive }) => `nav-item${isActive ? " active" : ""}`}>
                🔬 实验评测
              </NavLink>
              <NavLink to="/admin" className={({ isActive }) => `nav-item${isActive ? " active" : ""}`}>
                👑 管理员
              </NavLink>
            </>
          )}
        </nav>

        <div className="sidebar-footer">
          <p className="user-tag">登录用户：{username}</p>
          <p className="user-tag">角色：{roleLabel}</p>
          <button className="ghost" onClick={logout}>
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
