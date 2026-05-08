import { useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import { uploadVideo } from "../api";
import { EXERCISE_OPTIONS, exerciseTypeLabel } from "../utils/exerciseType";

const MAX_DURATION_SEC = 15;
const ACCEPT_EXT = ["mp4", "mov", "avi", "mkv", "webm", "m4v"];

export default function UploadPage() {
  const navigate = useNavigate();
  const fileInputRef = useRef(null);

  const [file, setFile] = useState(null);
  const [durationSec, setDurationSec] = useState(null);
  const [exerciseType, setExerciseType] = useState("PUSHUP");
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);
  const [fileError, setFileError] = useState("");
  const [dragOver, setDragOver] = useState(false);
  const [toast, setToast] = useState({ show: false, message: "", type: "error" });

  function showToast(message, type = "error") {
    setToast({ show: true, message, type });
    window.setTimeout(() => setToast((t) => ({ ...t, show: false })), 2200);
  }

  function getExt(filename) {
    const i = String(filename || "").lastIndexOf(".");
    return i >= 0 ? filename.slice(i + 1).toLowerCase() : "";
  }

  async function submit(e) {
    e.preventDefault();
    setError("");
    if (!file) return setError("请选择视频文件");
    if (fileError) return setError(fileError);

    setLoading(true);
    try {
      const resp = await uploadVideo({ file, exerciseType });
      showToast("上传成功，正在进入任务页面", "success");
      window.setTimeout(() => navigate(`/tasks/${resp.videoId}`), 250);
    } catch (err) {
      const msg = err.message || "上传失败";
      setError(msg);
      showToast(msg, "error");
    } finally {
      setLoading(false);
    }
  }

  async function onFileChange(f) {
    setError("");
    setFile(f);
    setDurationSec(null);
    setFileError("");
    if (!f) return;

    const ext = getExt(f.name);
    if (!ACCEPT_EXT.includes(ext)) {
      setFileError(`文件格式不支持：.${ext || "unknown"}，请上传 ${ACCEPT_EXT.join(" / ")} 格式`);
      return;
    }

    try {
      const d = await getVideoDuration(f);
      setDurationSec(d);
      if (d > MAX_DURATION_SEC) {
        setFileError(`当前视频时长 ${d.toFixed(2)} 秒，请选择 ${MAX_DURATION_SEC} 秒以内视频`);
      }
    } catch {
      setFileError("无法读取视频时长，请更换文件后重试");
      setDurationSec(null);
    }
  }

  function openPicker() {
    fileInputRef.current?.click();
  }

  function onDrop(e) {
    e.preventDefault();
    setDragOver(false);
    const f = e.dataTransfer?.files?.[0] || null;
    onFileChange(f);
  }

  const ext = file ? getExt(file.name) : "-";
  const fileMb = file ? (file.size / 1024 / 1024).toFixed(2) : "-";
  const canSubmit = !loading && !!file && !fileError;

  return (
    <div className="upload-page">
      {toast.show && (
        <div className={`toast ${toast.type === "error" ? "toast-error" : "toast-success"}`}>
          {toast.message}
        </div>
      )}

      <div className="upload-hero card">
        <p className="upload-eyebrow">AI 动作分析</p>
        <h1>上传训练视频</h1>
        <p className="upload-desc">拖入视频，选择动作类型，系统将在几秒内生成训练报告与改进建议。</p>
      </div>

      <form className="upload-layout" onSubmit={submit}>
        <section className="card upload-drop-card">
          <input
            ref={fileInputRef}
            id="upload-file"
            name="file"
            type="file"
            accept="video/*"
            style={{ display: "none" }}
            onChange={(e) => onFileChange(e.target.files?.[0] || null)}
          />

          <button
            type="button"
            className={`upload-dropzone ${dragOver ? "dragover" : ""}`}
            onClick={openPicker}
            onDragOver={(e) => {
              e.preventDefault();
              setDragOver(true);
            }}
            onDragLeave={() => setDragOver(false)}
            onDrop={onDrop}
          >
            <div className="drop-icon">⬆</div>
            <div className="drop-title">点击或拖拽视频到这里</div>
            <div className="drop-sub">支持 {ACCEPT_EXT.join(" / ")}，建议时长不超过 {MAX_DURATION_SEC} 秒</div>
          </button>

          <div className="upload-file-meta">
            <div><span>文件名</span><b>{file?.name || "尚未选择"}</b></div>
            <div><span>格式</span><b>{ext}</b></div>
            <div><span>大小</span><b>{fileMb} MB</b></div>
            <div><span>时长</span><b>{durationSec != null ? `${durationSec.toFixed(2)} 秒` : "待检测"}</b></div>
          </div>
          {fileError && <p className="error">{fileError}</p>}
        </section>

        <section className="card upload-side-card">
          <h3>分析设置</h3>
          <label htmlFor="upload-exercise-type">动作类型</label>
          <select id="upload-exercise-type" name="exerciseType" value={exerciseType} onChange={(e) => setExerciseType(e.target.value)}>
            {EXERCISE_OPTIONS.map((opt) => (
              <option key={opt.value} value={opt.value}>{opt.label}</option>
            ))}
          </select>

          <div className="upload-checklist">
            <div className={`check-item ${file ? "ok" : ""}`}>{file ? "✅" : "⬜"} 已选择视频</div>
            <div className={`check-item ${durationSec != null ? "ok" : ""}`}>{durationSec != null ? "✅" : "⬜"} 时长已检测</div>
            <div className={`check-item ${!fileError && file ? "ok" : ""}`}>{!fileError && file ? "✅" : "⬜"} 通过格式校验</div>
            <div className="check-item">🎯 当前动作：{exerciseTypeLabel(exerciseType)}</div>
          </div>

          {error && <p className="error">{error}</p>}
          <button className="upload-submit-btn" disabled={!canSubmit}>{loading ? "上传中..." : "开始分析"}</button>
        </section>
      </form>
    </div>
  );
}

function getVideoDuration(file) {
  return new Promise((resolve, reject) => {
    const url = URL.createObjectURL(file);
    const video = document.createElement("video");
    video.preload = "metadata";
    video.onloadedmetadata = () => {
      const d = video.duration;
      URL.revokeObjectURL(url);
      if (Number.isFinite(d)) resolve(d);
      else reject(new Error("invalid duration"));
    };
    video.onerror = () => {
      URL.revokeObjectURL(url);
      reject(new Error("read failed"));
    };
    video.src = url;
  });
}
