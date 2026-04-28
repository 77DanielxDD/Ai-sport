import { useEffect, useMemo, useState } from "react";
import { Link } from "react-router-dom";
import { deleteVideo, deleteVideosByFilter, listVideos } from "../api";
import StatusPill from "../components/StatusPill";
import { EXERCISE_OPTIONS, exerciseTypeLabel } from "../utils/exerciseType";

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
      <h1>我的视频历史</h1>
      {message && <p>{message}</p>}
      {error && <p className="error">{error}</p>}

      <div className="grid2">
        <div>
          <label htmlFor="history-status">按状态筛选</label>
          <select id="history-status" name="status" value={status} onChange={(e) => setStatus(e.target.value)}>
            <option value="ALL">全部</option>
            <option value="UPLOADED">UPLOADED</option>
            <option value="PROCESSING">PROCESSING</option>
            <option value="COMPLETED">COMPLETED</option>
            <option value="FAILED">FAILED</option>
            <option value="CANCELLED">CANCELLED</option>
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
            <th>上传时间</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          {filtered.length === 0 ? (
            <tr>
              <td colSpan="6">暂无符合条件的数据</td>
            </tr>
          ) : (
            filtered.map((v) => (
              <tr key={v.id}>
                <td>{v.id}</td>
                <td>{exerciseTypeLabel(v.exerciseType)}</td>
                <td>
                  <StatusPill status={v.status} />
                </td>
                <td>{v.trainingScore != null ? `${v.trainingScore}` : "-"}</td>
                <td>{v.uploadedAt || "-"}</td>
                <td>
                  <div className="inline-actions">
                    <Link to={`/tasks/${v.id}`}>任务</Link>
                    <Link to={`/reports/${v.id}`}>报告</Link>
                    <Link to={`/reports/${v.id}#qa`}>训练问答</Link>
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
            ))
          )}
        </tbody>
      </table>
    </div>
  );
}
