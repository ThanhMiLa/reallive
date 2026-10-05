---
name: commit
description: Create Git commits for this repository using Conventional Commits. Use when the user asks to prepare, organize, write, or create commits.
---

# Commit conventions

Create focused, reviewable commits. Stage only the files that belong to the same logical change; do not use `git add .`.

## Subject line

Use Conventional Commits with a concise imperative summary:

```text
<type>: <main change>
```

Allowed types:

- `feat`: a new user-facing capability
- `fix`: a correction to existing behavior
- `refactor`: a code-structure change that preserves behavior
- `test`: adding or correcting tests
- `docs`: documentation-only change
- `build`: build system or dependency change
- `ci`: continuous-integration workflow change
- `chore`: maintenance that does not fit another type
- `perf`: measurable performance improvement
- `style`: formatting-only change with no behavioral effect

Keep the subject under 72 characters where practical. Do not end it with a period.

## Commit body

When the commit contains important supporting changes, add a blank line after the subject and list them as bullets. The subject must still state the single main change.

```text
feat: add course enrollment checkout

- create enrollment after successful wallet payment
- prevent duplicate enrollment for an existing student
- add Vietnamese and English checkout messages
```

Use a body only when it helps reviewers understand meaningful sub-changes, tradeoffs, or impacts. Do not list trivial formatting, generated files, or mechanical renames.

## Commit boundaries

- Split unrelated changes into separate commits, each with its own type and subject.
- Include tests with the implementation change they verify when practical.
- Use `refactor`, not `feat` or `fix`, when external behavior is unchanged.
- Use `chore` only for maintenance that has no more specific type.
- Before committing, inspect the staged diff and verify that the message accurately describes it.
