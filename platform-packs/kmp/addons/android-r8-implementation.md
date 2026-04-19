# KMP Android R8 Add-On

Use this governed add-on only after stack routing has already selected `kmp` and the current work clearly touches Android shrinker configuration or keep rules.

This file is an implementation index, not a standalone skill. It adapts the transferable Android R8 analysis guidance for stack-owned KMP use without turning R8 work into a separate top-level package.

## Section index

Scan this file first. This add-on is intentionally compact because the upstream Android skill is already focused on one topic.

- `## Activation signals`
  Read first to decide whether `android-r8` should apply at all.
- `## Implementation guidance`
  Use when the work changes ProGuard/R8 rules, minification config, or Android release shrinking behavior.
- `## Verification checklist`
  Use when validating a proposed keep-rule cleanup.

## Activation signals

Activate `android-r8` when the routed KMP work includes signals such as:

- `proguard-rules.pro`, `consumer-rules.pro`, or custom keep-rule files
- `isMinifyEnabled`, `minifyEnabled`, `isShrinkResources`, or `proguardFiles`
- `android.enableR8.fullMode` or `android.r8.optimizedResourceShrinking`
- broad `-keep` rules, reflection-sensitive code, or Android release build shrinker tuning

## Implementation guidance

- Prefer release builds that use `proguard-android-optimize.txt`, enable minification, and enable resource shrinking where the Android app/module actually supports it.
- Check the module’s actual Android build configuration first. Shrinker advice is only meaningful if the touched module is an app or release-producing Android module that owns those settings.
- Treat `-dontshrink`, `-dontobfuscate`, and `-dontoptimize` as emergency-only switches, not normal Android configuration.
- Remove blanket keep rules for Android framework components, AndroidX, Kotlin, Kotlinx, Room, Retrofit, Gson, and similar libraries when modern consumer rules already cover them.
- Narrow keep rules to the exact reflective entry points or annotated members that require retention instead of preserving entire packages.
- Treat package-wide wildcards, inversion rules, and `-keep class ... { *; }` rules as high-risk defaults that need justification or refinement.
- Prioritize keep-rule cleanup by blast radius: package wildcards and inversion rules first, then broad class-and-members rules, then narrower member rules.
- When reflection is real, derive the keep rule from the actual loading or annotation pattern in code instead of guessing a broad package rule.
- Look for reflection categories explicitly: `Class.forName`, annotation-driven scanning, `::class.java` registrations, private member reflection, optional dependency toggles, and manual `Parcelable` implementations.
- Prefer deleting redundant library rules over replacing them with slightly narrower redundant library rules.
- If the Android module is on an older toolchain, note upgrade opportunities separately, but keep the add-on focused on transferable shrinker behavior rather than build migration playbooks.
- Keep shrinker changes scoped to Android release behavior. Do not mix them with unrelated dependency upgrade or build-system migration work.

## Verification checklist

- Release minification and resource shrinking are configured deliberately for the relevant Android module.
- Proposed keep-rule removals are backed by actual library consumer rules or by narrower replacement rules.
- Reflection-sensitive paths have precise keep rules instead of package-wide preservation.
- Keep-rule recommendations are ordered from highest-impact cleanup to lowest-impact cleanup.
- Validation covers the affected Android flows after keep-rule changes.

## Implementation boundary

This add-on should enrich KMP implementation work only after `kmp` routing. It must not be treated as a new top-level package, slash command, or default workflow outside the owning stack.
