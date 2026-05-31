import { useEffect, useMemo, useState } from "react";
import { useParams } from "react-router-dom";
import { API_BASE, askAgent, getVideoAnalysis } from "../api";
import StatusPill from "../components/StatusPill";
import SimpleLineChart from "../components/SimpleLineChart";
import { exerciseTypeLabel } from "../utils/exerciseType";

function ScoreBar({ label, score, color }) {
  return (
    <div className="score-bar-row">
      <span className="score-bar-label">{label}</span>
      <div className="score-bar-track">
        <div className="score-bar-fill" style={{ width: `${Math.min(100, score || 0)}%`, background: color }} />
      </div>
      <span style={{ fontSize: 13, fontWeight: 600, minWidth: 36, color: "var(--text)" }}>{score ?? "-"}</span>
    </div>
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

  useEffect(() => {
    getVideoAnalysis(videoId).then(setData).catch((e) => setError(e.body?.message || e.body?.error || e.message));
  }, [videoId]);

  const analysis = useMemo(() => data?.analysis || {}, [data]);
  const reportImages = analysis.report_images || [];
  const tips = analysis.tips || [];
  const suggestions = analysis.suggestions || [];
  const overallFeedback = analysis.overall_feedback || "";
  const score = analysis.score_breakdown || analysis.trainingScore || null;
  const linePoints = useMemo(() => {
    return tips.filter((t) => typeof t.rep_index !== "undefined" && typeof t.min_angle !== "undefined")
      .map((t) => ({ x: Number(t.rep_index), y: Number(t.min_angle) }))
      .filter((p) => !Number.isNaN(p.x) && !Number.isNaN(p.y)).sort((a, b) => a.x - b.x);
  }, [tips]);

  const resolveImageUrl = (url) => {
    if (!url) return "";
    if (String(url).startsWith("http://") || String(url).startsWith("https://")) return url;
    return `${API_BASE}${url}`;
  };

  function SeverityBadge({ severity }) {
    const colors = { high: "var(--red)", medium: "#e6a817", low: "var(--green)" };
    return <span style={{ fontSize: 11, color: "#fff", background: colors[severity] || "#888", borderRadius: 10, padding: "1px 8px", marginLeft: 8 }}>{severity}</span>;
  }

  function PriorityBadge({ priority }) {
    const colors = { high: "var(--red)", medium: "#e6a817", low: "var(--green)" };
    return <span style={{ fontSize: 11, color: "#fff", background: colors[priority] || "#888", borderRadius: 10, padding: "1px 8px", marginLeft: 8 }}>{priority}</span>;
  }

  return (
    <div>
      <h1>分析报告 #{videoId}</h1>
      {error && <p className="error">{error}</p>}
      {!data && !error && <p style={{ color: "var(--text-2)" }}>报告加载中...</p>}
      {data && (
        <>
          {(overallFeedback || suggestions.length > 0) && (
            <div className="card fade-in" style={{ borderLeft: "3px solid var(--accent)" }}>
              <h3 style={{ color: "var(--accent)" }}>AI 训练建议</h3>
              {overallFeedback && <p style={{ fontWeight: 500, marginBottom: 12, fontSize: 15 }}>{overallFeedback}</p>}
              {suggestions.length > 0 && (
                <ul style={{ paddingLeft: 18 }}>
                  {suggestions.map((s, idx) => <li key={idx} style={{ marginBottom: 6, color: "var(--text)" }}>{s}</li>)}
                </ul>
              )}
            </div>
          )}

          {score?.finalScore != null && (
            <div className="card fade-in fade-in-1">
              <h3>训练评分</h3>
              <div className="grid3" style={{ alignItems: "center" }}>
                <div style={{ textAlign: "center" }}>
                  <div style={{ fontSize: 56, fontWeight: 800, color: "var(--accent)" }}>
                    {score.finalScore}
                  </div>
                  <div style={{ color: "var(--accent)", fontWeight: 600 }}>{score.level}</div>
                </div>
                <div className="score-bars" style={{ gridColumn: "span 2" }}>
                  <ScoreBar label="幅度" score={score.formScore} color="var(--accent)" />
                  <ScoreBar label="节奏" score={score.rhythmScore} color="var(--blue)" />
                  <ScoreBar label="对称性" score={score.symmetryScore} color="var(--accent)" />
                  <ScoreBar label="一致性" score={score.consistencyScore} color="var(--blue)" />
                </div>
              </div>
            </div>
          )}

          <div className="grid2">
            <div className="card fade-in fade-in-2">
              <h3>概要</h3>
              <p>状态：<StatusPill status={data.status} /></p>
              <p>动作：{exerciseTypeLabel(analysis.exercise_type || analysis.exerciseType)}</p>
              <p>次数：{analysis.rep_count ?? "-"} · 耗时：{analysis.processing_time_ms ?? "-"} ms</p>
              <p>获取：{data.retrievedAt}</p>
            </div>
            <div className="card fade-in fade-in-2">
              <h3>逐次数据</h3>
              {tips.length === 0 ? <p style={{ color: "var(--text-2)" }}>暂无数据</p> : (
                <ul style={{ paddingLeft: 18, maxHeight: 240, overflowY: "auto" }}>
                  {tips.map((t, idx) => (
                    <li key={idx} style={{ marginBottom: 6, fontSize: 13 }}>
                      第 {t.rep_index} 次 · {t.min_angle}° · {t.tip}
                    </li>
                  ))}
                </ul>
              )}
            </div>
          </div>

          <div className="card fade-in fade-in-3">
            <h3>关键帧图集</h3>
            {reportImages.length === 0 ? <p style={{ color: "var(--text-2)" }}>暂无关键帧</p> : (
              <div className="img-grid">
                {reportImages.map((url) => (
                  <figure key={url}><img src={resolveImageUrl(url)} alt={url} loading="lazy" /></figure>
                ))}
              </div>
            )}
          </div>

          <div className="card"><h3>角度趋势图</h3><SimpleLineChart points={linePoints} /></div>

          <div className="card card-highlight fade-in fade-in-3">
            <h3 style={{ color: "var(--accent)" }}>Agent 训练助手</h3>
            <p style={{ fontSize: 13, color: "var(--text-2)", marginBottom: 12 }}>
              Agent 会结合本次报告数据、历史趋势和知识库给出结构化分析
            </p>
            <form onSubmit={submitQa}>
              <textarea
                value={qaQuestion} onChange={(e) => setQaQuestion(e.target.value)}
                placeholder="例如：这次训练主要问题是什么？如何改进幅度？对比上次训练有什么变化？"
                rows={2} style={{ resize: "vertical" }}
              />
              {qaError && <p className="error">{qaError}</p>}
              <button disabled={qaLoading} style={{ width: "auto", padding: "8px 24px" }}>
                {qaLoading ? "Agent 分析中..." : "提问"}
              </button>
            </form>

            {qaLoading && (
              <div style={{ marginTop: 12, color: "var(--text-2)", fontSize: 13 }}>
                Agent 正在调用工具分析...
              </div>
            )}

            {qaAnswer && (
              <div style={{ marginTop: 16 }}>
                {qaAnswer.toolCalls && qaAnswer.toolCalls.length > 0 && (
                  <div style={{ marginBottom: 12, padding: 8, background: "var(--bg)", borderRadius: "var(--radius-sm)", fontSize: 12 }}>
                    <div style={{ fontWeight: 600, marginBottom: 4, color: "var(--blue)" }}>工具调用</div>
                    {qaAnswer.toolCalls.map((tc, i) => (
                      <div key={i} style={{ marginBottom: 2 }}>
                        {tc.success ? "✓" : "✗"} <code style={{ background: "#eee", padding: "0 4px" }}>{tc.tool}</code> — {tc.summary} ({tc.durationMs}ms)
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
                        {p.focus && <span style={{ color: "var(--text-2)" }}> · 重点：{p.focus}</span>}
                      </div>
                    ))}
                  </div>
                )}

                {qaAnswer.references && qaAnswer.references.length > 0 && (
                  <div>
                    <h4 style={{ fontSize: 14, marginBottom: 6 }}>参考来源</h4>
                    {qaAnswer.references.map((ref, i) => (
                      <div key={i} style={{ fontSize: 12, marginBottom: 4, color: "var(--text-2)" }}>
                        {ref.title}
                      </div>
                    ))}
                  </div>
                )}
              </div>
            )}
          </div>
        </>
      )}
    </div>
  );
}
