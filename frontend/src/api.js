import useStore from "./store";

const API_BASE = import.meta.env.VITE_API_BASE || "http://127.0.0.1:8080";

export function getToken() {
  return localStorage.getItem("ai_sport_token") || "";
}

export function setToken(token) {
  if (token) localStorage.setItem("ai_sport_token", token);
}

export function getRole() {
  return localStorage.getItem("ai_sport_role") || "USER";
}

export function setRole(role) {
  if (role) localStorage.setItem("ai_sport_role", role);
}

export function clearToken() {
  localStorage.removeItem("ai_sport_token");
  localStorage.removeItem("ai_sport_username");
  localStorage.removeItem("ai_sport_role");
}

async function request(path, options = {}) {
  const token = getToken();
  const headers = {
    ...(options.headers || {}),
  };
  if (token) {
    headers.Authorization = `Bearer ${token}`;
  }

  const resp = await fetch(`${API_BASE}${path}`, {
    ...options,
    headers,
  });

  let body = null;
  const text = await resp.text();
  try {
    body = text ? JSON.parse(text) : null;
  } catch {
    body = text;
  }

  if (!resp.ok) {
    const error = new Error(typeof body === "string" ? body : body?.error || "请求失败");
    error.status = resp.status;
    error.body = body;
    if (resp.status === 401) {
      clearToken();
      if (typeof window !== "undefined") {
        window.location.replace("/login");
      }
    }
    throw error;
  }

  return body;
}

export function login(username, password) {
  return request("/api/users/login", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ username, password }),
  });
}

export function register(username, password, email) {
  return request("/api/users/register", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ username, password, email }),
  });
}

export function getCurrentUser() {
  return request("/api/users/me");
}

export function updateCurrentUserProfile({ username, currentPassword, newPassword }) {
  return request("/api/users/me", {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ username, currentPassword, newPassword }),
  });
}

export function uploadVideo({ file, exerciseType }) {
  const form = new FormData();
  form.append("file", file);
  form.append("exerciseType", exerciseType);
  return request("/api/videos/upload", { method: "POST", body: form }).then((r) => {
    useStore.getState().setVideos([]); // Invalidate cache
    return r;
  });
}

export function getVideoStatus(videoId) {
  return request(`/api/videos/${videoId}/status`);
}

export function getVideoAnalysis(videoId) {
  return request(`/api/videos/${videoId}/analysis`);
}

export function compareVideos(leftId, rightId) {
  const query = new URLSearchParams();
  query.set("leftId", leftId);
  query.set("rightId", rightId);
  return request(`/api/videos/compare?${query.toString()}`);
}

export function listVideos({ forceRefresh = false } = {}) {
  if (!forceRefresh) {
    const state = useStore.getState();
    if (!state.isVideoListStale() && state.videos.length > 0) return Promise.resolve(state.videos);
  }
  return request("/api/videos").then((data) => {
    const items = Array.isArray(data) ? data : data?.items || [];
    useStore.getState().setVideos(items);
    return items;
  });
}

export function getPerformanceSummary() {
  return request("/api/videos/performance/summary");
}

export function getTrainingTrends(days = 30) {
  return request(`/api/videos/trends?days=${days}`);
}

export function retryVideo(videoId) {
  return request(`/api/videos/${videoId}/retry`, { method: "POST" });
}

export function cancelVideo(videoId) {
  return request(`/api/videos/${videoId}/cancel`, { method: "POST" });
}

export function deleteVideo(videoId) {
  return request(`/api/videos/${videoId}`, { method: "DELETE" });
}

export function deleteVideosByFilter({ status, exerciseType } = {}) {
  const query = new URLSearchParams();
  if (status && status !== "ALL") query.set("status", status);
  if (exerciseType && exerciseType !== "ALL") query.set("exerciseType", exerciseType);
  const suffix = query.toString() ? `?${query.toString()}` : "";
  return request(`/api/videos${suffix}`, { method: "DELETE" });
}

export function listVideoTasks(videoId) {
  return request(`/api/tasks/video/${videoId}`).then((data) => {
    if (Array.isArray(data)) return data;
    if (data && Array.isArray(data.items)) return data.items;
    return [];
  });
}

export function runExperiment(manifest, outputDir) {
  return request("/api/experiments/run", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ manifest, outputDir }),
  });
}

export function listExperiments() {
  return request("/api/experiments");
}

export function getExperiment(runId) {
  return request(`/api/experiments/${runId}`);
}

export function getExperimentSummary(runId) {
  return request(`/api/experiments/${runId}/summary`);
}

export function systemHealth() {
  return request("/api/system/health");
}

export function runStorageCleanup() {
  return request("/api/system/cleanup/run", { method: "POST" });
}

export function getMetricsSummary() {
  return request("/api/system/metrics/summary");
}

export function adminOverview() {
  return request("/api/admin/overview");
}

export function adminUsers() {
  return request("/api/admin/users");
}

export function adminSetUserEnabled(userId, enabled) {
  return request(`/api/admin/users/${userId}/enabled`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ enabled }),
  });
}

export function adminCreateUser({ username, password, email, role = "USER", enabled = true }) {
  return request("/api/admin/users", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ username, password, email, role, enabled }),
  });
}

export function adminDeleteUser(userId) {
  return request(`/api/admin/users/${userId}`, { method: "DELETE" });
}

export function adminVideos() {
  return request("/api/admin/videos");
}

export function adminTasks() {
  return request("/api/admin/tasks");
}

export function askQuestion(question, videoId = null) {
  return request("/api/rag/qa", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ question, videoId }),
  });
}

export function deleteCurrentUserAccount(currentPassword) {
  return request("/api/users/me/delete", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ currentPassword }),
  });
}

export function askAgent(question, videoId = null) {
  return request("/api/agent/qa", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ question, videoId }),
  });
}

export { API_BASE };
