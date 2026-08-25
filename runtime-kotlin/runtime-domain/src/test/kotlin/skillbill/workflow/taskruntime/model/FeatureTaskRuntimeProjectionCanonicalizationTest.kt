@file:Suppress("MaxLineLength")

package skillbill.workflow.taskruntime.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeProjectionCanonicalizationTransform as Transform

class FeatureTaskRuntimeProjectionCanonicalizationTest {
  // --- task ids (task 2) ------------------------------------------------------------------------

  @Test
  fun `task id is lowercased, separator runs become one hyphen, and invalid chars are stripped`() {
    assertEquals("t1", FeatureTaskRuntimeProjectionCanonicalizer.canonicalizeTaskId("T1"))
    assertEquals("task-1", FeatureTaskRuntimeProjectionCanonicalizer.canonicalizeTaskId("Task_1"))
    assertEquals("task-1", FeatureTaskRuntimeProjectionCanonicalizer.canonicalizeTaskId("  task   1  "))
    assertEquals("task-1", FeatureTaskRuntimeProjectionCanonicalizer.canonicalizeTaskId("task__--  1"))
    assertEquals("ab1", FeatureTaskRuntimeProjectionCanonicalizer.canonicalizeTaskId("A!B@1"))
  }

  @Test
  fun `a task id that reduces to empty is left empty for the schema gate to reject`() {
    assertEquals("", FeatureTaskRuntimeProjectionCanonicalizer.canonicalizeTaskId("!!!"))
    assertEquals("", FeatureTaskRuntimeProjectionCanonicalizer.canonicalizeTaskId("   "))
  }

  @Test
  fun `declared task id and its depends_on reference canonicalize to the same value (task 3)`() {
    val produced = mapOf(
      "projection_kind" to "executable_plan",
      "tasks" to listOf(
        taskMap(taskId = "T1"),
        taskMap(taskId = "Task_2", dependsOn = listOf("T1")),
      ),
    )

    val result = FeatureTaskRuntimeProjectionCanonicalizer.canonicalize(produced)

    val tasks = result.canonical["tasks"] as List<*>
    assertEquals("t1", (tasks[0] as Map<*, *>)["task_id"])
    assertEquals("task-2", (tasks[1] as Map<*, *>)["task_id"])
    assertEquals(listOf("t1"), (tasks[1] as Map<*, *>)["depends_on"])
  }

  @Test
  fun `list order and cardinality are preserved in every id position (task 3)`() {
    val produced = mapOf(
      "tasks" to listOf(taskMap("C"), taskMap("A"), taskMap("B", dependsOn = listOf("C", "A"))),
      "completed_task_ids" to listOf("C", "A", "B"),
    )

    val result = FeatureTaskRuntimeProjectionCanonicalizer.canonicalize(produced)

    val tasks = result.canonical["tasks"] as List<*>
    assertEquals(listOf("c", "a", "b"), tasks.map { (it as Map<*, *>)["task_id"] })
    assertEquals(listOf("c", "a"), (tasks[2] as Map<*, *>)["depends_on"])
    assertEquals(listOf("c", "a", "b"), result.canonical["completed_task_ids"])
  }

  // --- compact summaries (task 2) ---------------------------------------------------------------

  @Test
  fun `compact summary collapses tab runs and strips backticks, trimming boundary whitespace`() {
    val produced = mapOf(
      "tasks" to listOf(taskMap("t1", description = "call\t\t`fn()`\tnow ")),
      "deviations" to listOf(mapOf("ref" to "AC-001", "note" to " see `x`\tand\ty ")),
    )

    val result = FeatureTaskRuntimeProjectionCanonicalizer.canonicalize(produced)

    val task = (result.canonical["tasks"] as List<*>)[0] as Map<*, *>
    assertEquals("call fn() now", task["description"])
    val deviation = (result.canonical["deviations"] as List<*>)[0] as Map<*, *>
    assertEquals("see x and y", deviation["note"])
  }

  @Test
  fun `compact summary never removes an interior line break, so a multi-line paste stays rejectable`() {
    // Collapsing CR/LF would flatten a multi-line body into a single line the schema's no-line-break
    // guard then accepts, and slide the diff marker off its line start. The interior break must survive.
    val produced = mapOf("tasks" to listOf(taskMap("t1", description = "changes:\ndiff --git a/x b/x")))

    val result = FeatureTaskRuntimeProjectionCanonicalizer.canonicalize(produced)

    val task = (result.canonical["tasks"] as List<*>)[0] as Map<*, *>
    assertEquals("changes:\ndiff --git a/x b/x", task["description"])
  }

  // --- nonBlank trims (task 2) ------------------------------------------------------------------

  @Test
  fun `nonBlank scalar and array string fields are trimmed without touching interior content`() {
    val produced = mapOf(
      "affected_boundaries" to listOf("  runtime-domain  ", "runtime-application"),
      "rollout" to mapOf("flag_required" to false, "notes" to "  no flag  "),
      "reconciliation_evidence" to mapOf("reconciled" to true, "evidence" to "  at target  "),
      "repository_checkpoint" to mapOf("fingerprint" to "  abc  ", "base_ref" to " main "),
    )

    val result = FeatureTaskRuntimeProjectionCanonicalizer.canonicalize(produced)

    assertEquals(listOf("runtime-domain", "runtime-application"), result.canonical["affected_boundaries"])
    assertEquals("no flag", (result.canonical["rollout"] as Map<*, *>)["notes"])
    assertEquals("at target", (result.canonical["reconciliation_evidence"] as Map<*, *>)["evidence"])
    val checkpoint = result.canonical["repository_checkpoint"] as Map<*, *>
    assertEquals("abc", checkpoint["fingerprint"])
    assertEquals("main", checkpoint["base_ref"])
  }

  @Test
  fun `non-string and unexpected shapes pass through untouched, never coerced or dropped`() {
    val produced = mapOf(
      "tasks" to "not-a-list",
      "completed_task_ids" to listOf("T1", 42, null),
      "rollout" to listOf("array-not-object"),
    )

    val result = FeatureTaskRuntimeProjectionCanonicalizer.canonicalize(produced)

    assertEquals("not-a-list", result.canonical["tasks"])
    assertEquals(listOf("t1", 42, null), result.canonical["completed_task_ids"])
    assertEquals(listOf("array-not-object"), result.canonical["rollout"])
  }

  // --- diagnostics (task 4) ---------------------------------------------------------------------

  @Test
  fun `an id canonicalization records the field path plus original and canonical values`() {
    val produced = mapOf("tasks" to listOf(taskMap("T1", dependsOn = listOf("T1"))))

    val records = FeatureTaskRuntimeProjectionCanonicalizer.canonicalize(produced).diagnostics

    val declaration = records.single { it.fieldPath == "tasks[0].task_id" }
    assertEquals(listOf(Transform.TASK_ID_NORMALIZED), declaration.transforms)
    assertEquals("T1", declaration.originalId)
    assertEquals("t1", declaration.canonicalId)
    val reference = records.single { it.fieldPath == "tasks[0].depends_on[0]" }
    assertEquals("t1", reference.canonicalId)
  }

  @Test
  fun `a compact-summary canonicalization records the field path and transform kinds but never the text`() {
    val produced = mapOf("tasks" to listOf(taskMap("t1", description = "call\t`fn`\tnow ")))

    val record = FeatureTaskRuntimeProjectionCanonicalizer.canonicalize(produced).diagnostics
      .single { it.fieldPath == "tasks[0].description" }

    assertEquals(
      listOf(Transform.TABS_TO_SPACE, Transform.BACKTICKS_STRIPPED, Transform.TRIMMED),
      record.transforms,
    )
    assertNull(record.originalId, "a compact-summary record must not carry the field text")
    assertNull(record.canonicalId, "a compact-summary record must not carry the field text")
  }

  @Test
  fun `an already-canonical projection produces no diagnostics`() {
    val produced = mapOf(
      "tasks" to listOf(taskMap("task-1", dependsOn = emptyList(), description = "add contract")),
      "affected_boundaries" to listOf("runtime-domain"),
    )

    assertTrue(FeatureTaskRuntimeProjectionCanonicalizer.canonicalize(produced).diagnostics.isEmpty())
  }

  @Test
  fun `the record count and recorded id length are bounded regardless of projection size`() {
    val manyTasks = (1..(MAX_CANONICALIZATION_RECORDS + 50)).map { taskMap("T$it") }
    val produced = mapOf("tasks" to manyTasks)

    val records = FeatureTaskRuntimeProjectionCanonicalizer.canonicalize(produced).diagnostics

    assertTrue(records.size <= MAX_CANONICALIZATION_RECORDS, "record count must be capped")
    val longId = "T".repeat(MAX_RECORDED_ID_LENGTH + 40)
    val longRecord = FeatureTaskRuntimeProjectionCanonicalizer
      .canonicalize(mapOf("tasks" to listOf(taskMap(longId)))).diagnostics.single()
    assertTrue(
      (longRecord.originalId?.length ?: 0) <= MAX_RECORDED_ID_LENGTH,
      "a recorded id value must be length-bounded",
    )
  }

  // --- SKILL-152 AC-005: the scoped unknown-key discard -----------------------------------------

  @Test
  fun `a bare evidence string is promoted to the declared reconciliation_evidence object`() {
    val produced = mapOf("reconciliation_evidence" to "  Read-only sweep after the last edit: 0 hits.  ")

    val result = FeatureTaskRuntimeProjectionCanonicalizer.canonicalize(produced)

    assertEquals(
      mapOf("reconciled" to true, "evidence" to "Read-only sweep after the last edit: 0 hits."),
      result.canonical["reconciliation_evidence"] as Map<*, *>,
    )
    val record = result.diagnostics.single { it.fieldPath == "reconciliation_evidence" }
    assertEquals(listOf(Transform.SCALAR_PROMOTED_TO_OBJECT), record.transforms)
    assertNull(record.originalId, "a promotion must never record the producer's evidence text")
    assertNull(record.canonicalId)
  }

  @Test
  fun `a blank evidence string is left for the schema gate rather than promoted to a reconciled claim`() {
    val result = FeatureTaskRuntimeProjectionCanonicalizer.canonicalize(
      mapOf("reconciliation_evidence" to "   "),
    )

    assertEquals("   ", result.canonical["reconciliation_evidence"])
    assertTrue(result.diagnostics.none { it.fieldPath == "reconciliation_evidence" })
  }

  @Test
  fun `a non-scalar reconciliation_evidence is never promoted`() {
    val alreadyShaped = FeatureTaskRuntimeProjectionCanonicalizer.canonicalize(
      mapOf("reconciliation_evidence" to mapOf("reconciled" to true, "evidence" to "at target")),
    )
    val wrongContainer = FeatureTaskRuntimeProjectionCanonicalizer.canonicalize(
      mapOf("reconciliation_evidence" to listOf("at target")),
    )

    assertTrue(
      alreadyShaped.diagnostics.none {
        it.transforms.contains(Transform.SCALAR_PROMOTED_TO_OBJECT)
      },
    )
    assertEquals(listOf("at target"), wrongContainer.canonical["reconciliation_evidence"])
  }

  /**
   * The observed defect: the discard stripped `notes` and the schema then rejected the object for the
   * required field `notes` was. In-cap, correct prose was deleted and reported missing.
   */
  @Test
  fun `a lone misnamed prose key is adopted as evidence instead of being discarded`() {
    val produced = mapOf(
      "reconciliation_evidence" to mapOf(
        "reconciled" to true,
        "notes" to "  Resumed implement phase; tasks 1-7 confirmed at target and treated as no-ops.  ",
      ),
    )

    val result = FeatureTaskRuntimeProjectionCanonicalizer.canonicalize(produced)

    assertEquals(
      mapOf(
        "reconciled" to true,
        "evidence" to "Resumed implement phase; tasks 1-7 confirmed at target and treated as no-ops.",
      ),
      result.canonical["reconciliation_evidence"] as Map<*, *>,
    )
    val record = result.diagnostics.single { it.fieldPath == "reconciliation_evidence.evidence" }
    assertEquals(listOf(Transform.MISNAMED_KEY_ADOPTED), record.transforms)
    assertNull(record.originalId, "an adoption must never record the producer's prose")
  }

  @Test
  fun `two unknown keys are an ambiguity no rename resolves, so both are discarded`() {
    val produced = mapOf(
      "reconciliation_evidence" to mapOf(
        "reconciled" to true,
        "method" to "git status and read-only greps",
        "observations" to "tree converged",
      ),
    )

    val result = FeatureTaskRuntimeProjectionCanonicalizer.canonicalize(produced)

    assertEquals(mapOf("reconciled" to true), result.canonical["reconciliation_evidence"] as Map<*, *>)
    assertTrue(result.diagnostics.none { it.transforms.contains(Transform.MISNAMED_KEY_ADOPTED) })
  }

  @Test
  fun `a stated evidence is never replaced by a misnamed sibling`() {
    val produced = mapOf(
      "reconciliation_evidence" to mapOf(
        "reconciled" to true,
        "evidence" to "the stated evidence",
        "notes" to "a sibling that must not win",
      ),
    )

    val result = FeatureTaskRuntimeProjectionCanonicalizer.canonicalize(produced)

    val evidence = result.canonical["reconciliation_evidence"] as Map<*, *>
    assertEquals("the stated evidence", evidence["evidence"])
    assertTrue(result.diagnostics.none { it.transforms.contains(Transform.MISNAMED_KEY_ADOPTED) })
  }

  @Test
  fun `a non-string or blank misnamed value is discarded rather than adopted`() {
    val nonString = FeatureTaskRuntimeProjectionCanonicalizer.canonicalize(
      mapOf("reconciliation_evidence" to mapOf("reconciled" to true, "observations" to listOf("a", "b"))),
    )
    val blank = FeatureTaskRuntimeProjectionCanonicalizer.canonicalize(
      mapOf("reconciliation_evidence" to mapOf("reconciled" to true, "notes" to "   ")),
    )

    assertEquals(mapOf("reconciled" to true), nonString.canonical["reconciliation_evidence"] as Map<*, *>)
    assertEquals(mapOf("reconciled" to true), blank.canonical["reconciliation_evidence"] as Map<*, *>)
    assertTrue(
      (nonString.diagnostics + blank.diagnostics).none { it.transforms.contains(Transform.MISNAMED_KEY_ADOPTED) },
    )
  }

  /**
   * Adoption is per call site precisely so this cannot happen: `deviation` requires `ref` and `note`,
   * and a general single-unknown-key rule would file a sentence as an identifier.
   */
  @Test
  fun `adoption never reaches an object whose missing required field is an identifier`() {
    val produced = mapOf(
      "deviations" to listOf(mapOf("note" to "what deviated", "reason" to "a sentence, not a ref")),
    )

    val result = FeatureTaskRuntimeProjectionCanonicalizer.canonicalize(produced)

    val deviation = (result.canonical["deviations"] as List<*>).single() as Map<*, *>
    assertEquals(mapOf("note" to "what deviated"), deviation)
    assertTrue(result.diagnostics.none { it.transforms.contains(Transform.MISNAMED_KEY_ADOPTED) })
  }

  @Test
  fun `an unknown key on a nested closed object is discarded and recorded without its value`() {
    val produced = mapOf(
      "reconciliation_evidence" to mapOf(
        "reconciled" to true,
        "evidence" to "tree at target",
        "confidence" to "a private body fragment",
      ),
    )

    val result = FeatureTaskRuntimeProjectionCanonicalizer.canonicalize(produced)

    val evidence = result.canonical["reconciliation_evidence"] as Map<*, *>
    assertEquals(mapOf("reconciled" to true, "evidence" to "tree at target"), evidence)
    val record = result.diagnostics.single { it.fieldPath == "reconciliation_evidence.confidence" }
    assertEquals(listOf(Transform.UNKNOWN_KEY_DISCARDED), record.transforms)
    assertNull(record.originalId, "a discard must never record the discarded key's value")
    assertNull(record.canonicalId)
  }

  @Test
  fun `the discard reaches every nested closed object and leaves governed fields intact`() {
    val produced = mapOf(
      "tasks" to listOf(
        mapOf(
          "task_id" to "task-1",
          "description" to "d",
          "criterion_refs" to listOf("AC-001"),
          "test_obligations" to listOf("t"),
          "estimate" to "2d",
        ),
      ),
      "task_commitments" to listOf(
        mapOf(
          "task_id" to "task-1",
          "criterion_refs" to listOf("AC-001"),
          "test_obligations" to listOf("t"),
          "owner" to "me",
        ),
      ),
      "tests_executed" to listOf(mapOf("name" to "FooTest", "outcome" to "passed", "duration_ms" to 12)),
      "deviations" to listOf(mapOf("ref" to "AC-001", "note" to "n", "severity" to "minor")),
      "rollout" to mapOf("flag_required" to false, "notes" to "n", "owner" to "me"),
      "repository_checkpoint" to mapOf("fingerprint" to "abc", "dirty" to true),
    )

    val result = FeatureTaskRuntimeProjectionCanonicalizer.canonicalize(produced)

    val discarded = result.diagnostics
      .filter { it.transforms == listOf(Transform.UNKNOWN_KEY_DISCARDED) }
      .map { it.fieldPath }
    assertEquals(
      listOf(
        "tasks[0].estimate",
        "task_commitments[0].owner",
        "tests_executed[0].duration_ms",
        "deviations[0].severity",
        "rollout.owner",
        "repository_checkpoint.dirty",
      ).sorted(),
      discarded.sorted(),
    )
    // Every governed field survives the prune.
    val task = (result.canonical["tasks"] as List<*>).single() as Map<*, *>
    assertEquals(setOf("task_id", "description", "criterion_refs", "test_obligations"), task.keys)
    val checkpoint = result.canonical["repository_checkpoint"] as Map<*, *>
    assertEquals("abc", checkpoint["fingerprint"])
  }

  @Test
  fun `the discard never synthesizes a missing field nor coerces a type`() {
    val produced = mapOf(
      // A required governed field is absent and an unknown key is present: only the unknown key goes.
      "reconciliation_evidence" to mapOf("reconciled" to true, "extra" to 1),
      // A wrong-typed governed field keeps its wrong type for the schema to reject.
      "rollout" to mapOf("flag_required" to "yes", "notes" to "n", "extra" to 1),
    )

    val result = FeatureTaskRuntimeProjectionCanonicalizer.canonicalize(produced)

    val evidence = result.canonical["reconciliation_evidence"] as Map<*, *>
    assertEquals(mapOf("reconciled" to true), evidence, "no field may be synthesized to satisfy the schema")
    val rollout = result.canonical["rollout"] as Map<*, *>
    assertEquals("yes", rollout["flag_required"], "a wrong-typed governed field must not be coerced")
  }

  @Test
  fun `a declared co-resident survives while an undeclared top-level key is discarded`() {
    // The co-residents another contract requires on the same output are declared properties of the
    // variant, so the top-level prune retains them; only a key no variant declares goes.
    val produced = mapOf(
      "projection_kind" to "implementation_receipt",
      "contract_version" to "0.1",
      "repair_item_results" to listOf(mapOf("repair_item_id" to "ac-001-gap-1-item-1")),
      "deferred_repair_item_ids" to listOf("ac-001-gap-1-item-2"),
      "unresolvable_repair" to mapOf("reason" to "blocked"),
      "narration" to "how the work went",
    )

    val result = FeatureTaskRuntimeProjectionCanonicalizer.canonicalize(produced)

    assertEquals(produced.keys - "narration", result.canonical.keys)
    assertEquals(
      listOf("implementation_receipt.narration"),
      result.diagnostics
        .filter { it.transforms == listOf(Transform.UNKNOWN_KEY_DISCARDED) }
        .map { it.fieldPath },
    )
  }

  @Test
  fun `the goal-planning shared context survives the preplanning digest prune`() {
    val sharedContext = mapOf("goal_id" to "SKILL-152")
    val produced = mapOf(
      "projection_kind" to "preplanning_digest",
      "contract_version" to "0.1",
      "_goal_planning_shared_context" to sharedContext,
      "presentation_summary" to "prose the consumer never asked for",
    )

    val result = FeatureTaskRuntimeProjectionCanonicalizer.canonicalize(produced)

    assertEquals(sharedContext, result.canonical["_goal_planning_shared_context"])
    assertEquals("preplanning_digest", result.canonical["projection_kind"])
    assertEquals("0.1", result.canonical["contract_version"])
    assertTrue("presentation_summary" !in result.canonical.keys)
  }

  @Test
  fun `an unrecognized projection kind prunes nothing`() {
    // Guessing a variant for an unknown kind could delete a declared field; the schema's oneOf rejects it.
    val produced = mapOf("projection_kind" to "something_else", "narration" to "kept")

    val result = FeatureTaskRuntimeProjectionCanonicalizer.canonicalize(produced)

    assertEquals(produced.keys, result.canonical.keys)
    assertTrue(result.diagnostics.none { it.transforms.contains(Transform.UNKNOWN_KEY_DISCARDED) })
  }

  @Test
  fun `a non-string-keyed nested object is never pruned`() {
    val produced = mapOf("reconciliation_evidence" to mapOf(1 to "not-a-string-key"))

    val result = FeatureTaskRuntimeProjectionCanonicalizer.canonicalize(produced)

    assertTrue(
      result.diagnostics.none { it.transforms.contains(Transform.UNKNOWN_KEY_DISCARDED) },
      "the discard applies only to a string-keyed view",
    )
  }

  @Test
  fun `a discarded key name is length-bounded in the record`() {
    val longKey = "k".repeat(MAX_RECORDED_ID_LENGTH + 40)
    // `evidence` is stated so prose adoption cannot claim the long key: this pins the discard path.
    val produced = mapOf(
      "reconciliation_evidence" to mapOf("reconciled" to true, "evidence" to "stated", longKey to "v"),
    )

    val record = FeatureTaskRuntimeProjectionCanonicalizer.canonicalize(produced).diagnostics
      .single { it.transforms == listOf(Transform.UNKNOWN_KEY_DISCARDED) }

    assertTrue(
      record.fieldPath.length <= "reconciliation_evidence.".length + MAX_RECORDED_ID_LENGTH,
      "a recorded discarded key must be length-bounded: ${record.fieldPath.length}",
    )
  }

  private fun taskMap(taskId: String, dependsOn: List<String>? = null, description: String = "d"): Map<String, Any?> =
    buildMap {
      put("task_id", taskId)
      if (dependsOn != null) put("depends_on", dependsOn)
      put("description", description)
      put("criterion_refs", listOf("AC-001"))
      put("test_obligations", listOf("t"))
    }
}
