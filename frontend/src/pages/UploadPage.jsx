import { useState, useRef, useCallback, useEffect } from "react";
import { useNavigate } from "react-router-dom";
import { uploadVideoWithProgress } from "../api";
import { EXERCISE_OPTIONS } from "../utils/exerciseType";

const MAX_VIDEO_DURATION_SEC = 30;
const MAX_VIDEO_SIZE_MB = 150;

function formatSize(bytes) {
  if (bytes == null) return "-";
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

function formatDuration(sec) {
  if (sec == null) return "-";
  const m = Math.floor(sec / 60);
  const s = Math.floor(sec % 60);
  return m > 0 ? `${m}分${s}秒` : `${s} 秒`;
}

export default function UploadPage() {
  const navigate = useNavigate();
  const [file, setFile] = useState(null);
  const [durationSec, setDurationSec] = useState(null);
  const [exerciseType, setExerciseType] = useState("PUSHUP");
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);
  const [dragOver, setDragOver] = useState(false);
  const [previewUrl, setPreviewUrl] = useState(null);
  const [uploadProgress, setUploadProgress] = useState(0);
  const inputRef = useRef(null);

  const durationOk = durationSec == null || durationSec <= MAX_VIDEO_DURATION_SEC;
  const sizeOk = !file || file.size <= MAX_VIDEO_SIZE_MB * 1024 * 1024;
  const canSubmit = !!file && durationOk && sizeOk && !loading;
  const selectedOption = EXERCISE_OPTIONS.find((o) => o.value === exerciseType);

  useEffect(() => {
    if (!file) { setPreviewUrl(null); return; }
    const url = URL.createObjectURL(file);
    setPreviewUrl(url);
    return () => URL.revokeObjectURL(url);
  }, [file]);

  const onFileChange = useCallback(async (f) => {
    setFile(f); setDurationSec(null); setError("");
    if (!f) return;
    const isVideo = f.type.startsWith("video/") || /\.(mp4|avi|mov|webm|mkv)$/i.test(f.name);
    if (!isVideo) {
      setFile(null); setError("请选择视频文件（MP4、AVI、MOV 等）"); return;
    }
    if (f.size > MAX_VIDEO_SIZE_MB * 1024 * 1024) {
      setFile(null); setError(`视频文件超过 ${MAX_VIDEO_SIZE_MB}MB，请压缩后重新上传`); return;
    }
    try { setDurationSec(await getVideoDuration(f)); } catch { setDurationSec(null); }
  }, []);

  const onDrop = useCallback((e) => {
    e.preventDefault(); setDragOver(false);
    onFileChange(e.dataTransfer.files?.[0] || null);
  }, [onFileChange]);

  async function submit(e) {
    e.preventDefault(); setError(""); setUploadProgress(0);
    if (!file) return setError("请选择视频文件");
    if (!durationOk) return setError(`视频超过 ${MAX_VIDEO_DURATION_SEC} 秒限制，请裁剪后重新上传`);
    if (!sizeOk) return setError(`视频文件超过 ${MAX_VIDEO_SIZE_MB}MB，请压缩后重新上传`);
    setLoading(true);
    try {
      const resp = await uploadVideoWithProgress({
        file, exerciseType, durationSec,
        onProgress: setUploadProgress,
      });
      navigate(`/tasks/${resp.videoId}`);
    } catch (err) {
      setError(err.message || "上传失败");
      setLoading(false);
    }
  }

  return (
    <div>
      <h1>上传分析</h1>
      <p className="section-subtitle">上传一段单动作训练视频，开始 AI 姿态分析</p>

      {/* Recording guide */}
      <div className="card fade-in" style={{ marginBottom: 16 }}>
        <h3>拍摄建议</h3>
        <div className="recording-guide">
          <div className="guide-item">
            <span className="guide-icon">&#9998;</span>
            <span>全身或目标关节完整入镜</span>
          </div>
          <div className="guide-item">
            <span className="guide-icon">&#9638;</span>
            <span>镜头固定，避免晃动</span>
          </div>
          <div className="guide-item">
            <span className="guide-icon">&#9788;</span>
            <span>光线充足，减少衣物遮挡</span>
          </div>
          <div className="guide-item">
            <span className="guide-icon">&#9202;</span>
            <span>单次动作 {MAX_VIDEO_DURATION_SEC} 秒以内为佳</span>
          </div>
          <div className="guide-item">
            <span className="guide-icon">&#9654;</span>
            <span>{selectedOption?.tip || "根据动作类型选择拍摄角度"}</span>
          </div>
        </div>
        <p style={{ fontSize: 11, color: "var(--text-3)", marginTop: 10 }}>
          建议 720p 或 1080p，文件不超过 {MAX_VIDEO_SIZE_MB}MB
        </p>
      </div>

      {/* Upload + Preview */}
      <div className="card fade-in fade-in-1">
        <form onSubmit={submit}>
          <div
            className={`drop-zone ${dragOver ? "drag-over" : ""} ${file ? "has-file" : ""}`}
            onClick={() => inputRef.current?.click()}
            onDragOver={(e) => { e.preventDefault(); setDragOver(true); }}
            onDragLeave={() => setDragOver(false)}
            onDrop={onDrop}
          >
            {!file ? (
              <>
                <div className="drop-zone-icon">&#11014;</div>
                <p style={{ fontWeight: 600, fontSize: 15 }}>拖拽视频到此处或点击选择</p>
                <p style={{ fontSize: 12, color: "var(--text-2)" }}>支持 MP4、AVI、MOV，最大 {MAX_VIDEO_SIZE_MB}MB</p>
              </>
            ) : (
              <div className="file-preview" onClick={(e) => e.stopPropagation()}>
                <div className="file-preview-meta">
                  <div className="file-preview-name" title={file.name}>{file.name}</div>
                  <div className="file-preview-stats">
                    <span>{formatSize(file.size)}</span>
                    <span className="file-preview-stat-sep"></span>
                    <span className={durationOk ? "" : "file-duration-over"}>{formatDuration(durationSec)}</span>
                  </div>
                  {!durationOk && <p className="error" style={{ marginTop: 4 }}>视频超过 {MAX_VIDEO_DURATION_SEC} 秒限制，请裁剪后再上传</p>}
                  {!sizeOk && <p className="error" style={{ marginTop: 4 }}>文件超过 {MAX_VIDEO_SIZE_MB}MB 限制</p>}
                  <button type="button" className="ghost btn-sm" style={{ marginTop: 8 }}
                    onClick={(e) => { e.stopPropagation(); setFile(null); setDurationSec(null); }}>
                    移除文件
                  </button>
                </div>
                {previewUrl && (
                  <div className="file-preview-video">
                    <video src={previewUrl} controls preload="metadata" style={{ maxWidth: "100%", maxHeight: 200, borderRadius: "var(--radius-xs)" }} />
                  </div>
                )}
              </div>
            )}
          </div>
          <input ref={inputRef} type="file" accept="video/*" style={{ display: "none" }}
            onChange={(e) => onFileChange(e.target.files?.[0] || null)} />

          <label style={{ marginTop: 20 }}>动作类型</label>
          <div className="chip-group">
            {EXERCISE_OPTIONS.map((opt) => (
              <div key={opt.value}
                className={`chip${exerciseType === opt.value ? " selected" : ""}`}
                onClick={() => setExerciseType(opt.value)}
                title={opt.tip}>
                {opt.label}
              </div>
            ))}
          </div>
          {selectedOption && (
            <p style={{ fontSize: 12, color: "var(--text-2)", marginTop: 4 }}>{selectedOption.tip}</p>
          )}

          {error && <p className="error" style={{ marginTop: 8 }}>{error}</p>}

          {loading && (
            <div className="upload-progress">
              <div className="upload-progress-bar" style={{ width: `${uploadProgress}%` }} />
              <span style={{ display: "block", marginTop: 6, fontSize: 12, color: "var(--text-2)", fontFamily: "var(--font-mono)" }}>
                {uploadProgress > 0 ? `上传中 ${uploadProgress}%` : "准备上传..."}
              </span>
            </div>
          )}

          <button disabled={!canSubmit} style={{ marginTop: 14 }}>
            {loading ? (uploadProgress > 0 ? `上传中 ${uploadProgress}%` : "上传中...") : "开始分析"}
          </button>
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
