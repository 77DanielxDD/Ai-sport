import { useEffect, useMemo, useState } from "react";
import { useParams } from "react-router-dom";
import { API_BASE, getVideoAnalysis } from "../api";
import StatusPill from "../components/StatusPill";
import SimpleLineChart from "../components/SimpleLineChart";
import { exerciseTypeLabel } from "../utils/exerciseType";

export default function ReportPage() {
  const { videoId } = useParams();
  const [data, setData] = useState(null);
  const [error, setError] = useState("");

  useEffect(() => {
    getVideoAnalysis(videoId).then(setData).catch((e) => setError(e.body?.message || e.body?.error || e.message));
  }, [videoId]);

  const analysis = useMemo(() => data?.analysis || {}, [data]);
  const reportImages = analysis.report_images || [];
  const tips = analysis.tips || [];
  const suggestions = analysis.suggestions || [];
  const overallFeedback = analysis.overall_feedback || "";
  const trainingScore = analysis.score_breakdown || analysis.trainingScore || null;
  const linePoints = useMemo(() => {
    return tips
      .filter((t) => typeof t.rep_index !== "undefined" && typeof t.min_angle !== "undefined")
      .map((t) => ({ x: Number(t.rep_index), y: Number(t.min_angle) }))
      .filter((p) => !Number.isNaN(p.x) && !Number.isNaN(p.y))
      .sort((a, b) => a.x - b.x);
  }, [tips]);

  const resolveImageUrl = (url) => {
    if (!url) return "";
    if (String(url).startsWith("http://") || String(url).startsWith("https://")) return url;
    return `${API_BASE}${url}`;
  };

  function downloadJson() {
    if (!data) return;
    const blob = new Blob([JSON.stringify(data, null, 2)], { type: "application/json;charset=utf-8" });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = `report_${videoId}.json`;
    a.click();
    URL.revokeObjectURL(url);
  }

  return (
    <div>
      <h1>分析报告 #{videoId}</h1>
      {error && <p className="error">{error}</p>}
      {!data && !error && <p>报告加载中...</p>}
      {data && (
        <>
          {(overallFeedback || suggestions.length > 0) && (
            <div className="card" style={{ borderLeft: "4px solid #4f46e5" }}>
              <h3>AI 训练建议</h3>
              {overallFeedback && <p style={{ fontWeight: 500, marginBottom: "0.5rem" }}>{overallFeedback}</p>}
              {suggestions.length > 0 && (
                <ul>
                  {suggestions.map((s, idx) => (
                    <li key={idx}>{s}</li>
                  ))}
                </ul>
              )}
            </div>
          )}

          <div className="grid2">
            <div className="card">
              <h3>概要</h3>
              <p>
                状态：<StatusPill status={data.status} />
              </p>
              <p>动作：{exerciseTypeLabel(analysis.exercise_type || analysis.exerciseType)}</p>
              <p>获取时间：{data.retrievedAt}</p>
              <p>动作次数：{analysis.rep_count ?? "-"}</p>
              <p>分析耗时：{analysis.processing_time_ms ?? "-"} ms</p>
              <p>端到端耗时：{data.endToEndMs ?? "-"} ms</p>
              <p>训练评分：{trainingScore?.finalScore != null ? `${trainingScore.finalScore} (${trainingScore.level})` : "-"}</p>
            </div>
            <div className="card">
              <h3>逐次数据</h3>
              {tips.length === 0 ? (
                <p>暂无数据</p>
              ) : (
                <ul>
                  {tips.map((t, idx) => (
                    <li key={idx}>
                      第 {t.rep_index} 次：角度={t.min_angle} 度，{t.tip}
                    </li>
                  ))}
                </ul>
              )}
            </div>
          </div>

          <div className="card">
            <h3>关键帧图集</h3>
            {reportImages.length === 0 ? (
              <p>暂无关键帧</p>
            ) : (
              <div className="img-grid">
                {reportImages.map((url) => (
                  <figure key={url}>
                    <img src={resolveImageUrl(url)} alt={url} />
                    <figcaption>{url}</figcaption>
                  </figure>
                ))}
              </div>
            )}
          </div>

          <div className="card">
            <h3>角度趋势图（按次数）</h3>
            <SimpleLineChart points={linePoints} />
          </div>

          <div className="card">
            <h3>导出</h3>
            <button onClick={downloadJson}>下载报告数据（JSON）</button>
          </div>

          <div className="card">
            <h3>原始数据（JSON）</h3>
            <pre>{JSON.stringify(data, null, 2)}</pre>
          </div>
        </>
      )}
    </div>
  );
}
