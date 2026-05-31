import { useEffect, useMemo, useState } from "react";
import { compareVideos, listVideos } from "../api";
import { exerciseTypeLabel } from "../utils/exerciseType";

function fmtAngle(v) { if (v == null || Number.isNaN(Number(v))) return "-"; return `${Number(v).toFixed(1)}°`; }

function Delta({ value, unit = "" }) {
  if (value == null) return <span style={{ color: "var(--text-3)" }}>—</span>;
  const cls = value > 0 ? "compare-delta-up" : value < 0 ? "compare-delta-down" : "compare-delta-same";
  const sign = value > 0 ? "+" : "";
  return <span className={`compare-delta ${cls}`}>{sign}{value}{unit}</span>;
}

export default function ComparePage() {
  const [videos, setVideos] = useState([]);
  const [leftId, setLeftId] = useState("");
  const [rightId, setRightId] = useState("");
  const [result, setResult] = useState(null);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    listVideos().then((rows) => {
      const completed = (rows || []).filter((v) => v.status === "COMPLETED");
      setVideos(completed);
      if (completed.length >= 2) { setLeftId(String(completed[0].id)); setRightId(String(completed[1].id)); }
    }).catch((e) => setError(e.message || "加载失败"));
  }, []);

  async function runCompare(e) {
    e.preventDefault(); setError(""); setResult(null);
    if (!leftId || !rightId) return setError("请选择两条已完成分析的视频");
    if (leftId === rightId) return setError("请选择两条不同的视频");
    setLoading(true);
    try { setResult(await compareVideos(leftId, rightId)); }
    catch (e1) { setError(e1?.body?.error || e1.message || "对比失败"); }
    finally { setLoading(false); }
  }

  return (
    <div>
      <div className="section-title"><h1>报告对比</h1></div>

      <div className="card card-data">
        <form onSubmit={runCompare}>
          <div className="grid2">
            <div><label>视频 A</label>
              <select value={leftId} onChange={(e) => setLeftId(e.target.value)}>
                <option value="">请选择</option>
                {videos.map((v) => <option key={`l-${v.id}`} value={v.id}>#{v.id} {exerciseTypeLabel(v.exerciseType)}</option>)}
              </select>
            </div>
            <div><label>视频 B</label>
              <select value={rightId} onChange={(e) => setRightId(e.target.value)}>
                <option value="">请选择</option>
                {videos.map((v) => <option key={`r-${v.id}`} value={v.id}>#{v.id} {exerciseTypeLabel(v.exerciseType)}</option>)}
              </select>
            </div>
          </div>
          {error && <p className="error">{error}</p>}
          <button disabled={loading} style={{ width: "auto", padding: "10px 32px" }}>{loading ? "对比中..." : "开始对比"}</button>
        </form>
      </div>

      {result && (
        <>
          <div className="compare-grid">
            <div className="card compare-side fade-in">
              <h3>A · #{result.left.videoId}</h3>
              <p style={{ fontSize: 14, color: "var(--text-2)" }}>{exerciseTypeLabel(result.left.exerciseType)}</p>
              <div className="metric-grid" style={{ marginTop: 16 }}>
                <div className="metric-card"><div className="metric-title">次数</div><div className="metric-value" style={{ fontSize: 24 }}>{result.left.repCount}</div></div>
                <div className="metric-card"><div className="metric-title">角度均值</div><div className="metric-value" style={{ fontSize: 24 }}>{fmtAngle(result.left.avgMinAngle)}</div></div>
                <div className="metric-card"><div className="metric-title">评分</div><div className="metric-value" style={{ fontSize: 24, color: "var(--accent)" }}>{result.left.trainingScore ?? "-"}</div></div>
              </div>
            </div>

            <div className="compare-spine fade-in fade-in-2">
              <Delta value={result.diff.repCountDelta} />
              <Delta value={result.diff.avgMinAngleDelta} unit="°" />
              <div style={{ fontSize: 11, color: "var(--text-3)", marginTop: 8 }}>{result.diff.summary}</div>
            </div>

            <div className="card compare-side fade-in fade-in-1">
              <h3>B · #{result.right.videoId}</h3>
              <p style={{ fontSize: 14, color: "var(--text-2)" }}>{exerciseTypeLabel(result.right.exerciseType)}</p>
              <div className="metric-grid" style={{ marginTop: 16 }}>
                <div className="metric-card"><div className="metric-title">次数</div><div className="metric-value" style={{ fontSize: 24 }}>{result.right.repCount}</div></div>
                <div className="metric-card"><div className="metric-title">角度均值</div><div className="metric-value" style={{ fontSize: 24 }}>{fmtAngle(result.right.avgMinAngle)}</div></div>
                <div className="metric-card"><div className="metric-title">评分</div><div className="metric-value" style={{ fontSize: 24, color: "var(--accent)" }}>{result.right.trainingScore ?? "-"}</div></div>
              </div>
            </div>
          </div>

          {((result.diff?.addedTips?.length > 0) || (result.diff?.removedTips?.length > 0)) && (
            <div className="grid2">
              <div className="card card-highlight fade-in fade-in-3">
                <h3 style={{ color: "var(--green)" }}>改进项（B 新增）</h3>
                {result.diff.addedTips?.length ? <ul style={{ paddingLeft: 18 }}>{result.diff.addedTips.map((t, i) => <li key={i} style={{ marginBottom: 6, fontSize: 14 }}>{t}</li>)}</ul> : <p style={{ color: "var(--text-3)" }}>无变化</p>}
              </div>
              <div className="card fade-in fade-in-3" style={{ opacity: 0.7 }}>
                <h3 style={{ color: "var(--text-2)" }}>已解决问题（B 消除）</h3>
                {result.diff.removedTips?.length ? <ul style={{ paddingLeft: 18 }}>{result.diff.removedTips.map((t, i) => <li key={i} style={{ marginBottom: 6, fontSize: 14 }}>{t}</li>)}</ul> : <p style={{ color: "var(--text-3)" }}>无变化</p>}
              </div>
            </div>
          )}
        </>
      )}
    </div>
  );
}
