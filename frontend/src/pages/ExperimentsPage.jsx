import { useEffect, useState } from "react";
import { getExperiment, getExperimentSummary, listExperiments, runExperiment } from "../api";
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
  const [manifest, setManifest] = useState("evaluation/action_analysis/dataset/dataset_manifest_template_60.csv");
  const [outputDir, setOutputDir] = useState("evaluation/action_analysis/dataset/results/full_run");
  const [runs, setRuns] = useState({});
  const [selectedRun, setSelectedRun] = useState("");
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
      setSelectedRun(resp.runId);
    } catch (err) {
      setError(err.message || "发起评测失败");
    }
  }

  async function checkRun() {
    if (!selectedRun) return;
    const run = await getExperiment(selectedRun);
    if (run.status === "COMPLETED") {
      const s = await getExperimentSummary(selectedRun);
      setSummary(s);
    }
    await refreshRuns();
  }

  const runList = Object.values(runs || {});
  const metrics = summary?.summaryMetricsCsv ? parseCsvOneRecord(summary.summaryMetricsCsv) : {};

  function downloadSummaryCsv() {
    if (!summary?.summaryMetricsCsv) return;
    const blob = new Blob([summary.summaryMetricsCsv], { type: "text/csv;charset=utf-8" });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = `summary_${selectedRun || "experiment"}.csv`;
    a.click();
    URL.revokeObjectURL(url);
  }

  return (
    <div>
      <h1>实验评测</h1>
      <div className="grid2">
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
          <h3>查询评测结果</h3>
          <label htmlFor="exp-run-select">选择运行任务</label>
          <select id="exp-run-select" name="runId" value={selectedRun} onChange={(e) => setSelectedRun(e.target.value)}>
            <option value="">请选择运行任务</option>
            {runList.map((r) => (
              <option key={r.runId} value={r.runId}>
                {r.runId}
              </option>
            ))}
          </select>
          <button onClick={checkRun}>刷新所选任务</button>
          {summary?.summaryTableMd && (
            <details open>
              <summary>汇总表（Markdown）</summary>
              <pre>{summary.summaryTableMd}</pre>
            </details>
          )}
          {summary?.summaryMetricsCsv && (
            <details>
              <summary>汇总指标（CSV）</summary>
              <pre>{summary.summaryMetricsCsv}</pre>
            </details>
          )}
          {summary?.summaryMetricsCsv && <button onClick={downloadSummaryCsv}>下载汇总表（CSV）</button>}
        </div>
      </div>

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
                  <td>
                    <StatusPill status={r.status} />
                  </td>
                  <td>{r.manifest}</td>
                  <td>{r.outputDir}</td>
                  <td>{r.startedAt}</td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>
    </div>
  );
}
