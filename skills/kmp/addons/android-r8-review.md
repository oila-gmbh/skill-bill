# KMP Android R8 Review Add-On

Use this governed add-on only after stack routing has already selected `kmp` and the review scope clearly contains Android shrinker configuration or keep-rule changes.

This file is a review index for `bill-kmp-code-review`. It adapts the transferable Android R8 analysis guidance and keeps build-system upgrade advice out of scope.

## Section index

Scan this file first. This add-on is intentionally compact because the upstream Android skill is already focused on one topic.

- `## Activation signals`
  Read first to decide whether `android-r8` should be active.
- `## Review focus`
  Use when the diff changes Android keep rules or release shrinking config.

## Activation signals

Select `android-r8` when the scoped diff includes:

- `proguard-rules.pro`, `consumer-rules.pro`, or custom `.pro` files
- Android build config changes for minification, resource shrinking, or R8 flags
- broad `-keep` rules, reflection-sensitive retention rules, or library-wide keep directives

## Review focus

- Flag `-dontshrink`, `-dontobfuscate`, or `-dontoptimize` as effectively disabling Android shrinker value for the whole module.
- Flag package-wide wildcard keep rules, inversion rules, or broad class-and-members preservation when narrower rules would work.
- Flag manual keep rules for Android components or mainstream Android/Kotlin libraries when consumer rules already cover them.
- Check reflection-sensitive code paths for evidence-backed narrow rules instead of speculative blanket retention.
- Check keep-rule changes for ordered impact: broad package retention first, then class-wide rules, then member-specific rules.
- Check manual Parcelable, Gson, Retrofit, coroutines, and Room rules carefully because those are common Android-specific sources of redundant keeps.
- Check that release shrinker configuration is coherent for the touched Android module and that rule cleanup has an obvious validation path.

## Review boundary

- Keep this add-on subordinate to the routed `kmp` review.
- Use it to extend the existing KMP review with Android shrinker risks.
- Do not turn review comments into AGP migration plans or general build upgrade advice.
