package dev.skillbill.runtime.buildlogic

// SKILL-55 subtask 2 (F-007): pure, side-effect-free derivation of a jpackage-legal
// package version from the Gradle project version. jpackage rejects anything that is
// not a strict numeric MAJOR.MINOR.PATCH (each component a non-negative integer; some
// platforms — notably macOS — also require the major component to be >= 1). The Gradle
// project version is `0.1.0-SNAPSHOT` (and may carry pre-release / build qualifiers),
// which jpackage will not accept verbatim, so the package version is DERIVED here.
//
// No Gradle types are referenced so this stays a trivially unit-testable pure function
// (covered by DesktopVersionsTest). The full, un-stripped `project.version` is still used
// for the canonical artifact file name (SkillBill-<fullVersion>-<os>-<arch>.<ext>); only
// the embedded jpackage `--app-version` uses this normalized form.

private const val VERSION_COMPONENTS = 3
private const val DEFAULT_COMPONENT = "0"

/**
 * Derive a jpackage-legal `MAJOR.MINOR.PATCH` version string from a raw Gradle version.
 *
 * Normalization rules (deterministic):
 * 1. Strip everything from the first `-` (pre-release, e.g. `-SNAPSHOT`, `-rc1`) and the
 *    first `+` (build metadata, e.g. `+build5`) — whichever comes first wins, so
 *    `1.2.3-rc1+build5` -> `1.2.3` and `1.2.3+build5` -> `1.2.3`.
 * 2. Split the remaining core on `.`. Keep at most three components; pad missing trailing
 *    components with `0` so `0.1` -> `0.1.0` and `1` -> `1.0.0`.
 * 3. Each component is reduced to its leading run of digits (e.g. `2beta` -> `2`). A
 *    component with no leading digits (e.g. empty, or `beta`) becomes `0`.
 * 4. The result always has exactly three dot-separated non-negative-integer components.
 *
 * This guarantees three numeric components for any input. Note jpackage may still reject a
 * `0` major on some platforms (macOS); that is a project-version concern, not this
 * function's — the rule above is documented so the behavior is predictable, and the
 * project version (`0.1.0-SNAPSHOT` -> `0.1.0`) is the live case.
 *
 * @param rawVersion the raw Gradle `project.version` string (may be blank/qualified).
 * @return a `MAJOR.MINOR.PATCH` string with three non-negative integer components.
 */
fun toJpackageVersion(rawVersion: String): String {
  val core = rawVersion.trim().substringBefore('-').substringBefore('+')
  val rawComponents = core.split('.')
  val components =
    (0 until VERSION_COMPONENTS).map { index ->
      val component = rawComponents.getOrNull(index).orEmpty()
      component.takeWhile(Char::isDigit).ifEmpty { DEFAULT_COMPONENT }
    }
  return components.joinToString(".")
}

private const val MIN_MAC_MAJOR = 1

/**
 * Derive a **macOS-legal** app version from a raw Gradle version.
 *
 * macOS jpackage (and the Compose Dmg validator, which checks eagerly at configuration
 * time) require the MAJOR component to be a strictly positive integer (`> 0`). The live
 * project version is `0.1.0-SNAPSHOT`, whose derived `0.1.0` has a `0` major — legal for
 * Linux `.deb` / `.rpm` and Windows `.msi`, but rejected for macOS `.dmg`.
 *
 * This builds on [toJpackageVersion] and, when (and only when) the major component is `0`,
 * bumps it to `1` so macOS accepts it, leaving MINOR/PATCH untouched (`0.1.0` -> `1.1.0`).
 * A non-zero major is returned unchanged. This is the documented, deterministic macOS-only
 * adjustment; the canonical artifact file name still uses the full un-stripped
 * `project.version`, and the Linux/Windows installer versions still use [toJpackageVersion]
 * directly, so this bump never leaks outside the macOS package metadata.
 *
 * Oversized-major behavior (F-003): [toJpackageVersion] applies no width cap, so a major
 * component can exceed `Int.MAX_VALUE` (e.g. raw `9999999999.0.0`). Parsing is therefore
 * non-throwing: an unparseable / oversized major deterministically CLAMPS to
 * [MIN_MAC_MAJOR] (`1`) rather than crashing. This is a defensive floor for an absurd input;
 * the live `0.1.0` -> `1.1.0` and the `0` -> `1` bump are unchanged.
 *
 * @param rawVersion the raw Gradle `project.version` string.
 * @return a macOS-legal `MAJOR.MINOR.PATCH` with MAJOR >= 1.
 */
fun toMacAppVersion(rawVersion: String): String {
  val components = toJpackageVersion(rawVersion).split('.')
  val major = (components[0].toIntOrNull() ?: MIN_MAC_MAJOR).coerceAtLeast(MIN_MAC_MAJOR)
  return "$major.${components[1]}.${components[2]}"
}
