You are writing one section of an original agent skill on **Amazon DynamoDB architecture**, from
your own knowledge.

## Clean room — mandatory

Write from what you know about DynamoDB. Do **not** search for, open, or reproduce any existing
DynamoDB skill, guide or article. Do not copy AWS documentation prose. Technical facts (limits,
behaviours, pricing mechanics) are facts and free to state; someone else's *wording* is not.
Express everything in your own words.

## The section you are writing

**{{SECTION}}**

{{BRIEF}}

## Audience and purpose

The reader is a coding agent helping an engineer design, review, refactor or debug a DynamoDB
data layer. It needs rules it can apply, not a tour of features. Assume the reader knows what a
database is and has never used DynamoDB seriously.

## Requirements

1. **Lead with the rules.** Numbered, imperative, each one a decision the agent can act on.
2. **Every rule states its consequence.** "Do X" is weak; "Do X, because Y happens at scale
   otherwise" is usable.
3. **Include at least two concrete worked examples** — a real key schema, a real access pattern,
   a real before/after. Use realistic entity names, not Foo and Bar.
4. **State the traps.** Where does an LLM or a relational engineer get DynamoDB wrong? Say so
   explicitly and say what is true instead.
5. **Be exact about anything irreversible.** If a change cannot be made in place, say so and give
   the migration path.
6. Markdown with `##`/`###` headings. 90–200 lines. No preamble, no "in this section we will".

## Style

Direct and specific. No marketing voice. A table beats a paragraph when comparing options. Never
pad with synonyms. If you are unsure of a number, describe the behaviour and say the exact figure
should be checked rather than inventing one.

## Output

Write `{{OUTFILE}}` in your working directory. Nothing else.

End your final message with a single line beginning `RESULT:` naming the section and its line count.
