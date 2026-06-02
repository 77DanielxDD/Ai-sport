import { useEffect, useMemo, useState } from "react";
import { compareVideos, getVideoAnalysis, listVideos } from "../api";
import { exerciseTypeLabel } from "../utils/exerciseType";

function fmtAngle(v) { if (v == null || Number.isNaN(Number(v))) return "-"; return `${Number(v).toFixed(1)}°`; }
function fmtPct(v) { if (v == null || Number.isNaN(Number(v))) return "-"; const sign = v > 0 ? "+" : ""; return `${sign}${Number(v).toFixed(1)}%`; }
function fmtNum(v) { if (v == null || Number.isNaN(Number(v))) return "-"; return Number(v).toFixed(1); }

function Delta({ value, unit = "", invert = false }) {
  if (value == null) return <span style={{ color: "var(--text-3)", fontSize: 12 }}>—</span>;
  const up = invert ? value < 0 : value > 0;
  const down = invert ? value > 0 : value < 0;
  const color = up ? "var(--green)" : down ? "var(--red)" : "var(--text-3)";
  const sign = value > 0 ? "+" : "";
  return <span style={{ fontWeight: 700, color, fontFamily: "var(--font-mono)", fontSize: 13 }}>{sign}{value}{unit}</span>;
}

function MetricDelta({ label, leftVal, rightVal, unit = "", fmt = fmtNum, invert = false, explain }) {
  const delta = leftVal != null && rightVal != null ? Number((rightVal - leftVal).toFixed(1)) : null;
  return (
    <div className="compare-metric-card">
      <div className="compare-metric-label">{label}</div>
      <div className="compare-metric-row">
        <span>{fmt(leftVal)}</span>
        <span style={{ color: "var(--text-3)", margin: "0 4px" }}>→</span>
        <span>{fmt(rightVal)}</span>
        <span style={{ marginLeft: 8 }}><Delta value={delta} unit={unit} invert={invert} /></span>
      </div>
      {explain && <div className="compare-metric-explain">{explain}</div>}
    </div>
  );
}

function Conclusion({ left, right, diff }) {
  const delta = diff?.repCountDelta != null ? Math.abs(diff.repCountDelta) : 0;
  const angleDelta = diff?.avgMinAngleDelta;
  const leftScore = left?.trainingScore ?? 0;
  const rightScore = right?.trainingScore ?? 0;
  const scoreDelta = rightScore - leftScore;

  let verdict, verdictColor, summary;
  if (scoreDelta > 5) { verdict = "明显进步"; verdictColor = "var(--green)"; summary = "本次综合表现显著优于上次。"; }
  else if (scoreDelta > 0) { verdict = "小幅提升"; verdictColor = "var(--green)"; summary = "整体有进步，部分维度仍需巩固。"; }
  else if (scoreDelta === 0) { verdict = "基本持平"; verdictColor = "var(--amber)"; summary = "两次表现相当，维持原有水平。"; }
  else { verdict = "有所退步"; verdictColor = "var(--red)"; summary = "本次综合表现低于上次，建议回顾训练安排。"; }

  return (
    <div className="compare-conclusion">
      <div className="compare-verdict" style={{ color: verdictColor }}>{verdict}</div>
      <div className="compare-conclusion-deltas">
        <span>总分 <Delta value={scoreDelta} /></span>
        <span>次数 <Delta value={diff?.repCountDelta ?? null} /></span>
        <span>平均角度 <Delta value={angleDelta} unit="°" /></span>
      </div>
      <div style={{ fontSize: 13, color: "var(--text-2)" }}>{summary}{diff?.summary ? ` ${diff.summary}` : ""}</div>
    </div>
  );
}

export default function ComparePage() {
  const [videos, setVideos] = useState([]);
  const [leftId, setLeftId] = useState("");
  const [rightId, setRightId] = useState("");
  const [result, setResult] = useState(null);
  const [leftAnalysis, setLeftAnalysis] = useState(null);
  const [rightAnalysis, setRightAnalysis] = useState(null);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);
  const [showAllKeyframes, setShowAllKeyframes] = useState(false);

  useEffect(() => {
    listVideos().then((rows) => {
      const completed = (rows || []).filter((v) => v.status === "COMPLETED");
      setVideos(completed);
      if (completed.length >= 2) { setLeftId(String(completed[0].id)); setRightId(String(completed[1].id)); }
    }).catch((e) => setError(e.message || "加载失败"));
  }, []);

  async function runCompare(e) {
    e.preventDefault(); setError(""); setResult(null);
    setLeftAnalysis(null); setRightAnalysis(null);
    if (!leftId || !rightId) return setError("请选择两条已完成分析的视频");
    if (leftId === rightId) return setError("请选择两条不同的视频");
    setLoading(true);
    try {
      const r = await compareVideos(leftId, rightId);
      setResult(r);
      const [la, ra] = await Promise.all([
        getVideoAnalysis(leftId).catch(() => null),
        getVideoAnalysis(rightId).catch(() => null),
      ]);
      setLeftAnalysis(la?.analysis || null);
      setRightAnalysis(ra?.analysis || null);
    } catch (e1) {
      setError(e1?.body?.error || e1.message || "对比失败");
    } finally {
      setLoading(false);
    }
  }

  const leftScore = useMemo(() => leftAnalysis?.score_breakdown || leftAnalysis?.trainingScore || null, [leftAnalysis]);
  const rightScore = useMemo(() => rightAnalysis?.score_breakdown || rightAnalysis?.trainingScore || null, [rightAnalysis]);
  const leftReps = leftAnalysis?.repEvaluations || [];
  const rightReps = rightAnalysis?.repEvaluations || [];
  const maxReps = Math.max(leftReps.length, rightReps.length);

  const leftTips = leftAnalysis?.tips || [];
  const rightTips = rightAnalysis?.tips || [];
  const improvedProblems = useMemo(() => {
    const leftSet = new Set(leftTips.map(t => t.tip || t));
    const rightSet = new Set(rightTips.map(t => t.tip || t));
    return [...leftSet].filter(t => !rightSet.has(t));
  }, [leftTips, rightTips]);
  const newProblems = useMemo(() => {
    const leftSet = new Set(leftTips.map(t => t.tip || t));
    const rightSet = new Set(rightTips.map(t => t.tip || t));
    return [...rightSet].filter(t => !leftSet.has(t));
  }, [leftTips, rightTips]);
  const persistentProblems = useMemo(() => {
    const leftSet = new Set(leftTips.map(t => t.tip || t));
    const rightSet = new Set(rightTips.map(t => t.tip || t));
    return [...leftSet].filter(t => rightSet.has(t));
  }, [leftTips, rightTips]);

  const leftImages = leftAnalysis?.report_images || [];
  const rightImages = rightAnalysis?.report_images || [];

  // Angle trend points from tips
  const leftTrend = useMemo(() => (leftTips || []).filter(t => t.rep_index != null).map(t => ({ x: t.rep_index, y: t.min_angle })).sort((a, b) => a.x - b.x), [leftTips]);
  const rightTrend = useMemo(() => (rightTips || []).filter(t => t.rep_index != null).map(t => ({ x: t.rep_index, y: t.min_angle })).sort((a, b) => a.x - b.x), [rightTips]);

  // Best/worst rep from repEvaluations
  const leftBest = leftReps.length ? [...leftReps].sort((a, b) => (b.score || 0) - (a.score || 0))[0] : null;
  const leftWorst = leftReps.length ? [...leftReps].sort((a, b) => (a.score || 0) - (b.score || 0))[0] : null;
  const rightBest = rightReps.length ? [...rightReps].sort((a, b) => (b.score || 0) - (a.score || 0))[0] : null;
  const rightWorst = rightReps.length ? [...rightReps].sort((a, b) => (a.score || 0) - (b.score || 0))[0] : null;

  // History ranking (mock if no real data)
  const historyRef = useMemo(() => {
    const allScores = videos
      .filter(v => v.exerciseType === (result?.left?.exerciseType || result?.right?.exerciseType))
      .map(v => ({ id: v.id, score: v.trainingScore || 0 }))
      .sort((a, b) => b.score - a.score);
    return allScores;
  }, [videos, result]);

  return (
    <div>
      <div className="section-title"><h1>报告对比</h1></div>

      {/* Selector */}
      <div className="card" style={{ marginBottom: 16 }}>
        <form onSubmit={runCompare}>
          <div className="grid2">
            <div><label>训练 A（上次）</label>
              <select value={leftId} onChange={(e) => setLeftId(e.target.value)}>
                <option value="">请选择</option>
                {videos.map((v) => <option key={`l-${v.id}`} value={v.id}>#{v.id} {exerciseTypeLabel(v.exerciseType)}</option>)}
              </select>
            </div>
            <div><label>训练 B（本次）</label>
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
          {/* 1. Conclusion */}
          <div className="card fade-in" style={{ marginBottom: 16 }}>
            <Conclusion left={result.left} right={result.right} diff={result.diff} />
          </div>

          {/* 2. Core Metrics */}
          <div className="card fade-in fade-in-1" style={{ marginBottom: 16 }}>
            <h3>核心指标变化</h3>
            <div className="compare-metrics-grid">
              <MetricDelta label="综合评分" leftVal={result.left.trainingScore} rightVal={result.right.trainingScore}
                explain={leftScore?.level && rightScore?.level ? `${leftScore.level} → ${rightScore.level}` : null} />
              <MetricDelta label="动作次数" leftVal={result.left.repCount} rightVal={result.right.repCount}
                explain={result.diff?.repCountDelta > 0 ? "完成更多次动作" : result.diff?.repCountDelta < 0 ? "动作次数减少" : "次数不变"} />
              <MetricDelta label="平均角度" leftVal={result.left.avgMinAngle} rightVal={result.right.avgMinAngle} unit="°" fmt={fmtAngle}
                explain={result.diff?.avgMinAngleDelta > 0 ? "角度变大，活动范围增加" : result.diff?.avgMinAngleDelta < 0 ? "角度变小，活动范围缩减" : "活动范围不变"} />
              <MetricDelta label="最佳单次" leftVal={leftBest?.score} rightVal={rightBest?.score}
                explain={leftBest && rightBest ? `最高分对比` : null} />
              <MetricDelta label="最差单次" leftVal={leftWorst?.score} rightVal={rightWorst?.score} invert
                explain={leftWorst && rightWorst ? "短板对比" : null} />
              <MetricDelta label="平均节奏" leftVal={leftAnalysis?.avg_tempo_ms} rightVal={rightAnalysis?.avg_tempo_ms} unit="ms" invert
                explain="每次动作耗时对比" />
            </div>
          </div>

          {/* 3. 4-Dimension Bars */}
          {(leftScore || rightScore) && (
            <div className="card fade-in fade-in-2" style={{ marginBottom: 16 }}>
              <h3>动作质量四维对比</h3>
              <div className="score-bars" style={{ gap: 14 }}>
                {[
                  ["幅度", leftScore?.formScore, rightScore?.formScore],
                  ["节奏", leftScore?.rhythmScore, rightScore?.rhythmScore],
                  ["对称性", leftScore?.symmetryScore, rightScore?.symmetryScore],
                  ["一致性", leftScore?.consistencyScore, rightScore?.consistencyScore],
                ].map(([label, l, r]) => {
                  const ld = Number(l) || 0; const rd = Number(r) || 0;
                  const delta = rd - ld;
                  return (
                    <div key={label}>
                      <div style={{ display: "flex", justifyContent: "space-between", fontSize: 12, marginBottom: 4 }}>
                        <span style={{ fontWeight: 600 }}>{label}</span>
                        <span style={{ fontFamily: "var(--font-mono)", fontSize: 12 }}>
                          {ld} → {rd} <Delta value={Math.round(delta)} />
                        </span>
                      </div>
                      <div className="compare-bar-dual">
                        <div className="compare-bar-fill-left" style={{ width: `${Math.min(100, ld)}%` }} />
                        <div className="compare-bar-fill-right" style={{ width: `${Math.min(100, rd)}%` }} />
                      </div>
                    </div>
                  );
                })}
              </div>
            </div>
          )}

          {/* 4. Rep-by-Rep Table */}
          {leftReps.length > 0 && rightReps.length > 0 && (
            <div className="card fade-in fade-in-2" style={{ marginBottom: 16 }}>
              <h3>逐次动作对比</h3>
              <div style={{ maxHeight: 400, overflowY: "auto" }}>
                <table style={{ width: "100%", fontSize: 12, borderCollapse: "collapse" }}>
                  <thead>
                    <tr style={{ borderBottom: "1.5px solid var(--border)" }}>
                      <th style={thStyle}>#</th>
                      <th style={thStyle}>A 评分</th>
                      <th style={thStyle}>B 评分</th>
                      <th style={thStyle}>分差</th>
                      <th style={thStyle}>深度</th>
                      <th style={thStyle}>节奏</th>
                      <th style={thStyle}>对称</th>
                      <th style={thStyle}>标签</th>
                    </tr>
                  </thead>
                  <tbody>
                    {Array.from({ length: maxReps }, (_, i) => {
                      const l = leftReps[i];
                      const r = rightReps[i];
                      const depthDiff = l && r && l.depthScore != null && r.depthScore != null ? Number((r.depthScore - l.depthScore).toFixed(1)) : null;
                      const tempoDiff = l && r && l.tempoMs && r.tempoMs ? Number((r.tempoMs - l.tempoMs).toFixed(0)) : null;
                      const symDiff = l && r && l.symmetryDiffDeg != null && r.symmetryDiffDeg != null ? Number((l.symmetryDiffDeg - r.symmetryDiffDeg).toFixed(1)) : null;
                      const stabDiff = l && r && l.stabilityScore != null && r.stabilityScore != null ? Number((r.stabilityScore - l.stabilityScore).toFixed(1)) : null;
                      const tags = [];
                      if (depthDiff != null && depthDiff > 1) tags.push("深度提升");
                      if (depthDiff != null && depthDiff < -1) tags.push("深度下降");
                      if (tempoDiff != null && tempoDiff > 50) tags.push("节奏变慢");
                      if (tempoDiff != null && tempoDiff < -50) tags.push("节奏变快");
                      if (stabDiff != null && stabDiff < -2) tags.push("稳定性下降");
                      if (i >= Math.min(leftReps.length, rightReps.length)) {
                        if (!l) tags.push("新增完成");
                        if (!r) tags.push("本次未完成");
                      }
                      if (tags.length === 0) tags.push("—");
                      return (
                        <tr key={i} style={{ borderBottom: "1px solid var(--border)", background: !l || !r ? "var(--accent-dim)" : "transparent" }}>
                          <td style={tdStyle}>{i + 1}</td>
                          <td style={tdStyle}><span style={{ fontFamily: "var(--font-mono)" }}>{l?.score ?? "—"}</span></td>
                          <td style={tdStyle}><span style={{ fontFamily: "var(--font-mono)" }}>{r?.score ?? "—"}</span></td>
                          <td style={tdStyle}><Delta value={l && r ? (r.score || 0) - (l.score || 0) : null} /></td>
                          <td style={tdStyle}><Delta value={depthDiff} /></td>
                          <td style={tdStyle}><Delta value={tempoDiff} unit="ms" invert /></td>
                          <td style={tdStyle}><Delta value={symDiff} unit="°" invert /></td>
                          <td style={tdStyle}>{tags.join(" · ")}</td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              </div>
            </div>
          )}

          {/* 5. Keyframe Comparison */}
          {leftImages.length > 0 && rightImages.length > 0 && (
            <div className="card fade-in fade-in-3" style={{ marginBottom: 16 }}>
              <h3>关键帧对比</h3>
              <p style={{ fontSize: 12, color: "var(--text-2)", marginBottom: 12 }}>
                默认展示变化最大的 3 组
              </p>
              <div className="keyframe-compare-grid">
                {(showAllKeyframes
                  ? Array.from({ length: Math.max(leftImages.length, rightImages.length) }, (_, i) => i)
                  : [0, 1, 2].filter(i => i < Math.max(leftImages.length, rightImages.length))
                ).map(i => (
                  <div key={i} className="keyframe-pair">
                    <div className="keyframe-side">
                      <div style={{ fontSize: 10, color: "var(--text-2)", marginBottom: 4 }}>A · 第 {i + 1} 次</div>
                      {leftImages[i] ? <img src={resolveUrl(leftImages[i])} alt={`A rep ${i + 1}`} loading="lazy" style={{ width: "100%", borderRadius: "var(--radius-xs)" }} />
                        : <div style={{ height: 100, background: "var(--bg-2)", borderRadius: "var(--radius-xs)", display: "grid", placeItems: "center", color: "var(--text-3)", fontSize: 11 }}>无数据</div>}
                    </div>
                    <div className="keyframe-side">
                      <div style={{ fontSize: 10, color: "var(--text-2)", marginBottom: 4 }}>B · 第 {i + 1} 次</div>
                      {rightImages[i] ? <img src={resolveUrl(rightImages[i])} alt={`B rep ${i + 1}`} loading="lazy" style={{ width: "100%", borderRadius: "var(--radius-xs)" }} />
                        : <div style={{ height: 100, background: "var(--bg-2)", borderRadius: "var(--radius-xs)", display: "grid", placeItems: "center", color: "var(--text-3)", fontSize: 11 }}>无数据</div>}
                    </div>
                  </div>
                ))}
              </div>
              {Math.max(leftImages.length, rightImages.length) > 3 && (
                <button className="ghost btn-sm" style={{ marginTop: 10 }} onClick={() => setShowAllKeyframes(!showAllKeyframes)}>
                  {showAllKeyframes ? "收起" : "显示全部"}
                </button>
              )}
            </div>
          )}

          {/* 6. Angle Trend Overlay */}
          <div className="grid2" style={{ marginBottom: 16 }}>
            <div className="card fade-in fade-in-3">
              <h3>角度趋势对比</h3>
              {leftTrend.length === 0 && rightTrend.length === 0 ? <p style={{ color: "var(--text-2)" }}>暂无趋势数据</p> : (
                <div className="compare-trend">
                  <div style={{ height: 200, position: "relative", borderLeft: "1px solid var(--border)", borderBottom: "1px solid var(--border)", padding: "8px 0 0 8px" }}>
                    {leftTrend.map((p, i) => (
                      <div key={`l-${i}`} style={{ position: "absolute", left: `${(p.x / Math.max(leftTrend.length, 1)) * 95}%`, bottom: `${((p.y || 0) / 180) * 100}%`, width: 6, height: 6, borderRadius: "50%", background: "var(--accent)", opacity: 0.7 }} title={`A rep ${p.x}: ${p.y}°`} />
                    ))}
                    {rightTrend.map((p, i) => (
                      <div key={`r-${i}`} style={{ position: "absolute", left: `${(p.x / Math.max(rightTrend.length, 1)) * 95}%`, bottom: `${((p.y || 0) / 180) * 100}%`, width: 6, height: 6, borderRadius: "50%", background: "var(--energy)", opacity: 0.7, border: "1px solid var(--energy)" }} title={`B rep ${p.x}: ${p.y}°`} />
                    ))}
                    <div style={{ position: "absolute", bottom: -20, left: 0, fontSize: 10, color: "var(--text-3)" }}>
                      <span style={{ marginRight: 12 }}>&#9679; A</span><span>&#9679; B</span>
                    </div>
                  </div>
                </div>
              )}
            </div>
            <div className="card fade-in fade-in-3">
              <h3>评分趋势对比</h3>
              {leftReps.length === 0 && rightReps.length === 0 ? <p style={{ color: "var(--text-2)" }}>暂无评分数据</p> : (
                <div className="compare-trend">
                  <div style={{ height: 200, position: "relative", borderLeft: "1px solid var(--border)", borderBottom: "1px solid var(--border)", padding: "8px 0 0 8px" }}>
                    {leftReps.map((r, i) => (
                      <div key={`ls-${i}`} style={{ position: "absolute", left: `${((i + 1) / Math.max(leftReps.length, 1)) * 95}%`, bottom: `${((r.score || 0) / 100) * 100}%`, width: 6, height: 6, borderRadius: "50%", background: "var(--accent)", opacity: 0.7 }} title={`A rep ${i + 1}: ${r.score}`} />
                    ))}
                    {rightReps.map((r, i) => (
                      <div key={`rs-${i}`} style={{ position: "absolute", left: `${((i + 1) / Math.max(rightReps.length, 1)) * 95}%`, bottom: `${((r.score || 0) / 100) * 100}%`, width: 6, height: 6, borderRadius: "50%", background: "var(--energy)", opacity: 0.7, border: "1px solid var(--energy)" }} title={`B rep ${i + 1}: ${r.score}`} />
                    ))}
                    <div style={{ position: "absolute", bottom: -20, left: 0, fontSize: 10, color: "var(--text-3)" }}>
                      <span style={{ marginRight: 12 }}>&#9679; A</span><span>&#9679; B</span>
                    </div>
                  </div>
                </div>
              )}
            </div>
          </div>

          {/* 7. Problem Changes */}
          <div className="grid3" style={{ marginBottom: 16 }}>
            <div className="card fade-in fade-in-3">
              <h3 style={{ color: "var(--green)" }}>已改善</h3>
              {improvedProblems.length === 0 ? <p style={{ fontSize: 12, color: "var(--text-3)" }}>无</p> :
                <ul style={{ paddingLeft: 18, fontSize: 13 }}>{improvedProblems.map((t, i) => <li key={i} style={{ marginBottom: 4 }}>{t}</li>)}</ul>}
            </div>
            <div className="card fade-in fade-in-3" style={{ borderColor: "var(--amber)" }}>
              <h3 style={{ color: "var(--amber)" }}>持续存在</h3>
              {persistentProblems.length === 0 ? <p style={{ fontSize: 12, color: "var(--text-3)" }}>无</p> :
                <ul style={{ paddingLeft: 18, fontSize: 13 }}>{persistentProblems.map((t, i) => <li key={i} style={{ marginBottom: 4 }}>{t}</li>)}</ul>}
            </div>
            <div className="card fade-in fade-in-3" style={{ borderColor: "var(--red)" }}>
              <h3 style={{ color: "var(--red)" }}>新出现</h3>
              {newProblems.length === 0 ? <p style={{ fontSize: 12, color: "var(--text-3)" }}>无</p> :
                <ul style={{ paddingLeft: 18, fontSize: 13 }}>{newProblems.map((t, i) => <li key={i} style={{ marginBottom: 4 }}>{t}</li>)}</ul>}
            </div>
          </div>

          {/* 8. Training Advice */}
          <div className="card card-highlight fade-in fade-in-3" style={{ marginBottom: 16 }}>
            <h3>下次训练建议</h3>
            {rightAnalysis?.suggestions?.length > 0 ? (
              <ul style={{ paddingLeft: 18 }}>
                {rightAnalysis.suggestions.map((s, i) => <li key={i} style={{ marginBottom: 6, fontSize: 14 }}>{s}</li>)}
              </ul>
            ) : rightAnalysis?.overall_feedback ? (
              <p style={{ fontSize: 14 }}>{rightAnalysis.overall_feedback}</p>
            ) : (
              <p style={{ color: "var(--text-2)", fontSize: 13 }}>基于本次对比，建议关注变化最大的维度，优先巩固提升项。</p>
            )}
          </div>

          {/* 9. History Ranking */}
          {historyRef.length > 1 && (
            <div className="card fade-in fade-in-3">
              <h3>{exerciseTypeLabel(result.left.exerciseType)} 历史排名</h3>
              <p style={{ fontSize: 12, color: "var(--text-2)", marginBottom: 10 }}>
                趋势：{historyRef[0]?.id === Number(rightId) ? "上升" : historyRef[0]?.id === Number(leftId) ? "下降" : "波动"}
              </p>
              <div style={{ maxHeight: 200, overflowY: "auto" }}>
                <table style={{ width: "100%", fontSize: 12 }}>
                  <thead><tr style={{ borderBottom: "1.5px solid var(--border)" }}><th style={thStyle}>排名</th><th style={thStyle}>视频</th><th style={thStyle}>评分</th></tr></thead>
                  <tbody>
                    {historyRef.slice(0, 10).map((v, i) => (
                      <tr key={v.id} style={{ borderBottom: "1px solid var(--border)", fontWeight: v.id === Number(leftId) || v.id === Number(rightId) ? 700 : 400 }}>
                        <td style={tdStyle}>#{i + 1}</td>
                        <td style={tdStyle}>视频 #{v.id}</td>
                        <td style={{ ...tdStyle, fontFamily: "var(--font-mono)" }}>{v.score}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>
          )}
        </>
      )}
    </div>
  );
}

const thStyle = { padding: "4px 8px", textAlign: "left", fontWeight: 600, fontSize: 11, color: "var(--text-2)", whiteSpace: "nowrap" };
const tdStyle = { padding: "4px 8px", borderBottom: "1px solid var(--border)", fontSize: 12, verticalAlign: "middle" };

import { API_BASE } from "../api";
function resolveUrl(url) {
  if (!url) return "";
  if (String(url).startsWith("http://") || String(url).startsWith("https://")) return url;
  return `${API_BASE}${url}`;
}
