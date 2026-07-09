import { describe, it } from "node:test";
import assert from "node:assert/strict";
import {
  validateStatsRequest,
  capabilitiesPayload,
  transformBatch,
  buildVerifyStatsQuery,
  buildVerifySeriesQuery,
  buildImplementStatsQuery,
  buildImplementSeriesQuery,
} from "./worker.js";

const VALID_DATE_RANGE = { date_from: "2026-05-01", date_to: "2026-06-01" };
const INGEST_SCHEMA_ERROR_FRAGMENT = "event_name must be the constant value";

describe("validateStatsRequest", () => {
  it("returns null for bill-feature-task (advertised workflow)", () => {
    const err = validateStatsRequest({ workflow: "bill-feature-task", ...VALID_DATE_RANGE });
    assert.equal(err, null);
  });

  it("returns null for bill-feature-verify (advertised workflow)", () => {
    const err = validateStatsRequest({ workflow: "bill-feature-verify", ...VALID_DATE_RANGE });
    assert.equal(err, null);
  });

  it("returns clean rejection for an unknown workflow", () => {
    const err = validateStatsRequest({ workflow: "bill-unknown-workflow", ...VALID_DATE_RANGE });
    assert.ok(err, "should return an error string");
    assert.match(err, /workflow must be one of/);
    assert.ok(!err.includes(INGEST_SCHEMA_ERROR_FRAGMENT), "must not return the ingest-schema error");
  });

  it("returns clean rejection for bill-feature-goal (unsupported workflow)", () => {
    const err = validateStatsRequest({ workflow: "bill-feature-goal", ...VALID_DATE_RANGE });
    assert.ok(err, "should return an error string");
    assert.match(err, /workflow must be one of/);
    assert.ok(!err.includes(INGEST_SCHEMA_ERROR_FRAGMENT), "must not return the ingest-schema error");
  });

  it("returns clean rejection for feature-task-runtime (unsupported workflow)", () => {
    const err = validateStatsRequest({ workflow: "feature-task-runtime", ...VALID_DATE_RANGE });
    assert.ok(err, "should return an error string");
    assert.match(err, /workflow must be one of/);
    assert.ok(!err.includes(INGEST_SCHEMA_ERROR_FRAGMENT), "must not return the ingest-schema error");
  });

  it("rejects invalid date_from format", () => {
    const err = validateStatsRequest({ workflow: "bill-feature-task", date_from: "01-05-2026", date_to: "2026-06-01" });
    assert.match(err, /YYYY-MM-DD/);
  });

  it("rejects date_from after date_to", () => {
    const err = validateStatsRequest({ workflow: "bill-feature-task", date_from: "2026-06-01", date_to: "2026-05-01" });
    assert.match(err, /on or before/);
  });
});

describe("capabilitiesPayload", () => {
  const fullEnv = {
    POSTHOG_API_KEY: "key",
    POSTHOG_PERSONAL_API_KEY: "personal-key",
    POSTHOG_PROJECT_ID: "12345",
  };

  it("advertises bill-feature-task and bill-feature-verify when stats is configured", () => {
    const caps = capabilitiesPayload(fullEnv);
    assert.deepEqual(caps.supported_workflows, ["bill-feature-verify", "bill-feature-task"]);
    assert.equal(caps.supports_stats, true);
  });

  it("advertises empty supported_workflows when stats is not configured", () => {
    const caps = capabilitiesPayload({ POSTHOG_API_KEY: "key" });
    assert.deepEqual(caps.supported_workflows, []);
    assert.equal(caps.supports_stats, false);
  });
});

describe("prose stats queries union legacy and renamed event names", () => {
  const RANGE = ["2026-05-01", "2026-06-01"];

  it("stats query reads both the legacy implement and the renamed prose started events", () => {
    const query = buildImplementStatsQuery(...RANGE);
    assert.ok(query.includes("skillbill_feature_implement_started"), "legacy started name must count");
    assert.ok(query.includes("skillbill_feature_task_prose_started"), "renamed started name must count");
    assert.ok(query.includes("skillbill_feature_implement_finished"), "legacy finished name must count");
    assert.ok(query.includes("skillbill_feature_task_prose_finished"), "renamed finished name must count");
  });

  it("series query reads both the legacy implement and the renamed prose events", () => {
    const query = buildImplementSeriesQuery(...RANGE);
    assert.ok(query.includes("skillbill_feature_implement_started"), "legacy started name must count");
    assert.ok(query.includes("skillbill_feature_task_prose_started"), "renamed started name must count");
    assert.ok(query.includes("skillbill_feature_implement_finished"), "legacy finished name must count");
    assert.ok(query.includes("skillbill_feature_task_prose_finished"), "renamed finished name must count");
  });
});

describe("stats queries default to production installs", () => {
  const RANGE = ["2026-05-01", "2026-06-01"];

  [
    ["verify stats", () => buildVerifyStatsQuery(...RANGE)],
    ["verify series", () => buildVerifySeriesQuery(...RANGE)],
    ["implement stats", () => buildImplementStatsQuery(...RANGE)],
    ["implement series", () => buildImplementSeriesQuery(...RANGE)],
  ].forEach(([name, buildQuery]) => {
    it(`${name} excludes null blank and test install ids`, () => {
      const query = buildQuery();
      assert.ok(query.includes("properties.install_id IS NOT NULL"), "null install ids must be excluded");
      assert.ok(query.includes("trim(toString(properties.install_id)) != ''"), "blank install ids must be excluded");
      assert.ok(query.includes("toString(properties.install_id) != 'test-install-id'"), "test install ids must be excluded");
    });
  });
});

describe("transformBatch", () => {
  const baseEvent = (event, props = {}) => ({
    event,
    distinct_id: "user-1",
    properties: props,
  });

  it("renames skillbill_runtime_exception to $exception", () => {
    const batch = [baseEvent("skillbill_runtime_exception", { error_type: "RuntimeException", error_message: "bad" })];
    const result = transformBatch(batch);
    assert.equal(result[0].event, "$exception");
  });

  it("maps error_type to $exception_type and error_message to $exception_message", () => {
    const batch = [baseEvent("skillbill_runtime_exception", { error_type: "IllegalStateException", error_message: "state error" })];
    const result = transformBatch(batch);
    assert.equal(result[0].properties.$exception_type, "IllegalStateException");
    assert.equal(result[0].properties.$exception_message, "state error");
  });

  it("preserves original workflow_phase in transformed event properties", () => {
    const batch = [baseEvent("skillbill_runtime_exception", { workflow_phase: "my_tool", error_type: "RuntimeException", error_message: "fail" })];
    const result = transformBatch(batch);
    assert.equal(result[0].properties.workflow_phase, "my_tool");
  });

  it("does not transform non-exception events", () => {
    const batch = [
      baseEvent("skillbill_feature_implement_started", { session_id: "fis-123" }),
      baseEvent("skillbill_runtime_exception", { error_type: "RuntimeException", error_message: "oops" }),
    ];
    const result = transformBatch(batch);
    assert.equal(result[0].event, "skillbill_feature_implement_started");
    assert.equal(result[1].event, "$exception");
  });
});
