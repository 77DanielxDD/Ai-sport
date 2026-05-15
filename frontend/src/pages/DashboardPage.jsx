import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { getCurrentUser, getMetricsSummary, getPerformanceSummary, getTrainingTrends, listVideos, setRole, systemHealth } from "../api";
import SimpleLineChart from "../components/SimpleLineChart";
import StatusPill from "../components/StatusPill";
import { exerciseTypeLabel } from "../utils/exerciseType";

export default function DashboardPage() {
  const [videos, setVideos] = useState([]);
  const [health, setHealth] = useState(null);
  const [me, setMe] = useState(null);
  const [perf, setPerf] = useState(null);
  const [trends, setTrends] = useState(null);
  const [ops, setOps] = useState(null);
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

    systemHealth().then(setHealth).catch(() => setHealth({ status: "DOWN" }));
    getPerformanceSummary().then(setPerf).catch(() => setPerf(null));
    getTrainingTrends(30).then(setTrends).catch(() => setTrends(null));
    getMetricsSummary().then(setOps).catch(() => setOps(null));
  }, []);

  const scorePoints = (trends?.recentScores || [])
    .slice()
    .reverse()
    .map((s, idx) => ({ x: idx + 1, y: Number(s.score || 0) }));

  return (
    <div>
      <h1>系统概览</h1>
      {me && (
        <p>
          当前用户：<b>{me.username}</b>
        </p>
      )}
      {error && <p className="error">{error}</p>}

      <div className="grid2">
        <div className="card">
          <h3>系统健康状态</h3>
          <p>
            状态：<StatusPill status={health?.status || "UNKNOWN"} />
          </p>
          {health?.checks && <pre>{JSON.stringify(health.checks, null, 2)}</pre>}
        </div>

        <div className="card">
          <h3>性能摘要（当前用户，近况）</h3>
          {!perf ? (
            <p>暂无数据</p>
          ) : (
            <ul>
              <li>样本数：{perf.sampleSize ?? 0}</li>
              <li>平均端到端：{perf.avgEndToEndMs ?? "-"} ms</li>
              <li>P95 端到端：{perf.p95EndToEndMs ?? "-"} ms</li>
              <li>成功率：{perf.successRate != null ? `${(perf.successRate * 100).toFixed(2)}%` : "-"}</li>
              <li>是否满足 &lt;20s：{String(perf.targetLt20sPass)}</li>
            </ul>
          )}
        </div>
      </div>

      <div className="grid2">
        <div className="card">
          <h3>训练评分趋势（近30天）</h3>
          {!trends ? (
            <p>暂无趋势数据</p>
          ) : (
            <>
              <ul>
                <li>完成训练：{trends.completedSessions ?? 0} 次</li>
                <li>综合评分：{trends.overallScore ?? "-"} / 100</li>
              </ul>
              <SimpleLineChart points={scorePoints} yMin={0} yMax={100} height={200} xLabel="最近训练序号" yLabel="综合评分" />
            </>
          )}
        </div>

        <div className="card">
          <h3>可观测性摘要</h3>
          {!ops ? (
            <p>仅管理员可查看</p>
          ) : (
            <ul>
              <li>进程CPU：{ops.processCpuUsage ?? 0}</li>
              <li>系统CPU：{ops.systemCpuUsage ?? 0}</li>
              <li>JVM内存：{ops.jvmMemoryUsedMb ?? 0} MB</li>
              <li>HTTP请求数：{ops.httpServerRequestsCount ?? 0}</li>
              <li>任务总数：{ops.tasksTotal ?? 0}</li>
            </ul>
          )}
        </div>
      </div>

      <div className="card">
        <h3>快捷入口</h3>
        <p>
          <Link to="/upload">上传新视频并分析</Link>
        </p>
        <p>
          <Link to="/history">查看我的视频历史</Link>
        </p>
        <p>
          <Link to="/experiments">运行实验评测</Link>
        </p>
      </div>

      <div className="card">
        <h3>最近视频</h3>
        <table>
          <thead>
            <tr>
              <th>ID</th>
              <th>动作类型</th>
              <th>状态</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            {videos.length === 0 ? (
              <tr>
                <td colSpan="4">暂无视频记录</td>
              </tr>
            ) : (
              videos.slice(0, 20).map((v) => (
                <tr key={v.id}>
                  <td>{v.id}</td>
                  <td>{exerciseTypeLabel(v.exerciseType)}</td>
                  <td>
                    <StatusPill status={v.status} />
                  </td>
                  <td>
                    <Link to={`/tasks/${v.id}`}>任务</Link>
                    {" | "}
                    <Link to={`/reports/${v.id}`}>报告</Link>
                  </td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>
    </div>
  );
}
