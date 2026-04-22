const DEFAULT_POSTHOG_INGEST_HOST = "https://us.i.posthog.com";
const DEFAULT_POSTHOG_APP_HOST = "https://us.posthog.com";
const MAX_BATCH_SIZE = 100;
const CONTRACT_VERSION = "1";

function jsonResponse(status, payload) {
  return new Response(JSON.stringify(payload), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

function normalizeHost(host, fallback) {
  return (host || fallback).replace(/\/+$/, "");
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

function isIsoDate(value) {
  return /^\d{4}-\d{2}-\d{2}$/.test(value || "");
}

function escapeSqlLiteral(value) {
  return String(value).replace(/'/g, "''");
}

function nextIsoDate(value) {
  const next = new Date(`${value}T00:00:00Z`);
  next.setUTCDate(next.getUTCDate() + 1);
  return next.toISOString().slice(0, 10);
}

function addDaysIsoDate(value, days) {
  const next = new Date(`${value}T00:00:00Z`);
  next.setUTCDate(next.getUTCDate() + days);
  return next.toISOString().slice(0, 10);
}

function maxIsoDate(left, right) {
  return left > right ? left : right;
}

function minIsoDate(left, right) {
  return left < right ? left : right;
}

function weekStartIsoDate(value) {
  const current = new Date(`${value}T00:00:00Z`);
  const day = current.getUTCDay();
  const offset = day === 0 ? -6 : 1 - day;
  current.setUTCDate(current.getUTCDate() + offset);
  return current.toISOString().slice(0, 10);
}

function rate(numerator, denominator) {
  if (!denominator) {
    return 0;
  }
  return Math.round((numerator / denominator) * 1000) / 1000;
}

function average(value) {
  const numeric = Number(value || 0);
  if (!Number.isFinite(numeric)) {
    return 0;
  }
  return Math.round(numeric * 100) / 100;
}

function toInt(value) {
  const numeric = Number(value || 0);
  if (!Number.isFinite(numeric)) {
    return 0;
  }
  return Math.trunc(numeric);
}

function validateStatsRequest(payload) {
  if (typeof payload !== "object" || payload === null) {
    return "Request body must be a JSON object.";
  }
  if (!["bill-feature-verify", "bill-feature-implement"].includes(payload.workflow)) {
    return "workflow must be one of: bill-feature-verify, bill-feature-implement.";
  }
  if (!isIsoDate(payload.date_from) || !isIsoDate(payload.date_to)) {
    return "date_from and date_to must use YYYY-MM-DD format.";
  }
  if (payload.date_from > payload.date_to) {
    return "date_from must be on or before date_to.";
  }
  if (payload.group_by && !["day", "week"].includes(payload.group_by)) {
    return "group_by must be one of: day, week.";
  }
  return null;
}

function capabilitiesPayload(env) {
  const supportsIngest = Boolean(env.POSTHOG_API_KEY);
  const supportsStats = Boolean(env.POSTHOG_PERSONAL_API_KEY && env.POSTHOG_PROJECT_ID);
  return {
    contract_version: CONTRACT_VERSION,
    source: "remote_proxy",
    supports_ingest: supportsIngest,
    supports_stats: supportsStats,
    supported_workflows: supportsStats
      ? ["bill-feature-verify", "bill-feature-implement"]
      : [],
    stats_auth_required: Boolean(env.PROXY_STATS_BEARER_TOKEN),
  };
}

async function readJson(request) {
  try {
    return await request.json();
  } catch {
    return null;
  }
}

async function forwardBatch(env, batch) {
  if (!env.POSTHOG_API_KEY) {
    return jsonResponse(500, { error: "POSTHOG_API_KEY is not configured." });
  }
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 10_000);
  let upstreamResponse;
  try {
    upstreamResponse = await fetch(`${normalizeHost(env.POSTHOG_HOST, DEFAULT_POSTHOG_INGEST_HOST)}/batch/`, {
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
}

async function runPostHogQuery(env, query) {
  if (!env.POSTHOG_PERSONAL_API_KEY) {
    return { error: "POSTHOG_PERSONAL_API_KEY is not configured.", status: 500 };
  }
  if (!env.POSTHOG_PROJECT_ID) {
    return { error: "POSTHOG_PROJECT_ID is not configured.", status: 500 };
  }

  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 10_000);
  let upstreamResponse;
  try {
    upstreamResponse = await fetch(
      `${normalizeHost(env.POSTHOG_APP_HOST, DEFAULT_POSTHOG_APP_HOST)}/api/projects/${env.POSTHOG_PROJECT_ID}/query/`,
      {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "Authorization": `Bearer ${env.POSTHOG_PERSONAL_API_KEY}`,
        },
        body: JSON.stringify({
          query: {
            kind: "HogQLQuery",
            query,
          },
        }),
        signal: controller.signal,
      },
    );
  } finally {
    clearTimeout(timer);
  }

  const responseText = await upstreamResponse.text();
  if (!upstreamResponse.ok) {
    return {
      error: "Upstream telemetry stats backend returned an error.",
      status: 502,
      details: responseText || null,
    };
  }

  let payload;
  try {
    payload = responseText ? JSON.parse(responseText) : {};
  } catch {
    return { error: "Upstream telemetry stats backend returned invalid JSON.", status: 502 };
  }
  return { payload, status: 200 };
}

function buildVerifyStatsQuery(dateFrom, dateToExclusive) {
  const from = escapeSqlLiteral(`${dateFrom} 00:00:00`);
  const to = escapeSqlLiteral(`${dateToExclusive} 00:00:00`);
  return `
    SELECT
      uniqExactIf(toString(properties.session_id), event = 'skillbill_feature_verify_started') AS started_runs,
      uniqExactIf(toString(properties.session_id), event = 'skillbill_feature_verify_finished') AS finished_runs,
      uniqExactIf(toString(properties.session_id), event = 'skillbill_feature_verify_started' AND lower(toString(properties.rollout_relevant)) = 'true') AS rollout_relevant_runs,
      uniqExactIf(toString(properties.session_id), event = 'skillbill_feature_verify_finished' AND lower(toString(properties.feature_flag_audit_performed)) = 'true') AS feature_flag_audit_performed_runs,
      uniqExactIf(toString(properties.session_id), event = 'skillbill_feature_verify_finished' AND toString(properties.audit_result) = 'all_pass') AS audit_result_all_pass,
      uniqExactIf(toString(properties.session_id), event = 'skillbill_feature_verify_finished' AND toString(properties.audit_result) = 'had_gaps') AS audit_result_had_gaps,
      uniqExactIf(toString(properties.session_id), event = 'skillbill_feature_verify_finished' AND toString(properties.audit_result) = 'skipped') AS audit_result_skipped,
      uniqExactIf(toString(properties.session_id), event = 'skillbill_feature_verify_finished' AND toString(properties.completion_status) = 'completed') AS completion_status_completed,
      uniqExactIf(toString(properties.session_id), event = 'skillbill_feature_verify_finished' AND toString(properties.completion_status) = 'abandoned_at_review') AS completion_status_abandoned_at_review,
      uniqExactIf(toString(properties.session_id), event = 'skillbill_feature_verify_finished' AND toString(properties.completion_status) = 'abandoned_at_audit') AS completion_status_abandoned_at_audit,
      uniqExactIf(toString(properties.session_id), event = 'skillbill_feature_verify_finished' AND toString(properties.completion_status) = 'error') AS completion_status_error,
      uniqExactIf(toString(properties.session_id), event = 'skillbill_feature_verify_finished' AND (
        toString(properties.history_relevance) IN ('irrelevant', 'low', 'medium', 'high')
        OR toString(properties.history_helpfulness) IN ('irrelevant', 'low', 'medium', 'high')
      )) AS history_read_runs,
      uniqExactIf(toString(properties.session_id), event = 'skillbill_feature_verify_finished' AND toString(properties.history_relevance) = 'none') AS history_relevance_none,
      uniqExactIf(toString(properties.session_id), event = 'skillbill_feature_verify_finished' AND toString(properties.history_relevance) = 'irrelevant') AS history_relevance_irrelevant,
      uniqExactIf(toString(properties.session_id), event = 'skillbill_feature_verify_finished' AND toString(properties.history_relevance) = 'low') AS history_relevance_low,
      uniqExactIf(toString(properties.session_id), event = 'skillbill_feature_verify_finished' AND toString(properties.history_relevance) = 'medium') AS history_relevance_medium,
      uniqExactIf(toString(properties.session_id), event = 'skillbill_feature_verify_finished' AND toString(properties.history_relevance) = 'high') AS history_relevance_high,
      uniqExactIf(toString(properties.session_id), event = 'skillbill_feature_verify_finished' AND toString(properties.history_helpfulness) = 'none') AS history_helpfulness_none,
      uniqExactIf(toString(properties.session_id), event = 'skillbill_feature_verify_finished' AND toString(properties.history_helpfulness) = 'irrelevant') AS history_helpfulness_irrelevant,
      uniqExactIf(toString(properties.session_id), event = 'skillbill_feature_verify_finished' AND toString(properties.history_helpfulness) = 'low') AS history_helpfulness_low,
      uniqExactIf(toString(properties.session_id), event = 'skillbill_feature_verify_finished' AND toString(properties.history_helpfulness) = 'medium') AS history_helpfulness_medium,
      uniqExactIf(toString(properties.session_id), event = 'skillbill_feature_verify_finished' AND toString(properties.history_helpfulness) = 'high') AS history_helpfulness_high,
      avgIf(toFloatOrZero(toString(properties.acceptance_criteria_count)), event = 'skillbill_feature_verify_started') AS average_acceptance_criteria_count,
      avgIf(toFloatOrZero(toString(properties.review_iterations)), event = 'skillbill_feature_verify_finished') AS average_review_iterations,
      avgIf(toFloatOrZero(toString(properties.duration_seconds)), event = 'skillbill_feature_verify_finished') AS average_duration_seconds
    FROM events
    WHERE event IN ('skillbill_feature_verify_started', 'skillbill_feature_verify_finished')
      AND timestamp >= toDateTime('${from}')
      AND timestamp < toDateTime('${to}')
  `;
}

function buildImplementStatsQuery(dateFrom, dateToExclusive) {
  const from = escapeSqlLiteral(`${dateFrom} 00:00:00`);
  const to = escapeSqlLiteral(`${dateToExclusive} 00:00:00`);
  return `
    SELECT
      uniqExactIf(toString(properties.session_id), event = 'skillbill_feature_implement_started') AS started_runs,
      uniqExactIf(toString(properties.session_id), event = 'skillbill_feature_implement_finished') AS finished_runs,
      uniqExactIf(toString(properties.session_id), event = 'skillbill_feature_implement_started' AND toString(properties.feature_size) = 'SMALL') AS feature_size_small,
      uniqExactIf(toString(properties.session_id), event = 'skillbill_feature_implement_started' AND toString(properties.feature_size) = 'MEDIUM') AS feature_size_medium,
      uniqExactIf(toString(properties.session_id), event = 'skillbill_feature_implement_started' AND toString(properties.feature_size) = 'LARGE') AS feature_size_large,
      uniqExactIf(toString(properties.session_id), event = 'skillbill_feature_implement_started' AND lower(toString(properties.rollout_needed)) = 'true') AS rollout_needed_runs,
      uniqExactIf(toString(properties.session_id), event = 'skillbill_feature_implement_finished' AND lower(toString(properties.feature_flag_used)) = 'true') AS feature_flag_used_runs,
      uniqExactIf(toString(properties.session_id), event = 'skillbill_feature_implement_finished' AND lower(toString(properties.pr_created)) = 'true') AS pr_created_runs,
      uniqExactIf(toString(properties.session_id), event = 'skillbill_feature_implement_finished' AND lower(toString(properties.boundary_history_written)) = 'true') AS boundary_history_written_runs,
      uniqExactIf(toString(properties.session_id), event = 'skillbill_feature_implement_finished' AND toString(properties.audit_result) = 'all_pass') AS audit_result_all_pass,
      uniqExactIf(toString(properties.session_id), event = 'skillbill_feature_implement_finished' AND toString(properties.audit_result) = 'had_gaps') AS audit_result_had_gaps,
      uniqExactIf(toString(properties.session_id), event = 'skillbill_feature_implement_finished' AND toString(properties.audit_result) = 'skipped') AS audit_result_skipped,
      uniqExactIf(toString(properties.session_id), event = 'skillbill_feature_implement_finished' AND toString(properties.validation_result) = 'pass') AS validation_result_pass,
      uniqExactIf(toString(properties.session_id), event = 'skillbill_feature_implement_finished' AND toString(properties.validation_result) = 'fail') AS validation_result_fail,
      uniqExactIf(toString(properties.session_id), event = 'skillbill_feature_implement_finished' AND toString(properties.validation_result) = 'skipped') AS validation_result_skipped,
      uniqExactIf(toString(properties.session_id), event = 'skillbill_feature_implement_finished' AND toString(properties.completion_status) = 'completed') AS completion_status_completed,
      uniqExactIf(toString(properties.session_id), event = 'skillbill_feature_implement_finished' AND toString(properties.completion_status) = 'abandoned_at_planning') AS completion_status_abandoned_at_planning,
      uniqExactIf(toString(properties.session_id), event = 'skillbill_feature_implement_finished' AND toString(properties.completion_status) = 'abandoned_at_implementation') AS completion_status_abandoned_at_implementation,
      uniqExactIf(toString(properties.session_id), event = 'skillbill_feature_implement_finished' AND toString(properties.completion_status) = 'abandoned_at_review') AS completion_status_abandoned_at_review,
      uniqExactIf(toString(properties.session_id), event = 'skillbill_feature_implement_finished' AND toString(properties.completion_status) = 'error') AS completion_status_error,
      uniqExactIf(toString(properties.session_id), event = 'skillbill_feature_implement_finished' AND toString(properties.boundary_history_value) = 'none') AS boundary_history_value_none,
      uniqExactIf(toString(properties.session_id), event = 'skillbill_feature_implement_finished' AND toString(properties.boundary_history_value) = 'irrelevant') AS boundary_history_value_irrelevant,
      uniqExactIf(toString(properties.session_id), event = 'skillbill_feature_implement_finished' AND toString(properties.boundary_history_value) = 'low') AS boundary_history_value_low,
      uniqExactIf(toString(properties.session_id), event = 'skillbill_feature_implement_finished' AND toString(properties.boundary_history_value) = 'medium') AS boundary_history_value_medium,
      uniqExactIf(toString(properties.session_id), event = 'skillbill_feature_implement_finished' AND toString(properties.boundary_history_value) = 'high') AS boundary_history_value_high,
      avgIf(toFloatOrZero(toString(properties.acceptance_criteria_count)), event = 'skillbill_feature_implement_started') AS average_acceptance_criteria_count,
      avgIf(toFloatOrZero(toString(properties.spec_word_count)), event = 'skillbill_feature_implement_started') AS average_spec_word_count,
      avgIf(toFloatOrZero(toString(properties.review_iterations)), event = 'skillbill_feature_implement_finished') AS average_review_iterations,
      avgIf(toFloatOrZero(toString(properties.audit_iterations)), event = 'skillbill_feature_implement_finished') AS average_audit_iterations,
      avgIf(toFloatOrZero(toString(properties.duration_seconds)), event = 'skillbill_feature_implement_finished') AS average_duration_seconds
    FROM events
    WHERE event IN ('skillbill_feature_implement_started', 'skillbill_feature_implement_finished')
      AND timestamp >= toDateTime('${from}')
      AND timestamp < toDateTime('${to}')
  `;
}

function buildVerifySeriesQuery(dateFrom, dateToExclusive) {
  const from = escapeSqlLiteral(`${dateFrom} 00:00:00`);
  const to = escapeSqlLiteral(`${dateToExclusive} 00:00:00`);
  return `
    SELECT
      toString(toDate(timestamp)) AS bucket_date,
      uniqExactIf(toString(properties.session_id), event = 'skillbill_feature_verify_started') AS started_runs,
      uniqExactIf(toString(properties.session_id), event = 'skillbill_feature_verify_finished') AS finished_runs,
      uniqExactIf(toString(properties.session_id), event = 'skillbill_feature_verify_started' AND lower(toString(properties.rollout_relevant)) = 'true') AS rollout_relevant_runs,
      uniqExactIf(toString(properties.session_id), event = 'skillbill_feature_verify_finished' AND lower(toString(properties.feature_flag_audit_performed)) = 'true') AS feature_flag_audit_performed_runs,
      uniqExactIf(toString(properties.session_id), event = 'skillbill_feature_verify_finished' AND toString(properties.audit_result) = 'all_pass') AS audit_result_all_pass,
      uniqExactIf(toString(properties.session_id), event = 'skillbill_feature_verify_finished' AND toString(properties.audit_result) = 'had_gaps') AS audit_result_had_gaps,
      uniqExactIf(toString(properties.session_id), event = 'skillbill_feature_verify_finished' AND toString(properties.audit_result) = 'skipped') AS audit_result_skipped,
      uniqExactIf(toString(properties.session_id), event = 'skillbill_feature_verify_finished' AND toString(properties.completion_status) = 'completed') AS completion_status_completed,
      uniqExactIf(toString(properties.session_id), event = 'skillbill_feature_verify_finished' AND toString(properties.completion_status) = 'abandoned_at_review') AS completion_status_abandoned_at_review,
      uniqExactIf(toString(properties.session_id), event = 'skillbill_feature_verify_finished' AND toString(properties.completion_status) = 'abandoned_at_audit') AS completion_status_abandoned_at_audit,
      uniqExactIf(toString(properties.session_id), event = 'skillbill_feature_verify_finished' AND toString(properties.completion_status) = 'error') AS completion_status_error,
      uniqExactIf(toString(properties.session_id), event = 'skillbill_feature_verify_finished' AND (
        toString(properties.history_relevance) IN ('irrelevant', 'low', 'medium', 'high')
        OR toString(properties.history_helpfulness) IN ('irrelevant', 'low', 'medium', 'high')
      )) AS history_read_runs,
      uniqExactIf(toString(properties.session_id), event = 'skillbill_feature_verify_finished' AND toString(properties.history_relevance) = 'none') AS history_relevance_none,
      uniqExactIf(toString(properties.session_id), event = 'skillbill_feature_verify_finished' AND toString(properties.history_relevance) = 'irrelevant') AS history_relevance_irrelevant,
      uniqExactIf(toString(properties.session_id), event = 'skillbill_feature_verify_finished' AND toString(properties.history_relevance) = 'low') AS history_relevance_low,
      uniqExactIf(toString(properties.session_id), event = 'skillbill_feature_verify_finished' AND toString(properties.history_relevance) = 'medium') AS history_relevance_medium,
      uniqExactIf(toString(properties.session_id), event = 'skillbill_feature_verify_finished' AND toString(properties.history_relevance) = 'high') AS history_relevance_high,
      uniqExactIf(toString(properties.session_id), event = 'skillbill_feature_verify_finished' AND toString(properties.history_helpfulness) = 'none') AS history_helpfulness_none,
      uniqExactIf(toString(properties.session_id), event = 'skillbill_feature_verify_finished' AND toString(properties.history_helpfulness) = 'irrelevant') AS history_helpfulness_irrelevant,
      uniqExactIf(toString(properties.session_id), event = 'skillbill_feature_verify_finished' AND toString(properties.history_helpfulness) = 'low') AS history_helpfulness_low,
      uniqExactIf(toString(properties.session_id), event = 'skillbill_feature_verify_finished' AND toString(properties.history_helpfulness) = 'medium') AS history_helpfulness_medium,
      uniqExactIf(toString(properties.session_id), event = 'skillbill_feature_verify_finished' AND toString(properties.history_helpfulness) = 'high') AS history_helpfulness_high
    FROM events
    WHERE event IN ('skillbill_feature_verify_started', 'skillbill_feature_verify_finished')
      AND timestamp >= toDateTime('${from}')
      AND timestamp < toDateTime('${to}')
    GROUP BY bucket_date
    ORDER BY bucket_date
  `;
}

function buildImplementSeriesQuery(dateFrom, dateToExclusive) {
  const from = escapeSqlLiteral(`${dateFrom} 00:00:00`);
  const to = escapeSqlLiteral(`${dateToExclusive} 00:00:00`);
  return `
    SELECT
      toString(toDate(timestamp)) AS bucket_date,
      uniqExactIf(toString(properties.session_id), event = 'skillbill_feature_implement_started') AS started_runs,
      uniqExactIf(toString(properties.session_id), event = 'skillbill_feature_implement_finished') AS finished_runs,
      uniqExactIf(toString(properties.session_id), event = 'skillbill_feature_implement_started' AND toString(properties.feature_size) = 'SMALL') AS feature_size_small,
      uniqExactIf(toString(properties.session_id), event = 'skillbill_feature_implement_started' AND toString(properties.feature_size) = 'MEDIUM') AS feature_size_medium,
      uniqExactIf(toString(properties.session_id), event = 'skillbill_feature_implement_started' AND toString(properties.feature_size) = 'LARGE') AS feature_size_large,
      uniqExactIf(toString(properties.session_id), event = 'skillbill_feature_implement_started' AND lower(toString(properties.rollout_needed)) = 'true') AS rollout_needed_runs,
      uniqExactIf(toString(properties.session_id), event = 'skillbill_feature_implement_finished' AND lower(toString(properties.feature_flag_used)) = 'true') AS feature_flag_used_runs,
      uniqExactIf(toString(properties.session_id), event = 'skillbill_feature_implement_finished' AND lower(toString(properties.pr_created)) = 'true') AS pr_created_runs,
      uniqExactIf(toString(properties.session_id), event = 'skillbill_feature_implement_finished' AND lower(toString(properties.boundary_history_written)) = 'true') AS boundary_history_written_runs,
      uniqExactIf(toString(properties.session_id), event = 'skillbill_feature_implement_finished' AND toString(properties.audit_result) = 'all_pass') AS audit_result_all_pass,
      uniqExactIf(toString(properties.session_id), event = 'skillbill_feature_implement_finished' AND toString(properties.audit_result) = 'had_gaps') AS audit_result_had_gaps,
      uniqExactIf(toString(properties.session_id), event = 'skillbill_feature_implement_finished' AND toString(properties.audit_result) = 'skipped') AS audit_result_skipped,
      uniqExactIf(toString(properties.session_id), event = 'skillbill_feature_implement_finished' AND toString(properties.validation_result) = 'pass') AS validation_result_pass,
      uniqExactIf(toString(properties.session_id), event = 'skillbill_feature_implement_finished' AND toString(properties.validation_result) = 'fail') AS validation_result_fail,
      uniqExactIf(toString(properties.session_id), event = 'skillbill_feature_implement_finished' AND toString(properties.validation_result) = 'skipped') AS validation_result_skipped,
      uniqExactIf(toString(properties.session_id), event = 'skillbill_feature_implement_finished' AND toString(properties.completion_status) = 'completed') AS completion_status_completed,
      uniqExactIf(toString(properties.session_id), event = 'skillbill_feature_implement_finished' AND toString(properties.completion_status) = 'abandoned_at_planning') AS completion_status_abandoned_at_planning,
      uniqExactIf(toString(properties.session_id), event = 'skillbill_feature_implement_finished' AND toString(properties.completion_status) = 'abandoned_at_implementation') AS completion_status_abandoned_at_implementation,
      uniqExactIf(toString(properties.session_id), event = 'skillbill_feature_implement_finished' AND toString(properties.completion_status) = 'abandoned_at_review') AS completion_status_abandoned_at_review,
      uniqExactIf(toString(properties.session_id), event = 'skillbill_feature_implement_finished' AND toString(properties.completion_status) = 'error') AS completion_status_error,
      uniqExactIf(toString(properties.session_id), event = 'skillbill_feature_implement_finished' AND toString(properties.boundary_history_value) = 'none') AS boundary_history_value_none,
      uniqExactIf(toString(properties.session_id), event = 'skillbill_feature_implement_finished' AND toString(properties.boundary_history_value) = 'irrelevant') AS boundary_history_value_irrelevant,
      uniqExactIf(toString(properties.session_id), event = 'skillbill_feature_implement_finished' AND toString(properties.boundary_history_value) = 'low') AS boundary_history_value_low,
      uniqExactIf(toString(properties.session_id), event = 'skillbill_feature_implement_finished' AND toString(properties.boundary_history_value) = 'medium') AS boundary_history_value_medium,
      uniqExactIf(toString(properties.session_id), event = 'skillbill_feature_implement_finished' AND toString(properties.boundary_history_value) = 'high') AS boundary_history_value_high
    FROM events
    WHERE event IN ('skillbill_feature_implement_started', 'skillbill_feature_implement_finished')
      AND timestamp >= toDateTime('${from}')
      AND timestamp < toDateTime('${to}')
    GROUP BY bucket_date
    ORDER BY bucket_date
  `;
}

function rowToObject(columns, row) {
  const payload = {};
  columns.forEach((column, index) => {
    payload[column] = row[index];
  });
  return payload;
}

function queryRowsToObjects(payload) {
  const columns = Array.isArray(payload?.columns) ? payload.columns : [];
  const results = Array.isArray(payload?.results) ? payload.results : [];
  return results
    .filter((row) => Array.isArray(row))
    .map((row) => rowToObject(columns, row));
}

export function normalizeVerifyStats(row, dateFrom, dateTo) {
  const startedRuns = toInt(row.started_runs);
  const finishedRuns = toInt(row.finished_runs);
  const inProgressRuns = Math.max(startedRuns - finishedRuns, 0);
  const completedRuns = toInt(row.completion_status_completed);
  const abandonedAtReviewRuns = toInt(row.completion_status_abandoned_at_review);
  const abandonedAtAuditRuns = toInt(row.completion_status_abandoned_at_audit);
  const abandonedRuns = abandonedAtReviewRuns + abandonedAtAuditRuns;
  const historyRelevantRuns = toInt(row.history_relevance_medium) + toInt(row.history_relevance_high);
  const historyHelpfulRuns = toInt(row.history_helpfulness_medium) + toInt(row.history_helpfulness_high);
  return {
    status: "ok",
    source: "remote_proxy",
    workflow: "bill-feature-verify",
    date_from: dateFrom,
    date_to: dateTo,
    started_runs: startedRuns,
    finished_runs: finishedRuns,
    in_progress_runs: inProgressRuns,
    in_progress_rate: rate(inProgressRuns, startedRuns),
    completion_rate: rate(completedRuns, startedRuns),
    abandonment_rate: rate(abandonedRuns, startedRuns),
    completion_status_counts: {
      completed: completedRuns,
      abandoned_at_review: abandonedAtReviewRuns,
      abandoned_at_audit: abandonedAtAuditRuns,
      error: toInt(row.completion_status_error),
    },
    audit_result_counts: {
      all_pass: toInt(row.audit_result_all_pass),
      had_gaps: toInt(row.audit_result_had_gaps),
      skipped: toInt(row.audit_result_skipped),
    },
    rollout_relevant_runs: toInt(row.rollout_relevant_runs),
    rollout_relevant_rate: rate(toInt(row.rollout_relevant_runs), startedRuns),
    feature_flag_audit_performed_runs: toInt(row.feature_flag_audit_performed_runs),
    feature_flag_audit_performed_rate: rate(toInt(row.feature_flag_audit_performed_runs), finishedRuns),
    history_read_runs: toInt(row.history_read_runs),
    history_read_rate: rate(toInt(row.history_read_runs), finishedRuns),
    history_relevant_runs: historyRelevantRuns,
    history_relevant_rate: rate(historyRelevantRuns, finishedRuns),
    history_helpful_runs: historyHelpfulRuns,
    history_helpful_rate: rate(historyHelpfulRuns, finishedRuns),
    history_relevance_counts: {
      none: toInt(row.history_relevance_none),
      irrelevant: toInt(row.history_relevance_irrelevant),
      low: toInt(row.history_relevance_low),
      medium: toInt(row.history_relevance_medium),
      high: toInt(row.history_relevance_high),
    },
    history_helpfulness_counts: {
      none: toInt(row.history_helpfulness_none),
      irrelevant: toInt(row.history_helpfulness_irrelevant),
      low: toInt(row.history_helpfulness_low),
      medium: toInt(row.history_helpfulness_medium),
      high: toInt(row.history_helpfulness_high),
    },
    average_acceptance_criteria_count: average(row.average_acceptance_criteria_count),
    average_review_iterations: average(row.average_review_iterations),
    average_duration_seconds: average(row.average_duration_seconds),
  };
}

export function normalizeImplementStats(row, dateFrom, dateTo) {
  const startedRuns = toInt(row.started_runs);
  const finishedRuns = toInt(row.finished_runs);
  const inProgressRuns = Math.max(startedRuns - finishedRuns, 0);
  const completedRuns = toInt(row.completion_status_completed);
  const boundaryHistoryUsefulRuns = toInt(row.boundary_history_value_medium) + toInt(row.boundary_history_value_high);
  return {
    status: "ok",
    source: "remote_proxy",
    workflow: "bill-feature-implement",
    date_from: dateFrom,
    date_to: dateTo,
    started_runs: startedRuns,
    finished_runs: finishedRuns,
    in_progress_runs: inProgressRuns,
    in_progress_rate: rate(inProgressRuns, startedRuns),
    completion_rate: rate(completedRuns, startedRuns),
    feature_size_counts: {
      SMALL: toInt(row.feature_size_small),
      MEDIUM: toInt(row.feature_size_medium),
      LARGE: toInt(row.feature_size_large),
    },
    completion_status_counts: {
      completed: completedRuns,
      abandoned_at_planning: toInt(row.completion_status_abandoned_at_planning),
      abandoned_at_implementation: toInt(row.completion_status_abandoned_at_implementation),
      abandoned_at_review: toInt(row.completion_status_abandoned_at_review),
      error: toInt(row.completion_status_error),
    },
    audit_result_counts: {
      all_pass: toInt(row.audit_result_all_pass),
      had_gaps: toInt(row.audit_result_had_gaps),
      skipped: toInt(row.audit_result_skipped),
    },
    validation_result_counts: {
      pass: toInt(row.validation_result_pass),
      fail: toInt(row.validation_result_fail),
      skipped: toInt(row.validation_result_skipped),
    },
    rollout_needed_runs: toInt(row.rollout_needed_runs),
    rollout_needed_rate: rate(toInt(row.rollout_needed_runs), startedRuns),
    feature_flag_used_runs: toInt(row.feature_flag_used_runs),
    feature_flag_used_rate: rate(toInt(row.feature_flag_used_runs), finishedRuns),
    pr_created_runs: toInt(row.pr_created_runs),
    pr_created_rate: rate(toInt(row.pr_created_runs), finishedRuns),
    boundary_history_written_runs: toInt(row.boundary_history_written_runs),
    boundary_history_written_rate: rate(toInt(row.boundary_history_written_runs), finishedRuns),
    boundary_history_useful_runs: boundaryHistoryUsefulRuns,
    boundary_history_useful_rate: rate(boundaryHistoryUsefulRuns, finishedRuns),
    boundary_history_value_counts: {
      none: toInt(row.boundary_history_value_none),
      irrelevant: toInt(row.boundary_history_value_irrelevant),
      low: toInt(row.boundary_history_value_low),
      medium: toInt(row.boundary_history_value_medium),
      high: toInt(row.boundary_history_value_high),
    },
    average_acceptance_criteria_count: average(row.average_acceptance_criteria_count),
    average_spec_word_count: average(row.average_spec_word_count),
    average_review_iterations: average(row.average_review_iterations),
    average_audit_iterations: average(row.average_audit_iterations),
    average_duration_seconds: average(row.average_duration_seconds),
  };
}

export function normalizeVerifySeriesEntry(row, bucketStart, bucketEnd) {
  const startedRuns = toInt(row.started_runs);
  const finishedRuns = toInt(row.finished_runs);
  const inProgressRuns = Math.max(startedRuns - finishedRuns, 0);
  const completedRuns = toInt(row.completion_status_completed);
  const abandonedAtReviewRuns = toInt(row.completion_status_abandoned_at_review);
  const abandonedAtAuditRuns = toInt(row.completion_status_abandoned_at_audit);
  const abandonedRuns = abandonedAtReviewRuns + abandonedAtAuditRuns;
  const historyRelevantRuns = toInt(row.history_relevance_medium) + toInt(row.history_relevance_high);
  const historyHelpfulRuns = toInt(row.history_helpfulness_medium) + toInt(row.history_helpfulness_high);
  return {
    bucket_start: bucketStart,
    bucket_end: bucketEnd,
    started_runs: startedRuns,
    finished_runs: finishedRuns,
    in_progress_runs: inProgressRuns,
    in_progress_rate: rate(inProgressRuns, startedRuns),
    completion_rate: rate(completedRuns, startedRuns),
    abandonment_rate: rate(abandonedRuns, startedRuns),
    completion_status_counts: {
      completed: completedRuns,
      abandoned_at_review: abandonedAtReviewRuns,
      abandoned_at_audit: abandonedAtAuditRuns,
      error: toInt(row.completion_status_error),
    },
    audit_result_counts: {
      all_pass: toInt(row.audit_result_all_pass),
      had_gaps: toInt(row.audit_result_had_gaps),
      skipped: toInt(row.audit_result_skipped),
    },
    rollout_relevant_runs: toInt(row.rollout_relevant_runs),
    rollout_relevant_rate: rate(toInt(row.rollout_relevant_runs), startedRuns),
    feature_flag_audit_performed_runs: toInt(row.feature_flag_audit_performed_runs),
    feature_flag_audit_performed_rate: rate(toInt(row.feature_flag_audit_performed_runs), finishedRuns),
    history_read_runs: toInt(row.history_read_runs),
    history_read_rate: rate(toInt(row.history_read_runs), finishedRuns),
    history_relevant_runs: historyRelevantRuns,
    history_relevant_rate: rate(historyRelevantRuns, finishedRuns),
    history_helpful_runs: historyHelpfulRuns,
    history_helpful_rate: rate(historyHelpfulRuns, finishedRuns),
    history_relevance_counts: {
      none: toInt(row.history_relevance_none),
      irrelevant: toInt(row.history_relevance_irrelevant),
      low: toInt(row.history_relevance_low),
      medium: toInt(row.history_relevance_medium),
      high: toInt(row.history_relevance_high),
    },
    history_helpfulness_counts: {
      none: toInt(row.history_helpfulness_none),
      irrelevant: toInt(row.history_helpfulness_irrelevant),
      low: toInt(row.history_helpfulness_low),
      medium: toInt(row.history_helpfulness_medium),
      high: toInt(row.history_helpfulness_high),
    },
  };
}

export function normalizeImplementSeriesEntry(row, bucketStart, bucketEnd) {
  const startedRuns = toInt(row.started_runs);
  const finishedRuns = toInt(row.finished_runs);
  const inProgressRuns = Math.max(startedRuns - finishedRuns, 0);
  const completedRuns = toInt(row.completion_status_completed);
  const boundaryHistoryUsefulRuns = toInt(row.boundary_history_value_medium) + toInt(row.boundary_history_value_high);
  return {
    bucket_start: bucketStart,
    bucket_end: bucketEnd,
    started_runs: startedRuns,
    finished_runs: finishedRuns,
    in_progress_runs: inProgressRuns,
    in_progress_rate: rate(inProgressRuns, startedRuns),
    completion_rate: rate(completedRuns, startedRuns),
    feature_size_counts: {
      SMALL: toInt(row.feature_size_small),
      MEDIUM: toInt(row.feature_size_medium),
      LARGE: toInt(row.feature_size_large),
    },
    completion_status_counts: {
      completed: completedRuns,
      abandoned_at_planning: toInt(row.completion_status_abandoned_at_planning),
      abandoned_at_implementation: toInt(row.completion_status_abandoned_at_implementation),
      abandoned_at_review: toInt(row.completion_status_abandoned_at_review),
      error: toInt(row.completion_status_error),
    },
    audit_result_counts: {
      all_pass: toInt(row.audit_result_all_pass),
      had_gaps: toInt(row.audit_result_had_gaps),
      skipped: toInt(row.audit_result_skipped),
    },
    validation_result_counts: {
      pass: toInt(row.validation_result_pass),
      fail: toInt(row.validation_result_fail),
      skipped: toInt(row.validation_result_skipped),
    },
    rollout_needed_runs: toInt(row.rollout_needed_runs),
    rollout_needed_rate: rate(toInt(row.rollout_needed_runs), startedRuns),
    feature_flag_used_runs: toInt(row.feature_flag_used_runs),
    feature_flag_used_rate: rate(toInt(row.feature_flag_used_runs), finishedRuns),
    pr_created_runs: toInt(row.pr_created_runs),
    pr_created_rate: rate(toInt(row.pr_created_runs), finishedRuns),
    boundary_history_written_runs: toInt(row.boundary_history_written_runs),
    boundary_history_written_rate: rate(toInt(row.boundary_history_written_runs), finishedRuns),
    boundary_history_useful_runs: boundaryHistoryUsefulRuns,
    boundary_history_useful_rate: rate(boundaryHistoryUsefulRuns, finishedRuns),
    boundary_history_value_counts: {
      none: toInt(row.boundary_history_value_none),
      irrelevant: toInt(row.boundary_history_value_irrelevant),
      low: toInt(row.boundary_history_value_low),
      medium: toInt(row.boundary_history_value_medium),
      high: toInt(row.boundary_history_value_high),
    },
  };
}

function summarizeVerifySeriesBucket(bucketStart, bucketEnd, entries) {
  const startedRuns = entries.reduce((sum, entry) => sum + toInt(entry.started_runs), 0);
  const finishedRuns = entries.reduce((sum, entry) => sum + toInt(entry.finished_runs), 0);
  const completedRuns = entries.reduce((sum, entry) => sum + toInt(entry.completion_status_counts?.completed), 0);
  const abandonedAtReviewRuns = entries.reduce((sum, entry) => sum + toInt(entry.completion_status_counts?.abandoned_at_review), 0);
  const abandonedAtAuditRuns = entries.reduce((sum, entry) => sum + toInt(entry.completion_status_counts?.abandoned_at_audit), 0);
  const abandonedRuns = abandonedAtReviewRuns + abandonedAtAuditRuns;
  const inProgressRuns = Math.max(startedRuns - finishedRuns, 0);
  const historyReadRuns = entries.reduce((sum, entry) => sum + toInt(entry.history_read_runs), 0);
  const historyRelevantRuns = entries.reduce((sum, entry) => sum + toInt(entry.history_relevant_runs), 0);
  const historyHelpfulRuns = entries.reduce((sum, entry) => sum + toInt(entry.history_helpful_runs), 0);
  return {
    bucket_start: bucketStart,
    bucket_end: bucketEnd,
    started_runs: startedRuns,
    finished_runs: finishedRuns,
    in_progress_runs: inProgressRuns,
    in_progress_rate: rate(inProgressRuns, startedRuns),
    completion_rate: rate(completedRuns, startedRuns),
    abandonment_rate: rate(abandonedRuns, startedRuns),
    completion_status_counts: {
      completed: completedRuns,
      abandoned_at_review: abandonedAtReviewRuns,
      abandoned_at_audit: abandonedAtAuditRuns,
      error: entries.reduce((sum, entry) => sum + toInt(entry.completion_status_counts?.error), 0),
    },
    audit_result_counts: {
      all_pass: entries.reduce((sum, entry) => sum + toInt(entry.audit_result_counts?.all_pass), 0),
      had_gaps: entries.reduce((sum, entry) => sum + toInt(entry.audit_result_counts?.had_gaps), 0),
      skipped: entries.reduce((sum, entry) => sum + toInt(entry.audit_result_counts?.skipped), 0),
    },
    rollout_relevant_runs: entries.reduce((sum, entry) => sum + toInt(entry.rollout_relevant_runs), 0),
    rollout_relevant_rate: rate(
      entries.reduce((sum, entry) => sum + toInt(entry.rollout_relevant_runs), 0),
      startedRuns,
    ),
    feature_flag_audit_performed_runs: entries.reduce((sum, entry) => sum + toInt(entry.feature_flag_audit_performed_runs), 0),
    feature_flag_audit_performed_rate: rate(
      entries.reduce((sum, entry) => sum + toInt(entry.feature_flag_audit_performed_runs), 0),
      finishedRuns,
    ),
    history_read_runs: historyReadRuns,
    history_read_rate: rate(historyReadRuns, finishedRuns),
    history_relevant_runs: historyRelevantRuns,
    history_relevant_rate: rate(historyRelevantRuns, finishedRuns),
    history_helpful_runs: historyHelpfulRuns,
    history_helpful_rate: rate(historyHelpfulRuns, finishedRuns),
    history_relevance_counts: {
      none: entries.reduce((sum, entry) => sum + toInt(entry.history_relevance_counts?.none), 0),
      irrelevant: entries.reduce((sum, entry) => sum + toInt(entry.history_relevance_counts?.irrelevant), 0),
      low: entries.reduce((sum, entry) => sum + toInt(entry.history_relevance_counts?.low), 0),
      medium: entries.reduce((sum, entry) => sum + toInt(entry.history_relevance_counts?.medium), 0),
      high: entries.reduce((sum, entry) => sum + toInt(entry.history_relevance_counts?.high), 0),
    },
    history_helpfulness_counts: {
      none: entries.reduce((sum, entry) => sum + toInt(entry.history_helpfulness_counts?.none), 0),
      irrelevant: entries.reduce((sum, entry) => sum + toInt(entry.history_helpfulness_counts?.irrelevant), 0),
      low: entries.reduce((sum, entry) => sum + toInt(entry.history_helpfulness_counts?.low), 0),
      medium: entries.reduce((sum, entry) => sum + toInt(entry.history_helpfulness_counts?.medium), 0),
      high: entries.reduce((sum, entry) => sum + toInt(entry.history_helpfulness_counts?.high), 0),
    },
  };
}

function summarizeImplementSeriesBucket(bucketStart, bucketEnd, entries) {
  const startedRuns = entries.reduce((sum, entry) => sum + toInt(entry.started_runs), 0);
  const finishedRuns = entries.reduce((sum, entry) => sum + toInt(entry.finished_runs), 0);
  const completedRuns = entries.reduce((sum, entry) => sum + toInt(entry.completion_status_counts?.completed), 0);
  const inProgressRuns = Math.max(startedRuns - finishedRuns, 0);
  const boundaryHistoryUsefulRuns = entries.reduce((sum, entry) => sum + toInt(entry.boundary_history_useful_runs), 0);
  return {
    bucket_start: bucketStart,
    bucket_end: bucketEnd,
    started_runs: startedRuns,
    finished_runs: finishedRuns,
    in_progress_runs: inProgressRuns,
    in_progress_rate: rate(inProgressRuns, startedRuns),
    completion_rate: rate(completedRuns, startedRuns),
    feature_size_counts: {
      SMALL: entries.reduce((sum, entry) => sum + toInt(entry.feature_size_counts?.SMALL), 0),
      MEDIUM: entries.reduce((sum, entry) => sum + toInt(entry.feature_size_counts?.MEDIUM), 0),
      LARGE: entries.reduce((sum, entry) => sum + toInt(entry.feature_size_counts?.LARGE), 0),
    },
    completion_status_counts: {
      completed: completedRuns,
      abandoned_at_planning: entries.reduce((sum, entry) => sum + toInt(entry.completion_status_counts?.abandoned_at_planning), 0),
      abandoned_at_implementation: entries.reduce((sum, entry) => sum + toInt(entry.completion_status_counts?.abandoned_at_implementation), 0),
      abandoned_at_review: entries.reduce((sum, entry) => sum + toInt(entry.completion_status_counts?.abandoned_at_review), 0),
      error: entries.reduce((sum, entry) => sum + toInt(entry.completion_status_counts?.error), 0),
    },
    audit_result_counts: {
      all_pass: entries.reduce((sum, entry) => sum + toInt(entry.audit_result_counts?.all_pass), 0),
      had_gaps: entries.reduce((sum, entry) => sum + toInt(entry.audit_result_counts?.had_gaps), 0),
      skipped: entries.reduce((sum, entry) => sum + toInt(entry.audit_result_counts?.skipped), 0),
    },
    validation_result_counts: {
      pass: entries.reduce((sum, entry) => sum + toInt(entry.validation_result_counts?.pass), 0),
      fail: entries.reduce((sum, entry) => sum + toInt(entry.validation_result_counts?.fail), 0),
      skipped: entries.reduce((sum, entry) => sum + toInt(entry.validation_result_counts?.skipped), 0),
    },
    rollout_needed_runs: entries.reduce((sum, entry) => sum + toInt(entry.rollout_needed_runs), 0),
    rollout_needed_rate: rate(
      entries.reduce((sum, entry) => sum + toInt(entry.rollout_needed_runs), 0),
      startedRuns,
    ),
    feature_flag_used_runs: entries.reduce((sum, entry) => sum + toInt(entry.feature_flag_used_runs), 0),
    feature_flag_used_rate: rate(
      entries.reduce((sum, entry) => sum + toInt(entry.feature_flag_used_runs), 0),
      finishedRuns,
    ),
    pr_created_runs: entries.reduce((sum, entry) => sum + toInt(entry.pr_created_runs), 0),
    pr_created_rate: rate(
      entries.reduce((sum, entry) => sum + toInt(entry.pr_created_runs), 0),
      finishedRuns,
    ),
    boundary_history_written_runs: entries.reduce((sum, entry) => sum + toInt(entry.boundary_history_written_runs), 0),
    boundary_history_written_rate: rate(
      entries.reduce((sum, entry) => sum + toInt(entry.boundary_history_written_runs), 0),
      finishedRuns,
    ),
    boundary_history_useful_runs: boundaryHistoryUsefulRuns,
    boundary_history_useful_rate: rate(boundaryHistoryUsefulRuns, finishedRuns),
    boundary_history_value_counts: {
      none: entries.reduce((sum, entry) => sum + toInt(entry.boundary_history_value_counts?.none), 0),
      irrelevant: entries.reduce((sum, entry) => sum + toInt(entry.boundary_history_value_counts?.irrelevant), 0),
      low: entries.reduce((sum, entry) => sum + toInt(entry.boundary_history_value_counts?.low), 0),
      medium: entries.reduce((sum, entry) => sum + toInt(entry.boundary_history_value_counts?.medium), 0),
      high: entries.reduce((sum, entry) => sum + toInt(entry.boundary_history_value_counts?.high), 0),
    },
  };
}

export function buildVerifySeries(rows, groupBy, dateFrom, dateTo) {
  const dailySeries = rows.map((row) => normalizeVerifySeriesEntry(row, String(row.bucket_date || ""), String(row.bucket_date || "")));
  if (groupBy !== "week") {
    return dailySeries;
  }
  const grouped = new Map();
  dailySeries.forEach((entry) => {
    const naturalWeekStart = weekStartIsoDate(entry.bucket_start);
    const bucketStart = maxIsoDate(naturalWeekStart, dateFrom);
    const bucketEnd = minIsoDate(addDaysIsoDate(naturalWeekStart, 6), dateTo);
    if (!grouped.has(naturalWeekStart)) {
      grouped.set(naturalWeekStart, {
        bucket_start: bucketStart,
        bucket_end: bucketEnd,
        entries: [],
      });
    }
    grouped.get(naturalWeekStart).entries.push(entry);
  });
  return Array.from(grouped.entries())
    .sort((left, right) => left[0].localeCompare(right[0]))
    .map(([, bucket]) => summarizeVerifySeriesBucket(bucket.bucket_start, bucket.bucket_end, bucket.entries));
}

export function buildImplementSeries(rows, groupBy, dateFrom, dateTo) {
  const dailySeries = rows.map((row) => normalizeImplementSeriesEntry(row, String(row.bucket_date || ""), String(row.bucket_date || "")));
  if (groupBy !== "week") {
    return dailySeries;
  }
  const grouped = new Map();
  dailySeries.forEach((entry) => {
    const naturalWeekStart = weekStartIsoDate(entry.bucket_start);
    const bucketStart = maxIsoDate(naturalWeekStart, dateFrom);
    const bucketEnd = minIsoDate(addDaysIsoDate(naturalWeekStart, 6), dateTo);
    if (!grouped.has(naturalWeekStart)) {
      grouped.set(naturalWeekStart, {
        bucket_start: bucketStart,
        bucket_end: bucketEnd,
        entries: [],
      });
    }
    grouped.get(naturalWeekStart).entries.push(entry);
  });
  return Array.from(grouped.entries())
    .sort((left, right) => left[0].localeCompare(right[0]))
    .map(([, bucket]) => summarizeImplementSeriesBucket(bucket.bucket_start, bucket.bucket_end, bucket.entries));
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const normalizedPath = url.pathname.replace(/\/+$/, "");

    if (
      request.method === "GET"
      && (normalizedPath === "/capabilities" || normalizedPath.endsWith("/capabilities"))
    ) {
      return jsonResponse(200, capabilitiesPayload(env));
    }

    if (request.method !== "POST") {
      return jsonResponse(405, { error: "Method not allowed." });
    }

    const payload = await readJson(request);
    if (payload === null) {
      return jsonResponse(400, { error: "Request body must be valid JSON." });
    }

    if (normalizedPath === "/stats" || normalizedPath.endsWith("/stats")) {
      const validationError = validateStatsRequest(payload);
      if (validationError) {
        return jsonResponse(400, { error: validationError });
      }

      const authToken = env.PROXY_STATS_BEARER_TOKEN;
      if (authToken) {
        const header = request.headers.get("Authorization") || "";
        if (header !== `Bearer ${authToken}`) {
          return jsonResponse(401, { error: "Missing or invalid proxy stats token." });
        }
      }

      const dateToExclusive = nextIsoDate(payload.date_to);
      const query = payload.workflow === "bill-feature-verify"
        ? buildVerifyStatsQuery(payload.date_from, dateToExclusive)
        : buildImplementStatsQuery(payload.date_from, dateToExclusive);
      const result = await runPostHogQuery(env, query);
      if (result.error) {
        const responsePayload = { error: result.error };
        if (result.details) {
          responsePayload.details = result.details;
        }
        return jsonResponse(result.status, responsePayload);
      }

      const summaryRows = queryRowsToObjects(result.payload);
      const row = summaryRows[0] || {};
      const normalized = payload.workflow === "bill-feature-verify"
        ? normalizeVerifyStats(row, payload.date_from, payload.date_to)
        : normalizeImplementStats(row, payload.date_from, payload.date_to);
      if (payload.group_by) {
        const seriesQuery = payload.workflow === "bill-feature-verify"
          ? buildVerifySeriesQuery(payload.date_from, dateToExclusive)
          : buildImplementSeriesQuery(payload.date_from, dateToExclusive);
        const seriesResult = await runPostHogQuery(env, seriesQuery);
        if (seriesResult.error) {
          const responsePayload = { error: seriesResult.error };
          if (seriesResult.details) {
            responsePayload.details = seriesResult.details;
          }
          return jsonResponse(seriesResult.status, responsePayload);
        }
        const seriesRows = queryRowsToObjects(seriesResult.payload);
        normalized.group_by = payload.group_by;
        normalized.series = payload.workflow === "bill-feature-verify"
          ? buildVerifySeries(seriesRows, payload.group_by, payload.date_from, payload.date_to)
          : buildImplementSeries(seriesRows, payload.group_by, payload.date_from, payload.date_to);
      }
      return jsonResponse(200, normalized);
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

    return forwardBatch(env, batch);
  },
};
