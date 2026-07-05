---
name: bill-ios-code-review-performance
description: Use when reviewing iOS performance risks on hot reducer paths, main-thread work, and image/PDF-heavy processing.
internal-for: bill-code-review
---

# Performance Review Specialist

Review only performance issues with real, demonstrable production impact.

## Focus

- Hot reducer/store paths that run on every state update
- Main-thread work that can cause dropped frames or UI stalls
- Image, PDF, and media-processing hot paths
- Large sync/import loops with unbounded or repeated work

## Ignore

- Micro-optimizations with no measurable or plausible production impact
- Premature optimization of cold paths

## Applicability

Use this specialist for reducer/store code that runs on frequent state updates, any code executing on the main thread that affects UI responsiveness, and image/PDF/media-processing code paths (for example, gallery, editor, or camera-adjacent features).

## Project-Specific Rules

- Reducer logic invoked on every action dispatch must avoid expensive synchronous work (large collection copies, repeated filtering/sorting, synchronous I/O); move expensive computation into an effect off the hot reducer path
- Image and PDF decoding, downscaling, and rendering must not run synchronously on the main thread for anything larger than trivial thumbnail-sized content
- Large sync/import loops must avoid O(n²) patterns (e.g. repeated linear scans per item) when a single pass or indexed lookup is available
- For Major or Critical findings, describe the concrete user-visible stall, dropped-frame, or memory-pressure consequence
