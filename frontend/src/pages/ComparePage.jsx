import { useEffect, useMemo, useState } from "react";
import { compareVideos, listVideos } from "../api";
import { exerciseTypeLabel } from "../utils/exerciseType";

function fmtAngle(v) {
  if (v == null || Number.isNaN(Number(v))) return "-";
  return `${Number(v).toFixed(1)} 度`;
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
        if (completed.length >= 2) {
          setLeftId(String(completed[0].id));
          setRightId(String(completed[1].id));
        }
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
      setError("请选择两条已完成分析的视频");
      return;
    }
    if (leftId === rightId) {
      setError("请选择两条不同的视频进行对比");
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

  return (
    <div>
      <h1>报告对比</h1>
      <div className="card">
        <form onSubmit={runCompare}>
          <div className="grid2">
            <div>
              <label htmlFor="compare-left">对比视频 A</label>
              <select id="compare-left" name="leftId" value={leftId} onChange={(e) => setLeftId(e.target.value)}>
                <option value="">请选择</option>
                {videos.map((v) => (
                  <option key={`l-${v.id}`} value={v.id}>
                    #{v.id} - {exerciseTypeLabel(v.exerciseType)} - {v.uploadedAt || "-"}
                  </option>
                ))}
              </select>
            </div>
            <div>
              <label htmlFor="compare-right">对比视频 B</label>
              <select id="compare-right" name="rightId" value={rightId} onChange={(e) => setRightId(e.target.value)}>
                <option value="">请选择</option>
                {videos.map((v) => (
                  <option key={`r-${v.id}`} value={v.id}>
                    #{v.id} - {exerciseTypeLabel(v.exerciseType)} - {v.uploadedAt || "-"}
                  </option>
                ))}
              </select>
            </div>
          </div>
          {error && <p className="error">{error}</p>}
          <button disabled={loading}>{loading ? "对比中..." : "开始对比"}</button>
        </form>
      </div>

      {leftVideo && rightVideo && (
        <div className="card">
          <p>
            当前选择：A（#{leftVideo.id}，{exerciseTypeLabel(leftVideo.exerciseType)}） vs B（#{rightVideo.id}，
            {exerciseTypeLabel(rightVideo.exerciseType)}）
          </p>
        </div>
      )}

      {result && (
        <>
          <div className="grid2">
            <div className="card">
              <h3>视频 A（#{result.left.videoId}）</h3>
              <p>动作：{exerciseTypeLabel(result.left.exerciseType)}</p>
              <p>次数：{result.left.repCount}</p>
              <p>角度均值：{fmtAngle(result.left.avgMinAngle)}</p>
              <p>建议条数：{result.left.tipsCount}</p>
            </div>
            <div className="card">
              <h3>视频 B（#{result.right.videoId}）</h3>
              <p>动作：{exerciseTypeLabel(result.right.exerciseType)}</p>
              <p>次数：{result.right.repCount}</p>
              <p>角度均值：{fmtAngle(result.right.avgMinAngle)}</p>
              <p>建议条数：{result.right.tipsCount}</p>
            </div>
          </div>

          <div className="card">
            <h3>变化总结</h3>
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
    </div>
  );
}
