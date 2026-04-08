# Skill Overrides

## bill-boundary-history

Write history entries to `.agents/history/<module-name>.md` where `<module-name>` matches the primary module or package being changed (e.g. `.agents/history/auth.md`, `.agents/history/payments.md`). Do not create or update `agent/history.md` files inside individual modules or packages. If the target file does not exist, create it along with any missing parent directories.
