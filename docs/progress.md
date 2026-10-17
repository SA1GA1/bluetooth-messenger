# Execution ledger — plan: docs/implementation-plan.md

Baseline: 861c44d. Existing staged deletions and untracked replacements belong to the user.
Ruling: use a feature branch in the current checkout to preserve the selected working files and index; no separate worktree copies stale HEAD.
Baseline build: duplicate annotations and AAR compileSdk mismatch (36.1 vs dependencies requiring 37).
Pre-flight: discovery feeds authenticated transport; transport carries protocol; storage owns durable queue; session gates storage and radios.
Task 1: in progress.
