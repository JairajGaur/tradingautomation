# Project context

## Read context first

At the start of each new chat working in this repository, before inspecting implementation
code or making changes:

1. Read `/Users/jairaj/TradingVault/Home.md`.
2. Read `/Users/jairaj/TradingVault/Report Knowledge Graph.md`.
3. Read `/Users/jairaj/TradingVault/Daily Log/_index.md` and the latest notes relevant to the task.
   Resolve Obsidian links relative to `/Users/jairaj/TradingVault`.
4. Read `reports/KNOWLEDGE-GRAPH.md` and follow the relevant report-guide links.
5. Read `.kiro/steering/project-overview.md` and any task-relevant steering files.

Briefly state which context notes were read before proceeding. If a note is unavailable or
the vault is inaccessible, state that limitation and continue with available repository context;
do not claim to have read inaccessible content. The vault path is local to this machine.

Use notes to recover prior decisions, constraints and reusable components. Verify them against
current code and configuration; identify stale notes when they conflict with the implementation.
The current project overview takes precedence over the partly stale root `STEERING.md` blueprint.

## Maintain existing knowledge

After meaningful implementation, configuration, report or architectural changes:

- Update the existing relevant Obsidian notes and indexes with decisions, validation and limits.
- Reuse existing concept notes and links rather than creating duplicate knowledge.
- Keep report procedures in the existing `reports/*-GUIDE.md` files and update
  `reports/KNOWLEDGE-GRAPH.md` when report relationships or sources change.
- Treat dated HTML reports as historical snapshots; do not silently rewrite them to match new code.
- Follow filesystem permissions when updating the vault. If access is blocked, record a concise
  context update in the repository and report that Obsidian synchronization remains pending.

Do not store credentials, tokens, `.env` contents or sensitive broker responses in context notes.

## Generated report policy

Never commit or push generated reports, captured report data, previews, comparison
snapshots or reproduction archives. Keep these local under `reports/`, which is
ignored except for reusable report guides and context notes. Report generation
scripts and templates may be committed. Do not force-add ignored report artifacts.
