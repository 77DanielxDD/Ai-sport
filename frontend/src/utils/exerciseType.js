export const EXERCISE_TYPE_LABELS = {
  PUSHUP: "俯卧撑",
  SQUAT: "深蹲",
  BENCH_PRESS: "卧推",
  DEADLIFT: "硬拉",
  DUMBBELL_SHOULDER_PRESS: "哑铃推肩",
  DUMBBELL_LATERAL_RAISE: "哑铃侧平举",
  DUMBBELL_BICEP_CURL: "哑铃二头弯举",
  PULL_UP: "引体向上",
};

export function normalizeExerciseType(type) {
  if (!type) return "";
  const raw = String(type).trim().toUpperCase();
  const alias = {
    BENCHPRESS: "BENCH_PRESS",
    "BENCH-PRESS": "BENCH_PRESS",
    "BENCH PRESS": "BENCH_PRESS",
    DEAD_LIFT: "DEADLIFT",
    "DEAD-LIFT": "DEADLIFT",
    SHOULDER_PRESS: "DUMBBELL_SHOULDER_PRESS",
    DUMBBELL_PRESS: "DUMBBELL_SHOULDER_PRESS",
    DUMBBELL_OVERHEAD_PRESS: "DUMBBELL_SHOULDER_PRESS",
    LATERAL_RAISE: "DUMBBELL_LATERAL_RAISE",
    SIDE_RAISE: "DUMBBELL_LATERAL_RAISE",
    DUMBBELL_SIDE_RAISE: "DUMBBELL_LATERAL_RAISE",
    BICEP_CURL: "DUMBBELL_BICEP_CURL",
    BICEPS_CURL: "DUMBBELL_BICEP_CURL",
    DUMBBELL_CURL: "DUMBBELL_BICEP_CURL",
    PULLUP: "PULL_UP",
    "PULL-UP": "PULL_UP",
    CHINUP: "PULL_UP",
    CHIN_UP: "PULL_UP",
  };
  return alias[raw] || raw;
}

export function exerciseTypeLabel(type) {
  const normalized = normalizeExerciseType(type);
  return EXERCISE_TYPE_LABELS[normalized] || normalized || "未知动作";
}

export const EXERCISE_OPTIONS = [
  { value: "PUSHUP", label: "俯卧撑" },
  { value: "SQUAT", label: "深蹲" },
  { value: "BENCH_PRESS", label: "卧推" },
  { value: "DEADLIFT", label: "硬拉" },
  { value: "DUMBBELL_SHOULDER_PRESS", label: "哑铃推肩" },
  { value: "DUMBBELL_LATERAL_RAISE", label: "哑铃侧平举" },
  { value: "DUMBBELL_BICEP_CURL", label: "哑铃二头弯举" },
  { value: "PULL_UP", label: "引体向上" },
];
