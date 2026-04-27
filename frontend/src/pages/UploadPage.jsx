import { useState } from "react";
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

  async function submit(e) {
    e.preventDefault();
    setError("");
    if (!file) return setError("请选择视频文件");
    if (durationSec != null && durationSec > 15) {
      return setError(`当前视频约 ${durationSec.toFixed(2)} 秒，请选择 15 秒以内视频`);
    }

    setLoading(true);
    try {
      const resp = await uploadVideo({ file, exerciseType });
      navigate(`/tasks/${resp.videoId}`);
    } catch (err) {
      setError(err.message || "上传失败");
    } finally {
      setLoading(false);
    }
  }

  async function onFileChange(f) {
    setFile(f);
    setDurationSec(null);
    if (!f) return;
    try {
      const d = await getVideoDuration(f);
      setDurationSec(d);
    } catch {
      setDurationSec(null);
    }
  }

  return (
    <div className="card">
      <h1>上传视频</h1>
      <form onSubmit={submit}>
        <label htmlFor="upload-file">视频文件（建议时长 {"<="} 15 秒）</label>
        <input
          id="upload-file"
          name="file"
          type="file"
          accept="video/*"
          onChange={(e) => onFileChange(e.target.files?.[0] || null)}
        />
        {durationSec != null && <p>检测到视频时长：{durationSec.toFixed(2)} 秒</p>}

        <label htmlFor="upload-exercise-type">动作类型</label>
        <select
          id="upload-exercise-type"
          name="exerciseType"
          value={exerciseType}
          onChange={(e) => setExerciseType(e.target.value)}
        >
          {EXERCISE_OPTIONS.map((opt) => (
            <option key={opt.value} value={opt.value}>
              {opt.label}
            </option>
          ))}
        </select>

        {error && <p className="error">{error}</p>}
        <button disabled={loading}>{loading ? "上传中..." : "上传并分析"}</button>
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
