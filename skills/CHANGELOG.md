# Skills changelog

Each skill carries its own `metadata.version` (SemVer) in `SKILL.md` frontmatter.

| Bump  | When |
|-------|------|
| patch | Wording, examples, typo, clarification; no rule changes meaning |
| minor | New rule, new section, or a relaxed rule; existing code stays compliant |
| major | A rule becomes stricter or contradicts the previous version; existing code may violate it |

Log every bump below as `skill x.y.z — summary`, newest first.

`metadata.scope`: `shared` skills are reusable across projects; `project` skills
belong to this repository only and are replaced when the set is copied.

## 2026-11-03

- All skills `0.0.1` — initial split of the former `RULE.md` / `LIBRARY.md` into
  focused skills; Lombok identity-equality policy; mandatory audit + soft-delete
  columns on every table. Includes reviewed, non-conflicting knowledge from
  piomin/claude-ai-spring-boot: JPA bulk-update/locking/mapping pitfalls,
  pagination over fetch joins, constraint-to-error translation, Spring Boot 4
  test mechanics, MDC propagation, record `toString` leaks, cookie CSRF,
  actuator exposure, virtual-thread pinning, and a review output process.
