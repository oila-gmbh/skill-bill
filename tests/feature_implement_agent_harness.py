from __future__ import annotations

from skill_bill.mcp_server import (
  feature_implement_workflow_continue,
  feature_implement_workflow_get,
  feature_implement_workflow_update,
)


class FeatureImplementAgentHarness:
  """Deterministic stand-in for an AI orchestrator resuming a workflow."""

  STEP_ORDER: tuple[str, ...] = (
    "assess",
    "create_branch",
    "preplan",
    "plan",
    "implement",
    "review",
    "audit",
    "validate",
    "write_history",
    "commit_push",
    "pr_description",
    "finish",
  )

  def continue_feature_implement_workflow(self, workflow_id: str) -> dict[str, object]:
    payload = feature_implement_workflow_continue(workflow_id)
    result: dict[str, object] = {"tool_payload": payload}
    if payload["status"] != "ok":
      result["skill_call"] = None
      return result
    result["skill_call"] = self.call_skill(
      str(payload["skill_name"]),
      continuation_payload=payload,
    )
    return result

  def call_skill(
    self,
    skill_name: str,
    *,
    continuation_payload: dict[str, object],
  ) -> dict[str, object]:
    if skill_name != "bill-feature-implement":
      raise ValueError(f"Unsupported skill: {skill_name}")
    continue_status = str(continuation_payload.get("continue_status", ""))
    if continue_status == "blocked":
      raise RuntimeError("Blocked continuations must not dispatch the skill.")
    if continue_status == "done":
      return {
        "skill_name": skill_name,
        "dispatch_mode": "terminal-summary",
        "start_step_id": "finish",
        "should_restart_from_step1": False,
      }

    step_artifacts = continuation_payload.get("step_artifacts", {})
    reference_sections = continuation_payload.get("reference_sections", [])
    assert isinstance(step_artifacts, dict)
    assert isinstance(reference_sections, list)
    return {
      "skill_name": skill_name,
      "dispatch_mode": str(continuation_payload.get("continuation_mode", "")),
      "start_step_id": str(continuation_payload.get("continue_step_id", "")),
      "directive": str(continuation_payload.get("continue_step_directive", "")),
      "reference_sections": [str(value) for value in reference_sections],
      "artifact_keys": sorted(str(key) for key in step_artifacts.keys()),
      "entry_prompt": str(continuation_payload.get("continuation_entry_prompt", "")),
      "should_restart_from_step1": False,
    }

  def continue_and_complete_workflow(
    self,
    workflow_id: str,
    *,
    review_loops: int = 0,
    audit_loops: int = 0,
  ) -> dict[str, object]:
    resumed = self.continue_feature_implement_workflow(workflow_id)
    payload = resumed["tool_payload"]
    skill_call = resumed["skill_call"]
    if payload["status"] != "ok" or skill_call is None:
      resumed["executed_steps"] = []
      resumed["final_workflow"] = feature_implement_workflow_get(workflow_id)
      return resumed
    return self.complete_from_continuation_payload(
      payload,
      review_loops=review_loops,
      audit_loops=audit_loops,
    )

  def complete_from_continuation_payload(
    self,
    continuation_payload: dict[str, object],
    *,
    review_loops: int = 0,
    audit_loops: int = 0,
  ) -> dict[str, object]:
    workflow_id = str(continuation_payload["workflow_id"])
    result: dict[str, object] = {"tool_payload": continuation_payload}
    if continuation_payload["status"] != "ok":
      result["skill_call"] = None
      result["executed_steps"] = []
      result["final_workflow"] = feature_implement_workflow_get(workflow_id)
      return result

    skill_call = self.call_skill(
      str(continuation_payload["skill_name"]),
      continuation_payload=continuation_payload,
    )
    result["skill_call"] = skill_call
    executed_steps: list[str] = []
    current_step_id = str(skill_call["start_step_id"])
    remaining_review_loops = review_loops
    remaining_audit_loops = audit_loops

    while current_step_id:
      executed_steps.append(current_step_id)
      current_payload = feature_implement_workflow_get(workflow_id)
      steps = current_payload["steps"]
      assert isinstance(steps, list)
      steps_by_id = {
        str(step["step_id"]): step
        for step in steps
        if isinstance(step, dict) and "step_id" in step
      }
      current_attempt = int(steps_by_id[current_step_id]["attempt_count"])

      if current_step_id == "review" and remaining_review_loops > 0:
        remaining_review_loops -= 1
        next_attempt = int(steps_by_id["implement"]["attempt_count"]) + 1
        feature_implement_workflow_update(
          workflow_id=workflow_id,
          workflow_status="running",
          current_step_id="implement",
          step_updates=[
            {"step_id": "review", "status": "blocked", "attempt_count": current_attempt},
            {"step_id": "implement", "status": "running", "attempt_count": next_attempt},
          ],
          artifacts_patch={
            "review_result": {
              "iteration_count": review_loops - remaining_review_loops,
              "status": "needs_fix",
            }
          },
        )
        current_step_id = "implement"
        continue

      if current_step_id == "audit" and remaining_audit_loops > 0:
        remaining_audit_loops -= 1
        next_attempt = int(steps_by_id["plan"]["attempt_count"]) + 1
        feature_implement_workflow_update(
          workflow_id=workflow_id,
          workflow_status="running",
          current_step_id="plan",
          step_updates=[
            {"step_id": "audit", "status": "blocked", "attempt_count": current_attempt},
            {"step_id": "plan", "status": "running", "attempt_count": next_attempt},
          ],
          artifacts_patch={
            "audit_report": {
              "pass": False,
              "gap_count": 1,
            }
          },
        )
        current_step_id = "plan"
        continue

      if current_step_id == "finish":
        feature_implement_workflow_update(
          workflow_id=workflow_id,
          workflow_status="completed",
          current_step_id="finish",
          step_updates=[
            {"step_id": "finish", "status": "completed", "attempt_count": current_attempt},
          ],
          artifacts_patch={"terminal_note": "completed via agent harness"},
        )
        break

      next_step_id = self._next_step_id(current_step_id)
      assert next_step_id is not None
      next_attempt = max(int(steps_by_id[next_step_id]["attempt_count"]), 0) + 1
      feature_implement_workflow_update(
        workflow_id=workflow_id,
        workflow_status="running",
        current_step_id=next_step_id,
        step_updates=[
          {"step_id": current_step_id, "status": "completed", "attempt_count": current_attempt},
          {"step_id": next_step_id, "status": "running", "attempt_count": next_attempt},
        ],
        artifacts_patch=self._artifact_patch_for_step(current_step_id),
      )
      current_step_id = next_step_id

    result["executed_steps"] = executed_steps
    result["final_workflow"] = feature_implement_workflow_get(workflow_id)
    return result

  def _next_step_id(self, step_id: str) -> str | None:
    index = self.STEP_ORDER.index(step_id)
    if index + 1 >= len(self.STEP_ORDER):
      return None
    return self.STEP_ORDER[index + 1]

  def _artifact_patch_for_step(self, step_id: str) -> dict[str, object] | None:
    if step_id == "plan":
      return {"plan": {"task_count": 4, "phase_count": 1}}
    if step_id == "implement":
      return {
        "implementation_summary": {
          "tasks_completed": 4,
          "files_modified": ["skill_bill/feature_implement.py"],
        }
      }
    if step_id == "review":
      return {"review_result": {"iteration_count": 1, "status": "pass"}}
    if step_id == "audit":
      return {"audit_report": {"pass": True, "gap_count": 0}}
    if step_id == "validate":
      return {"validation_result": {"validation_result": "pass"}}
    if step_id == "write_history":
      return {"history_result": {"written": True}}
    if step_id == "pr_description":
      return {"pr_result": {"pr_created": True, "pr_url": "https://example.test/pr/1"}}
    return None
