import { useState, useRef, useCallback } from "react";
import { useNavigate } from "react-router-dom";
import { uploadVideo } from "../api";
import { EXERCISE_OPTIONS } from "../utils/exerciseType";

export default function UploadPage() {
  const navigate = useNavigate();
  const [file, setFile] = useState(null);
  const [durationSec, setDurationSec] = useState(null);
  const [exerciseType, setExerciseType] = useState("PUSHUP");
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);
  const [dragOver, setDragOver] = useState(false);
  const inputRef = useRef(null);

  const onFileChange = useCallback(async (f) => {
    setFile(f); setDurationSec(null);
    if (!f) return;
    try { setDurationSec(await getVideoDuration(f)); } catch { setDurationSec(null); }
  }, []);

  const onDrop = useCallback((e) => { e.preventDefault(); setDragOver(false); onFileChange(e.dataTransfer.files?.[0] || null); }, [onFileChange]);

  async function submit(e) {
    e.preventDefault(); setError("");
    if (!file) return setError("请选择视频文件");
    if (durationSec != null && durationSec > 15) return setError(`视频约 ${durationSec.toFixed(1)} 秒，请选择 15 秒以内`);
    setLoading(true);
    try { const resp = await uploadVideo({ file, exerciseType }); navigate(`/tasks/${resp.videoId}`); }
    catch (err) { setError(err.message || "上传失败"); }
    finally { setLoading(false); }
  }

  return (
    <div>
      <h1>上传视频</h1>
      <div className="card fade-in">
        <form onSubmit={submit}>
          <div
            className={`drop-zone ${dragOver ? "drag-over" : ""}`}
            onClick={() => inputRef.current?.click()}
            onDragOver={(e) => { e.preventDefault(); setDragOver(true); }}
            onDragLeave={() => setDragOver(false)}
            onDrop={onDrop}
          >
            <div className="drop-zone-icon">{file ? "✅" : "⬇"}</div>
            <p style={{ fontWeight: 600, fontSize: 15 }}>{file ? file.name : "拖拽视频文件到此处或点击选择"}</p>
            <p style={{ fontSize: 12, color: "var(--text-2)" }}>支持 MP4 / AVI / MOV，建议 ≤15 秒</p>
            {durationSec != null && <p style={{ fontSize: 13, color: "var(--accent)" }}>时长：{durationSec.toFixed(1)} 秒</p>}
          </div>
          <input ref={inputRef} type="file" accept="video/*" style={{ display: "none" }}
            onChange={(e) => onFileChange(e.target.files?.[0] || null)} />

          <label style={{ marginTop: 16 }}>动作类型</label>
          <div className="chip-group">
            {EXERCISE_OPTIONS.map((opt) => (
              <div key={opt.value} className={`chip${exerciseType === opt.value ? " selected" : ""}`}
                onClick={() => setExerciseType(opt.value)}>{opt.label}</div>
            ))}
          </div>

          {error && <p className="error">{error}</p>}
          <button disabled={loading} style={{ marginTop: 12 }}>{loading ? "上传中..." : "开始分析"}</button>
        </form>
      </div>
    </div>
  );
}

function getVideoDuration(file) {
  return new Promise((resolve, reject) => {
    const url = URL.createObjectURL(file);
    const video = document.createElement("video");
    video.preload = "metadata";
    video.onloadedmetadata = () => { URL.revokeObjectURL(url); Number.isFinite(video.duration) ? resolve(video.duration) : reject(); };
    video.onerror = () => { URL.revokeObjectURL(url); reject(); };
    video.src = url;
  });
}
