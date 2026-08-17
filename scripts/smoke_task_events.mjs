/**
 * SSE 推送验证：原生 fetch 订阅 /api/tasks/video/{id}/events，
 * 记录收到的事件，主动断开后验证 polling 能拿到终态。
 * 零外部依赖。
 *
 * 用法:
 *   node scripts/smoke_task_events.mjs <videoId> [baseUrl] [token]
 *
 * 默认: http://localhost:8080
 * 环境变量: TOKEN 可代替命令行参数
 */
const BASE = process.argv[3] ?? "http://localhost:8080";
const TOKEN = process.argv[4] ?? process.env.TOKEN ?? "";
const VIDEO_ID = process.argv[2];

if (!VIDEO_ID || !TOKEN) {
  console.error("Usage: node smoke_task_events.mjs <videoId> [baseUrl] [token]");
  console.error("Or set TOKEN env var");
  process.exit(1);
}

const results = { sseEvents: [], pollingOk: false, analysisCount: 0 };

async function subscribeSSE() {
  const url = `${BASE}/api/tasks/video/${VIDEO_ID}/events?token=${encodeURIComponent(TOKEN)}`;
  const resp = await fetch(url);
  if (!resp.ok || !resp.body) {
    console.warn("SSE connection failed:", resp.status);
    return;
  }
  const reader = resp.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";

  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    const lines = buffer.split("\n");
    buffer = lines.pop() ?? "";
    for (const line of lines) {
      if (line.startsWith("data: ")) {
        const event = line.slice(6).trim();
        if (event) {
          results.sseEvents.push(event);
          console.log("SSE:", event);
          if (["COMPLETED", "FAILED", "CANCELLED"].includes(event)) {
            reader.cancel();
            return;
          }
        }
      }
    }
  }
}

async function pollUntilTerminal() {
  const start = Date.now();
  while (Date.now() - start < 30000) {
    const resp = await fetch(`${BASE}/api/videos/${VIDEO_ID}/status`, {
      headers: TOKEN ? { Authorization: `Bearer ${TOKEN}` } : {},
    });
    if (resp.ok) {
      const data = await resp.json();
      if (["COMPLETED", "FAILED", "CANCELLED"].includes(data.status)) {
        results.pollingOk = true;
        console.log("Poll terminal:", data.status);

        const aResp = await fetch(`${BASE}/api/videos/${VIDEO_ID}/analysis`, {
          headers: TOKEN ? { Authorization: `Bearer ${TOKEN}` } : {},
        });
        results.analysisCount = aResp.ok ? 1 : 0;
        return;
      }
      const delay = Math.min(data.retryAfterMs ?? 1500, 8000);
      await new Promise((r) => setTimeout(r, delay));
    } else {
      await new Promise((r) => setTimeout(r, 2000));
    }
  }
  console.warn("Poll timeout");
}

async function main() {
  // Try SSE first
  await subscribeSSE();

  // Fallback: poll if SSE didn't get terminal
  if (!results.sseEvents.some((e) => ["COMPLETED", "FAILED", "CANCELLED"].includes(e))) {
    await pollUntilTerminal();
  }

  const passed = results.sseEvents.length > 0 && results.pollingOk && results.analysisCount === 1;
  console.log("\n=== SSE Smoke Report ===");
  console.log("sseEvents:", results.sseEvents.join(" -> "));
  console.log("pollingOk:", results.pollingOk);
  console.log("analysisCount:", results.analysisCount);
  console.log("PASSED:", passed);
  process.exit(passed ? 0 : 1);
}

main().catch((e) => {
  console.error("Fatal:", e);
  process.exit(1);
});