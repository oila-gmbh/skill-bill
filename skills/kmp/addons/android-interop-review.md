# KMP Android Interop Review Add-On

Use this governed add-on only after stack routing has already selected `kmp` and the review scope clearly mixes Android Compose with legacy Views, Fragments, or framework-owned host boundaries.

This file is a review index for `bill-kmp-code-review` and `bill-kmp-code-review-ui`. It is not a standalone review command.

## Section index

Scan this file first.

- `## Activation signals`
  Read first to decide whether `android-interop` should be active.
- `## Review focus`
  Use when the diff changes Android host-boundary behavior.

## Activation signals

Select `android-interop` when the scoped diff includes:

- `ComposeView`, `AndroidView`, `AndroidViewBinding`, or `AndroidFragment`
- Compose hosted inside existing Android Views or Fragments
- framework-boundary Compose code that uses `LocalContext`, broadcast receivers, or host lifecycle glue

## Review focus

- Flag Compose/View interop that leaks lifecycle or host concerns into reusable leaf composables.
- Flag `AndroidView` reuse bugs in lazy containers.
- Flag raw View construction outside the `AndroidView` factory.
- Flag duplicated state ownership across Compose and embedded Views/Fragments.
- Flag framework callbacks or receivers that are not lifecycle-safe.
- Flag interop wrappers that have become permanent unowned infrastructure with no clear removal path.

## Review boundary

- Keep this add-on subordinate to the routed `kmp` review.
- Use it to extend the existing KMP review with Android interop risks.
- Do not turn review comments into bulk XML migration workflows.
