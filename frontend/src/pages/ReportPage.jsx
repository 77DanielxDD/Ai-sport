import { useEffect, useMemo, useRef, useState } from "react";
import { useParams } from "react-router-dom";
import { API_BASE, askRagQa, getVideoAnalysis, streamRagQa } from "../api";
import { exerciseTypeLabel } from "../utils/exerciseType";
import SimpleLineChart from "../components/SimpleLineChart";

function toArray(value) {
  if (Array.isArray(value)) return value;
  if (typeof value === "string") {
    try {
      const parsed = JSON.parse(value);
      return Array.isArray(parsed) ? parsed : [];
    } catch {
      return [];
    }
  }
  return [];
}

function getFinalScore(trainingScore) {
  if (trainingScore == null) return null;
  if (typeof trainingScore === "number") return trainingScore;
  if (typeof trainingScore === "object") {
    const raw = trainingScore.finalScore ?? trainingScore.score ?? null;
    const n = Number(raw);
    return Number.isNaN(n) ? null : n;
  }
  const n = Number(trainingScore);
  return Number.isNaN(n) ? null : n;
}

export default function ReportPage() {
  const { videoId } = useParams();
  const [data, setData] = useState(null);
  const [error, setError] = useState("");
  const [question, setQuestion] = useState("");
  const [qaAnswer, setQaAnswer] = useState("");
  const [qaError, setQaError] = useState("");
  const [streaming, setStreaming] = useState(false);
  const [activeIndex, setActiveIndex] = useState(0);
  const [showLightbox, setShowLightbox] = useState(false);
  const abortRef = useRef(null);

  useEffect(() => {
    getVideoAnalysis(videoId)
      .then((resp) => setData(resp))
      .catch((e) => setError(e.body?.message || e.body?.error || e.message));
  }, [videoId]);

  const analysis = useMemo(() => data?.analysis || {}, [data]);
  const reportImages = useMemo(() => toArray(analysis.report_images), [analysis]);
  const tips = useMemo(() => toArray(analysis.tips), [analysis]);
  const trainingScore = useMemo(() => getFinalScore(analysis.trainingScore), [analysis]);

  const linePoints = useMemo(
    () =>
      tips
        .filter((t) => t && typeof t === "object")
        .filter((t) => typeof t.rep_index !== "undefined" && typeof t.min_angle !== "undefined")
        .map((t) => ({ x: Number(t.rep_index), y: Number(t.min_angle) }))
        .filter((p) => !Number.isNaN(p.x) && !Number.isNaN(p.y))
        .sort((a, b) => a.x - b.x),
    [tips],
  );

  useEffect(() => {
    setActiveIndex(0);
  }, [videoId, reportImages.length]);

  const activeImage = reportImages[activeIndex] || reportImages[0] || "";

  const resolveImageUrl = (url) => {
    if (!url) return "";
    const raw = String(url).trim();
    if (!raw) return "";
    if (raw.startsWith("http://") || raw.startsWith("https://")) return raw;
    if (raw.startsWith("/")) return `${API_BASE}${raw}`;
    if (raw.startsWith("media/")) return `${API_BASE}/${raw}`;
    return `${API_BASE}/${raw}`;
  };

  function prevImage() {
    if (reportImages.length <= 1) return;
    setActiveIndex((i) => (i - 1 + reportImages.length) % reportImages.length);
  }

  function nextImage() {
    if (reportImages.length <= 1) return;
    setActiveIndex((i) => (i + 1) % reportImages.length);
  }

  async function askRag() {
    const q = question.trim();
    if (!q) return setQaError("请输入追问内容");
    if (abortRef.current) abortRef.current.abort();
    const controller = new AbortController();
    abortRef.current = controller;
    setQaAnswer("");
    setQaError("");
    setStreaming(true);

    try {
      await streamRagQa({
        question: q,
        videoId: Number(videoId),
        signal: controller.signal,
        onChunk: (chunk) => setQaAnswer((prev) => prev + chunk),
        onError: (err) => setQaError(err?.message || "问答失败"),
      });
    } catch (e) {
      if (e?.name !== "AbortError") {
        try {
          const fallback = await askRagQa(q, Number(videoId));
          setQaAnswer(fallback?.answer || "未获取到回答");
        } catch (e2) {
          setQaError(e2?.body?.error || e2?.message || "问答失败");
        }
      }
    } finally {
      setStreaming(false);
    }
  }

  function stopStream() {
    if (abortRef.current) abortRef.current.abort();
    setStreaming(false);
  }

  if (error) return <p className="error">{error}</p>;
  if (!data) return <p>加载报告中...</p>;

  return (
    <div>
      <div className="card">
        <h1>分析报告 #{videoId}</h1>
        <p>动作类型：<b>{exerciseTypeLabel(analysis.exercise_type || data.exerciseType)}</b></p>
        <p>训练评分：<b>{trainingScore ?? "-"}</b></p>
      </div>

      <div className="report-layout report-layout-top">
        <div className="card">
          <h3>关键帧图集</h3>
          {reportImages.length === 0 ? (
            <p>暂无关键帧</p>
          ) : (
            <>
              <div className="report-main-image-wrap">
                <button type="button" className="report-nav-btn left" onClick={prevImage} disabled={reportImages.length <= 1}>‹</button>
                <img
                  className="report-image report-image-main"
                  src={resolveImageUrl(activeImage)}
                  alt={`rep-${activeIndex + 1}`}
                  onClick={() => setShowLightbox(true)}
                  role="button"
                />
                <button type="button" className="report-nav-btn right" onClick={nextImage} disabled={reportImages.length <= 1}>›</button>
              </div>

              <div className="report-thumbs">
                {reportImages.map((img, idx) => (
                  <button
                    type="button"
                    key={idx}
                    className={`report-thumb${idx === activeIndex ? " active" : ""}`}
                    onClick={() => setActiveIndex(idx)}
                  >
                    <img src={resolveImageUrl(img)} alt={`thumb-${idx + 1}`} />
                    <span>Rep {idx + 1}</span>
                  </button>
                ))}
              </div>
            </>
          )}
        </div>

        <aside className="card tips-panel tips-panel-wide">
          <h3>动作建议</h3>
          {tips.length === 0 ? <p>暂无建议</p> : (
            <ul>
              {tips.map((t, idx) => {
                if (typeof t === "string") {
                  return <li key={idx}>Rep {idx + 1}：{t}</li>;
                }
                return (
                  <li key={idx}>
                    Rep {t?.rep_index ?? idx + 1}：{t?.tip || "-"}
                  </li>
                );
              })}
            </ul>
          )}
        </aside>
      </div>

      <div className="card">
        <h3>角度曲线</h3>
        <SimpleLineChart points={linePoints} yMin={0} yMax={180} height={240} xLabel="重复次数" yLabel="最低角度" />
      </div>

      <div id="rag-qa-card" className="card">
        <h3>训练追问（AI教练）</h3>
        <textarea
          rows={3}
          placeholder="例：我深蹲膝盖痛怎么改"
          value={question}
          onChange={(e) => setQuestion(e.target.value)}
        />
        <div className="inline-actions" style={{ marginBottom: 8 }}>
          <button className="qa-action-btn" disabled={streaming} onClick={askRag}>开始追问</button>
          <button className="qa-action-btn ghost" disabled={!streaming} onClick={stopStream}>停止</button>
        </div>
        {qaError && <p className="error">{qaError}</p>}
        <pre>{qaAnswer || "等待回答..."}</pre>
      </div>

      {showLightbox && (
        <div className="report-lightbox" onClick={() => setShowLightbox(false)}>
          <img src={resolveImageUrl(activeImage)} alt={`lightbox-${activeIndex + 1}`} onClick={(e) => e.stopPropagation()} />
        </div>
      )}
    </div>
  );
}
