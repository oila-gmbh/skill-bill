package skillbill.domain.skillremove.model

/** Typed refusal codes paired with [skillbill.domain.skillremove.SkillRemovalRefusedException]. */
enum class SkillRemovalRefusalReason {
  /** `.bill-shared` is never deletable. */
  BILL_SHARED_PROTECTED,

  /** A shipped built-in (`kotlin`, `kmp`) was requested without `allowShipped = true`. */
  SHIPPED_REQUIRES_ALLOW_SHIPPED,

  /**
   * F-S01: target identifier failed input validation (blank, traversal-prone, or outside the
   * allow-listed character set). Used by both the CLI and the desktop dialog so attackers can't
   * point removal at `/etc/passwd` via `Path.resolve("/etc/passwd")` semantics.
   */
  INVALID_TARGET,
}
