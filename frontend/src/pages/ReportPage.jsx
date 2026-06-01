import { useEffect, useMemo, useState } from "react";
import { useParams } from "react-router-dom";
import { API_BASE, askAgent, getVideoAnalysis } from "../api";
import StatusPill from "../components/StatusPill";
import SimpleLineChart from "../components/SimpleLineChart";
import { exerciseTypeLabel } from "../utils/exerciseType";

const PRESET_QUESTIONS = [
  "这次训练最大问题是什么？",
  "下一次训练应该重点改什么？",
  "和我的历史表现相比如何？",
];

function ScoreBar({ label, score, color }) {
  return (
    <div className="score-bar-row">
      <span className="score-bar-label">{label}</span>
      <div className="score-bar-track">
        <div className="score-bar-fill" style={{ width: `${Math.min(100, score || 0)}%`, background: color }} />
      </div>
      <span style={{ fontSize: 13, fontWeight: 600, minWidth: 36, fontFamily: "var(--font-mono)" }}>{score ?? "-"}</span>
    </div>
  );
}

function SeverityBadge({ severity }) {
  const colors = { high: "var(--red)", medium: "var(--amber)", low: "var(--green)" };
  return <span style={{ fontSize: 11, color: "#fff", background: colors[severity] || "var(--text-3)", borderRadius: 10, padding: "1px 8px", marginLeft: 8 }}>{severity}</span>;
}

function PriorityBadge({ priority }) {
  const colors = { high: "var(--red)", medium: "var(--amber)", low: "var(--green)" };
  return <span style={{ fontSize: 11, color: "#fff", background: colors[priority] || "var(--text-3)", borderRadius: 10, padding: "1px 8px", marginLeft: 8 }}>{priority}</span>;
}

function MetricCell({ score, level }) {
  if (score == null) return <span>-</span>;
  const colors = { good: "var(--green)", warning: "var(--amber)", bad: "var(--red)" };
  return (
    <span style={{ color: colors[level] || "var(--text)", fontFamily: "var(--font-mono)" }}>
      {score} <span style={{ fontSize: 10 }}>{level === "good" ? "✓" : level === "warning" ? "△" : "✗"}</span>
    </span>
  );
}

export default function ReportPage() {
  const { videoId } = useParams();
  const [data, setData] = useState(null);
  const [error, setError] = useState("");
  const [qaQuestion, setQaQuestion] = useState("");
  const [qaAnswer, setQaAnswer] = useState(null);
  const [qaLoading, setQaLoading] = useState(false);
  const [qaError, setQaError] = useState("");
  const [activeRepIdx, setActiveRepIdx] = useState(null);
  const [modalImage, setModalImage] = useState(null);

  async function submitQa(e) {
    e.preventDefault();
    if (!qaQuestion.trim()) return;
    setQaError(""); setQaAnswer(null); setQaLoading(true);
    try {
      const resp = await askAgent(qaQuestion, Number(videoId));
      setQaAnswer(resp);
    } catch (err) {
      setQaError(err?.body?.error || err.message || "failed");
    } finally {
      setQaLoading(false);
    }
  }

  function usePreset(q) { setQaQuestion(q); setQaAnswer(null); setQaError(""); }

  useEffect(() => {
    getVideoAnalysis(videoId).then(setData).catch((e) => setError(e.body?.message || e.body?.error || e.message));
  }, [videoId]);

  useEffect(() => {
    if (modalImage) document.body.style.overflow = "hidden";
    else document.body.style.overflow = "";
  }, [modalImage]);

  const analysis = useMemo(() => data?.analysis || {}, [data]);
  const reportImages = analysis.report_images || [];
  const tips = analysis.tips || [];
  const repEvaluations = analysis.repEvaluations || [];
  const hasRichEvals = repEvaluations.length > 0;
  const suggestions = analysis.suggestions || [];
  const overallFeedback = analysis.overall_feedback || "";
  const score = analysis.score_breakdown || analysis.trainingScore || null;
  const exerciseType = analysis.exercise_type || analysis.exerciseType;
  const repCount = analysis.rep_count ?? "-";
  const processMs = analysis.processing_time_ms ?? "-";

  const linePoints = useMemo(() => {
    const source = hasRichEvals ? repEvaluations : tips;
    return source.filter((t) => typeof t.repIndex !== "undefined" || typeof t.rep_index !== "undefined")
      .map((t) => ({ x: Number(t.repIndex ?? t.rep_index), y: Number(t.min_angle ?? 0) }))
      .filter((p) => !Number.isNaN(p.x)).sort((a, b) => a.x - b.x);
  }, [repEvaluations, tips, hasRichEvals]);

  const resolveUrl = (url) => {
    if (!url) return "";
    if (String(url).startsWith("http://") || String(url).startsWith("https://")) return url;
    return `${API_BASE}${url}`;
  };

  function levelColor(level) {
    return level === "优秀" ? "var(--green)" : level === "良好" ? "var(--accent)" : level === "一般" ? "var(--amber)" : "var(--red)";
  }
  function tempoColor(level) {
    return level === "normal" ? "var(--green)" : level === "fast" ? "var(--amber)" : level === "slow" ? "var(--blue)" : "var(--text-2)";
  }
  function tempoLabel(level) {
    return level === "normal" ? "正常" : level === "fast" ? "偏快" : level === "slow" ? "偏慢" : "";
  }
  function symColor(level) {
    return level === "good" ? "var(--green)" : level === "warning" ? "var(--amber)" : "var(--red)";
  }
  function symLabel(level) {
    return level === "good" ? "良好" : level === "warning" ? "偏差" : level === "bad" ? "不足" : "";
  }

  const thStyle = { padding: "4px 8px", textAlign: "left", fontWeight: 600, fontSize: 11, color: "var(--text-2)", whiteSpace: "nowrap" };
  const tdStyle = { padding: "4px 8px", borderBottom: "1px solid var(--border)", fontSize: 12, verticalAlign: "middle" };

  return (
    <div>
      <h1>分析报告 #{videoId}</h1>
      {error && <p className="error">{error}</p>}
      {!data && !error && <p style={{ color: "var(--text-2)" }}>报告加载中...</p>}

      {data && (
        <>
          {/* Zone 1: Summary Header */}
          <div className="report-summary fade-in" style={{ marginBottom: 20 }}>
            {score?.finalScore != null ? (
              <>
                <div className="report-score-big">{score.finalScore}</div>
                <div className="report-meta-grid">
                  <div className="report-meta-item">等级 <b style={{ color: "var(--accent)" }}>{score.level}</b></div>
                  <div className="report-meta-item">动作 <b>{exerciseTypeLabel(exerciseType)}</b></div>
                  <div className="report-meta-item">次数 <b>{repCount}</b></div>
                  <div className="report-meta-item">耗时 <b>{processMs} ms</b></div>
                  <div className="report-meta-item">状态 <StatusPill status={data.status} /></div>
                </div>
              </>
            ) : (
              <div className="report-meta-grid" style={{ gridColumn: "1 / -1" }}>
                <div className="report-meta-item">动作 <b>{exerciseTypeLabel(exerciseType)}</b></div>
                <div className="report-meta-item">次数 <b>{repCount}</b></div>
                <div className="report-meta-item">耗时 <b>{processMs} ms</b></div>
                <div className="report-meta-item">状态 <StatusPill status={data.status} /></div>
              </div>
            )}
          </div>

          {/* Zone 2: Multi-Dimension Scores */}
          {score?.finalScore != null && (
            <div className="card fade-in fade-in-1">
              <h3>多维评分</h3>
              <div className="score-bars">
                <ScoreBar label="幅度" score={score.formScore} color="var(--accent)" />
                <ScoreBar label="节奏" score={score.rhythmScore} color="var(--blue)" />
                <ScoreBar label="对称性" score={score.symmetryScore} color="var(--accent)" />
                <ScoreBar label="一致性" score={score.consistencyScore} color="var(--blue)" />
              </div>
            </div>
          )}

          <div className="grid2">
            {/* Zone 3: Rep-by-Rep Analysis */}
            <div className="card fade-in fade-in-2">
              <h3>逐次动作分析</h3>
              {!hasRichEvals && tips.length === 0 ? (
                <p style={{ color: "var(--text-2)" }}>暂无逐次数据</p>
              ) : hasRichEvals ? (
                <div className="rep-evals-table">
                  <table style={{ width: "100%", fontSize: 12, borderCollapse: "collapse" }}>
                    <thead>
                      <tr style={{ borderBottom: "1.5px solid var(--border)" }}>
                        <th style={thStyle}>#</th>
                        <th style={thStyle}>综合</th>
                        <th style={thStyle}>幅度</th>
                        <th style={thStyle}>节奏</th>
                        <th style={thStyle}>稳定性</th>
                        <th style={thStyle}>对称性</th>
                      </tr>
                    </thead>
                    <tbody>
                      {repEvaluations.map((ev, i) => (
                        <tr
                          key={i}
                          onClick={() => setActiveRepIdx(activeRepIdx === i ? null : i)}
                          style={{
                            cursor: "pointer",
                            background: activeRepIdx === i ? "var(--accent-dim)" : "transparent",
                            transition: "background 0.15s",
                          }}
                        >
                          <td style={tdStyle}>{ev.repIndex}</td>
                          <td style={tdStyle}>
                            <span style={{ fontWeight: 700, color: levelColor(ev.level), fontFamily: "var(--font-mono)" }}>{ev.score}</span>
                          </td>
                          <td style={tdStyle}><MetricCell score={ev.depthScore} level={ev.depthLevel} /></td>
                          <td style={tdStyle}>
                            {ev.tempoLevel !== "unknown" ? (
                              <span style={{ color: tempoColor(ev.tempoLevel), fontFamily: "var(--font-mono)" }}>
                                {ev.tempoMs > 0 ? `${(ev.tempoMs / 1000).toFixed(1)}s` : "-"} {tempoLabel(ev.tempoLevel)}
                              </span>
                            ) : "-"}
                          </td>
                          <td style={tdStyle}><span style={{ fontFamily: "var(--font-mono)" }}>{ev.stabilityScore != null ? ev.stabilityScore : "-"}</span></td>
                          <td style={tdStyle}>
                            {ev.symmetryDiffDeg != null ? (
                              <span style={{ color: symColor(ev.symmetryLevel), fontFamily: "var(--font-mono)" }}>
                                {ev.symmetryDiffDeg}° {symLabel(ev.symmetryLevel)}
                              </span>
                            ) : "-"}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>

                  {activeRepIdx != null && repEvaluations[activeRepIdx] && (
                    <RepDetail ev={repEvaluations[activeRepIdx]} images={reportImages} idx={activeRepIdx} resolveUrl={resolveUrl} />
                  )}
                </div>
              ) : (
                <ul style={{ paddingLeft: 18, maxHeight: 240, overflowY: "auto" }}>
                  {tips.map((t, idx) => (
                    <li key={idx} style={{ marginBottom: 4, fontSize: 13, color: "var(--text-2)" }}>
                      第 {t.rep_index} 次 · {t.min_angle}° · {t.tip}
                    </li>
                  ))}
                </ul>
              )}
            </div>

            {/* Keyframes + Chart */}
            <div>
              <div className="card fade-in fade-in-2" style={{ marginBottom: 16 }}>
                <h3>关键帧图集</h3>
                {reportImages.length === 0 ? (
                  <p style={{ color: "var(--text-2)" }}>暂无关键帧</p>
                ) : (
                  <div className="img-grid">
                    {reportImages.map((url) => (
                      <figure key={url}>
                        <img src={resolveUrl(url)} alt="关键帧" loading="lazy"
                          onClick={() => setModalImage(resolveUrl(url))}
                          style={{ cursor: "pointer" }} />
                      </figure>
                    ))}
                  </div>
                )}
              </div>
              <div className="card fade-in fade-in-2">
                <h3>角度趋势</h3>
                <SimpleLineChart points={linePoints} />
              </div>
            </div>
          </div>

          {/* AI Advice (no side stripe) */}
          {(overallFeedback || suggestions.length > 0) && (
            <div className="card card-highlight fade-in fade-in-3">
              <h3>AI 改进建议</h3>
              {overallFeedback && (
                <p style={{ fontWeight: 500, marginBottom: 10, fontSize: 14 }}>{overallFeedback}</p>
              )}
              {suggestions.length > 0 && (
                <ul style={{ paddingLeft: 18 }}>
                  {suggestions.map((s, idx) => (
                    <li key={idx} style={{ marginBottom: 4, color: "var(--text)", fontSize: 13, lineHeight: 1.5 }}>{s}</li>
                  ))}
                </ul>
              )}
            </div>
          )}

          {/* Zone 4: Agent Q&A */}
          <div className="card fade-in fade-in-3">
            <h3>基于本报告提问</h3>
            <p style={{ fontSize: 12, color: "var(--text-2)", marginBottom: 10 }}>
              Agent 结合本次报告、历史数据和健身知识库给出结构化分析
            </p>

            <div style={{ marginBottom: 10 }}>
              {PRESET_QUESTIONS.map((q) => (
                <span key={q} className="preset-chip" onClick={() => usePreset(q)}>{q}</span>
              ))}
            </div>

            <form onSubmit={submitQa}>
              <textarea value={qaQuestion} onChange={(e) => setQaQuestion(e.target.value)}
                placeholder="输入你的问题..."
                rows={2} style={{ resize: "vertical" }}
              />
              {qaError && <p className="error">{qaError}</p>}
              <button disabled={qaLoading} style={{ width: "auto", padding: "8px 24px" }}>
                {qaLoading ? "Agent 分析中..." : "提问"}
              </button>
            </form>

            {qaLoading && <p style={{ marginTop: 12, color: "var(--text-2)", fontSize: 13 }}>Agent 正在调用工具分析...</p>}

            {qaAnswer && (
              <div style={{ marginTop: 16 }}>
                {qaAnswer.toolCalls && qaAnswer.toolCalls.length > 0 && (
                  <div style={{ marginBottom: 12, padding: 8, background: "var(--bg)", borderRadius: "var(--radius-sm)", fontSize: 12 }}>
                    <div style={{ fontWeight: 600, marginBottom: 4, color: "var(--accent)" }}>工具调用</div>
                    {qaAnswer.toolCalls.map((tc, i) => (
                      <div key={i} style={{ marginBottom: 2 }}>
                        {tc.success ? "✓" : "✗"} <code style={{ background: "var(--bg-2)", padding: "0 4px" }}>{tc.tool}</code> — {tc.summary} ({tc.durationMs}ms)
                      </div>
                    ))}
                  </div>
                )}
                {qaAnswer.summary && (
                  <div style={{ padding: "10px 14px", background: "var(--accent)", color: "#fff", borderRadius: "var(--radius-sm)", marginBottom: 12, fontWeight: 500 }}>
                    {qaAnswer.summary}
                  </div>
                )}
                {qaAnswer.diagnosis && qaAnswer.diagnosis.length > 0 && (
                  <div style={{ marginBottom: 12 }}>
                    <h4 style={{ fontSize: 14, marginBottom: 6 }}>问题诊断</h4>
                    {qaAnswer.diagnosis.map((d, i) => (
                      <div key={i} style={{ marginBottom: 8, padding: "6px 10px", background: "var(--bg)", borderRadius: "var(--radius-sm)", fontSize: 13 }}>
                        <span style={{ fontWeight: 600 }}>{d.issue}</span>
                        <SeverityBadge severity={d.severity} />
                        <div style={{ color: "var(--text-2)", marginTop: 2 }}>{d.evidence}</div>
                      </div>
                    ))}
                  </div>
                )}
                {qaAnswer.recommendations && qaAnswer.recommendations.length > 0 && (
                  <div style={{ marginBottom: 12 }}>
                    <h4 style={{ fontSize: 14, marginBottom: 6 }}>改进建议</h4>
                    {qaAnswer.recommendations.map((r, i) => (
                      <div key={i} style={{ marginBottom: 8, padding: "6px 10px", background: "var(--bg)", borderRadius: "var(--radius-sm)", fontSize: 13 }}>
                        <span style={{ fontWeight: 600 }}>{r.title}</span>
                        <PriorityBadge priority={r.priority} />
                        <div style={{ color: "var(--text-2)", marginTop: 2 }}>{r.detail}</div>
                      </div>
                    ))}
                  </div>
                )}
                {qaAnswer.trainingPlan && qaAnswer.trainingPlan.length > 0 && (
                  <div>
                    <h4 style={{ fontSize: 14, marginBottom: 6 }}>训练计划</h4>
                    {qaAnswer.trainingPlan.map((p, i) => (
                      <div key={i} style={{ marginBottom: 6, padding: "6px 10px", background: "var(--bg)", borderRadius: "var(--radius-sm)", fontSize: 13 }}>
                        <span style={{ fontWeight: 700, color: "var(--accent)" }}>{p.day}</span>: {p.content}
                        {p.focus && <span style={{ color: "var(--text-2)" }}> · {p.focus}</span>}
                      </div>
                    ))}
                  </div>
                )}
                {qaAnswer.references && qaAnswer.references.length > 0 && (
                  <div style={{ marginTop: 12 }}>
                    <h4 style={{ fontSize: 14, marginBottom: 6 }}>参考来源</h4>
                    {qaAnswer.references.map((ref, i) => (
                      <div key={i} style={{ fontSize: 12, marginBottom: 4, color: "var(--text-2)" }}>{ref.title}</div>
                    ))}
                  </div>
                )}
              </div>
            )}
          </div>

          {/* Image Modal */}
          {modalImage && (
            <div className="img-modal-overlay" onClick={() => setModalImage(null)}>
              <img src={modalImage} alt="关键帧放大" onClick={(e) => e.stopPropagation()} />
            </div>
          )}
        </>
      )}
    </div>
  );
}

function RepDetail({ ev, idx, images, resolveUrl }) {
  return (
    <div style={{ marginTop: 10, padding: 10, background: "var(--bg)", borderRadius: "var(--radius-sm)", fontSize: 12, border: "1px solid var(--border)" }}>
      <div style={{ fontWeight: 600, marginBottom: 8, fontSize: 13 }}>
        第 {ev.repIndex} 次<span style={{ marginLeft: 8, fontFamily: "var(--font-mono)", color: "var(--accent)" }}>{ev.score} 分</span>
        <span style={{ marginLeft: 8, fontSize: 11, color: "var(--text-2)" }}>{ev.level}</span>
      </div>

      <div className="metric-grid" style={{ marginBottom: 8 }}>
        <div className="metric-card">
          <div className="metric-title">幅度</div>
          <div className="task-metric-value" style={{ color: ev.depthLevel === "good" ? "var(--green)" : "var(--amber)" }}>{ev.depthScore ?? "-"}</div>
        </div>
        <div className="metric-card">
          <div className="metric-title">节奏</div>
          <div className="task-metric-value" style={{ fontFamily: "var(--font-mono)" }}>
            {ev.tempoMs > 0 ? `${(ev.tempoMs / 1000).toFixed(1)}s` : "-"}
          </div>
        </div>
        <div className="metric-card">
          <div className="metric-title">稳定性</div>
          <div className="task-metric-value">{ev.stabilityScore ?? "-"}</div>
        </div>
        <div className="metric-card">
          <div className="metric-title">对称性</div>
          <div className="task-metric-value" style={{ color: ev.symmetryLevel === "good" ? "var(--green)" : "var(--amber)" }}>
            {ev.symmetryDiffDeg != null ? `${ev.symmetryDiffDeg}°` : "-"}
          </div>
        </div>
      </div>

      {ev.evidence && ev.evidence.length > 0 && (
        <div style={{ marginBottom: 6 }}>
          {ev.evidence.map((e, i) => (
            <span key={i} style={{ marginRight: 14, color: "var(--text-2)", fontSize: 11 }}>{e}</span>
          ))}
        </div>
      )}

      {ev.diagnosis && (
        <div style={{ color: "var(--text)", marginBottom: 4 }}>{ev.diagnosis}</div>
      )}

      {(ev.suggestion || ev.tip) && (
        <div style={{ fontWeight: 500, color: "var(--accent)" }}>建议：{ev.suggestion || ev.tip}</div>
      )}

      {images && images[idx] && (
        <img src={resolveUrl(images[idx])} alt={`rep ${ev.repIndex}`} loading="lazy"
          style={{ marginTop: 8, maxWidth: "100%", maxHeight: 160, borderRadius: "var(--radius-xs)", border: "1px solid var(--border)" }} />
      )}
    </div>
  );
}
