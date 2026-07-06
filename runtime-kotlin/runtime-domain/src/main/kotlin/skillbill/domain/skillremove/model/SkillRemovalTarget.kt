package skillbill.domain.skillremove.model

/**
 * Sealed input contract for the [SkillRemove] service. Models the three removal scopes the desktop
 * tree-context-menu (SKILL-46) and the `skill-bill remove` CLI command (AC7) need to address.
 *
 * - [HorizontalSkill] removes a horizontal skill plus its full platform-pack-override + agent
 *   symlink cascade described in AC3.
 * - [PlatformPack] removes the platform-pack tree and every agent symlink owned by it (AC4).
 * - [AddOn] removes a single governed add-on `.md` file inside `platform-packs/<platform>/addons/`.
 * - [ExternalAddOn] removes a single governed add-on `.md` file from a registered external add-on source.
 *
 * The `allowShipped` flag on [HorizontalSkill] honors AC7: the domain service refuses to delete
 * shipped `bill-*` product skills unless this flag is `true`.
 */
sealed class SkillRemovalTarget {
  /**
   * Remove the horizontal skill at `skills/<skillName>/` plus every `bill-<platform>-<skillName>`
   * override (and `bill-<platform>-<skillName>-<area>` specialists) discovered under
   * `platform-packs/<platform>/code-review/` and `platform-packs/<platform>/quality-check/`.
   */
  data class HorizontalSkill(
    val skillName: String,
    val allowShipped: Boolean = false,
  ) : SkillRemovalTarget()

  /**
   * Remove `platform-packs/<platform>/`.
   */
  data class PlatformPack(
    val platform: String,
    val allowShipped: Boolean = false,
  ) : SkillRemovalTarget()

  /**
   * Remove a governed add-on file. `relativePath` is the repo-root-relative path to the `.md`,
   * e.g. `platform-packs/kmp/addons/my-addon.md`.
   */
  data class AddOn(
    val relativePath: String,
  ) : SkillRemovalTarget()

  data class ExternalAddOn(
    val sourceRootAbsolutePath: String,
    val platform: String,
    val fileName: String,
  ) : SkillRemovalTarget()

  companion object {
    /**
     * F-S01 / F-606: the canonical built-in surface that MUST never be deleted. The desktop
     * right-click filter and the route gate share this set so the modifier layer and the route
     * layer always agree.
     */
    val BUILT_IN_NAMES: Set<String> = setOf(".bill-shared")

    /**
     * SKILL-49: every horizontal skill that begins with the `bill-` prefix is a shipped product
     * surface (`bill-code-review`, `bill-feature-task`, `bill-code-check`, ...). The UI
     * never opens the Delete affordance on these because `isBuiltInName` returns true; the
     * domain `enforceRefusalPolicy` mirrors the same predicate so even a CLI request without
     * `--allow-shipped` is refused. Maintainer paths that genuinely need to remove a deprecated
     * `bill-*` skill must pass `allowShipped = true`.
     */
    const val HORIZONTAL_PRODUCT_PREFIX: String = "bill-"

    /**
     * SKILL-49: protection for the HORIZONTAL-skill axis (`SkillRemovalTarget.HorizontalSkill`).
     * Returns `true` for names in [BUILT_IN_NAMES] and for any `bill-*` product skill.
     */
    fun isProtectedHorizontalName(name: String): Boolean =
      name in BUILT_IN_NAMES || name.startsWith(HORIZONTAL_PRODUCT_PREFIX)

    /**
     * SKILL-49: protection for the PLATFORM-PACK axis (`SkillRemovalTarget.PlatformPack`). Only
     * `.bill-shared` is unconditionally protected here — shipped first-party packs (`kotlin`,
     * `kmp`) are user-removable because platform packs are the user-extension surface (forks may
     * remove packs they do not use).
     */
    fun isProtectedPlatformName(name: String): Boolean = name == ".bill-shared"

    /**
     * Generic axis-agnostic predicate. Retained for callers that color the UI tree without
     * knowing whether the node will resolve to a `HorizontalSkill` or `PlatformPack` target.
     * For gate logic prefer the axis-specific predicates above.
     */
    fun isBuiltInName(name: String): Boolean = name in BUILT_IN_NAMES || name.startsWith(HORIZONTAL_PRODUCT_PREFIX)
  }
}
