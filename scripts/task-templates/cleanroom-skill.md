You are writing an **original** agent skill for the drom-flow toolkit, from your own knowledge of
a public product-management framework.

## Clean room — read this first

This is a clean-room rewrite. An existing skill covering this topic has an incompatible licence
and is being replaced. **You must not look for, open, search for, or reproduce that skill or any
third-party skill on this topic.** Do not search the web. Do not read any file under
`.ri-test/`, `quarantine`, or any existing `{{SKILL_NAME}}` directory. If you happen to know a
particular published phrasing, do not use it.

Write from the *framework itself* — the underlying method, which is public knowledge and not
anyone's property. Your expression of it must be your own.

## The skill to write

- **Name:** `{{SKILL_NAME}}`
- **Framework:** {{FRAMEWORK}}
- **What a user wants from it:** {{PURPOSE}}

## Required file format

Write exactly one file: `{{SKILL_NAME}}.md`, starting with YAML frontmatter in this shape:

```
---
name: {{SKILL_NAME}}
description: <one sentence, under 200 characters, saying what it does and when to use it>
user-invocable: true
---
```

Then the body. Follow the shape of drom-flow's own skills — read this one for structure and tone,
and only for that:

`{{ROOT}}/template/.claude/skills/planner/planner.md`

Body requirements:

1. An H1 title, then a short paragraph stating what the skill does and when it applies.
2. A **Responsibilities** or equivalent section: numbered, concrete, imperative. What the agent
   actually does, in order.
3. A **worked template or output format** the agent produces — a markdown skeleton, a table, or a
   structured artifact. This is the most useful part of a skill; make it specific and copyable.
4. A **quality bar** section: how to tell a good output from a bad one, with at least three
   concrete failure modes stated as things to avoid.
5. A short **when NOT to use this** section. A skill that claims to apply everywhere is useless.

## Style — this matters as much as the content

- Direct, declarative, specific. No filler, no marketing voice, no "leverage" or "robust".
- Prefer a concrete example over an abstract description every time.
- Never pad with a bulleted list of synonyms.
- 120–260 lines. Shorter than that is thin; longer is usually padding.
- British or American spelling is fine, be consistent.

## Verify before finishing

Re-read what you wrote and check: would this actually change what an agent does, or is it a
description of a framework anyone could recite? A skill earns its place by being *operational* —
it tells the agent what to produce and what "good" looks like.

## Output

`{{SKILL_NAME}}.md` in your working directory. Nothing else.

End your final message with a single line beginning `RESULT:` giving the skill name, the line
count, and one sentence on what makes your version operational rather than descriptive.
