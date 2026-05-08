import { useEffect, useMemo, useState } from "react";
import { Navigate } from "react-router-dom";
import { getExperimentSummary, listExperiments, runExperiment, getRole } from "../api";
import MetricCards from "../components/MetricCards";
import StatusPill from "../components/StatusPill";

function parseCsvOneRecord(csvText = "") {
  const lines = csvText.trim().split(/\r?\n/);
  if (lines.length < 2) return {};
  const headers = lines[0].split(",");
  const values = lines[1].split(",");
  const obj = {};
  headers.forEach((h, i) => {
    const raw = values[i];
    const n = Number(raw);
    obj[h] = Number.isNaN(n) || raw === "" ? raw : n;
  });
  return obj;
}

export default function ExperimentsPage() {
  const role = getRole();
  if (role !== "ADMIN") {
    return <Navigate to="/dashboard" replace />;
  }

  const [manifest, setManifest] = useState("evaluation/action_analysis/dataset/dataset_manifest_template_60.csv");
  const [outputDir, setOutputDir] = useState("evaluation/action_analysis/dataset/results/full_run");
  const [runs, setRuns] = useState({});
  const [summary, setSummary] = useState(null);
  const [error, setError] = useState("");

  async function refreshRuns() {
    const r = await listExperiments();
    setRuns(r || {});
  }

  useEffect(() => {
    refreshRuns().catch(() => setRuns({}));
  }, []);

  async function startRun(e) {
    e.preventDefault();
    setError("");
    try {
      const resp = await runExperiment(manifest, outputDir);
      await refreshRuns();
      if (resp?.runId) {
        const s = await getExperimentSummary(resp.runId).catch(() => null);
        if (s) setSummary(s);
      }
    } catch (err) {
      setError(err.message || "发起评测失败");
    }
  }

  const runList = useMemo(() => Object.values(runs || {}), [runs]);
  const latestRun = runList[0] || null;
  const metrics = summary?.summaryMetricsCsv ? parseCsvOneRecord(summary.summaryMetricsCsv) : {};

  function fmtTime(input) {
    if (!input) return "—";
    const d = new Date(input);
    if (Number.isNaN(d.getTime())) return String(input);
    const p = (n) => String(n).padStart(2, "0");
    return `${d.getFullYear()}/${p(d.getMonth() + 1)}/${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
  }

  return (
    <div>
      <h1>实验评测</h1>

      <form className="card" onSubmit={startRun}>
        <h3>发起评测任务</h3>
        <label htmlFor="exp-manifest">数据清单（Manifest）</label>
        <input id="exp-manifest" name="manifest" value={manifest} onChange={(e) => setManifest(e.target.value)} />
        <label htmlFor="exp-output-dir">输出目录</label>
        <input id="exp-output-dir" name="outputDir" value={outputDir} onChange={(e) => setOutputDir(e.target.value)} />
        {error && <p className="error">{error}</p>}
        <button>开始评测</button>
      </form>

      <div className="card">
        <h3>核心指标看板</h3>
        <MetricCards metrics={metrics} />
      </div>

      <div className="card">
        <h3>运行历史</h3>
        <table>
          <thead>
            <tr>
              <th>运行ID</th>
              <th>状态</th>
              <th>数据清单</th>
              <th>输出目录</th>
              <th>开始时间</th>
            </tr>
          </thead>
          <tbody>
            {runList.length === 0 ? (
              <tr>
                <td colSpan="5">暂无运行记录</td>
              </tr>
            ) : (
              runList.map((r) => (
                <tr key={r.runId}>
                  <td>{r.runId}</td>
                  <td><StatusPill status={r.status} /></td>
                  <td>{r.manifest || "—"}</td>
                  <td>{r.outputDir || "—"}</td>
                  <td>{fmtTime(r.startedAt)}</td>
                </tr>
              ))
            )}
          </tbody>
        </table>

        {latestRun && (
          <p style={{ marginTop: 8, color: "#4b5b76" }}>最新任务：{latestRun.runId}（{latestRun.status || "—"}）</p>
        )}
      </div>
    </div>
  );
}
