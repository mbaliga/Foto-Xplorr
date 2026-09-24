# Constellation toolchain upgrade — a plan, not a Phase 1 work package

**Written:** 24 Sep 2026, as part of Foto Xplorr WP1.1 (ADR-009 decision 6).
**Status:** not started. This document exists so the eventual bump has a plan; it commits
nobody to executing it, and Phase 1 needs none of it (ADR-009).

## Why this is its own project

`hyle-design-system` and `shared-libraries` (`Shared-Libraries-asoc`) are consumed by more than
one app through Gradle composite builds (`includeBuild`, decision D-A in Foto Xplorr's own
history). A composite build hard-fails the moment its members disagree on Android Gradle Plugin
version (D-Q, Foto Xplorr's TRAPS #3): "Using multiple versions of the Android Gradle Plugin ...
is not allowed." That means neither submodule repo can bump AGP alone, and neither can a single
consumer — the bump has to land in the submodules first, then propagate to every consumer in one
coordinated pass. That coordination is a project of its own, not a line item inside any one
consumer's roadmap. See ADR-009 for the Foto Xplorr-side decision this document is the flip side
of: Phase 1 stays on the current pins and defers this entirely.

## Repos that include Hyle and/or shared-libraries (visible to this session)

Only repos already attached to this session are listed. Other consumers may exist (ADR-009
mentions "Fonebrew and others") — this document does not enumerate them further, since cloning
repos outside what the owner has already given this session is out of scope for a docs-only WP.
The owner should extend this table before actually executing the upgrade.

| Repo | Includes Hyle | Includes shared-libraries | Mechanism |
|---|---|---|---|
| `hyle-design-system` | — (is Hyle) | no | — |
| `Shared-Libraries-asoc` | no | — (is shared-libraries) | — |
| `Foto-Xplorr` | yes | yes | git submodule + `includeBuild` (this repo) |
| `Fyl-Manager` | yes | yes | git submodule + `includeBuild` |
| `asystemofcells` | no (Astro/Node monorepo; `packages/kit` is *conceptually* derived from Hyle's design tokens, not a Gradle submodule) | no | n/a — not a Gradle consumer |
| `Music_Player` | no (Capacitor/JS app, no Gradle/Kotlin build at all) | no | n/a |

## Current pins (as of 24 Sep 2026)

| Repo | AGP | Kotlin | Gradle | Compose BOM | Notes |
|---|---|---|---|---|---|
| `hyle-design-system` | 8.9.1 | 2.1.0 | 8.14.3 | 2025.05.01 | Also pins KSP `2.1.0-1.0.29`, Room `2.7.1` |
| `Shared-Libraries-asoc` | 8.9.1 (assumed identical; composite-included by both consumers below without AGP conflict) | 2.1.0 | 8.14.3 | n/a (no Compose UI in this module) | |
| `Foto-Xplorr` | 8.9.1 | 2.1.0 | 8.14.3 | 2025.05.01 | Has an explicit `kotlin-stdlib:2.1.0` force-pin (ADR-009 consequence) |
| `Fyl-Manager` | 8.9.1 | **2.1.20** | 8.14.3 | (not checked — not this repo's concern) | **Already diverges** from the other three on Kotlin's exact patch. AGP and Gradle agree, which is why the composite build doesn't hard-fail (D-Q is an AGP rule, not a Kotlin one) — but it means the constellation is not actually at one Kotlin version today, and the eventual bump should reconcile this rather than assume a clean starting line. |

The Kotlin divergence in `Fyl-Manager` was found by inspection while writing this document, not
introduced by it. It is not Foto Xplorr's to fix; noting it here so whoever executes the
constellation bump doesn't discover it mid-bump.

## Target versions

No fixed target AGP/Kotlin/Gradle/Compose BOM is pinned in this document. Phase 9 (Compose
Multiplatform Desktop, the trigger below) is many phases away; a specific version chosen today
would be stale long before anyone executes this. Instead, pick the latest stable combination
*at bump time* that satisfies:

| Need | Requirement |
|---|---|
| Room, if moving past 2.7.x | Kotlin floor for the target Room minor (check Room's release notes) |
| Compose Multiplatform Desktop (Phase 9's own trigger) | Compose MP's stated minimum Kotlin for the desktop target |
| `compileSdk` at bump time | AGP version supporting it |
| Hyle's and shared-libraries' own dependencies | Re-verify each still resolves at the new Kotlin/AGP before committing |
| The `kotlin-stdlib` force-pin in Foto Xplorr's `app/build.gradle.kts` | Either widen it to the new version or confirm it can be dropped (ADR-009 consequence: some Android deps already compile against a newer stdlib than the pin) |

Re-run the same due-diligence ADR-009 did for Phase 1 (a needs-table against current pins)
before choosing numbers, rather than upgrading because a newer version merely exists.

## Trigger

Execute this upgrade when either:
1. Phase 9 (Compose Multiplatform Desktop) starts, or
2. any earlier phase needs a Kotlin version above 2.1 for a specific dependency (for example,
   Room 2.8.x, or an AndroidX KMP library whose native-target support requires it) — record
   that need under Decisions in `docs/handoff/MASTER-PROGRESS.md` when it happens, and open this
   as its own project rather than smuggling a partial bump into that phase's own WP.

## Order of execution (once triggered)

1. **`hyle-design-system`**: bump AGP/Kotlin/Gradle/Compose BOM/KSP/Room there first, on its own
   branch, merged with a merge commit (never squash — submodule history needs to stay bisectable
   for consumers pinned to an older SHA mid-rollout).
2. **`Shared-Libraries-asoc`**: same bump, same merge-commit discipline. Depends on step 1 only
   in the sense that both should land on the same target versions; there's no build-order
   dependency between the two submodules themselves.
3. **Each consumer**, in any order, but not before both submodules have merged:
   - update its submodule pin (`git submodule update --remote` or equivalent, committed
     explicitly, not silently picked up);
   - bump its own root `build.gradle.kts` / plugin versions to match;
   - re-verify: `./scripts/verify.sh` (Foto Xplorr) or the equivalent gate in each other consumer;
   - for Foto Xplorr specifically: re-check the `kotlin-stdlib` force-pin (see Target versions
     above) and the native-target (`-Pfotoz.native=true`) build, since Kotlin/Native toolchains
     are version-sensitive in ways the JVM/Android targets aren't.
4. Known consumers to update in step 3, from the table above: `Foto-Xplorr`, `Fyl-Manager`, plus
   any consumer not visible to this session (see the caveat above).

## Explicitly out of scope here

- Decoupling from the composite build (consuming Hyle/shared-libraries as published artifacts
  instead of submodules) — ADR-009 lists this as a rejected alternative for Phase 1 and defers
  it to an owner-level decision; if the owner wants it, it replaces this whole document rather
  than extending it.
- Picking the actual target version numbers now (see Target versions above).
- Touching `asystemofcells` or `Music_Player` — neither is a Gradle/Kotlin consumer of either
  submodule.
