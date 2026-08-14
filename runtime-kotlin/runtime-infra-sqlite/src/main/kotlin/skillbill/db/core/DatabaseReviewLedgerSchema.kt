package skillbill.db.core

/**
 * Review-ledger DDL, held apart from [DatabaseSchema] so the base-schema object stays within its
 * size budget. Each list is shared verbatim between the base schema and its named migration so an
 * existing store and a fresh one converge on one definition.
 */
internal object DatabaseReviewLedgerSchema {
  // Per-lane review attribution.
  val reviewRunLaneStatements: List<String> =
    listOf(
      """
      CREATE TABLE IF NOT EXISTS review_run_lanes (
        review_run_id TEXT NOT NULL,
        lane_skill_name TEXT NOT NULL,
        pack_slug TEXT NOT NULL,
        area TEXT NOT NULL,
        depth INTEGER NOT NULL DEFAULT 0,
        required INTEGER NOT NULL DEFAULT 0,
        order_index INTEGER NOT NULL DEFAULT 0,
        origin_layer_chain TEXT NOT NULL DEFAULT '',
        resolution_state TEXT NOT NULL DEFAULT 'resolved'
          CHECK (resolution_state IN ('resolved', 'unresolved')),
        review_disposition TEXT NOT NULL DEFAULT 'incomplete',
        bundle_composition_digest TEXT,
        segment_accounting_json TEXT,
        unreviewed_segment_ids TEXT NOT NULL DEFAULT '',
        budget_dimension TEXT,
        recorded_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
        PRIMARY KEY (review_run_id, lane_skill_name),
        FOREIGN KEY (review_run_id) REFERENCES review_runs(review_run_id) ON DELETE CASCADE
      )
      """.trimIndent(),
      """
      CREATE INDEX IF NOT EXISTS idx_review_run_lanes_pack_area
        ON review_run_lanes(pack_slug, area, review_run_id)
      """.trimIndent(),
      // Finding-to-lane attribution the runtime records from its own merge result, before the
      // review text is imported. It is the authoritative source for a finding's producing lane;
      // parsed provenance is only the fallback for externally supplied review text.
      """
      CREATE TABLE IF NOT EXISTS review_run_finding_lanes (
        review_run_id TEXT NOT NULL,
        finding_id TEXT NOT NULL,
        lane_skill_name TEXT NOT NULL,
        recorded_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
        PRIMARY KEY (review_run_id, finding_id),
        FOREIGN KEY (review_run_id) REFERENCES review_runs(review_run_id) ON DELETE CASCADE
      )
      """.trimIndent(),
    )

  /**
   * The shared finding key joining the workflow review loop to review-run import. It is a table of
   * its own rather than columns on `unaddressed_findings` because that ledger is retracted
   * (`replaceLedgerForPass`, `clearWorkflowLedger` both DELETE), so an outcome recorded on it would
   * be destroyed by the next pass. There is deliberately no foreign key to `findings`:
   * workflow-loop findings need not have been imported as a review run, and `review_run_id` stays
   * NULL (`key_state = 'unresolved'`) in exactly that case rather than being guessed.
   */
  val reviewFindingOutcomeStatements: List<String> =
    listOf(
      """
      CREATE TABLE IF NOT EXISTS review_finding_outcomes (
        workflow_id TEXT NOT NULL,
        review_pass_number INTEGER NOT NULL,
        finding_ordinal INTEGER NOT NULL,
        review_run_id TEXT,
        finding_id TEXT,
        -- Content-derived cross-pass identity. The finding id above is per-run positional, so it
        -- cannot match a finding to the same finding in a later pass; this column can.
        finding_key TEXT,
        key_state TEXT NOT NULL DEFAULT 'unresolved'
          CHECK (key_state IN ('resolved', 'unresolved')),
        outcome TEXT NOT NULL CHECK (outcome IN ('addressed', 'carried', 'rejected')),
        recorded_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
        PRIMARY KEY (workflow_id, review_pass_number, finding_ordinal),
        CHECK ((key_state = 'resolved') = (review_run_id IS NOT NULL AND finding_id IS NOT NULL))
      )
      """.trimIndent(),
      """
      CREATE INDEX IF NOT EXISTS idx_review_finding_outcomes_run
        ON review_finding_outcomes(review_run_id, finding_id)
      """.trimIndent(),
    )

  val reviewStageStateStatements: List<String> =
    listOf(
      """
      CREATE TABLE IF NOT EXISTS review_run_finding_verdicts (
        review_run_id TEXT NOT NULL,
        finding_id TEXT NOT NULL,
        stage TEXT NOT NULL CHECK (stage IN ('verification', 'adjudication')),
        claim_verdict TEXT NOT NULL CHECK (claim_verdict IN ('confirmed', 'refuted', 'unresolved')),
        scope_disposition TEXT CHECK (
          scope_disposition IS NULL OR scope_disposition IN (
            'in_scope', 'out_of_scope_preexisting', 'spec_deviation', 'spec_accepted_tradeoff'
          )
        ),
        citations TEXT NOT NULL DEFAULT '',
        severity_adjustment_direction TEXT CHECK (
          severity_adjustment_direction IS NULL OR severity_adjustment_direction IN ('raise', 'lower')
        ),
        severity_adjustment_justification TEXT,
        recorded_at TEXT NOT NULL,
        contract_version TEXT NOT NULL,
        rejection_reason TEXT,
        PRIMARY KEY (review_run_id, finding_id, stage),
        FOREIGN KEY (review_run_id) REFERENCES review_runs(review_run_id) ON DELETE CASCADE
      )
      """.trimIndent(),
      """
      CREATE INDEX IF NOT EXISTS idx_review_run_finding_verdicts_run
        ON review_run_finding_verdicts(review_run_id)
      """.trimIndent(),
      """
      CREATE TABLE IF NOT EXISTS review_run_stage_boundaries (
        review_run_id TEXT NOT NULL,
        stage TEXT NOT NULL CHECK (stage IN ('review', 'verification', 'adjudication')),
        reached TEXT NOT NULL CHECK (reached IN ('reached', 'not_reached')),
        recorded_at TEXT NOT NULL,
        contract_version TEXT NOT NULL,
        PRIMARY KEY (review_run_id, stage),
        FOREIGN KEY (review_run_id) REFERENCES review_runs(review_run_id) ON DELETE CASCADE
      )
      """.trimIndent(),
      """
      CREATE INDEX IF NOT EXISTS idx_review_run_stage_boundaries_run
        ON review_run_stage_boundaries(review_run_id)
      """.trimIndent(),
      """
      CREATE TABLE IF NOT EXISTS review_run_spec_projections (
        review_run_id TEXT PRIMARY KEY,
        spec_path TEXT,
        content_digest TEXT,
        absence_reason TEXT,
        recorded_at TEXT NOT NULL,
        FOREIGN KEY (review_run_id) REFERENCES review_runs(review_run_id) ON DELETE CASCADE,
        CHECK (
          (absence_reason IS NOT NULL AND spec_path IS NULL AND content_digest IS NULL) OR
          (absence_reason IS NULL AND spec_path IS NOT NULL AND content_digest IS NOT NULL)
        )
      )
      """.trimIndent(),
    )
}
