import { useEffect, useMemo, useRef, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { cancelVideo, getVideoAnalysis, getVideoStatus, listVideoTasks, retryVideo } from "../api";
import StatusPill from "../components/StatusPill";
import { exerciseTypeLabel } from "../utils/exerciseType";

function formatMs(ms) {
  if (ms == null || Number.isNaN(Number(ms))) return "-";
  const n = Number(ms);
  if (n < 1000) return `${n} ms`;
  return `${(n / 1000).toFixed(2)} s`;
}

export default function TaskPage() {
  const { videoId } = useParams();
  const [statusInfo, setStatusInfo] = useState(null);
  const [analysis, setAnalysis] = useState(null);
  const [tasks, setTasks] = useState([]);
  const [error, setError] = useState("");
  const [canceling, setCanceling] = useState(false);
  const timer = useRef(null);
  const [fallbackProgress, setFallbackProgress] = useState(0);

  const done = useMemo(() => statusInfo?.status === "COMPLETED", [statusInfo]);
  const canCancel = useMemo(() => statusInfo?.status === "UPLOADED" || statusInfo?.status === "PROCESSING", [statusInfo]);
  const latestTask = useMemo(() => (tasks.length > 0 ? tasks[0] : null), [tasks]);
  const serverProgress = Number(statusInfo?.progress || 0);
  const progress = Math.max(serverProgress, fallbackProgress);

  useEffect(() => {
    let stop = false;

    async function poll() {
      try {
        const s = await getVideoStatus(videoId);
        setFallbackProgress((prev) => {
          if (s?.status === "COMPLETED" || s?.status === "FAILED" || s?.status === "CANCELLED") return 100;
          if (s?.status === "UPLOADED" || s?.status === "PROCESSING") return Math.min(95, Math.max(prev + 6, 8));
          return prev;
        });
        if (stop) return;
        setStatusInfo(s);
        listVideoTasks(videoId).then(setTasks).catch(() => {});

        if (s?.status === "CANCELLED") return;

        try {
          const a = await getVideoAnalysis(videoId);
          if (stop) return;
          setAnalysis(a);
          setStatusInfo((prev) => ({ ...(prev || {}), status: "COMPLETED", progress: 100 }));
          return;
        } catch (e) {
          if (e.status === 202) {
            const delay = Number(e.body?.retryAfterMs || 1000);
            timer.current = setTimeout(poll, Number.isFinite(delay) && delay > 0 ? delay : 1000);
            return;
          }
          if (e.status === 500 || e.status === 409 || e.status === 404) {
            setError(e.body?.error || e.message || "任务查询失败");
            return;
          }
        }
        timer.current = setTimeout(poll, 1000);
      } catch (e) {
        setError(e.message || "轮询失败");
      }
    }

    poll();
    return () => {
      stop = true;
      if (timer.current) clearTimeout(timer.current);
    };
  }, [videoId]);

  async function doRetry() {
    setError("");
    await retryVideo(videoId);
    window.location.reload();
  }

  async function doCancel() {
    const ok = window.confirm("确认取消当前分析任务吗？");
    if (!ok) return;
    setCanceling(true);
    setError("");
    try {
      await cancelVideo(videoId);
      setStatusInfo((prev) => ({ ...(prev || {}), status: "CANCELLED" }));
      listVideoTasks(videoId).then(setTasks).catch(() => {});
    } catch (e) {
      setError(e?.body?.error || e.message || "取消失败");
    } finally {
      setCanceling(false);
    }
  }

  return (
    <div className="card">
      <h1>任务 #{videoId}</h1>
      {statusInfo?.exerciseType && <p>动作类型：<b>{exerciseTypeLabel(statusInfo.exerciseType)}</b></p>}
      <p>当前状态：<StatusPill status={statusInfo?.status || "UNKNOWN"} /></p>

      <div className="card" style={{ marginTop: 12 }}>
        <h3>分析进度</h3>
        <div className="progress-wrap">
          <div className="progress-bar" style={{ width: `${Math.max(0, Math.min(100, progress))}%` }} />
        </div>
        <p style={{ marginTop: 8 }}>{progress}% {statusInfo?.progressText ? `（${statusInfo.progressText}）` : ""}</p>
      </div>

      {latestTask && (
        <div className="task-metric-grid">
          <div className="task-metric-card"><div className="task-metric-title">任务ID</div><div className="task-metric-value">{latestTask.id}</div></div>
          <div className="task-metric-card"><div className="task-metric-title">尝试次数</div><div className="task-metric-value">{latestTask.attempt ?? "-"}</div></div>
          <div className="task-metric-card"><div className="task-metric-title">队列耗时</div><div className="task-metric-value">{formatMs(latestTask.queueMs)}</div></div>
          <div className="task-metric-card"><div className="task-metric-title">执行耗时</div><div className="task-metric-value">{formatMs(latestTask.runMs)}</div></div>
        </div>
      )}

      {error && <p className="error">{error}</p>}

      <div className="inline-actions">
        {!done && canCancel && (
          <button className="danger-btn" onClick={doCancel} disabled={canceling}>
            {canceling ? "取消中..." : "取消任务"}
          </button>
        )}
        {(statusInfo?.status === "FAILED" || statusInfo?.status === "CANCELLED") && (
          <button onClick={doRetry}>重试分析</button>
        )}
        {done && <Link to={`/reports/${videoId}`}>查看报告</Link>}
      </div>

      {analysis && (
        <details style={{ marginTop: 12 }}>
          <summary>分析原始结果</summary>
          <pre>{JSON.stringify(analysis, null, 2)}</pre>
        </details>
      )}
    </div>
  );
}


