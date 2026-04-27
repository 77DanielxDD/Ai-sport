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

  const done = useMemo(() => statusInfo?.status === "COMPLETED", [statusInfo]);
  const cancelled = useMemo(() => statusInfo?.status === "CANCELLED", [statusInfo]);
  const canCancel = useMemo(
    () => statusInfo?.status === "UPLOADED" || statusInfo?.status === "PROCESSING",
    [statusInfo],
  );
  const latestTask = useMemo(() => (tasks.length > 0 ? tasks[0] : null), [tasks]);

  useEffect(() => {
    let stop = false;

    async function poll() {
      try {
        const s = await getVideoStatus(videoId);
        if (stop) return;
        setStatusInfo(s);
        listVideoTasks(videoId).then(setTasks).catch(() => {});

        if (s?.status === "CANCELLED") {
          return;
        }

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

      {statusInfo?.exerciseType && (
        <p>
          动作类型：<b>{exerciseTypeLabel(statusInfo.exerciseType)}</b>
        </p>
      )}
      <p>
        状态：<StatusPill status={statusInfo?.status || "UNKNOWN"} />
      </p>
      {statusInfo?.endToEndMs != null && <p>端到端耗时：{formatMs(statusInfo.endToEndMs)}</p>}
      {error && <p className="error">{error}</p>}

      {latestTask && (
        <div className="task-metric-grid">
          <div className="task-metric-card">
            <div className="task-metric-title">排队耗时</div>
            <div className="task-metric-value">{formatMs(latestTask.queueMs)}</div>
          </div>
          <div className="task-metric-card">
            <div className="task-metric-title">执行耗时</div>
            <div className="task-metric-value">{formatMs(latestTask.runMs)}</div>
          </div>
          <div className="task-metric-card">
            <div className="task-metric-title">总耗时</div>
            <div className="task-metric-value">{formatMs(latestTask.totalMs)}</div>
          </div>
        </div>
      )}

      <div className="inline-actions" style={{ margin: "8px 0 12px" }}>
        {done && <Link to={`/reports/${videoId}`}>查看分析报告</Link>}
        {statusInfo?.status === "FAILED" && <button onClick={doRetry}>重试分析</button>}
        {canCancel && (
          <button className="danger-btn" disabled={canceling} onClick={doCancel}>
            {canceling ? "取消中..." : "取消任务"}
          </button>
        )}
      </div>

      {cancelled && <p>任务已取消，你可以重新上传视频或重新发起分析。</p>}

      <details>
        <summary>任务状态原始 JSON</summary>
        <pre>{JSON.stringify(statusInfo, null, 2)}</pre>
      </details>

      {analysis && (
        <details>
          <summary>分析结果原始 JSON</summary>
          <pre>{JSON.stringify(analysis, null, 2)}</pre>
        </details>
      )}

      <div className="card">
        <h3>任务时间线</h3>
        {tasks.length === 0 ? (
          <p>暂无任务记录</p>
        ) : (
          <ul className="timeline">
            {tasks.map((t) => (
              <li key={t.id}>
                <div>
                  <b>任务 #{t.id}</b>（第 {t.attempt} 次）
                </div>
                <div>
                  状态：<StatusPill status={t.status || "UNKNOWN"} />
                  {t.canCancel ? <span style={{ marginLeft: 8 }}>可取消</span> : <span style={{ marginLeft: 8 }}>不可取消</span>}
                </div>
                <div>排队：{t.queuedAt || "-"}</div>
                <div>开始：{t.startedAt || "-"}</div>
                <div>结束：{t.finishedAt || "-"}</div>
                <div>排队耗时：{formatMs(t.queueMs)} | 执行耗时：{formatMs(t.runMs)} | 总耗时：{formatMs(t.totalMs)}</div>
                {t.errorMessage && <div className="error">错误：{t.errorMessage}</div>}
              </li>
            ))}
          </ul>
        )}
      </div>
    </div>
  );
}
