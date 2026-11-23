# Consuming walt-passes-android via composite Gradle build

Status: current. This is the supported way for a consumer Gradle build to depend on the library
modules until the GitHub Packages migration in [ADR 0004](adr/0004-distribution-and-github-packages.md)
ships. Why composite build: the consumer's compiler reads the same source files any auditor reads on
GitHub, which keeps "the host never reimplements trust-claim logic" cheap to verify.

## Coordinates

Every library module publishes under group `is.walt`; the artifactId equals the Gradle project name
(`is.walt:passes-core`, `is.walt:passes-storage`, `is.walt:passes-ui`, and so on for each module
included in `settings.gradle.kts`). The version carries a `-SNAPSHOT` suffix on trunk (ADR 0004).

The convention plugins (`KotlinLibraryConventionPlugin`, `AndroidLibraryConventionPlugin`) stamp
group and version from `gradle.properties` keys `walt.passes.group` and `walt.passes.version`. To
bump the version, edit those; never set `group` or `version` in a module's `build.gradle.kts`.

## Consumer setup

The consumer checks out this repo as a sibling of its own:

```
~/workspace/walt/
  android/                       <- consumer repo
    android-main/
  passes-android/                <- this repo
    passes-android-main/
```

In the consumer's `settings.gradle.kts`:

```kotlin
includeBuild("../../passes-android/passes-android-main")
```

Then declare ordinary dependencies. Substitution matches `(group, name)` and swaps the external
coordinate for the included project, so nothing resolves from a Maven repository:

```kotlin
dependencies {
    implementation("is.walt:passes-core")
    implementation("is.walt:passes-storage")
}
```

A version is not required. If supplied it should match `walt.passes.version` so a later cutover to
artifact resolution needs no call-site edits.

## Constraints

1. **Sibling checkout.** The `includeBuild(...)` path is filesystem-relative; consumers with another layout adjust it.
2. **Third-party use is unsupported.** Third parties who want to depend on this repo should wait for the GitHub Packages release (ADR 0004) rather than replicate the composite workflow.
3. **Build-logic is not composed.** The `is.walt.passes.*` convention plugins stay internal; the consumer keeps its own.
4. **Version catalogs are independent.** A module exports no third-party dependency it does not `api(...)`; the consumer resolves its own copies of shared transitives.
5. **CI checks out both repos.** Without the included build on disk, configuration fails with "Project ... not found".

## Trust-claim framing

A reader asking what the host actually runs when it parses a pass finds: a closed-source app that
imports `is.walt:passes-core`, a path on disk that resolves to this repository's source, and
build-logic plugins in this repository that compile it. Nothing is renamed, relocated or rewritten on
the way in.
