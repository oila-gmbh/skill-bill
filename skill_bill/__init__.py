from __future__ import annotations

__version__ = "0.1.0"

# The shell+content contract API is available via
# ``skill_bill.shell_content_contract`` but is deliberately not re-exported at
# package import time. The loader depends on PyYAML, and the skill_bill CLI is
# frequently imported from a system Python that does not have optional
# contract-validation dependencies installed. Eagerly importing the loader
# would tie every CLI entry point (telemetry, learnings, review import) to
# PyYAML for no good reason. Callers that want the contract loader import the
# submodule directly.

__all__ = ["__version__"]
