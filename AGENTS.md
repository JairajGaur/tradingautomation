# Project context

## Read context first

At the start of each new chat working in this repository, before inspecting implementation
code or making changes:

1. Read `/Users/jairaj/TradingVault/Home.md`.
2. Read `/Users/jairaj/TradingVault/Report Knowledge Graph.md`.
3. Read `/Users/jairaj/TradingVault/Daily Log/_index.md` and the latest notes relevant to the task.
   Resolve Obsidian links relative to `/Users/jairaj/TradingVault`.
4. Read `/Users/jairaj/TradingVault/Reports/KNOWLEDGE-GRAPH.md` and follow the relevant report-guide links.
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
- Keep report procedures in `/Users/jairaj/TradingVault/Reports/*-GUIDE.md` and update
  that folder’s `KNOWLEDGE-GRAPH.md` when report relationships or sources change.
- Treat dated HTML reports as historical snapshots; do not silently rewrite them to match new code.
- Follow filesystem permissions when updating the vault. If access is blocked, record a concise
  context update in the repository and report that Obsidian synchronization remains pending.

Do not store credentials, tokens, `.env` contents or sensitive broker responses in context notes.

## Report storage policy

The repository contains application code and operational scripts. Keep ALL report
materials in `/Users/jairaj/TradingVault/Reports/`: generated artifacts, captured
data, previews, comparisons, archives, report guides, knowledge graph, report-only
Python generators and CSS templates. Never commit or push these materials, and
never force-add ignored report paths. Report generators live in the vault's
`Reports/scripts/` directory; run them from the application checkout when relative
log/input paths are needed. The ignored local `reports` symlink preserves report
server URLs. Application APIs, indicators, tests and runtime alert/LLM integration
remain application code and stay in the repository.
