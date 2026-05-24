package skillbill.domain.skillremove.model

/**
 * Single payload builder reused by both [SkillRemove.previewRemoval] and [SkillRemove.executeRemoval].
 * Following the same pattern as `ScaffoldPayload`/`ScaffoldRunRequest`, this lets the desktop
 * ViewModel capture the target+repo on Main BEFORE the Dispatchers.Default hop and keeps
 * preview/execute parity guaranteed by construction (no two builders to drift).
 *
 * [repoRootAbsolutePath] is the absolute, normalized path the executor will operate against.
 * The ViewModel resolves it from the currently-open repo on the Main dispatcher before invoking
 * the gateway.
 */
data class SkillRemovalRequest(
  val target: SkillRemovalTarget,
  val repoRootAbsolutePath: String,
  val userHomeAbsolutePath: String? = null,
)
