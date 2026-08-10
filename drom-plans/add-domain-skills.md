---
title: Add Domain-Specific Skills (Tier 1–3)
status: pending
created: 2026-05-22
updated: 2026-05-22
current_chapter: 1
---

# Plan: Add Domain-Specific Skills (Tier 1–3)

Add nine domain skills to drom-flow tuned to the user's actual portfolio (Java 21 + Spring Boot + Maven, with Spring AI / LangChain4j / LangGraph4j / Jlama, MongoDB + Flapdoodle, DuckDB, Reladomo, MCP servers, dark-mode interactive viz).

Skills follow the existing drom-flow format: a single `<name>/<name>.md` with frontmatter (`name`, `description`, `user-invocable: true`), concise sections, no fluff, matching the style of `api-expert.md`, `architect.md`, `implementer.md`. Each skill mirrored into `template/.claude/skills/` and registered in `SCRIPTS.md`, `CLAUDE.md`, `template/CLAUDE.md`, and `README.md`.

The plan is tiered. Tier 1 is the high-frequency core; Tier 2 is recurring but narrower; Tier 3 is optional and can be skipped without losing value.

## Chapter 1: Tier 1 — Core Domain Skills
**Status:** pending
**Depends on:** none

Four high-frequency skills built in parallel — each one independent (separate file, separate registry entry).

- [ ] **`/spring-boot-test`** — `.claude/skills/spring-boot-test/spring-boot-test.md`
  - Test slices: `@WebMvcTest`, `@DataMongoTest`, `@DataJpaTest`, `@WebFluxTest`, `@JsonTest`
  - `@SpringBootTest` only when truly needed; `webEnvironment` choices
  - Testcontainers vs Flapdoodle (when to pick which); `@DynamicPropertySource` patterns
  - MockMvc vs WebTestClient idioms; security test config (`@WithMockUser`, `SecurityMockMvcRequestPostProcessors`)
  - `@MockBean` last-resort vs constructor-injected mocks
  - Transactional rollback, `@Sql` data setup, faster slice tests
  - Naming: `should_X_when_Y`; AAA structure; one assertion concept per test
- [ ] **`/langchain4j-expert`** — `.claude/skills/langchain4j-expert/langchain4j-expert.md`
  - Model wiring: OpenAI, Anthropic, Jlama-local — when to choose which
  - `AiServices` interface pattern, tool/function calling, structured output (Java records)
  - RAG: embedding store choice, chunking strategy, hybrid retrieval, `EmbeddingStoreContentRetriever`
  - Streaming responses (`TokenStream`), retries, token budgeting, cost guardrails
  - LangGraph4j: stateful graphs, node + edge design, checkpointing
  - Spring AI parallel pattern (when the project uses `spring-ai-*` instead)
  - Testing LLM code: deterministic seeds, recorded responses, prompt-regression tests
- [ ] **`/reladomo-expert`** — `.claude/skills/reladomo-expert/reladomo-expert.md`
  - Object model XML, generated classes, finder API, transactional patterns
  - Deep-fetch profiles for N+1 elimination; relationship modes (related-object vs key)
  - Audit-only and processing-temporal chains; bi-temporal patterns
  - Transaction participation, `MithraTransaction`, async + parallel queries
  - When to lean on JavaDucker: `javaducker_reladomo_relationships`, `_finders`, `_deepfetch`, `_graph`
  - Common pitfalls (silent N+1, missed deep-fetch, transaction scope mistakes)
  - Migration from kata exercises to production patterns
- [ ] **`/mcp-server`** — `.claude/skills/mcp-server/mcp-server.md`
  - Tool design: small, composable, one verb per tool; JSON schema generation
  - Transport choice: stdio for desktop, SSE/HTTP for shared; `spring-ai-starter-mcp-server-webmvc`
  - Error contracts: structured errors vs free-text; correlation IDs across tool calls
  - Resource and prompt providers (not just tools)
  - Authentication for HTTP transport; rate limiting
  - Testing MCP servers: in-process harness, `mcp inspector`, contract tests
  - Anti-patterns: stateful tools, hidden side effects, over-broad tool surface
- [ ] Mirror all four into `template/.claude/skills/<name>/<name>.md`
- [ ] Register all four in `SCRIPTS.md` `MANAGED_DIRS` array (init.sh canonical source)
- [ ] Update `SCRIPTS.md` install banner: bump "11 agent skills" → "15 agent skills"
- [ ] Add four bullets to `CLAUDE.md` Skills section
- [ ] Add four bullets to `template/CLAUDE.md` Skills section
- [ ] Add four rows to README.md agent-skills table
- [ ] Single commit for Tier 1, push

**Notes:**
> Spawn four parallel agents — one per skill .md — in a single message. Each agent writes both `.claude/skills/<name>/<name>.md` and `template/.claude/skills/<name>/<name>.md` to keep the pair in sync. Style anchor: re-read `.claude/skills/api-expert/api-expert.md` first — match its tone, depth, and section structure. Cap each file around 200–300 lines; no padding.

## Chapter 2: Tier 2 — Recurring Domain Skills
**Status:** pending
**Depends on:** Chapter 1 (so registration patterns are settled)

Three narrower but still recurring skills.

- [ ] **`/mongo-expert`** — `.claude/skills/mongo-expert/mongo-expert.md`
  - Index strategy (compound, partial, TTL); `explain()` reading
  - Aggregation pipelines: stage ordering, `$lookup` cost, `$facet`
  - Change streams, capped collections, schema versioning patterns
  - Flapdoodle vs Testcontainers; when each is right
  - Reactive (`ReactiveMongoTemplate`) vs imperative; mixing carefully
  - ObjectId vs ULID vs natural keys; migration patterns
  - Common pitfalls: unbounded scans, write concern surprises, schema drift
- [ ] **`/observability`** — `.claude/skills/observability/observability.md`
  - Micrometer + OpenTelemetry wiring in Spring Boot 3.x
  - Structured JSON logging (Logback `JsonEncoder`, MDC propagation)
  - Correlation IDs across HTTP, MCP, async agent calls
  - Actuator hardening (which endpoints, behind which auth)
  - Tracing across LangGraph4j nodes and tool calls
  - SLOs, RED metrics, useful dashboards vs vanity metrics
  - Local dev: OTLP collector, Grafana stack via Docker Compose
- [ ] **`/maven-multi-module`** — `.claude/skills/maven-multi-module/maven-multi-module.md`
  - Parent POM + BOM pattern; `dependencyManagement` vs `dependencies`
  - Plugin pinning (no `LATEST` / `RELEASE`); `enforcer-plugin` rules
  - Module layout: protocol / persistence / runtime / tools (mirroring javaclawv1)
  - `generate-sources` for OpenAPI / protobuf; output to `target/generated-sources`
  - Release vs snapshot profiles; reproducible builds (`-Dproject.build.outputTimestamp`)
  - Dependency convergence, classpath hygiene, banning duplicate slf4j bindings
  - When to split a module; when to *not* (too-small modules are friction)
- [ ] Mirror all three into `template/.claude/skills/`
- [ ] Register in `SCRIPTS.md` `MANAGED_DIRS` + bump banner to "18 agent skills"
- [ ] Update `CLAUDE.md`, `template/CLAUDE.md`, `README.md`
- [ ] Single commit for Tier 2, push

**Notes:**
> Same parallel-agent pattern as Chapter 1. If the user wants to stop after Tier 1, mark this chapter `skipped` instead of `pending` and proceed to Chapter 4 with banner count adjusted to 15.

## Chapter 3: Tier 3 — Optional Specialty Skills
**Status:** pending
**Depends on:** Chapter 2

Two narrower skills that pay off only for specific recurring contexts.

- [ ] **`/dark-viz`** — `.claude/skills/dark-viz/dark-viz.md`
  - Codifies the recurring dark-theme aesthetic across archviz / process-graph / mongodiff / dark-factory
  - Color tokens, typography, motion grammar; SVG vs Canvas vs WebGL trade-offs
  - Particle/edge animation patterns, orthogonal routing, swimlane layout
  - Accessibility in dark UIs (contrast ratios, focus rings, prefers-reduced-motion)
  - Performance: animation budget, requestAnimationFrame patterns, off-DOM diffing
  - Reusable primitives the user has built across these repos
- [ ] **`/dependency-upgrader`** — `.claude/skills/dependency-upgrader/dependency-upgrader.md`
  - Read upstream changelogs (Spring Boot, Spring AI, LangChain4j, langgraph4j) before bumping
  - Stepwise upgrade: pin first, run `mvn verify`, fix breakages, then move to next minor
  - Spring Boot 3.x → 3.y migration notes (Jakarta EE move, observation API changes)
  - Spring AI is pre-1.0 — breaking changes are routine; how to spot them
  - Dependabot/Renovate config sketch; auto-merge policies
  - When to fork (LangChain4j forks visible in the user's tree) vs PR upstream
- [ ] Mirror both into `template/.claude/skills/`
- [ ] Register in `SCRIPTS.md` `MANAGED_DIRS` + bump banner to "20 agent skills"
- [ ] Update `CLAUDE.md`, `template/CLAUDE.md`, `README.md`
- [ ] Single commit for Tier 3, push

**Notes:**
> Optional. If the user skips, mark this chapter `skipped` and adjust the final banner count in Chapter 4 to whatever the running total is.

## Chapter 4: Verification & Release
**Status:** pending
**Depends on:** Chapter 1 (Chapters 2 and 3 if not skipped)

- [ ] Verify every new skill loads correctly: list each via the slash-command surface (`grep -l "user-invocable: true" .claude/skills/*/*.md`)
- [ ] Confirm `.claude/skills/` and `template/.claude/skills/` are identical for each new skill (`diff -r` on the new dirs)
- [ ] Confirm `SCRIPTS.md` `MANAGED_DIRS` count matches the actual `.claude/skills/` subdir count
- [ ] Confirm skill counts in `SCRIPTS.md`, `CLAUDE.md`, `template/CLAUDE.md`, and `README.md` agree
- [ ] Smoke test: regenerate `init.sh` from `SCRIPTS.md` and run `bash init.sh --check .` against a scratch dir to confirm no missing files
- [ ] Bump `VERSION` (0.4.1 → 0.5.0 — new skills are user-visible feature additions, minor bump)
- [ ] Final commit: `Release v0.5.0: add N domain skills`; push

**Notes:**
> If any Tier was skipped, the version bump still goes to 0.5.0 — the *first* shipped set of domain skills is the feature, regardless of completeness. Subsequent tiers can ship as 0.5.x.
