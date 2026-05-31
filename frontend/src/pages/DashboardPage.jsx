import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { getCurrentUser, getMetricsSummary, getPerformanceSummary, getTrainingTrends, listVideos, setRole, systemHealth } from "../api";
import SimpleLineChart from "../components/SimpleLineChart";
import StatusPill from "../components/StatusPill";
import { exerciseTypeLabel } from "../utils/exerciseType";

function ScoreRing({ score, size = 120, stroke = 8 }) {
  const r = (size - stroke) / 2;
  const circ = 2 * Math.PI * r;
  const pct = Math.min(100, Math.max(0, score || 0)) / 100;
  return (
    <div className="score-ring" style={{ width: size, height: size }}>
      <svg width={size} height={size} viewBox={`0 0 ${size} ${size}`}>
        <circle className="bg" cx={size / 2} cy={size / 2} r={r} strokeWidth={stroke} />
        <circle
          className="fg"
          cx={size / 2} cy={size / 2} r={r}
          strokeWidth={stroke}
          stroke="url(#ringGrad)"
          strokeDasharray={circ}
          strokeDashoffset={circ * (1 - pct)}
        />
        <defs>
          <linearGradient id="ringGrad" x1="0%" y1="0%" x2="100%" y2="100%">
            <stop offset="0%" stopColor="#FF6B6B" />
            <stop offset="100%" stopColor="#FF9F7C" />
          </linearGradient>
        </defs>
      </svg>
      <div className="value">
        <span style={{ fontSize: size * 0.26 }}>{score ?? "-"}</span>
        <span className="label">综合评分</span>
      </div>
    </div>
  );
}

export default function DashboardPage() {
  const [videos, setVideos] = useState([]);
  const [health, setHealth] = useState(null);
  const [me, setMe] = useState(null);
  const [perf, setPerf] = useState(null);
  const [trends, setTrends] = useState(null);
  const [ops, setOps] = useState(null);
  const [error, setError] = useState("");

  useEffect(() => {
    getCurrentUser().then((u) => { setMe(u); localStorage.setItem("ai_sport_username", u.username); setRole(u.role || "USER"); }).catch(() => setMe(null));
    listVideos().then(setVideos).catch((e) => { setError(e.message || "加载失败"); setVideos([]); });
    systemHealth().then(setHealth).catch(() => setHealth({ status: "DOWN" }));
    getPerformanceSummary().then(setPerf).catch(() => setPerf(null));
    getTrainingTrends(30).then(setTrends).catch(() => setTrends(null));
    getMetricsSummary().then(setOps).catch(() => setOps(null));
  }, []);

  const scorePoints = (trends?.recentScores || []).slice().reverse().map((s, idx) => ({ x: idx + 1, y: Number(s.score || 0) }));
  const overallScore = trends?.overallScore ?? 0;

  return (
    <div>
      <h1>训练概览</h1>
      {me && <p style={{ color: "var(--text-2)" }}>欢迎回来，<b style={{ color: "var(--text)" }}>{me.username}</b></p>}
      {error && <p className="error">{error}</p>}

      <div className="grid2 fade-in">
        <div className="card" style={{ textAlign: "center" }}>
          <h3>综合评分</h3>
          <ScoreRing score={overallScore} size={140} />
          <p style={{ color: "var(--text-2)", fontSize: 13, marginTop: 8 }}>
            {trends?.completedSessions ?? 0} 次训练 · 近30天
          </p>
        </div>

        <div className="card">
          <h3>系统健康</h3>
          <div className="metric-grid">
            <div className="metric-card">
              <div className="metric-title">系统状态</div>
              <div className="metric-value" style={{ fontSize: 18 }}><StatusPill status={health?.status || "UNKNOWN"} /></div>
            </div>
            <div className="metric-card">
              <div className="metric-title">数据库</div>
              <div className="metric-value" style={{ fontSize: 18, color: health?.checks?.database?.status === "UP" ? "var(--green)" : "var(--red)" }}>
                {health?.checks?.database?.status || "?"}
              </div>
            </div>
            <div className="metric-card">
              <div className="metric-title">消息队列</div>
              <div className="metric-value" style={{ fontSize: 18, color: health?.checks?.rabbitmq?.status === "UP" ? "var(--green)" : "var(--red)" }}>
                {health?.checks?.rabbitmq?.status || "?"}
              </div>
            </div>
          </div>
        </div>
      </div>

      <div className="card fade-in fade-in-1">
        <h3>训练评分趋势（近30天）</h3>
        {!trends ? <p style={{ color: "var(--text-2)" }}>暂无数据</p> : (
          <SimpleLineChart points={scorePoints} yMin={0} yMax={100} height={200} xLabel="最近训练" yLabel="评分" />
        )}
      </div>

      {ops && (
        <div className="card fade-in fade-in-2">
          <h3>运行指标</h3>
          <div className="metric-grid">
            <div className="metric-card"><div className="metric-title">进程 CPU</div><div className="metric-value">{ops.processCpuUsage ?? 0}</div></div>
            <div className="metric-card"><div className="metric-title">JVM 内存 MB</div><div className="metric-value">{ops.jvmMemoryUsedMb ?? 0}</div></div>
            <div className="metric-card"><div className="metric-title">HTTP 请求</div><div className="metric-value">{ops.httpServerRequestsCount ?? 0}</div></div>
            <div className="metric-card"><div className="metric-title">任务总数</div><div className="metric-value">{ops.tasksTotal ?? 0}</div></div>
          </div>
        </div>
      )}

      <div className="card fade-in fade-in-3">
        <h3>最近视频</h3>
        <table>
          <thead><tr><th>ID</th><th>动作</th><th>状态</th><th>操作</th></tr></thead>
          <tbody>
            {videos.length === 0 ? <tr><td colSpan="4" style={{ color: "var(--text-2)" }}>暂无视频</td></tr> :
              videos.slice(0, 10).map((v) => (
                <tr key={v.id}>
                  <td>{v.id}</td>
                  <td>{exerciseTypeLabel(v.exerciseType)}</td>
                  <td><StatusPill status={v.status} /></td>
                  <td><Link to={`/reports/${v.id}`}>查看报告</Link></td>
                </tr>
              ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
