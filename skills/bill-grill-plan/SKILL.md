---
name: bill-grill-plan
description: Stress-test a plan or design by walking every branch of the decision tree until reaching shared understanding. Use when user wants to challenge a design, stress-test a plan, get grilled, poke holes, or says "grill me".
---

# Grill Plan

Interview the user relentlessly about every aspect of their plan or design until reaching shared understanding. Walk down each branch of the decision tree, resolving dependencies between decisions one by one.

## Project Overrides

Follow the shell ceremony in [shell-ceremony.md](shell-ceremony.md).

If `.agents/skill-overrides.md` exists in the project root and contains a matching section, read that section and apply it as the highest-priority instruction for this skill.

## Rules

- Ask questions **one at a time**
- For each question, provide your recommended answer
- If a question can be answered by exploring the codebase, explore instead of asking
- Cover: edge cases, failure modes, scalability, backwards compatibility, rollback safety
- Challenge assumptions — don't accept "it should work" without evidence
- When all branches are resolved, summarize the final agreed design as a numbered list of decisions
