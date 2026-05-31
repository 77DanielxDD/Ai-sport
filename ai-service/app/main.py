from __future__ import annotations

import math
import os
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, List, Optional, Tuple

import cv2
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field

try:
    import mediapipe as mp
except Exception:  # pragma: no cover
    mp = None


class AnalyzeRequest(BaseModel):
    video_id: int = Field(..., ge=1)
    video_path: str
    video_path_candidates: Optional[List[str]] = None
    exercise_type: str


@dataclass(frozen=True)
class ExerciseRule:
    landmarks: Tuple[str, str, str]
    detect_min_angle: float
    good_angle: float
    warn_angle: float
    min_gap_seconds: float
    good_tip: str
    warn_tip: str
    bad_tip: str


RULES: Dict[str, ExerciseRule] = {
    "PUSHUP": ExerciseRule(
        landmarks=("shoulder", "elbow", "wrist"),
        detect_min_angle=100.0,
        good_angle=80.0,
        warn_angle=95.0,
        min_gap_seconds=0.6,
        good_tip="Depth is good. Keep elbow and wrist stable.",
        warn_tip="Depth is acceptable. Lower a bit more with control.",
        bad_tip="Depth is shallow. Increase elbow flexion range.",
    ),
    "SQUAT": ExerciseRule(
        landmarks=("hip", "knee", "ankle"),
        detect_min_angle=135.0,
        good_angle=95.0,
        warn_angle=115.0,
        min_gap_seconds=0.8,
        good_tip="Squat depth is good. Knee-hip coordination is stable.",
        warn_tip="Depth is average. Lower hips more and keep torso stable.",
        bad_tip="Depth is insufficient. Lower center of mass and control knees.",
    ),
    "BENCH_PRESS": ExerciseRule(
        landmarks=("shoulder", "elbow", "wrist"),
        detect_min_angle=100.0,
        good_angle=82.0,
        warn_angle=95.0,
        min_gap_seconds=0.7,
        good_tip="Bench press depth is good and trajectory is stable.",
        warn_tip="Depth is fair. Improve eccentric control.",
        bad_tip="Depth is shallow. Increase elbow flexion range.",
    ),
    "DEADLIFT": ExerciseRule(
        landmarks=("hip", "knee", "ankle"),
        detect_min_angle=145.0,
        good_angle=120.0,
        warn_angle=135.0,
        min_gap_seconds=0.9,
        good_tip="Deadlift range is good. Keep core stability.",
        warn_tip="Range is fair. Improve hip-dominant movement.",
        bad_tip="Range is limited. Lower more while keeping neutral spine.",
    ),
    "DUMBBELL_SHOULDER_PRESS": ExerciseRule(
        landmarks=("shoulder", "elbow", "wrist"),
        detect_min_angle=115.0,
        good_angle=85.0,
        warn_angle=100.0,
        min_gap_seconds=0.7,
        good_tip="Press range is good and arm path is stable.",
        warn_tip="Range is fair. Pause briefly at top.",
        bad_tip="Range is insufficient. Raise endpoint and control tempo.",
    ),
    "DUMBBELL_LATERAL_RAISE": ExerciseRule(
        landmarks=("shoulder", "elbow", "wrist"),
        detect_min_angle=130.0,
        good_angle=100.0,
        warn_angle=115.0,
        min_gap_seconds=0.7,
        good_tip="Lateral raise range is good with stable shoulder control.",
        warn_tip="Range is fair. Reduce momentum and raise higher.",
        bad_tip="Range is insufficient. Increase top position height.",
    ),
    "DUMBBELL_BICEP_CURL": ExerciseRule(
        landmarks=("shoulder", "elbow", "wrist"),
        detect_min_angle=105.0,
        good_angle=70.0,
        warn_angle=85.0,
        min_gap_seconds=0.6,
        good_tip="Curl range is good with clear peak contraction.",
        warn_tip="Range is fair. Reduce momentum and control eccentric phase.",
        bad_tip="Range is insufficient. Increase peak elbow flexion.",
    ),
    "PULL_UP": ExerciseRule(
        landmarks=("shoulder", "elbow", "wrist"),
        detect_min_angle=110.0,
        good_angle=75.0,
        warn_angle=90.0,
        min_gap_seconds=0.9,
        good_tip="Pull-up height is good with solid top contraction.",
        warn_tip="Height is fair. Raise chest closer to the bar.",
        bad_tip="Height is insufficient. Improve top position and reduce swing.",
    ),
}

ALIASES = {
    "BENCHPRESS": "BENCH_PRESS",
    "DEAD_LIFT": "DEADLIFT",
    "BICEP_CURL": "DUMBBELL_BICEP_CURL",
    "BICEPS_CURL": "DUMBBELL_BICEP_CURL",
    "DUMBBELL_CURL": "DUMBBELL_BICEP_CURL",
    "LATERAL_RAISE": "DUMBBELL_LATERAL_RAISE",
    "SIDE_RAISE": "DUMBBELL_LATERAL_RAISE",
    "SHOULDER_PRESS": "DUMBBELL_SHOULDER_PRESS",
    "PULLUP": "PULL_UP",
    "CHINUP": "PULL_UP",
}

LANDMARK_INDEX = {
    "left_shoulder": 11,
    "right_shoulder": 12,
    "left_elbow": 13,
    "right_elbow": 14,
    "left_wrist": 15,
    "right_wrist": 16,
    "left_hip": 23,
    "right_hip": 24,
    "left_knee": 25,
    "right_knee": 26,
    "left_ankle": 27,
    "right_ankle": 28,
}

NAME_TO_SIDES = {
    "shoulder": ("left_shoulder", "right_shoulder"),
    "elbow": ("left_elbow", "right_elbow"),
    "wrist": ("left_wrist", "right_wrist"),
    "hip": ("left_hip", "right_hip"),
    "knee": ("left_knee", "right_knee"),
    "ankle": ("left_ankle", "right_ankle"),
}

MEDIA_BASE_DIR = Path(os.getenv("AI_MEDIA_BASE_DIR", "./uploaded-videos/output")).resolve()

app = FastAPI(title="AI Sport Analyze Service", version="1.0.0")


@app.get("/health")
def health() -> Dict[str, object]:
    return {
        "status": "ok",
        "mediapipe": bool(mp is not None),
        "opencv": bool(cv2.__version__),
    }


@app.post("/analyze")
def analyze(req: AnalyzeRequest) -> Dict[str, object]:
    start = time.perf_counter()
    exercise_type = normalize_exercise(req.exercise_type)
    rule = RULES.get(exercise_type)
    if rule is None:
        raise HTTPException(status_code=400, detail=f"Unsupported exercise_type: {req.exercise_type}")

    video_path = resolve_video_path(req.video_path, req.video_path_candidates)
    if not video_path.exists() or not video_path.is_file():
        raise HTTPException(status_code=400, detail=f"Video path does not exist: {video_path}")
    if mp is None:
        raise HTTPException(status_code=503, detail="mediapipe is not installed")

    output_dir = MEDIA_BASE_DIR / str(req.video_id)
    output_dir.mkdir(parents=True, exist_ok=True)

    try:
        result = process_video(video_path, req.video_id, exercise_type, rule, output_dir)
    except HTTPException:
        raise
    except Exception as ex:
        raise HTTPException(status_code=500, detail=f"Analyze failed: {ex}") from ex

    result["processing_time_ms"] = int((time.perf_counter() - start) * 1000)
    result["schema_version"] = "v1"
    return result


def resolve_video_path(primary: str, candidates: Optional[List[str]]) -> Path:
    ordered: List[str] = []
    if primary:
        ordered.append(primary)
    if candidates:
        for item in candidates:
            if item and item not in ordered:
                ordered.append(item)
    for raw in ordered:
        path = Path(raw)
        if path.exists() and path.is_file():
            return path
    return Path(primary)


def process_video(
    video_path: Path,
    video_id: int,
    exercise_type: str,
    rule: ExerciseRule,
    output_dir: Path,
) -> Dict[str, object]:
    cap = cv2.VideoCapture(str(video_path))
    if not cap.isOpened():
        raise HTTPException(status_code=400, detail=f"Cannot open video: {video_path}")

    fps = cap.get(cv2.CAP_PROP_FPS)
    if not fps or fps <= 1:
        fps = 25.0
    min_gap_frames = max(1, int(rule.min_gap_seconds * fps))

    pose = mp.solutions.pose.Pose(
        static_image_mode=False,
        model_complexity=1,
        smooth_landmarks=True,
        min_detection_confidence=0.5,
        min_tracking_confidence=0.5,
    )

    history: List[Dict[str, object]] = []
    events: List[Dict[str, object]] = []
    event_landmarks_dict: Dict[int, object] = {}

    frame_idx = 0
    while True:
        ok, frame = cap.read()
        if not ok:
            break

        rgb = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
        pred = pose.process(rgb)
        row = extract_frame_record(pred.pose_landmarks, frame, frame_idx, rule)
        if row is not None:
            history.append(row)
            update_events_with_local_min(history, events, min_gap_frames, rule.detect_min_angle, pred.pose_landmarks, event_landmarks_dict)
        frame_idx += 1

    cap.release()
    pose.close()

    if not history:
        raise HTTPException(status_code=422, detail="No pose landmarks detected in this video")

    if not events:
        fallback = min(history, key=lambda x: float(x["angle"]))
        events.append(
            {
                "frame_idx": fallback["frame_idx"],
                "angle": fallback["angle"],
                "frame": fallback["frame"],
                "points": fallback["points"],
            }
        )

    report_images: List[str] = []
    tips: List[Dict[str, object]] = []
    rep_events: List[Dict[str, object]] = []

    events = sorted(events, key=lambda x: int(x["frame_idx"]))
    per_event_symmetry = compute_per_event_symmetry(events, event_landmarks_dict, rule)
    for i, event in enumerate(events, start=1):
        angle = float(event["angle"])
        tip = build_tip_text(rule, angle)
        img_name = f"rep_{i:02d}.png"
        img_path = output_dir / img_name
        rendered = render_keyframe(event["frame"], event["points"], i, angle, tip)
        cv2.imwrite(str(img_path), rendered)

        depth_score_val, depth_level = compute_depth_metric(angle, rule)
        tempo_ms, tempo_level = compute_tempo_metric(events, i - 1, fps)
        symmetry_diff = per_event_symmetry[i - 1]["diff_deg"] if i - 1 < len(per_event_symmetry) else 0.0
        symmetry_score_val, symmetry_level = compute_symmetry_metric(symmetry_diff)
        stability_score_val = compute_stability_metric(history, int(event["frame_idx"]), angle)

        evidence: List[str] = [
            f"最低角度 {angle:.1f}°",
            f"与目标角度 {rule.good_angle:.0f}° 相差 {abs(angle - rule.good_angle):.1f}°",
        ]
        if symmetry_diff > 0:
            evidence.append(f"左右角度差 {symmetry_diff:.1f}°")

        rep_event: Dict[str, object] = {
            "rep_index": i,
            "keyframe_frame": int(event["frame_idx"]),
            "min_angle": round(angle, 2),
            "depth_level": depth_level,
            "depth_score": round(depth_score_val, 1),
            "tempo_ms": tempo_ms,
            "tempo_level": tempo_level,
            "stability_score": round(stability_score_val, 1),
            "symmetry_diff_deg": round(symmetry_diff, 1),
            "symmetry_level": symmetry_level,
            "tip": tip,
            "evidence": evidence,
        }
        report_images.append(f"/media/{video_id}/{img_name}")
        tips.append({"rep_index": i, "min_angle": round(angle, 2), "tip": tip})
        rep_events.append(rep_event)

    rhythm = compute_rhythm(events, fps)
    symmetry = compute_symmetry(events, event_landmarks_dict, rule)

    return {
        "video_id": video_id,
        "exercise_type": exercise_type,
        "rep_count": len(events),
        "tips": tips,
        "rep_events": rep_events,
        "report_images": report_images,
        "rhythm": rhythm,
        "symmetry": symmetry,
    }


def normalize_exercise(raw: str) -> str:
    v = (raw or "").strip().upper().replace("-", "_").replace(" ", "_")
    return ALIASES.get(v, v)


def extract_frame_record(pose_landmarks, frame, frame_idx: int, rule: ExerciseRule) -> Optional[Dict[str, object]]:
    if pose_landmarks is None:
        return None
    landmarks = pose_landmarks.landmark
    side = choose_side(landmarks, rule.landmarks)
    if side is None:
        return None

    p1 = get_point(landmarks, side[0], frame.shape)
    p2 = get_point(landmarks, side[1], frame.shape)
    p3 = get_point(landmarks, side[2], frame.shape)
    if p1 is None or p2 is None or p3 is None:
        return None

    angle = calc_angle(p1, p2, p3)
    if angle is None or math.isnan(angle):
        return None

    return {
        "frame_idx": frame_idx,
        "angle": angle,
        "frame": frame.copy(),
        "points": (p1, p2, p3),
    }


def choose_side(landmarks, base_triplet: Tuple[str, str, str]) -> Optional[Tuple[str, str, str]]:
    left_triplet = tuple(NAME_TO_SIDES[name][0] for name in base_triplet)
    right_triplet = tuple(NAME_TO_SIDES[name][1] for name in base_triplet)
    left_vis = avg_visibility(landmarks, left_triplet)
    right_vis = avg_visibility(landmarks, right_triplet)
    if max(left_vis, right_vis) < 0.35:
        return None
    return left_triplet if left_vis >= right_vis else right_triplet


def avg_visibility(landmarks, names: Tuple[str, str, str]) -> float:
    vals = []
    for name in names:
        lm = landmarks[LANDMARK_INDEX[name]]
        vals.append(float(getattr(lm, "visibility", 0.0)))
    return sum(vals) / len(vals)


def get_point(landmarks, name: str, frame_shape) -> Optional[Tuple[int, int]]:
    lm = landmarks[LANDMARK_INDEX[name]]
    vis = float(getattr(lm, "visibility", 0.0))
    if vis < 0.3:
        return None
    h, w = frame_shape[0], frame_shape[1]
    x = int(max(0, min(1, lm.x)) * w)
    y = int(max(0, min(1, lm.y)) * h)
    return x, y


def calc_angle(p1: Tuple[int, int], p2: Tuple[int, int], p3: Tuple[int, int]) -> Optional[float]:
    ax, ay = p1
    bx, by = p2
    cx, cy = p3
    v1 = (ax - bx, ay - by)
    v2 = (cx - bx, cy - by)
    d1 = math.hypot(v1[0], v1[1])
    d2 = math.hypot(v2[0], v2[1])
    if d1 < 1e-6 or d2 < 1e-6:
        return None
    dot = v1[0] * v2[0] + v1[1] * v2[1]
    cosv = max(-1.0, min(1.0, dot / (d1 * d2)))
    return math.degrees(math.acos(cosv))


def update_events_with_local_min(
    history: List[Dict[str, object]],
    events: List[Dict[str, object]],
    min_gap_frames: int,
    detect_min_angle: float,
    pose_landmarks: object = None,
    event_landmarks_dict: Dict[int, object] = None,
) -> None:
    if len(history) < 3:
        return
    a = history[-3]
    b = history[-2]
    c = history[-1]

    a_angle = float(a["angle"])
    b_angle = float(b["angle"])
    c_angle = float(c["angle"])
    if not (b_angle <= a_angle and b_angle <= c_angle and b_angle <= detect_min_angle):
        return

    frame_idx = int(b["frame_idx"])
    if not events:
        events.append({"frame_idx": frame_idx, "angle": b_angle, "frame": b["frame"], "points": b["points"]})
        if pose_landmarks is not None and event_landmarks_dict is not None:
            event_landmarks_dict[frame_idx] = pose_landmarks
        return

    last = events[-1]
    gap = frame_idx - int(last["frame_idx"])
    if gap >= min_gap_frames:
        events.append({"frame_idx": frame_idx, "angle": b_angle, "frame": b["frame"], "points": b["points"]})
        if pose_landmarks is not None and event_landmarks_dict is not None:
            event_landmarks_dict[frame_idx] = pose_landmarks
        return

    if b_angle < float(last["angle"]):
        events[-1] = {"frame_idx": frame_idx, "angle": b_angle, "frame": b["frame"], "points": b["points"]}
        if pose_landmarks is not None and event_landmarks_dict is not None:
            event_landmarks_dict[frame_idx] = pose_landmarks


def build_tip_text(rule: ExerciseRule, angle: float) -> str:
    if angle <= rule.good_angle:
        return rule.good_tip
    if angle <= rule.warn_angle:
        return rule.warn_tip
    return rule.bad_tip


def compute_depth_metric(angle: float, rule: ExerciseRule) -> Tuple[float, str]:
    diff = angle - rule.good_angle
    if diff <= 0:
        score = min(100.0, 100.0 - abs(diff) * 0.5)
        level = "good"
    elif diff <= (rule.warn_angle - rule.good_angle):
        ratio = diff / (rule.warn_angle - rule.good_angle)
        score = max(50.0, 100.0 - ratio * 50.0)
        level = "warning"
    else:
        score = max(0.0, 50.0 - diff * 0.4)
        level = "bad"
    return round(score, 1), level


def compute_tempo_metric(events: List[Dict[str, object]], idx: int, fps: float) -> Tuple[int, str]:
    if idx == 0 or len(events) < 2:
        return 0, "unknown"
    prev = events[idx - 1]
    curr = events[idx]
    gap_frames = int(curr["frame_idx"]) - int(prev["frame_idx"])
    if gap_frames <= 0:
        return 0, "unknown"
    tempo_ms = int(gap_frames / max(fps, 1.0) * 1000)
    if 800 <= tempo_ms <= 2200:
        level = "normal"
    elif tempo_ms < 800:
        level = "fast"
    else:
        level = "slow"
    return tempo_ms, level


def compute_stability_metric(history: List[Dict[str, object]], frame_idx: int, event_angle: float) -> float:
    nearby = [float(h["angle"]) for h in history if abs(int(h["frame_idx"]) - frame_idx) <= 15]
    if len(nearby) < 3:
        return 50.0
    mean = sum(nearby) / len(nearby)
    variance = sum((a - mean) ** 2 for a in nearby) / len(nearby)
    std = math.sqrt(variance)
    score = max(0.0, min(100.0, 100.0 - std * 4.0))
    return round(score, 1)


def compute_symmetry_metric(diff_deg: float) -> Tuple[float, str]:
    if diff_deg < 3.0:
        return min(100.0, 100.0 - diff_deg * 3.0), "good"
    if diff_deg < 8.0:
        return max(50.0, 100.0 - diff_deg * 5.0), "warning"
    return max(0.0, 100.0 - diff_deg * 4.0), "bad"


def compute_per_event_symmetry(events: List[Dict[str, object]], event_landmarks_dict: Dict[int, object], rule: ExerciseRule) -> List[Dict[str, object]]:
    out: List[Dict[str, object]] = []
    left_trip = tuple(NAME_TO_SIDES[name][0] for name in rule.landmarks)
    right_trip = tuple(NAME_TO_SIDES[name][1] for name in rule.landmarks)
    events_sorted = sorted(events, key=lambda e: int(e["frame_idx"]))
    for event in events_sorted:
        frame_idx = int(event["frame_idx"])
        landmarks = event_landmarks_dict.get(frame_idx)
        if landmarks is None:
            out.append({"diff_deg": 0.0})
            continue
        left_angle = calc_angle_for_side(landmarks.landmark, left_trip, None)
        right_angle = calc_angle_for_side(landmarks.landmark, right_trip, None)
        if left_angle is not None and right_angle is not None:
            out.append({"diff_deg": round(abs(left_angle - right_angle), 1)})
        else:
            out.append({"diff_deg": 0.0})
    return out


def compute_rhythm(events: List[Dict[str, object]], fps: float) -> Dict[str, object]:
    if len(events) < 2:
        return {"rhythm_score": 50.0, "avg_rep_time_ms": 0, "speed_consistency": 0.0, "concentric_ratio": 0.45}
    times = []
    for i in range(1, len(events)):
        gap_frames = int(events[i]["frame_idx"]) - int(events[i - 1]["frame_idx"])
        gap_s = gap_frames / max(fps, 1.0)
        times.append(gap_s * 1000)
    avg_ms = sum(times) / len(times)
    if avg_ms < 1:
        return {"rhythm_score": 50.0, "avg_rep_time_ms": round(avg_ms, 1), "speed_consistency": 0.0, "concentric_ratio": 0.45}
    std = (sum((t - avg_ms) ** 2 for t in times) / len(times)) ** 0.5
    cv = std / avg_ms
    consistency = round(max(0.0, min(100.0, 100.0 - cv * 100.0)), 1)
    score = round(0.6 * consistency + 0.4 * min(100.0, 2000.0 / avg_ms * 50.0), 1)
    return {"rhythm_score": score, "avg_rep_time_ms": round(avg_ms, 1), "speed_consistency": consistency, "concentric_ratio": 0.45}


def compute_symmetry(events: List[Dict[str, object]], event_landmarks_dict: Dict[int, object], rule: ExerciseRule) -> Dict[str, object]:
    if not event_landmarks_dict:
        return {"symmetry_score": 50.0, "left_angles": [], "right_angles": [], "avg_diff_deg": 0.0}
    left_angles = []
    right_angles = []
    left_trip = tuple(NAME_TO_SIDES[name][0] for name in rule.landmarks)
    right_trip = tuple(NAME_TO_SIDES[name][1] for name in rule.landmarks)
    events_sorted = sorted(events, key=lambda e: int(e["frame_idx"]))
    for event in events_sorted:
        frame_idx = int(event["frame_idx"])
        landmarks = event_landmarks_dict.get(frame_idx)
        if landmarks is None:
            continue
        left_angle = calc_angle_for_side(landmarks.landmark, left_trip, None)
        right_angle = calc_angle_for_side(landmarks.landmark, right_trip, None)
        if left_angle is not None and right_angle is not None:
            left_angles.append(round(left_angle, 2))
            right_angles.append(round(right_angle, 2))
    if not left_angles:
        return {"symmetry_score": 50.0, "left_angles": [], "right_angles": [], "avg_diff_deg": 0.0}
    diffs = [abs(l - r) for l, r in zip(left_angles, right_angles)]
    avg_diff = round(sum(diffs) / len(diffs), 1)
    score = round(max(0.0, min(100.0, 100.0 - avg_diff * 4.0)), 1)
    return {"symmetry_score": score, "left_angles": left_angles, "right_angles": right_angles, "avg_diff_deg": avg_diff}


def calc_angle_for_side(landmarks, triplet, frame_shape=None) -> Optional[float]:
    p1 = get_point_raw(landmarks, triplet[0])
    p2 = get_point_raw(landmarks, triplet[1])
    p3 = get_point_raw(landmarks, triplet[2])
    if p1 is None or p2 is None or p3 is None:
        return None
    return calc_angle(p1, p2, p3)


def get_point_raw(landmarks, name: str) -> Optional[Tuple[int, int]]:
    lm = landmarks[LANDMARK_INDEX[name]]
    vis = float(getattr(lm, "visibility", 0.0))
    if vis < 0.3:
        return None
    return (int(lm.x * 1000), int(lm.y * 1000))


def render_keyframe(
    frame,
    points: Tuple[Tuple[int, int], Tuple[int, int], Tuple[int, int]],
    rep_index: int,
    angle: float,
    tip: str,
):
    canvas = frame.copy()
    p1, p2, p3 = points
    cv2.line(canvas, p1, p2, (0, 255, 0), 2)
    cv2.line(canvas, p2, p3, (0, 255, 0), 2)
    cv2.circle(canvas, p1, 4, (0, 200, 255), -1)
    cv2.circle(canvas, p2, 5, (0, 0, 255), -1)
    cv2.circle(canvas, p3, 4, (0, 200, 255), -1)

    text_1 = f"Rep {rep_index}  Min Angle: {angle:.2f}"
    cv2.putText(canvas, text_1, (20, 32), cv2.FONT_HERSHEY_SIMPLEX, 0.7, (20, 20, 20), 4, cv2.LINE_AA)
    cv2.putText(canvas, text_1, (20, 32), cv2.FONT_HERSHEY_SIMPLEX, 0.7, (255, 255, 255), 2, cv2.LINE_AA)

    safe_tip = tip if len(tip) <= 60 else tip[:60]
    cv2.putText(canvas, safe_tip, (20, 62), cv2.FONT_HERSHEY_SIMPLEX, 0.6, (20, 20, 20), 4, cv2.LINE_AA)
    cv2.putText(canvas, safe_tip, (20, 62), cv2.FONT_HERSHEY_SIMPLEX, 0.6, (255, 255, 0), 2, cv2.LINE_AA)
    return canvas
