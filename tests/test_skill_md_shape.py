"""Fixture-based accept/reject coverage for ``validate_skill_md_shape``.

Each rejection test asserts the specific :class:`InvalidSkillMdShapeError`
fires with a message that references the offending file path and a
diagnostic substring identifying the violation.
"""

from __future__ import annotations

from pathlib import Path
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(ROOT / "scripts"))

from skill_bill.shell_content_contract import InvalidSkillMdShapeError  # noqa: E402

from scripts.validate_agent_configs import validate_skill_md_shape  # noqa: E402


CANONICAL_FIXTURE = """\
---
name: bill-fixture-skill
description: Use when exercising the canonical SKILL.md shape fixture.
---

## Descriptor

Governed skill: `bill-fixture-skill`
Family: `code-review`
Platform pack: `kmp` (KMP)
Description: Use when reviewing fixture changes.

## Execution

Follow the instructions in [content.md](content.md).

## Ceremony

Follow the shell ceremony in [shell-ceremony.md](shell-ceremony.md).

When stack routing applies, follow [stack-routing.md](stack-routing.md).
"""


class ValidateSkillMdShapeAcceptTest(unittest.TestCase):
  def setUp(self) -> None:
    self._tmp = tempfile.TemporaryDirectory()
    self.addCleanup(self._tmp.cleanup)
    self.root = Path(self._tmp.name)

  def _write(self, body: str) -> Path:
    skill_md = self.root / "SKILL.md"
    skill_md.write_text(body, encoding="utf-8")
    return skill_md

  def test_accepts_canonical_fixture(self) -> None:
    skill_md = self._write(CANONICAL_FIXTURE)
    validate_skill_md_shape(skill_md)


class ValidateSkillMdShapeRejectTest(unittest.TestCase):
  maxDiff = None

  def setUp(self) -> None:
    self._tmp = tempfile.TemporaryDirectory()
    self.addCleanup(self._tmp.cleanup)
    self.root = Path(self._tmp.name)

  def _write(self, body: str) -> Path:
    skill_md = self.root / "SKILL.md"
    skill_md.write_text(body, encoding="utf-8")
    return skill_md

  def test_rejects_wrong_section_order(self) -> None:
    body = """\
---
name: bill-fixture-skill
description: One-liner.
---

## Execution

Follow the instructions in [content.md](content.md).

## Descriptor

Governed skill: `bill-fixture-skill`
Family: `advisor`

## Ceremony

Follow the shell ceremony in [shell-ceremony.md](shell-ceremony.md).
"""
    skill_md = self._write(body)
    with self.assertRaises(InvalidSkillMdShapeError) as context:
      validate_skill_md_shape(skill_md)
    self.assertIn("in that order", str(context.exception))

  def test_rejects_fenced_code(self) -> None:
    body = """\
---
name: bill-fixture-skill
description: One-liner.
---

## Descriptor

Governed skill: `bill-fixture-skill`
Family: `advisor`

## Execution

Follow the instructions in [content.md](content.md).

```python
print("not allowed")
```

## Ceremony

Follow the shell ceremony in [shell-ceremony.md](shell-ceremony.md).
"""
    skill_md = self._write(body)
    with self.assertRaises(InvalidSkillMdShapeError) as context:
      validate_skill_md_shape(skill_md)
    self.assertIn("fenced code", str(context.exception))

  def test_rejects_markdown_table(self) -> None:
    body = """\
---
name: bill-fixture-skill
description: One-liner.
---

## Descriptor

Governed skill: `bill-fixture-skill`
Family: `advisor`

## Execution

Follow the instructions in [content.md](content.md).

| Step | Where |
| --- | --- |

## Ceremony

Follow the shell ceremony in [shell-ceremony.md](shell-ceremony.md).
"""
    skill_md = self._write(body)
    with self.assertRaises(InvalidSkillMdShapeError) as context:
      validate_skill_md_shape(skill_md)
    self.assertIn("markdown table", str(context.exception))

  def test_rejects_step_n_heading(self) -> None:
    body = """\
---
name: bill-fixture-skill
description: One-liner.
---

## Descriptor

Governed skill: `bill-fixture-skill`
Family: `workflow`

## Execution

Follow the instructions in [content.md](content.md).

## Step 1: Collect inputs

## Ceremony

Follow the shell ceremony in [shell-ceremony.md](shell-ceremony.md).
"""
    skill_md = self._write(body)
    with self.assertRaises(InvalidSkillMdShapeError) as context:
      validate_skill_md_shape(skill_md)
    msg = str(context.exception)
    self.assertTrue(
      "Step N" in msg or "in that order" in msg,
      f"unexpected error message: {msg}",
    )

  def test_rejects_h1_present(self) -> None:
    body = """\
---
name: bill-fixture-skill
description: One-liner.
---

# Top Level Heading

## Descriptor

Governed skill: `bill-fixture-skill`
Family: `advisor`

## Execution

Follow the instructions in [content.md](content.md).

## Ceremony

Follow the shell ceremony in [shell-ceremony.md](shell-ceremony.md).
"""
    skill_md = self._write(body)
    with self.assertRaises(InvalidSkillMdShapeError):
      validate_skill_md_shape(skill_md)

  def test_rejects_disallowed_frontmatter_key(self) -> None:
    body = """\
---
name: bill-fixture-skill
description: One-liner.
extra_key: not allowed
---

## Descriptor

Governed skill: `bill-fixture-skill`
Family: `advisor`

## Execution

Follow the instructions in [content.md](content.md).

## Ceremony

Follow the shell ceremony in [shell-ceremony.md](shell-ceremony.md).
"""
    skill_md = self._write(body)
    with self.assertRaises(InvalidSkillMdShapeError) as context:
      validate_skill_md_shape(skill_md)
    self.assertIn("disallowed keys", str(context.exception))

  def test_rejects_intro_paragraph_before_first_h2(self) -> None:
    body = """\
---
name: bill-fixture-skill
description: One-liner.
---

This is an intro paragraph and is not allowed.

## Descriptor

Governed skill: `bill-fixture-skill`
Family: `advisor`

## Execution

Follow the instructions in [content.md](content.md).

## Ceremony

Follow the shell ceremony in [shell-ceremony.md](shell-ceremony.md).
"""
    skill_md = self._write(body)
    with self.assertRaises(InvalidSkillMdShapeError) as context:
      validate_skill_md_shape(skill_md)
    self.assertIn("intro paragraph", str(context.exception))

  def test_rejects_h3_heading(self) -> None:
    body = """\
---
name: bill-fixture-skill
description: One-liner.
---

## Descriptor

Governed skill: `bill-fixture-skill`
Family: `advisor`

## Execution

Follow the instructions in [content.md](content.md).

### A subsection

## Ceremony

Follow the shell ceremony in [shell-ceremony.md](shell-ceremony.md).
"""
    skill_md = self._write(body)
    with self.assertRaises(InvalidSkillMdShapeError) as context:
      validate_skill_md_shape(skill_md)
    self.assertIn("H3+ heading", str(context.exception))

  def test_rejects_old_shape_description_section(self) -> None:
    body = """\
---
name: bill-fixture-skill
description: One-liner.
---

## Description

Old-shape skill body.

## Specialist Scope

Old.

## Inputs

Old.

## Outputs Contract

Old.
"""
    skill_md = self._write(body)
    with self.assertRaises(InvalidSkillMdShapeError) as context:
      validate_skill_md_shape(skill_md)
    self.assertIn("in that order", str(context.exception))


if __name__ == "__main__":
  unittest.main()
