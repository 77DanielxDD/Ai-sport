import { useEffect, useMemo, useRef, useState } from "react";
import { useParams } from "react-router-dom";
import { API_BASE, askRagQa, getVideoAnalysis, streamRagQa } from "../api";
import StatusPill from "../components/StatusPill";
import SimpleLineChart from "../components/SimpleLineChart";
import { exerciseTypeLabel } from "../utils/exerciseType";

export default function ReportPage() {
  const { videoId } = useParams();
  const [data, setData] = useState(null);
  const [error, setError] = useState("");

  const [question, setQuestion] = useState("");
  const [qaAnswer, setQaAnswer] = useState("");
  const [qaError, setQaError] = useState("");
  const [streaming, setStreaming] = useState(false);
  const [brokenImages, setBrokenImages] = useState({});
  const abortRef = useRef(null);

  useEffect(() => {
    getVideoAnalysis(videoId)
      .then((resp) => {
        setData(resp);
        setBrokenImages({});
      })
      .catch((e) => setError(e.body?.message || e.body?.error || e.message));
  }, [videoId]);

  useEffect(() => {
    if (typeof window !== "undefined" && window.location.hash === "#qa") {
      const el = document.getElementById("rag-qa-card");
      if (el) {
        el.scrollIntoView({ behavior: "smooth", block: "start" });
      }
    }
  }, [data]);

  useEffect(() => {
    return () => {
      if (abortRef.current) {
        abortRef.current.abort();
      }
    };
  }, []);

  const analysis = useMemo(() => data?.analysis || {}, [data]);
  const reportImages = analysis.report_images || [];
  const tips = analysis.tips || [];
  const trainingScore = analysis.trainingScore || null;

  const linePoints = useMemo(() => {
    return tips
      .filter((t) => typeof t.rep_index !== "undefined" && typeof t.min_angle !== "undefined")
      .map((t) => ({ x: Number(t.rep_index), y: Number(t.min_angle) }))
      .filter((p) => !Number.isNaN(p.x) && !Number.isNaN(p.y))
      .sort((a, b) => a.x - b.x);
  }, [tips]);

  const resolveImageUrl = (url) => {
    if (!url) return "";
    const raw = String(url).trim();
    if (!raw) return "";
    if (raw.startsWith("http://") || raw.startsWith("https://")) return raw;
    if (raw.startsWith("/")) return `${API_BASE}${raw}`;
    if (raw.startsWith("media/")) return `${API_BASE}/${raw}`;
    return `${API_BASE}/${raw}`;
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

  async function askRag() {
    const q = question.trim();
    if (!q) {
      setQaError("请输入你想追问的问题");
      return;
    }

    if (abortRef.current) {
      abortRef.current.abort();
    }

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
          setQaError("流式通道异常，已自动切换为普通回答");
        } catch (e2) {
          setQaError(e2?.body?.error || e2?.message || e?.message || "问答失败");
        }
      }
    } finally {
      setStreaming(false);
      abortRef.current = null;
    }
  }

  function stopStream() {
    if (abortRef.current) {
      abortRef.current.abort();
    }
    setStreaming(false);
  }

  return (
    <div>
      <h1>分析报告 #{videoId}</h1>
      {error && <p className="error">{error}</p>}
      {!data && !error && <p>报告加载中...</p>}

      {data && (
        <>
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
              <p>
                训练评分：{" "}
                {trainingScore?.finalScore != null ? `${trainingScore.finalScore} (${trainingScore.level})` : "-"}
              </p>
            </div>

            <div className="card">
              <h3>动作建议</h3>
              {tips.length === 0 ? (
                <p>暂无建议</p>
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

          <div id="rag-qa-card" className="card">
            <h3>训练问答（RAG）</h3>
            <p>你可以继续追问，例如：我的下蹲幅度不足，具体应该怎么练？</p>
            <div className="grid2">
              <div>
                <textarea
                  rows={3}
                  value={question}
                  onChange={(e) => setQuestion(e.target.value)}
                  placeholder="输入你的训练问题..."
                />
              </div>
              <div>
                <div className="inline-actions">
                  <button className="qa-action-btn" onClick={askRag} disabled={streaming}>
                    {streaming ? "回答生成中..." : "开始追问"}
                  </button>
                  <button type="button" className="danger-btn qa-action-btn" onClick={stopStream} disabled={!streaming}>
                    停止
                  </button>
                </div>
                {qaError && <p className="error">{qaError}</p>}
              </div>
            </div>
            <pre style={{ whiteSpace: "pre-wrap", minHeight: 140 }}>{qaAnswer || "等待你的提问..."}</pre>
          </div>

          <div className="card">
            <h3>关键帧图集</h3>
            {reportImages.length === 0 ? (
              <p>暂无关键帧</p>
            ) : (
              <div className="img-grid">
                {reportImages.map((url, idx) => {
                  const resolved = resolveImageUrl(url);
                  const broken = !!brokenImages[url];
                  return (
                    <figure key={`${url}-${idx}`}>
                      {!broken ? (
                        <img
                          className="report-image"
                          src={resolved}
                          alt={`keyframe-${idx + 1}`}
                          loading="lazy"
                          onError={() => setBrokenImages((prev) => ({ ...prev, [url]: true }))}
                        />
                      ) : (
                        <div className="report-image report-image-fallback">图片加载失败</div>
                      )}
                      <figcaption>{resolved || url}</figcaption>
                    </figure>
                  );
                })}
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
