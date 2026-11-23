# ADR 0004: distribution mechanism and GitHub Packages migration plan

- Status: Accepted (composite build, in effect); Proposed (GitHub Packages cutover, not implemented)
- Decision context: `decision-wlt-0tn-q4` (separate repo, Apache 2.0, composite Gradle build first, GitHub Packages later), in [`docs/brainstorm-decisions.md`](../brainstorm-decisions.md)
- Related: [`docs/composite-build.md`](../composite-build.md)

## Context

The closed-source host app must consume the library modules in this repository, and a third-party auditor or reuser must be able to consume them without the host's local-checkout topology. The trust claim requires the host to compile the same source files an auditor reads here.

## Decisions

### D1. Current distribution: composite Gradle build, sibling checkouts

The host adds `includeBuild(...)` pointing at a sibling checkout of this repo and declares `implementation("is.walt:passes-core")` and so on. Gradle's dependency substitution matches on `(group, module-name)` and resolves the coordinate to the included project. [`docs/composite-build.md`](../composite-build.md) holds the operational detail.

Rejected:

- *Monorepo*: puts trust-claim-bearing code in the closed repo and breaks the audit story.
- *Local Maven publish*: every consumer contributor must run `publishToMavenLocal` before each build; easy to forget, hard to validate in CI. Composite build does it in one Gradle line.
- *git submodule*: orthogonal to dependency resolution and adds detached-HEAD and two-step-pull friction.
- *Maven Central from day one*: leaves an unretractable graveyard of `0.x` artifacts before the API is stable.

Coordinates are pinned in `gradle.properties` (`walt.passes.group`, `walt.passes.version`) and stamped on every library module by the convention plugins in `build-logic/`. No module sets `group` or `version` itself.

### D2. Planned: GitHub Packages with Maven Publish

The cutover publishes the same coordinates to `https://maven.pkg.github.com/walt-app/walt-passes-android`. Composite build stays supported for engineers working on both repos at once; GitHub Packages decouples the consumer from the sibling-checkout assumption and lets auditors depend on a published artifact. Chosen over Maven Central because the org already has GitHub credentials (no separate Sonatype account), token auth fits the closed consumer's CI, and the pipeline (signing, versioning, release workflow) can be iterated before promoting anything to the unretractable Maven Central. Maven Central is not ruled out later; the pipeline is portable to it.

- **Plugin surface.** A `PublishingConventionPlugin` in `build-logic/` applies `maven-publish` and `signing` to each library module, with sources and javadoc jars (sources for every module, `passes-storage` included, so the audit story holds on the registry). Signing is required for release versions only; SNAPSHOTs publish unsigned. No such plugin exists yet.
- **Versioning.** Trunk carries `X.Y.Z-SNAPSHOT` in `gradle.properties`, published on each successful CI run on `main` (a workflow separate from PR validation, so fork PRs never see publish credentials). A tagged commit `vX.Y.Z` triggers a release workflow that asserts `walt.passes.version` matches the tag with no `-SNAPSHOT`, publishes every library module signed, and creates a GitHub Release. A follow-up PR bumps to the next SNAPSHOT. SemVer applies, with any public-API change a major bump until 1.0.0 and a minor bump after (`explicitApi` is Strict in both convention plugins).
- **Consumer auth.** GitHub Packages requires authentication even for public packages: `gpr.user` and `gpr.token` (a token with `read:packages` only) in `~/.gradle/gradle.properties`, or `GITHUB_ACTOR` / `GITHUB_TOKEN` in Actions.
- **Cutover stages.** Producer ships the pipeline, composite build stays. Consumer adds an opt-in flag (`waltPasses.useArtifacts`) that declares the GitHub Packages repository instead of `includeBuild`. Producer cuts a tagged release and the consumer pins that version. Consumer flips the flag's default. Composite build stays available indefinitely for cross-repo development.

### D3. Out of scope

- Maven Central.
- Snapshot publishing from PR builds (fork-PR credential exposure).
- Reproducible builds.
- Per-module versioning: all modules ship at one version. Revisit if `passes-core` stabilizes much faster than the Android modules.

## Consequences

- The coordinates `is.walt:passes-*` already match what GitHub Packages will publish.
- The version literal lives only in `gradle.properties`, so the release-workflow assertion is one grep.
- Until the pipeline lands, third parties read the source on GitHub or use composite build at their own risk.
