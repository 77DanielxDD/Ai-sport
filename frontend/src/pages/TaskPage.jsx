import { useEffect, useMemo, useRef, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { cancelVideo, getVideoAnalysis, getVideoStatus, listVideoTasks, retryVideo } from "../api";
import StatusPill from "../components/StatusPill";
import { exerciseTypeLabel } from "../utils/exerciseType";

const STAGES = ["已上传", "排队中", "姿态识别", "动作评分", "报告生成", "完成"];

function formatMs(ms) {
  if (ms == null || Number.isNaN(Number(ms))) return "-";
  const n = Number(ms);
  if (n < 1000) return `${n} ms`;
  return `${(n / 1000).toFixed(2)} s`;
}

function stageIndex(status, hasAnalysis) {
  if (hasAnalysis) return 5;
  switch (status) {
    case "UPLOADED": return 0;
    case "PROCESSING": return 2;
    case "COMPLETED": return 5;
    case "FAILED": return 3;
    case "CANCELLED": return 2;
    default: return 0;
  }
}

export default function TaskPage() {
  const { videoId } = useParams();
  const [statusInfo, setStatusInfo] = useState(null);
  const [analysis, setAnalysis] = useState(null);
  const [tasks, setTasks] = useState([]);
  const [error, setError] = useState("");
  const [canceling, setCanceling] = useState(false);
  const [retrying, setRetrying] = useState(false);
  const timer = useRef(null);

  const status = statusInfo?.status;
  const hasAnalysis = !!analysis;
  const done = status === "COMPLETED";
  const cancelled = status === "CANCELLED";
  const failed = status === "FAILED";
  const canCancel = status === "UPLOADED" || status === "PROCESSING";
  const currentStage = stageIndex(status, hasAnalysis);
  const latestTask = useMemo(() => (tasks.length > 0 ? tasks[0] : null), [tasks]);

  useEffect(() => {
    let stop = false;

    async function poll() {
      try {
        const s = await getVideoStatus(videoId);
        if (stop) return;
        setStatusInfo(s);
        listVideoTasks(videoId).then(setTasks).catch(() => {});

        if (s?.status === "CANCELLED") return;

        try {
          const a = await getVideoAnalysis(videoId);
          if (stop) return;
          setAnalysis(a);
          setStatusInfo((prev) => ({ ...(prev || {}), status: "COMPLETED" }));
          return;
        } catch (e) {
          if (e.status === 202) {
            setStatusInfo((prev) => ({ ...(prev || {}), status: e.body?.status || "PROCESSING" }));
            const delay = Number(e.body?.retryAfterMs || 1000);
            timer.current = setTimeout(poll, Number.isFinite(delay) && delay > 0 ? delay : 1000);
            return;
          }
          if (e.status === 500) {
            setStatusInfo((prev) => ({ ...(prev || {}), status: "FAILED" }));
            setError(e.body?.error || e.message);
            return;
          }
          if (e.status === 409) {
            setStatusInfo((prev) => ({ ...(prev || {}), status: e.body?.status || "CANCELLED" }));
            setError(e.body?.error || e.message);
            return;
          }
          if (e.status === 404) {
            setError("视频不存在");
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
    setError(""); setRetrying(true);
    try {
      await retryVideo(videoId);
      window.location.reload();
    } catch (e) {
      setError(e?.body?.error || e.message || "重试失败");
      setRetrying(false);
    }
  }

  async function doCancel() {
    const ok = window.confirm("确认取消当前分析任务吗？");
    if (!ok) return;
    setCanceling(true); setError("");
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
    <div>
      <h1>分析任务 #{videoId}</h1>
      {statusInfo?.exerciseType && (
        <p className="section-subtitle">
          动作：{exerciseTypeLabel(statusInfo.exerciseType)}
          <span style={{ marginLeft: 10 }}><StatusPill status={status || "UNKNOWN"} /></span>
        </p>
      )}

      {/* Stage progress */}
      <div className="card fade-in">
        <h3>分析进度</h3>
        <div className="stage-progress">
          {STAGES.map((label, i) => {
            let cls = "stage-item";
            if (i < currentStage) cls += " done";
            else if (i === currentStage) cls += failed ? " failed" : cancelled ? "" : " current";
            if (failed && i < currentStage) cls += " done";
            return (
              <div key={i} className={cls}>
                <span className="stage-label-sm">{i + 1}</span>
                {label}
              </div>
            );
          })}
        </div>
        {status === "PROCESSING" && (
          <p style={{ fontSize: 12, color: "var(--text-2)", marginTop: 8 }}>
            分析正在进行中，页面会自动刷新...
          </p>
        )}
      </div>

      {/* Error / action states */}
      {error && <p className="error" style={{ marginTop: 12 }}>{error}</p>}

      <div className="inline-actions" style={{ margin: "12px 0" }}>
        {done && <Link to={`/reports/${videoId}`} className="btn btn-sm" style={{ background: "var(--accent)", color: "#fff", padding: "8px 20px", borderRadius: "var(--radius-xs)", fontWeight: 600 }}>查看分析报告</Link>}
        {failed && (
          <button onClick={doRetry} disabled={retrying} style={{ width: "auto", padding: "8px 20px" }}>
            {retrying ? "重试中..." : "重新分析"}
          </button>
        )}
        {cancelled && <Link to="/upload" className="btn btn-sm" style={{ background: "var(--accent)", color: "#fff", padding: "8px 20px", borderRadius: "var(--radius-xs)", fontWeight: 600 }}>重新上传</Link>}
        {canCancel && (
          <button className="danger-btn" disabled={canceling} onClick={doCancel}>
            {canceling ? "取消中..." : "取消任务"}
          </button>
        )}
      </div>

      {cancelled && <p style={{ color: "var(--text-2)", fontSize: 13 }}>任务已取消，你可以重新上传视频或重新发起分析。</p>}
      {failed && <p style={{ color: "var(--text-2)", fontSize: 13 }}>分析失败。请检查视频是否符合拍摄建议后重试。</p>}

      {/* Timing metrics */}
      {latestTask && (
        <div className="card fade-in fade-in-1">
          <h3>任务耗时</h3>
          <div className="metric-grid">
            <div className="metric-card">
              <div className="metric-title">排队耗时</div>
              <div className="task-metric-value">{formatMs(latestTask.queueMs)}</div>
            </div>
            <div className="metric-card">
              <div className="metric-title">执行耗时</div>
              <div className="task-metric-value">{formatMs(latestTask.runMs)}</div>
            </div>
            <div className="metric-card">
              <div className="metric-title">总耗时</div>
              <div className="task-metric-value">{formatMs(latestTask.totalMs)}</div>
            </div>
            <div className="metric-card">
              <div className="metric-title">尝试次数</div>
              <div className="task-metric-value">{latestTask.attempt ?? "-"}</div>
            </div>
          </div>
        </div>
      )}

      {/* Debug info — collapsed by default */}
      <details style={{ marginTop: 12, fontSize: 12, color: "var(--text-2)" }}>
        <summary>调试信息</summary>
        <div style={{ marginTop: 8 }}>
          <p style={{ fontSize: 11, marginBottom: 4 }}>任务状态</p>
          <pre>{JSON.stringify(statusInfo, null, 2)}</pre>
          {analysis && (
            <>
              <p style={{ fontSize: 11, margin: "8px 0 4px" }}>分析结果</p>
              <pre>{JSON.stringify(analysis, null, 2)}</pre>
            </>
          )}
        </div>
      </details>

      {/* Timeline */}
      {tasks.length > 0 && (
        <div className="card" style={{ marginTop: 16 }}>
          <h3>任务记录</h3>
          <ul className="timeline">
            {tasks.map((t) => (
              <li key={t.id}>
                <div style={{ fontWeight: 600, fontSize: 13 }}>
                  任务 #{t.id}<span style={{ color: "var(--text-2)", fontWeight: 400, marginLeft: 8 }}>第 {t.attempt} 次</span>
                </div>
                <div style={{ marginTop: 2 }}>
                  <StatusPill status={t.status || "UNKNOWN"} />
                </div>
                <div style={{ fontSize: 12, color: "var(--text-2)", marginTop: 2 }}>
                  排队：{formatMs(t.queueMs)} | 执行：{formatMs(t.runMs)} | 总计：{formatMs(t.totalMs)}
                </div>
                {t.errorMessage && <div className="error" style={{ fontSize: 12 }}>{t.errorMessage}</div>}
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  );
}
