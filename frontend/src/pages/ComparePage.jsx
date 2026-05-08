import { useEffect, useMemo, useState } from "react";
import { compareVideos, listVideos } from "../api";
import { exerciseTypeLabel } from "../utils/exerciseType";

function fmtAngle(v) {
  if (v == null || Number.isNaN(Number(v))) return "-";
  return `${Number(v).toFixed(1)} 度`;
}

function fmtTime(input) {
  if (!input) return "-";
  const d = new Date(input);
  if (Number.isNaN(d.getTime())) return String(input).replace("T", " ").slice(0, 16);
  const p = (n) => String(n).padStart(2, "0");
  return `${d.getFullYear()}/${p(d.getMonth() + 1)}/${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
}

function fmtScore(v) {
  const n = Number(v);
  if (Number.isNaN(n)) return "-";
  return Number.isInteger(n) ? `${n}` : n.toFixed(1);
}

function scoreClass(v) {
  const n = Number(v);
  if (Number.isNaN(n)) return "";
  if (n < 60) return "score-low";
  if (n >= 80) return "score-high";
  return "";
}

export default function ComparePage() {
  const [videos, setVideos] = useState([]);
  const [leftId, setLeftId] = useState("");
  const [rightId, setRightId] = useState("");
  const [result, setResult] = useState(null);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    listVideos()
      .then((rows) => {
        const completed = (rows || []).filter((v) => v.status === "COMPLETED");
        setVideos(completed);
      })
      .catch((e) => setError(e.message || "加载视频列表失败"));
  }, []);

  const leftVideo = useMemo(() => videos.find((v) => String(v.id) === String(leftId)), [videos, leftId]);
  const rightVideo = useMemo(() => videos.find((v) => String(v.id) === String(rightId)), [videos, rightId]);

  async function runCompare(e) {
    e.preventDefault();
    setError("");
    setResult(null);
    if (!leftId || !rightId) {
      setError("请先选择两条训练记录再开始对比");
      return;
    }
    if (leftId === rightId) {
      setError("请选择两条不同的训练记录");
      return;
    }

    setLoading(true);
    try {
      const resp = await compareVideos(leftId, rightId);
      setResult(resp);
    } catch (e1) {
      setError(e1?.body?.error || e1.message || "对比失败");
    } finally {
      setLoading(false);
    }
  }

  const guideEmpty = !result;

  return (
    <div>
      <h1>报告对比</h1>
      <div className="card">
        <form onSubmit={runCompare}>
          <div className="grid2">
            <div>
              <label htmlFor="compare-left">对比训练 A</label>
              <select id="compare-left" name="leftId" value={leftId} onChange={(e) => setLeftId(e.target.value)}>
                <option value="">请选择训练</option>
                {videos.map((v) => (
                  <option key={`l-${v.id}`} value={v.id}>
                    {exerciseTypeLabel(v.exerciseType)} · {fmtTime(v.uploadedAt)} · 评分 {fmtScore(v.trainingScore)}
                  </option>
                ))}
              </select>
            </div>
            <div>
              <label htmlFor="compare-right">对比训练 B</label>
              <select id="compare-right" name="rightId" value={rightId} onChange={(e) => setRightId(e.target.value)}>
                <option value="">请选择训练</option>
                {videos.map((v) => (
                  <option key={`r-${v.id}`} value={v.id}>
                    {exerciseTypeLabel(v.exerciseType)} · {fmtTime(v.uploadedAt)} · 评分 {fmtScore(v.trainingScore)}
                  </option>
                ))}
              </select>
            </div>
          </div>
          {error && <p className="error">{error}</p>}
          <button disabled={loading}>{loading ? "对比中..." : "开始对比"}</button>
        </form>
      </div>

      {guideEmpty ? (
        <div className="card compare-empty">
          <h3>开始你的训练对比</h3>
          <p>先在上方选择两条“已完成”的训练记录，然后点击“开始对比”。</p>
          <p>对比后将显示左右训练数据、评分变化和建议差异摘要。</p>
        </div>
      ) : (
        <>
          <div className="grid2">
            <div className="card">
              <h3>训练 A</h3>
              <p>动作：{exerciseTypeLabel(result.left.exerciseType)}</p>
              <p>时间：{fmtTime(result.left.uploadedAt)}</p>
              <p className={scoreClass(result.left.trainingScore)}>评分：{fmtScore(result.left.trainingScore)}</p>
              <p>次数：{result.left.repCount}</p>
              <p>角度均值：{fmtAngle(result.left.avgMinAngle)}</p>
              <p>建议条数：{result.left.tipsCount}</p>
            </div>
            <div className="card">
              <h3>训练 B</h3>
              <p>动作：{exerciseTypeLabel(result.right.exerciseType)}</p>
              <p>时间：{fmtTime(result.right.uploadedAt)}</p>
              <p className={scoreClass(result.right.trainingScore)}>评分：{fmtScore(result.right.trainingScore)}</p>
              <p>次数：{result.right.repCount}</p>
              <p>角度均值：{fmtAngle(result.right.avgMinAngle)}</p>
              <p>建议条数：{result.right.tipsCount}</p>
            </div>
          </div>

          <div className="card compare-summary">
            <h3>差异摘要</h3>
            <p>{result.diff.summary}</p>
            <p>次数变化（B-A）：{result.diff.repCountDelta}</p>
            <p>角度均值变化（B-A）：{result.diff.avgMinAngleDelta == null ? "-" : `${result.diff.avgMinAngleDelta} 度`}</p>
            <p>动作类型一致：{result.diff.sameExerciseType ? "是" : "否"}</p>
          </div>

          <div className="grid2">
            <div className="card">
              <h3>新增建议（B 相比 A）</h3>
              {result.diff.addedTips?.length ? (
                <ul>
                  {result.diff.addedTips.map((t, idx) => (
                    <li key={`add-${idx}`}>{t}</li>
                  ))}
                </ul>
              ) : (
                <p>无</p>
              )}
            </div>
            <div className="card">
              <h3>减少建议（B 相比 A）</h3>
              {result.diff.removedTips?.length ? (
                <ul>
                  {result.diff.removedTips.map((t, idx) => (
                    <li key={`remove-${idx}`}>{t}</li>
                  ))}
                </ul>
              ) : (
                <p>无</p>
              )}
            </div>
          </div>
        </>
      )}

      {leftVideo && rightVideo && (
        <div className="card compare-picked">
          当前选择：A（{exerciseTypeLabel(leftVideo.exerciseType)}，{fmtTime(leftVideo.uploadedAt)}） vs B（{exerciseTypeLabel(rightVideo.exerciseType)}，{fmtTime(rightVideo.uploadedAt)}）
        </div>
      )}
    </div>
  );
}
