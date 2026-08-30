const NAMED_PREFIX_ABSOLUTE_PATH =
  /(?:[A-Za-z]:\\(?:[^\s"]+)|\/(?:home|Users|var|tmp|private|opt)\/[^\s"]+)/;

const SCHEME_OR_BARE_ABSOLUTE_PATH = /(?:^|:)(?:\/[^\s"]+|[A-Za-z]:\\[^\s"]+)/;

export function containsAbsolutePath(value: string): boolean {
  return NAMED_PREFIX_ABSOLUTE_PATH.test(value) || SCHEME_OR_BARE_ABSOLUTE_PATH.test(value);
}

export function redactAbsolutePaths(value: string, replacement = "[path]"): string {
  return value
    .replace(NAMED_PREFIX_ABSOLUTE_PATH, replacement)
    .replace(SCHEME_OR_BARE_ABSOLUTE_PATH, replacement);
}
