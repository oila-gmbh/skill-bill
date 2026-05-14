# Subtask 11: Open Compare URL in System Browser

Status: Draft

## Parent Context

This subtask belongs to
`docs/desktop-skill-bill-app/ui-feature-implementation-specs.md`. It is a small
follow-up to subtask 06 (Commit, Push, and Fork Publishing): the
post-push `compareUrl` is currently rendered as selectable monospace text, so
the only way to use it is to manually select and copy the string. The Push
panel is the natural exit point to a hosted PR creation flow and should open
that URL in the user's default browser.

## UI Entry Points

- Push panel `compareUrl` row inside the Changes dock tab.

## Goal

Make the post-push compare URL a real link: clicking it opens the URL in the
user's system browser, with a safe fallback to copy when the platform refuses
the browse request.

## Scope

- Add a `BrowserLauncher` service in `core/common` (or wherever
  platform-side process orchestration lives) with a `commonMain` interface
  and a `jvmMain` actual that delegates to
  `java.awt.Desktop.getDesktop().browse(URI(url))`.
- Wire the launcher through DI into `SkillBillRoute` and pass a new
  `onOpenCompareUrl: (String) -> Unit` callback down to the Push panel.
- The compare URL row keeps `SelectionContainer` for accessibility and
  manual copy, but gains a `Role.Button`, hover affordance, and clipboard
  fallback. The clipboard fallback fires only when the browser launch
  throws or reports the action is unsupported.
- Surface a transient "Opened in browser" or "Copied" feedback affordance
  consistent with the existing `recentlyCopiedKey` pattern.
- The launcher must not be invoked off the UI dispatcher in a way that
  blocks the main thread for more than a single Desktop API call. If the
  platform implementation can block, route through `Dispatchers.Default`
  using the same `begin/run/finish` pattern other route actions follow.

## Runtime and Service Requirements

- No new runtime services. The compare URL already comes from
  `RuntimeGitGateway` via the publishing status.
- The `BrowserLauncher` jvmMain actual must not throw on unsupported
  platforms; it must return a typed failure that the route translates into
  the clipboard fallback.
- Do not introduce a transitive dependency on AWT in `commonMain` — keep
  the interface platform-neutral.

## Acceptance Criteria

- Clicking the compare URL opens the URL in the system default browser on
  platforms that support `Desktop.browse`.
- When the browser launch fails or is unsupported, the URL is copied to the
  clipboard and a transient "Copied" affordance flashes on the row.
- The row remains screen-reader accessible with a parameterized
  `contentDescription` (e.g. "Open compare URL: $url").
- The launcher API surface is defined in `commonMain` with a `jvmMain`
  actual; tests cover the success path, the unsupported-platform path, and
  the throwing path with a fake launcher.
- No UI code calls `Desktop.getDesktop()` directly.

## Validation

```bash
cd runtime-kotlin
./gradlew --no-configuration-cache :runtime-desktop:feature:skillbill:jvmTest :runtime-desktop:core:common:jvmTest
```

Manual smoke:

1. Push a branch to a remote that produces a compare URL.
2. Click the compare URL and confirm the default browser opens at the
   expected page.
3. Simulate an unsupported launcher (run with a fake that throws) and
   confirm the URL is copied and the row shows the "Copied" affordance.

## Non-Goals

- GitHub-authenticated PR creation.
- Opening any URL other than the compare URL.
- Rendering Markdown previews of remote PR descriptions.
- Embedding a browser inside the app.
