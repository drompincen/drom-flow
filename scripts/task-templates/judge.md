You are judging whether a documented behaviour would actually fire. You are NOT being asked
your opinion about whether the tool is good — only whether, following the instructions exactly
as written, the described tool would be used for each task below.

## The instructions to follow
Read this file completely and treat it as your operating instructions:

{{SKILLFILE}}

## The decision
For each task, decide: following those instructions literally, would you invoke
`bash .claude/df/repo-intel/run ...` before doing the work?

Answer YES only if the instructions direct you to. Answer NO if they direct you to skip it, or
if nothing in them applies. Do not answer based on what you personally think is efficient.

## Tasks
{{TASKS}}

## Output
Write `verdicts.json` in your working directory, exactly this shape and nothing else:

```
{ "skill": "{{SKILL}}",
  "verdicts": [ {"id": "X1", "use": true, "why": "one short clause quoting the rule that decided it"} ] }
```

One entry per task, in order, every id present. `use` must be a JSON boolean.

End your final message with a single line beginning `RESULT:` giving the skill name and the
count of YES and NO verdicts.
