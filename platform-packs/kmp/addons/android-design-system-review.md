# KMP Android Design System Review Add-On

Use this governed add-on only after stack routing has already selected `kmp` and the review scope clearly touches Android Compose theming, design tokens, styled components, or XML-theme-to-Compose translation.

This file is a review index for `bill-kmp-code-review` and `bill-kmp-code-review-ui`. It is not a standalone review command.

## Section index

Scan this file first.

- `## Activation signals`
  Read first to decide whether `android-design-system` should be active.
- `## Review focus`
  Use when the diff changes Android theme or styled component behavior.

## Activation signals

Select `android-design-system` when the scoped diff includes:

- `MaterialTheme`, color schemes, typography, shapes, or token definitions
- XML style/theme migration into Compose
- reusable styled Android components that replace view-era styling contracts

## Review focus

- Flag duplicate or parallel Android theme layers.
- Flag hardcoded design values that should be theme- or token-backed.
- Flag design drift from the existing XML or branded Android theme without intent.
- Flag inline recreation of XML styles instead of readable styled component APIs.
- Flag loss of resource-backed sizing, typography, or shape contracts during migration.
- Flag hybrid XML/Compose theming changes that erase one side’s ownership prematurely.

## Review boundary

- Keep this add-on subordinate to the routed `kmp` review.
- Use it to extend the existing KMP review with Android design-system risks.
- Do not turn review comments into whole-app theme migration plans.
