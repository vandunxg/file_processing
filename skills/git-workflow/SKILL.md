---
name: git-workflow
description: Use before creating a git commit, writing a commit message, pushing, amending, or claiming a change is complete in a Maven project formatted with Spotless.
metadata:
  version: "0.0.1"
  author: vandunxg
  scope: shared
  category: workflow
  stack: maven, spotless
---

# Git workflow

## Before every commit

```bash
./mvnw spotless:apply && ./mvnw verify
```

Both must pass. If `verify` fails, fix it or report the failure — do not commit
around it. Never bypass hooks with `--no-verify` without explicit approval.

## Commit message — Conventional Commits

```text
type(scope): imperative lowercase subject

Optional body explaining why, when not obvious.
```

- Types: `feat`, `fix`, `refactor`, `perf`, `docs`, `test`, `chore`, `style`,
  `ci`, `build`.
- Scope: the module or area (`auth`, `fileimport`, `config`).
- Subject under 72 characters, no trailing period, states intent.
- One logical change per commit.

## Branches

- Never amend or force-push a shared branch without approval.
- Branch off the default branch for feature work.
