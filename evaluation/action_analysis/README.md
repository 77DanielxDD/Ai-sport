# 动作分析实验验证评测脚手架

## 1. 数据集目录结构（建议 >= 50 视频）

```text
evaluation/action_analysis/
├─ dataset/
│  ├─ videos/
│  │  ├─ pushup/
│  │  │  ├─ standard/
│  │  │  └─ error/
│  │  │     ├─ shallow_depth/
│  │  │     ├─ elbow_flare/
│  │  │     └─ hip_sag/
│  │  └─ squat/
│  │     ├─ standard/
│  │     └─ error/
│  │        ├─ knee_valgus/
│  │        ├─ shallow_depth/
│  │        └─ torso_lean/
│  ├─ annotations/
│  ├─ predictions/
│  ├─ dataset_manifest_template_60.csv
│  ├─ dataset_manifest_example.csv
│  └─ results/
└─ scripts/
   └─ evaluate_action_analysis.py
```

说明：
- `standard`：标准动作样本
- `error/*`：错误动作样本（可多标签）
- 推荐样本规模：`pushup 30 + squat 30 = 60`（已提供 60 条模板清单）

## 2. 人工标注 JSON 格式设计

每个视频一个标注 JSON，核心字段：
- `action_label`：动作类别（如 `PUSHUP` / `SQUAT`）
- `keypoints_gt`（可选）：关键点 GT（逐帧）
- `angles_gt`：关键关节角度 GT（每个 rep 或关键帧）
- `error_type_labels`：错误类型标签（可多标签）

参考示例：`dataset/annotations/example_pushup_001.json`

## 3. 评测指标

脚本输出以下指标：
- `PCK`（有关键点 GT 时计算）
- 动作分类准确率（`action_label` vs 预测动作）
- 角度 MAE（角度 GT 与预测角度）

输出文件：
- `per_video_metrics.csv`：逐视频指标
- `summary_metrics.csv`：汇总指标
- `summary_table.md`：论文可用的简表

## 4. 如何标注

1. 为每个视频在 `dataset/annotations/` 新建同名 JSON。
2. `action_label` 填写真实动作类别。
3. `error_type_labels` 填写错误类型（标准样本可空数组）。
4. 若做关键点评测，补充 `keypoints_gt.frames`。
5. 在 `angles_gt.items` 填入关键帧/rep 的关节角度。

`angles_gt.items` 推荐字段：
- `id`：唯一键（如 `rep_1`）
- `rep_index`：第几次重复
- `frame_index`：关键帧索引
- `joint`：关节名（如 `left_elbow` / `left_knee`）
- `angle_deg`：角度 GT

## 5. 如何运行

### 5.1 最小示例（1 个视频）

```bash
python evaluation/action_analysis/scripts/evaluate_action_analysis.py \
  --manifest evaluation/action_analysis/dataset/dataset_manifest_example.csv \
  --output-dir evaluation/action_analysis/dataset/results/example_run
```

### 5.2 全量实验（模板 60 条）

先把 `dataset_manifest_template_60.csv` 中路径替换为真实视频/标注/预测文件，再运行：

```bash
python evaluation/action_analysis/scripts/evaluate_action_analysis.py \
  --manifest evaluation/action_analysis/dataset/dataset_manifest_template_60.csv \
  --output-dir evaluation/action_analysis/dataset/results/full_run
```

## 6. 论文可用表格

运行后打开：
- `summary_metrics.csv`
- `summary_table.md`

`summary_table.md` 可直接粘贴到论文/报告（Markdown 转 Word/LaTeX 表格也方便）。