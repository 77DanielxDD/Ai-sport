import { useEffect, useMemo, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { cancelVideo, getToken, getVideoAnalysis, getVideoStatus, listVideoTasks, retryVideo } from "../api";
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
    let timer = null;
    let es = null;
    let polling = false;

    const BASE_POLL_MS = 1000;
    const MAX_POLL_MS = 8000;

    function isTerminal(status) {
      return status === "COMPLETED" || status === "FAILED" || status === "CANCELLED";
    }

    async function fetchAnalysisOnce() {
      if (stop) return;
      try {
        const a = await getVideoAnalysis(videoId);
        if (stop) return;
        setAnalysis(a);
        setStatusInfo((prev) => ({ ...(prev || {}), status: "COMPLETED" }));
      } catch (e) {
        // 202 = 仍在分析；其余错误由轮询兜底展示
        if (e.status === 202) return;
        if (e.status === 500) {
          setStatusInfo((prev) => ({ ...(prev || {}), status: "FAILED" }));
          setError(e.body?.error || e.message);
        } else if (e.status === 409) {
          setStatusInfo((prev) => ({ ...(prev || {}), status: e.body?.status || "CANCELLED" }));
          setError(e.body?.error || e.message);
        } else if (e.status === 404) {
          setError("视频不存在");
        }
      }
    }

    async function pollStatus(delayMs) {
      if (stop || !polling) return;
      try {
        const s = await getVideoStatus(videoId);
        if (stop) return;
        setStatusInfo(s);

        if (isTerminal(s?.status)) {
          // 终态：停止轮询 + 刷新任务列表 + 只拉一次 analysis
          polling = false;
          listVideoTasks(videoId).then(setTasks).catch(() => {});
          fetchAnalysisOnce();
          return;
        }

        // 活跃态：只轮询 status，指数退避（服务端 retryAfterMs 优先）
        const serverMs = Number(s?.retryAfterMs);
        const next = Number.isFinite(serverMs) && serverMs > 0
          ? Math.min(serverMs, MAX_POLL_MS)
          : Math.min(delayMs * 2, MAX_POLL_MS);
        timer = setTimeout(() => pollStatus(next), next);
      } catch (e) {
        setError(e.message || "轮询失败");
        timer = setTimeout(() => pollStatus(BASE_POLL_MS), 2000);
      }
    }

    function startPolling() {
      if (stop || polling) return;
      polling = true;
      timer = setTimeout(() => pollStatus(BASE_POLL_MS), BASE_POLL_MS);
    }

    function stopPolling() {
      polling = false;
      if (timer) clearTimeout(timer);
      timer = null;
    }

    // SSE 推送：连上后停高频轮询，断线自动回退轮询
    function connectSSE() {
      if (stop) return;
      stopPolling();
      try {
        es = new EventSource(`/api/tasks/video/${videoId}/events?token=${encodeURIComponent(getToken())}`);
        es.addEventListener("status", (e) => {
          if (stop) return;
          const status = e.data;
          setStatusInfo((prev) => ({ ...(prev || {}), status }));
          if (isTerminal(status)) {
            if (es) { es.close(); es = null; }
            listVideoTasks(videoId).then(setTasks).catch(() => {});
            fetchAnalysisOnce();
          }
        });
        es.onerror = () => {
          if (es) { es.close(); es = null; }
          if (!stop) startPolling();
        };
      } catch (e) {
        startPolling();
      }
    }

    // 首次进入：拉一次 status + task list；活跃态走 SSE，终态直接拉 analysis
    (async () => {
      try {
        const s = await getVideoStatus(videoId);
        if (stop) return;
        setStatusInfo(s);
        listVideoTasks(videoId).then(setTasks).catch(() => {});
        if (isTerminal(s?.status)) {
          fetchAnalysisOnce();
        } else {
          connectSSE();
        }
      } catch (e) {
        setError(e.message || "加载失败");
        startPolling();
      }
    })();

    return () => {
      stop = true;
      if (timer) clearTimeout(timer);
      if (es) es.close();
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
