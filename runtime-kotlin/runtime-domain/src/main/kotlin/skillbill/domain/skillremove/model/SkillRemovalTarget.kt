package skillbill.domain.skillremove.model

/**
 * Sealed input contract for the [SkillRemove] service. Models the three removal scopes the desktop
 * tree-context-menu (SKILL-46) and the `skill-bill remove` CLI command (AC7) need to address.
 *
 * - [HorizontalSkill] removes a horizontal skill plus its full platform-pack-override + agent
 *   symlink cascade described in AC3.
 * - [PlatformPack] removes the platform-pack tree, the paired pre-shell skills tree, and every
 *   agent symlink owned by either tree (AC4).
 * - [AddOn] removes a single governed add-on `.md` file inside `platform-packs/<platform>/addons/`.
 *
 * The `allowShipped` flag on [HorizontalSkill] and [PlatformPack] honors AC7: the domain service
 * refuses to delete the shipped built-in surfaces (`kotlin`, `kmp`) unless this flag is `true`.
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
   * Remove `platform-packs/<platform>/` and the paired `skills/<platform>/` pre-shell tree.
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

  companion object {
    /**
     * F-S01 / F-606: the canonical set of built-in surfaces that MUST never be deleted (the
     * `kotlin` / `kmp` pair are only deletable with `--allow-shipped`, but `.bill-shared` is
     * unconditionally protected). The desktop right-click filter and the route gate share this
     * set so the modifier layer and the route layer always agree.
     */
    val BUILT_IN_NAMES: Set<String> = setOf(".bill-shared", "kotlin", "kmp")

    /**
     * SKILL-49: every horizontal skill that begins with the `bill-` prefix is a shipped product
     * surface (`bill-code-review`, `bill-feature-implement`, `bill-quality-check`, ...). The UI
     * never opens the Delete affordance on these because `isBuiltInName` returns true; the
     * domain `enforceRefusalPolicy` mirrors the same predicate so even a CLI request without
     * `--allow-shipped` is refused. Maintainer paths that genuinely need to remove a deprecated
     * `bill-*` skill must pass `allowShipped = true` (same shape as `kotlin` / `kmp`).
     */
    const val HORIZONTAL_PRODUCT_PREFIX: String = "bill-"

    /**
     * SKILL-49: protection for the HORIZONTAL-skill axis (`SkillRemovalTarget.HorizontalSkill`).
     * Returns `true` for names in [BUILT_IN_NAMES] (so `kotlin` / `kmp` pre-shells stay safe —
     * removing them as horizontals would orphan the platform pack) and for any `bill-*` product
     * skill. Users who really want to remove the `kotlin` / `kmp` directories should use the
     * `PlatformPack` removal axis, which cascades the pre-shell tree cleanly.
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
