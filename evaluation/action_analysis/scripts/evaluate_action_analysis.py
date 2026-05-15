#!/usr/bin/env python3
"""
Evaluate action analysis outputs with:
- PCK (if keypoints GT exists)
- Action classification accuracy
- Angle MAE
"""

from __future__ import annotations

import argparse
import csv
import json
import math
from pathlib import Path
from typing import Dict, List, Optional, Tuple, Any


def load_json(path: Path) -> Dict[str, Any]:
    with path.open("r", encoding="utf-8") as f:
        return json.load(f)


def normalize_action_label(value: Optional[str]) -> Optional[str]:
    if value is None:
        return None
    v = str(value).strip().upper()
    alias = {
        "PUSH_UP": "PUSHUP",
        "PUSHUPS": "PUSHUP",
        "SQUATS": "SQUAT",
        "BENCHPRESS": "BENCH_PRESS",
        "BENCH-PRESS": "BENCH_PRESS",
        "BENCH PRESS": "BENCH_PRESS",
        "DEAD_LIFT": "DEADLIFT",
        "DEAD-LIFT": "DEADLIFT",
    }
    return alias.get(v, v)


def keypoint_scale(gt_frame: Dict[str, Any]) -> float:
    bbox = gt_frame.get("bbox")
    if isinstance(bbox, list) and len(bbox) == 4:
        w = float(bbox[2])
        h = float(bbox[3])
        s = max(w, h)
        if s > 0:
            return s

    kps = gt_frame.get("keypoints", {})
    xs = []
    ys = []
    for pt in kps.values():
        if isinstance(pt, list) and len(pt) >= 2:
            xs.append(float(pt[0]))
            ys.append(float(pt[1]))
    if len(xs) >= 2 and len(ys) >= 2:
        return max(max(xs) - min(xs), max(ys) - min(ys), 1e-6)
    return 1.0


def compute_pck(annotation: Dict[str, Any], prediction: Dict[str, Any], pck_alpha: float) -> Tuple[Optional[float], int, int]:
    gt_block = annotation.get("keypoints_gt")
    pred_block = prediction.get("keypoints_pred")

    if not isinstance(gt_block, dict):
        return None, 0, 0
    if not isinstance(pred_block, dict):
        return None, 0, 0

    gt_frames = gt_block.get("frames", [])
    pred_frames = pred_block.get("frames", [])
    if not isinstance(gt_frames, list) or not isinstance(pred_frames, list):
        return None, 0, 0

    pred_frame_map: Dict[int, Dict[str, List[float]]] = {}
    for pf in pred_frames:
        if not isinstance(pf, dict):
            continue
        fi = pf.get("frame_index")
        kps = pf.get("keypoints")
        if isinstance(fi, int) and isinstance(kps, dict):
            pred_frame_map[fi] = kps

    correct = 0
    total = 0

    for gf in gt_frames:
        if not isinstance(gf, dict):
            continue
        fi = gf.get("frame_index")
        gt_kps = gf.get("keypoints")
        if not isinstance(fi, int) or not isinstance(gt_kps, dict):
            continue

        pred_kps = pred_frame_map.get(fi)
        if not isinstance(pred_kps, dict):
            continue

        threshold = pck_alpha * keypoint_scale(gf)
        if threshold <= 0:
            threshold = pck_alpha

        for kp_name, gt_pt in gt_kps.items():
            pred_pt = pred_kps.get(kp_name)
            if not (isinstance(gt_pt, list) and len(gt_pt) >= 2 and isinstance(pred_pt, list) and len(pred_pt) >= 2):
                continue

            gx, gy = float(gt_pt[0]), float(gt_pt[1])
            px, py = float(pred_pt[0]), float(pred_pt[1])
            dist = math.hypot(gx - px, gy - py)

            total += 1
            if dist <= threshold:
                correct += 1

    if total == 0:
        return None, 0, 0
    return correct / total, correct, total


def build_angle_lookup(prediction: Dict[str, Any]) -> Dict[Tuple[str, str], float]:
    lookup: Dict[Tuple[str, str], float] = {}

    angles_pred = prediction.get("angles_pred")
    if isinstance(angles_pred, list):
        for item in angles_pred:
            if not isinstance(item, dict):
                continue
            angle = item.get("angle_deg")
            if not isinstance(angle, (int, float)):
                continue
            rep_index = item.get("rep_index")
            key_id = str(item.get("id") or (f"rep_{rep_index}" if rep_index is not None else "unknown"))
            joint = str(item.get("joint") or "main_joint")
            lookup[(key_id, joint)] = float(angle)

    rep_events = prediction.get("rep_events")
    if isinstance(rep_events, list):
        for ev in rep_events:
            if not isinstance(ev, dict):
                continue
            rep_index = ev.get("rep_index")
            angle = ev.get("min_angle")
            if not isinstance(angle, (int, float)):
                angle = ev.get("min_knee_angle")
            if not isinstance(angle, (int, float)):
                continue
            key_id = f"rep_{rep_index}" if rep_index is not None else "unknown"
            joint = str(ev.get("joint") or "main_joint")
            lookup.setdefault((key_id, joint), float(angle))

    return lookup


def compute_angle_mae(annotation: Dict[str, Any], prediction: Dict[str, Any]) -> Tuple[Optional[float], int, float]:
    gt_block = annotation.get("angles_gt")
    if not isinstance(gt_block, dict):
        return None, 0, 0.0

    gt_items = gt_block.get("items", [])
    if not isinstance(gt_items, list):
        return None, 0, 0.0

    pred_lookup = build_angle_lookup(prediction)

    abs_errors: List[float] = []
    for gt in gt_items:
        if not isinstance(gt, dict):
            continue
        gt_angle = gt.get("angle_deg")
        if not isinstance(gt_angle, (int, float)):
            continue

        rep_index = gt.get("rep_index")
        key_id = str(gt.get("id") or (f"rep_{rep_index}" if rep_index is not None else "unknown"))
        joint = str(gt.get("joint") or "main_joint")

        pred_angle = pred_lookup.get((key_id, joint))
        if pred_angle is None:
            continue

        abs_errors.append(abs(float(gt_angle) - pred_angle))

    if not abs_errors:
        return None, 0, 0.0

    sum_abs = float(sum(abs_errors))
    return sum_abs / len(abs_errors), len(abs_errors), sum_abs


def eval_one_row(row: Dict[str, str], pck_alpha: float) -> Dict[str, Any]:
    video_id = row.get("video_id", "")
    ann_path = Path(row.get("annotation_path", ""))
    pred_path = Path(row.get("prediction_path", ""))

    result: Dict[str, Any] = {
        "video_id": video_id,
        "annotation_path": str(ann_path),
        "prediction_path": str(pred_path),
        "has_prediction": False,
        "action_gt": "",
        "action_pred": "",
        "action_correct": "",
        "pck": "",
        "pck_correct": 0,
        "pck_total": 0,
        "angle_mae_deg": "",
        "angle_pairs": 0,
        "error_type_labels": "",
        "status": "OK",
        "message": "",
    }

    if not ann_path.exists():
        result["status"] = "MISSING_ANNOTATION"
        result["message"] = f"annotation not found: {ann_path}"
        return result

    ann = load_json(ann_path)
    result["action_gt"] = normalize_action_label(ann.get("action_label")) or ""

    err_tags = ann.get("error_type_labels", [])
    if isinstance(err_tags, list):
        result["error_type_labels"] = "|".join(str(x) for x in err_tags)

    if not pred_path.exists():
        result["status"] = "MISSING_PREDICTION"
        result["message"] = f"prediction not found: {pred_path}"
        return result

    pred = load_json(pred_path)
    result["has_prediction"] = True

    action_pred = normalize_action_label(pred.get("action_pred") or pred.get("exercise_type") or pred.get("exerciseType"))
    result["action_pred"] = action_pred or ""

    if result["action_gt"] and action_pred:
        action_correct = int(result["action_gt"] == action_pred)
        result["action_correct"] = action_correct

    pck, pck_correct, pck_total = compute_pck(ann, pred, pck_alpha=pck_alpha)
    if pck is not None:
        result["pck"] = f"{pck:.6f}"
        result["pck_correct"] = pck_correct
        result["pck_total"] = pck_total

    mae, angle_pairs, _sum_abs = compute_angle_mae(ann, pred)
    if mae is not None:
        result["angle_mae_deg"] = f"{mae:.6f}"
        result["angle_pairs"] = angle_pairs

    return result


def write_csv(path: Path, rows: List[Dict[str, Any]], fieldnames: List[str]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=fieldnames)
        writer.writeheader()
        for r in rows:
            writer.writerow(r)


def summarize(per_video: List[Dict[str, Any]]) -> Dict[str, Any]:
    n = len(per_video)
    with_pred = sum(1 for r in per_video if r.get("has_prediction") is True)

    action_items = [int(r["action_correct"]) for r in per_video if str(r.get("action_correct")) in {"0", "1", "0.0", "1.0"}]
    action_acc = (sum(action_items) / len(action_items)) if action_items else None

    pck_values = [float(r["pck"]) for r in per_video if str(r.get("pck", "")).strip() != ""]
    pck_mean = (sum(pck_values) / len(pck_values)) if pck_values else None

    pck_correct_total = sum(int(r.get("pck_correct", 0)) for r in per_video)
    pck_total_total = sum(int(r.get("pck_total", 0)) for r in per_video)
    pck_overall = (pck_correct_total / pck_total_total) if pck_total_total > 0 else None

    angle_mae_values = [float(r["angle_mae_deg"]) for r in per_video if str(r.get("angle_mae_deg", "")).strip() != ""]
    angle_mae_mean = (sum(angle_mae_values) / len(angle_mae_values)) if angle_mae_values else None

    angle_sum_abs = 0.0
    angle_pairs_total = 0
    for r in per_video:
        if str(r.get("angle_mae_deg", "")).strip() != "" and int(r.get("angle_pairs", 0)) > 0:
            angle_sum_abs += float(r["angle_mae_deg"]) * int(r["angle_pairs"])
            angle_pairs_total += int(r["angle_pairs"])
    angle_mae_overall = (angle_sum_abs / angle_pairs_total) if angle_pairs_total > 0 else None

    return {
        "num_videos": n,
        "num_with_prediction": with_pred,
        "action_accuracy": None if action_acc is None else round(action_acc, 6),
        "pck_mean": None if pck_mean is None else round(pck_mean, 6),
        "pck_overall": None if pck_overall is None else round(pck_overall, 6),
        "pck_total_points": pck_total_total,
        "angle_mae_mean_deg": None if angle_mae_mean is None else round(angle_mae_mean, 6),
        "angle_mae_overall_deg": None if angle_mae_overall is None else round(angle_mae_overall, 6),
        "angle_pairs_total": angle_pairs_total,
    }


def write_summary_markdown(path: Path, summary: Dict[str, Any]) -> None:
    lines = [
        "# Action Analysis Evaluation Summary",
        "",
        "| Metric | Value |",
        "|---|---:|",
    ]
    for k in [
        "num_videos",
        "num_with_prediction",
        "action_accuracy",
        "pck_mean",
        "pck_overall",
        "pck_total_points",
        "angle_mae_mean_deg",
        "angle_mae_overall_deg",
        "angle_pairs_total",
    ]:
        lines.append(f"| {k} | {summary.get(k)} |")

    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def load_manifest(manifest_path: Path) -> List[Dict[str, str]]:
    rows: List[Dict[str, str]] = []
    with manifest_path.open("r", encoding="utf-8", newline="") as f:
        reader = csv.DictReader(f)
        for row in reader:
            rows.append(row)
    return rows


def main() -> None:
    parser = argparse.ArgumentParser(description="Evaluate action analysis results")
    parser.add_argument("--manifest", required=True, type=Path, help="CSV with columns: video_id,video_path,annotation_path,prediction_path")
    parser.add_argument("--output-dir", required=True, type=Path, help="Output folder for CSV and summary files")
    parser.add_argument("--pck-alpha", type=float, default=0.2, help="PCK threshold alpha (default: 0.2)")
    args = parser.parse_args()

    manifest_path = args.manifest
    output_dir = args.output_dir

    rows = load_manifest(manifest_path)
    per_video = [eval_one_row(r, pck_alpha=args.pck_alpha) for r in rows]

    per_video_csv = output_dir / "per_video_metrics.csv"
    summary_csv = output_dir / "summary_metrics.csv"
    summary_md = output_dir / "summary_table.md"

    per_video_fields = [
        "video_id",
        "annotation_path",
        "prediction_path",
        "has_prediction",
        "action_gt",
        "action_pred",
        "action_correct",
        "pck",
        "pck_correct",
        "pck_total",
        "angle_mae_deg",
        "angle_pairs",
        "error_type_labels",
        "status",
        "message",
    ]
    write_csv(per_video_csv, per_video, per_video_fields)

    summary = summarize(per_video)
    write_csv(summary_csv, [summary], list(summary.keys()))
    write_summary_markdown(summary_md, summary)

    print(f"[OK] per-video metrics: {per_video_csv}")
    print(f"[OK] summary metrics : {summary_csv}")
    print(f"[OK] summary table   : {summary_md}")


if __name__ == "__main__":
    main()
