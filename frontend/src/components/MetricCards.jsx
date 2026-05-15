export default function MetricCards({ metrics = {} }) {
  const entries = [
    ["动作分类准确率", metrics.action_accuracy],
    ["PCK（整体）", metrics.pck_overall],
    ["角度 MAE（整体）", metrics.angle_mae_overall_deg],
    ["样本数", metrics.num_videos],
  ];

  return (
    <div className="metric-grid">
      {entries.map(([k, v]) => (
        <div className="metric-card" key={k}>
          <div className="metric-title">{k}</div>
          <div className="metric-value">{v ?? "-"}</div>
        </div>
      ))}
    </div>
  );
}