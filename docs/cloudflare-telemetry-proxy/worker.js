const DEFAULT_POSTHOG_HOST = "https://us.i.posthog.com";
const MAX_BATCH_SIZE = 100;

function jsonResponse(status, payload) {
  return new Response(JSON.stringify(payload), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

function normalizeHost(host) {
  return (host || DEFAULT_POSTHOG_HOST).replace(/\/+$/, "");
}

function isValidEvent(event) {
  return (
    typeof event === "object" &&
    event !== null &&
    typeof event.event === "string" &&
    event.event.length > 0 &&
    typeof event.distinct_id === "string" &&
    event.distinct_id.length > 0 &&
    typeof event.properties === "object" &&
    event.properties !== null
  );
}

export default {
  async fetch(request, env) {
    if (request.method !== "POST") {
      return jsonResponse(405, { error: "Method not allowed." });
    }

    if (!env.POSTHOG_API_KEY) {
      return jsonResponse(500, { error: "POSTHOG_API_KEY is not configured." });
    }

    let payload;
    try {
      payload = await request.json();
    } catch {
      return jsonResponse(400, { error: "Request body must be valid JSON." });
    }

    const batch = payload?.batch;
    if (!Array.isArray(batch) || batch.length === 0) {
      return jsonResponse(400, { error: "Request body must contain a non-empty batch array." });
    }
    if (batch.length > MAX_BATCH_SIZE) {
      return jsonResponse(400, { error: `Batch must contain at most ${MAX_BATCH_SIZE} events.` });
    }
    if (!batch.every(isValidEvent)) {
      return jsonResponse(400, { error: "Each batch entry must include event, distinct_id, and properties." });
    }

    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 10_000);
    let upstreamResponse;
    try {
      upstreamResponse = await fetch(`${normalizeHost(env.POSTHOG_HOST)}/batch/`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          api_key: env.POSTHOG_API_KEY,
          batch,
        }),
        signal: controller.signal,
      });
    } finally {
      clearTimeout(timer);
    }

    if (!upstreamResponse.ok) {
      return jsonResponse(502, { error: "Upstream telemetry relay returned an error." });
    }

    const responseText = await upstreamResponse.text();
    return new Response(
      responseText || JSON.stringify({ ok: true }),
      {
        status: upstreamResponse.status,
        headers: {
          "Content-Type": upstreamResponse.headers.get("Content-Type") || "application/json",
        },
      },
    );
  },
};
