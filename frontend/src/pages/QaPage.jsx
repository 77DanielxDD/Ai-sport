import { useState } from "react";
import { askAgent } from "../api";

function SeverityBadge({ severity }) {
  const colors = { high: "var(--red)", medium: "var(--amber)", low: "var(--green)" };
  return <span style={{ fontSize: 11, color: "#fff", background: colors[severity] || "var(--text-3)", borderRadius: 10, padding: "1px 8px", marginLeft: 8 }}>{severity}</span>;
}

function PriorityBadge({ priority }) {
  const colors = { high: "var(--red)", medium: "var(--amber)", low: "var(--green)" };
  return <span style={{ fontSize: 11, color: "#fff", background: colors[priority] || "var(--text-3)", borderRadius: 10, padding: "1px 8px", marginLeft: 8 }}>{priority}</span>;
}

export default function QaPage() {
  const [question, setQuestion] = useState("");
  const [answer, setAnswer] = useState(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");

  async function submit(e) {
    e.preventDefault();
    if (!question.trim()) return;
    setError(""); setAnswer(null); setLoading(true);
    try {
      const resp = await askAgent(question);
      setAnswer(resp);
    } catch (e) {
      setError(e?.body?.error || e.message || "failed");
    } finally {
      setLoading(false);
    }
  }

  return (
    <div>
      <div className="section-title"><h1>AI 训练助手</h1></div>
      <p className="section-subtitle">Agent 驱动的个性化问答，结合你的训练数据、知识库和画像进行分析</p>

      <div className="card">
        <form onSubmit={submit}>
          <label>训练问题</label>
          <textarea
            value={question}
            onChange={(e) => setQuestion(e.target.value)}
            placeholder="例如：俯卧撑肘部外翻怎么纠正？我最近深蹲有进步吗？"
            rows={3}
            style={{ resize: "vertical" }}
          />
          {error && <p className="error">{error}</p>}
          <button disabled={loading} style={{ width: "auto", padding: "10px 28px" }}>
            {loading ? "分析中..." : "提问"}
          </button>
        </form>
      </div>

      {loading && (
        <div className="card fade-in" style={{ textAlign: "center", color: "var(--text-2)" }}>
          <p>Agent 正在分析...</p>
          <div className="loading-dots"><span>.</span><span>.</span><span>.</span></div>
        </div>
      )}

      {answer && (
        <>
          {answer.summary && (
            <div className="card fade-in fade-in-1" style={{ borderLeft: "3px solid var(--accent)" }}>
              <h3 style={{ color: "var(--accent)" }}>分析结论</h3>
              <p style={{ fontSize: 15, lineHeight: 1.6 }}>{answer.summary}</p>
            </div>
          )}

          {answer.diagnosis && answer.diagnosis.length > 0 && (
            <div className="card fade-in fade-in-2">
              <h3>问题诊断</h3>
              {answer.diagnosis.map((d, i) => (
                <div key={i} style={{ marginBottom: 12, padding: "8px 12px", background: "var(--bg)", borderRadius: "var(--radius-sm)" }}>
                  <div style={{ fontWeight: 600, marginBottom: 4 }}>
                    {d.issue}
                    <SeverityBadge severity={d.severity} />
                  </div>
                  <div style={{ fontSize: 13, color: "var(--text-2)" }}>{d.evidence}</div>
                </div>
              ))}
            </div>
          )}

          {answer.recommendations && answer.recommendations.length > 0 && (
            <div className="card fade-in fade-in-2">
              <h3>改进建议</h3>
              {answer.recommendations.map((r, i) => (
                <div key={i} style={{ marginBottom: 12, padding: "8px 12px", background: "var(--bg)", borderRadius: "var(--radius-sm)" }}>
                  <div style={{ fontWeight: 600, marginBottom: 4 }}>
                    {r.title}
                    <PriorityBadge priority={r.priority} />
                  </div>
                  <div style={{ fontSize: 13, color: "var(--text-2)" }}>{r.detail}</div>
                </div>
              ))}
            </div>
          )}

          {answer.trainingPlan && answer.trainingPlan.length > 0 && (
            <div className="card fade-in fade-in-3">
              <h3>训练计划</h3>
              <div className="grid2">
                {answer.trainingPlan.map((p, i) => (
                  <div key={i} style={{ padding: "8px 12px", background: "var(--bg)", borderRadius: "var(--radius-sm)" }}>
                    <div style={{ fontWeight: 700, color: "var(--accent)", marginBottom: 4 }}>{p.day}</div>
                    <div style={{ fontSize: 13, marginBottom: 2 }}>{p.content}</div>
                    {p.focus && <div style={{ fontSize: 12, color: "var(--text-2)" }}>重点：{p.focus}</div>}
                  </div>
                ))}
              </div>
            </div>
          )}

          {answer.references && answer.references.length > 0 && (
            <div className="card fade-in fade-in-3">
              <h3>参考来源</h3>
              {answer.references.map((ref, i) => (
                <div key={i} style={{ fontSize: 13, marginBottom: 6, padding: "6px 10px", background: "var(--bg)", borderRadius: "var(--radius-sm)" }}>
                  <span style={{ fontWeight: 600 }}>{ref.title}</span>
                  {ref.snippet && <span style={{ color: "var(--text-2)", marginLeft: 8 }}>— {ref.snippet}</span>}
                </div>
              ))}
            </div>
          )}
        </>
      )}

      <style>{`
        .loading-dots span {
          animation: blink 1.4s infinite both;
          font-size: 24px;
        }
        .loading-dots span:nth-child(2) { animation-delay: 0.2s; }
        .loading-dots span:nth-child(3) { animation-delay: 0.4s; }
        @keyframes blink {
          0% { opacity: 0.2; }
          20% { opacity: 1; }
          100% { opacity: 0.2; }
        }
      `}</style>
    </div>
  );
}
