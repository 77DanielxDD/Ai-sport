import { useEffect, useMemo, useState } from "react";
import { Link } from "react-router-dom";
import { deleteVideo, deleteVideosByFilter, listVideos } from "../api";
import StatusPill from "../components/StatusPill";
import { EXERCISE_OPTIONS, exerciseTypeLabel } from "../utils/exerciseType";

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

function formatLocalTime(input) {
  if (!input) return "-";
  const d = new Date(input);
  if (Number.isNaN(d.getTime())) return input;
  return new Intl.DateTimeFormat("zh-CN", {
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
  }).format(d);
}

function statusHint(status) {
  const s = String(status || "").toUpperCase();
  if (s === "COMPLETED") return "可查看完整分析报告";
  if (s === "PROCESSING" || s === "UPLOADED" || s === "QUEUED") return "分析进行中";
  if (s === "FAILED") return "分析失败，可重传视频";
  if (s === "CANCELLED") return "任务已取消";
  return "状态待确认";
}

function scoreClass(score) {
  if (score == null || Number.isNaN(Number(score))) return "";
  const n = Number(score);
  if (n < 60) return "score-low";
  if (n >= 80) return "score-high";
  return "";
}

function primaryAction(v) {
  const s = String(v.status || "").toUpperCase();
  if (s === "COMPLETED") return { to: `/reports/${v.id}`, label: "查看报告" };
  return { to: `/tasks/${v.id}`, label: "查看进度" };
}

export default function HistoryPage() {
  const [videos, setVideos] = useState([]);
  const [status, setStatus] = useState("ALL");
  const [type, setType] = useState("ALL");
  const [deletingId, setDeletingId] = useState(null);
  const [message, setMessage] = useState("");
  const [error, setError] = useState("");

  useEffect(() => {
    listVideos().then(setVideos).catch(() => setVideos([]));
  }, []);

  const filtered = useMemo(() => {
    return videos.filter(
      (v) => (status === "ALL" || v.status === status) && (type === "ALL" || v.exerciseType === type),
    );
  }, [videos, status, type]);

  async function handleDelete(videoId) {
    const ok = window.confirm(`确认删除视频 #${videoId} 及其分析结果吗？此操作不可恢复。`);
    if (!ok) return;

    setDeletingId(videoId);
    setMessage("");
    setError("");
    try {
      await deleteVideo(videoId);
      setVideos((prev) => prev.filter((v) => v.id !== videoId));
      setMessage(`视频 #${videoId} 已删除`);
    } catch (e) {
      setError(e?.body?.error || e.message || "删除失败");
    } finally {
      setDeletingId(null);
    }
  }

  async function handleBulkDelete() {
    const targetCount = filtered.length;
    if (targetCount === 0) {
      setMessage("当前筛选结果为空，无需删除");
      setError("");
      return;
    }

    const ok = window.confirm(`确认删除当前筛选结果中的 ${targetCount} 条记录吗？此操作不可恢复。`);
    if (!ok) return;

    setDeletingId("BULK");
    setMessage("");
    setError("");
    try {
      const resp = await deleteVideosByFilter({ status, exerciseType: type });
      const deletedCount = Number(resp?.deletedCount || 0);
      setMessage(`批量删除完成，删除 ${deletedCount} 条`);
      const latest = await listVideos();
      setVideos(latest);
    } catch (e) {
      setError(e?.body?.error || e.message || "批量删除失败");
    } finally {
      setDeletingId(null);
    }
  }

  return (
    <div className="card">
      <h1>我的训练日志</h1>
      {message && <p>{message}</p>}
      {error && <p className="error">{error}</p>}

      <div className="grid2">
        <div>
          <label htmlFor="history-status">按状态筛选</label>
          <select id="history-status" name="status" value={status} onChange={(e) => setStatus(e.target.value)}>
            <option value="ALL">全部</option>
            <option value="UPLOADED">已上传</option>
            <option value="PROCESSING">处理中</option>
            <option value="COMPLETED">已完成</option>
            <option value="FAILED">失败</option>
            <option value="CANCELLED">已取消</option>
          </select>
        </div>
        <div>
          <label htmlFor="history-type">按动作筛选</label>
          <select id="history-type" name="exerciseType" value={type} onChange={(e) => setType(e.target.value)}>
            <option value="ALL">全部</option>
            {EXERCISE_OPTIONS.map((opt) => (
              <option key={opt.value} value={opt.value}>
                {opt.label}
              </option>
            ))}
          </select>
        </div>
      </div>

      <div className="inline-actions" style={{ marginBottom: 12 }}>
        <button type="button" className="danger-btn" disabled={deletingId === "BULK"} onClick={handleBulkDelete}>
          {deletingId === "BULK" ? "删除中..." : "删除当前筛选结果"}
        </button>
      </div>

      <table>
        <thead>
          <tr>
            <th>ID</th>
            <th>动作</th>
            <th>状态</th>
            <th>评分</th>
            <th>次数</th>
            <th>训练时间</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          {filtered.length === 0 ? (
            <tr>
              <td colSpan="7">还没有符合条件的训练记录</td>
            </tr>
          ) : (
            filtered.map((v) => {
              const action = primaryAction(v);
              const repCount = parseRepCount(v);
              const repText = String(v.status || "").toUpperCase() === "COMPLETED"
                ? (repCount != null && !Number.isNaN(repCount) ? `${repCount} 次` : "-")
                : "-";
              return (
                <tr key={v.id}>
                  <td>{v.id}</td>
                  <td>{exerciseTypeLabel(v.exerciseType)}</td>
                  <td>
                    <div>
                      <StatusPill status={v.status} />
                      <div className="history-status-hint">{statusHint(v.status)}</div>
                    </div>
                  </td>
                  <td className={scoreClass(v.trainingScore)}>{v.trainingScore != null ? `${v.trainingScore}` : "-"}</td>
                  <td>{repText}</td>
                  <td>{formatLocalTime(v.uploadedAt)}</td>
                  <td>
                    <div className="history-actions">
                      <Link className="history-primary-link" to={action.to}>{action.label}</Link>
                      {String(v.status || "").toUpperCase() === "COMPLETED" && (
                        <Link to={`/reports/${v.id}#qa`}>问问AI教练</Link>
                      )}
                      <button
                        type="button"
                        className="danger-btn"
                        disabled={deletingId === v.id}
                        onClick={() => handleDelete(v.id)}
                      >
                        {deletingId === v.id ? "删除中..." : "删除"}
                      </button>
                    </div>
                  </td>
                </tr>
              );
            })
          )}
        </tbody>
      </table>
    </div>
  );
}
