import { useEffect, useMemo, useState } from "react";
import { Link } from "react-router-dom";
import { getCurrentUser, getPerformanceSummary, getTrainingTrends, listVideos, setRole, systemHealth } from "../api";
import SimpleLineChart from "../components/SimpleLineChart";
import StatusPill from "../components/StatusPill";
import { exerciseTypeLabel } from "../utils/exerciseType";

function isThisWeek(dateText) {
  if (!dateText) return false;
  const d = new Date(dateText);
  if (Number.isNaN(d.getTime())) return false;
  const now = new Date();
  const weekAgo = new Date(now);
  weekAgo.setDate(now.getDate() - 7);
  return d >= weekAgo && d <= now;
}

function parseRepCount(v) {
  if (v?.repCount != null) return Number(v.repCount);
  const raw = v?.analysisResult;
  if (!raw || typeof raw !== "string") return null;
  try {
    const parsed = JSON.parse(raw);
    if (parsed?.rep_count != null) return Number(parsed.rep_count);
    if (parsed?.repCount != null) return Number(parsed.repCount);
    return null;
  } catch {
    return null;
  }
}

function scoreClass(score) {
  if (score == null || Number.isNaN(Number(score))) return "";
  const n = Number(score);
  if (n < 60) return "score-low";
  if (n >= 80) return "score-high";
  return "";
}

function formatRecentLine(v) {
  const scoreText = v.trainingScore != null ? `${v.trainingScore}分` : "无评分";
  if (v.status !== "COMPLETED") return `处理中 · ${scoreText}`;
  const rep = parseRepCount(v);
  const repText = rep != null && !Number.isNaN(rep) ? `${rep}次` : "处理中";
  return `${repText} · ${scoreText}`;
}

export default function DashboardPage() {
  const [videos, setVideos] = useState([]);
  const [health, setHealth] = useState(null);
  const [me, setMe] = useState(null);
  const [perf, setPerf] = useState(null);
  const [trends, setTrends] = useState(null);
  const [error, setError] = useState("");

  useEffect(() => {
    getCurrentUser()
      .then((u) => {
        setMe(u);
        localStorage.setItem("ai_sport_username", u.username);
        setRole(u.role || "USER");
      })
      .catch(() => setMe(null));

    listVideos()
      .then(setVideos)
      .catch((e) => {
        setError(e.message || "加载视频列表失败");
        setVideos([]);
      });

    systemHealth().then(setHealth).catch(() => setHealth({ status: "DOWN", checks: {} }));
    getPerformanceSummary().then(setPerf).catch(() => setPerf(null));
    getTrainingTrends(30).then(setTrends).catch(() => setTrends(null));
  }, []);

  const scorePoints = useMemo(
    () =>
      (trends?.recentScores || [])
        .slice()
        .reverse()
        .map((s, idx) => ({ x: idx + 1, y: Number(s.score || 0) })),
    [trends],
  );

  const stats = useMemo(() => {
    const completed = videos.filter((v) => v.status === "COMPLETED");
    const weekCompleted = completed.filter((v) => isThisWeek(v.uploadedAt)).length;

    const avgScore = completed.length
      ? (completed.reduce((sum, v) => sum + Number(v.trainingScore || 0), 0) / completed.length).toFixed(1)
      : "-";

    const bestByType = new Map();
    for (const v of completed) {
      const t = v.exerciseType || "UNKNOWN";
      const score = Number(v.trainingScore || 0);
      if (!bestByType.has(t)) {
        bestByType.set(t, { sum: score, cnt: 1 });
      } else {
        const cur = bestByType.get(t);
        bestByType.set(t, { sum: cur.sum + score, cnt: cur.cnt + 1 });
      }
    }

    let bestType = "-";
    let bestAvg = -1;
    for (const [type, val] of bestByType.entries()) {
      const avg = val.cnt > 0 ? val.sum / val.cnt : 0;
      if (avg > bestAvg) {
        bestAvg = avg;
        bestType = exerciseTypeLabel(type);
      }
    }

    return {
      totalTraining: completed.length,
      weekCompleted,
      avgScore,
      bestType,
    };
  }, [videos]);

  const healthItems = useMemo(() => {
    const checks = health?.checks || {};
    const map = [
      ["database", "数据库"],
      ["rabbitmq", "消息队列"],
      ["python_ai", "AI服务"],
      ["object_storage", "存储"],
    ];
    return map.map(([key, label]) => {
      const status = String(checks?.[key]?.status || "UNKNOWN").toUpperCase();
      const ok = status === "UP" || status === "DISABLED";
      return { key, label, ok };
    });
  }, [health]);

  return (
    <div>
      <h1>系统概览</h1>
      {me && (
        <p>
          当前用户：<b>{me.username}</b>
        </p>
      )}
      {error && <p className="error">{error}</p>}

      <div className="metric-grid metric-grid-4">
        <div className="metric-card"><div className="metric-title">📊 累计训练</div><div className="metric-value">{stats.totalTraining}次</div></div>
        <div className="metric-card"><div className="metric-title">✅ 本周完成</div><div className="metric-value">{stats.weekCompleted}次</div></div>
        <div className="metric-card">
          <div className="metric-title">⭐ 平均评分</div>
          <div className={`metric-value ${scoreClass(stats.avgScore === "-" ? null : stats.avgScore)}`}>
            {stats.avgScore === "-" ? "-" : `${stats.avgScore}分`}
          </div>
        </div>
        <div className="metric-card"><div className="metric-title">🏆 最佳动作</div><div className="metric-value">{stats.bestType}</div></div>
      </div>

      <div className="card trend-main">
        <h3>近30天训练评分趋势</h3>
        {!trends ? (
          <p>暂无趋势数据</p>
        ) : (
          <SimpleLineChart points={scorePoints} yMin={0} yMax={100} height={320} yLabel="综合评分" />
        )}
      </div>

      <div className="card">
        <h3>最近训练记录</h3>
        {videos.length === 0 ? (
          <p>还没有分析记录</p>
        ) : (
          <div className="recent-list">
            {videos.slice(0, 8).map((v) => (
              <div key={v.id} className="recent-item">
                <div className="recent-left">
                  <div className="recent-title">🏋️ {exerciseTypeLabel(v.exerciseType)}</div>
                  <div className={`recent-sub ${scoreClass(v.trainingScore)}`}>{formatRecentLine(v)}</div>
                </div>
                <div className="recent-right">
                  <StatusPill status={v.status} />
                  {v.status === "COMPLETED" ? <Link to={`/reports/${v.id}`}>查看报告</Link> : <Link to={`/tasks/${v.id}`}>查看进度</Link>}
                </div>
              </div>
            ))}
          </div>
        )}
      </div>

      <div className="card health-strip">
        {healthItems.map((h) => (
          <div key={h.key} className="health-dot-item">
            <span className={h.ok ? "dot ok" : "dot bad"}>●</span>
            <span>{h.label}</span>
          </div>
        ))}
      </div>

      {perf && me?.role === "ADMIN" && (
        <div className="card" style={{ marginTop: 12 }}>
          <h3>性能摘要</h3>
          <div className="inline-actions">
            <span>平均端到端：{perf.avgEndToEndMs ?? "-"} ms</span>
            <span>P95：{perf.p95EndToEndMs ?? "-"} ms</span>
            <span>成功率：{perf.successRate != null ? `${(perf.successRate * 100).toFixed(2)}%` : "-"}</span>
          </div>
        </div>
      )}
    </div>
  );
}
