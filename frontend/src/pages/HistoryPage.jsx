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

  useEffect(() => { listVideos().then(setVideos).catch(() => setVideos([])); }, []);

  const filtered = useMemo(() =>
    videos.filter((v) => (status === "ALL" || v.status === status) && (type === "ALL" || v.exerciseType === type)),
    [videos, status, type]);

  async function handleDelete(videoId) {
    if (!window.confirm(`确认删除视频 #${videoId}？不可恢复。`)) return;
    setDeletingId(videoId); setMessage(""); setError("");
    try { await deleteVideo(videoId); setVideos((p) => p.filter((v) => v.id !== videoId)); setMessage(`已删除 #${videoId}`); }
    catch (e) { setError(e?.body?.error || e.message || "删除失败"); }
    finally { setDeletingId(null); }
  }

  async function handleBulkDelete() {
    if (filtered.length === 0) { setMessage("无数据可删"); return; }
    if (!window.confirm(`确认删除 ${filtered.length} 条记录？不可恢复。`)) return;
    setDeletingId("BULK"); setMessage(""); setError("");
    try { const r = await deleteVideosByFilter({ status, exerciseType: type }); setMessage(`已删除 ${r?.deletedCount || 0} 条`); setVideos(await listVideos()); }
    catch (e) { setError(e?.body?.error || e.message || "删除失败"); }
    finally { setDeletingId(null); }
  }

  return (
    <div>
      <h1>视频历史</h1>
      {message && <p style={{ color: "var(--green)" }}>{message}</p>}
      {error && <p className="error">{error}</p>}

      <div className="card fade-in">
        <div className="grid2" style={{ marginBottom: 12 }}>
          <div>
            <label>状态筛选</label>
            <select value={status} onChange={(e) => setStatus(e.target.value)}>
              <option value="ALL">全部</option>
              <option value="COMPLETED">已完成</option>
              <option value="PROCESSING">处理中</option>
              <option value="UPLOADED">已上传</option>
              <option value="FAILED">失败</option>
              <option value="CANCELLED">已取消</option>
            </select>
          </div>
          <div>
            <label>动作筛选</label>
            <select value={type} onChange={(e) => setType(e.target.value)}>
              <option value="ALL">全部</option>
              {EXERCISE_OPTIONS.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
            </select>
          </div>
        </div>

        <button type="button" className="danger-btn" disabled={deletingId === "BULK"} onClick={handleBulkDelete} style={{ marginBottom: 12 }}>
          {deletingId === "BULK" ? "删除中..." : "清空筛选结果"}
        </button>

        <table>
          <thead><tr><th>ID</th><th>动作</th><th>状态</th><th>评分</th><th>上传时间</th><th>操作</th></tr></thead>
          <tbody>
            {filtered.length === 0 ? <tr><td colSpan="6" style={{ color: "var(--text-2)" }}>暂无数据</td></tr> :
              filtered.map((v) => (
                <tr key={v.id}>
                  <td style={{ fontWeight: 600 }}>#{v.id}</td>
                  <td>{exerciseTypeLabel(v.exerciseType)}</td>
                  <td><StatusPill status={v.status} /></td>
                  <td style={{ fontWeight: 700, color: v.trainingScore != null ? "var(--accent)" : "var(--text-2)" }}>
                    {v.trainingScore != null ? v.trainingScore : "-"}
                  </td>
                  <td style={{ fontSize: 12, color: "var(--text-2)" }}>{v.uploadedAt || "-"}</td>
                  <td>
                    <div className="inline-actions">
                      {v.status === "COMPLETED" ? (
                        <Link to={`/reports/${v.id}`}>报告</Link>
                      ) : (
                        <span style={{ color: "var(--text-3)", fontSize: 12 }}>报告</span>
                      )}
                      <Link to={`/tasks/${v.id}`}>任务</Link>
                      <button type="button" className="danger-btn" disabled={deletingId === v.id} onClick={() => handleDelete(v.id)}>
                        {deletingId === v.id ? "..." : "删"}
                      </button>
                    </div>
                  </td>
                </tr>
              ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
