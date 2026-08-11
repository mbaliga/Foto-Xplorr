# TRAPS — invariants that bind every session working on this repo

Each of these was earned, not invented: every entry is a defect that either shipped or
was caught one line before shipping. Breaking one is never a refactor; it is a behaviour
change that needs saying out loud. Source: the 6 Aug 2026 handoff plus plan FX-IMP-002 §8.

## Long-standing

1. **A delta scan must never call `replaceAll`.** One line away from deleting the
   library's index. The delta branch is deliberately `Unit` with a comment saying exactly
   that; `MediaIndexerTest` pins it.
2. **Never squash-merge `shared-libraries` (or `hyle-design-system`) while this app pins
   it.** A squash mints a new SHA and orphans the submodule pin; a fresh
   `clone --recurse-submodules` fails weeks later with `fatal: reference is not a tree`,
   pointing nowhere near the cause. Merge with `--no-ff`, then bump the pin here.
3. **AGP must match `hyle-design-system` exactly** (currently 8.9.1), Gradle wrapper
   pinned at 8.14.3 in all three builds. Composite builds hard-fail with *"Using multiple
   versions of the Android Gradle plugin"* otherwise.
4. **The `dependencySubstitution` block in `settings.gradle.kts` is load-bearing.**
   Declaring ANY explicit substitution for an included build disables AUTOMATIC
   substitution for that build — a module you forget to declare is silently not
   substituted. The inline reasoning stays with the block.
5. **The scrubber assumes a headerless grid.** Grid item *n* is asset *n*. Date headers,
   stacks or any non-1:1 grid item silently desync the edge timeline strip from the list.
6. **`local.properties` (with `sdk.dir`) is needed in every included build** on a fresh
   clone — the root's copy does not propagate. `scripts/verify.sh` creates them if absent
   and never clobbers an existing file.
7. **Trash requires Android 11+.** On older versions decline rather than deleting without
   the platform dialog.

## Added by FX-IMP-002 (10 Aug 2026)

8. **A partial scan never sweeps.** The generalisation of #1 to every future source: a
   scan that did not fully enumerate its root must not remove anything it failed to see.
9. **Nothing that changes grid item count ships before `GridIndexMap`.** Conflict stacks,
   duplicate stacks, grouping and headers all break #5 through different doors. (WP3
   ticket FX-044; nothing on the current branch stacks yet.)
10. **Foreign keys are enabled in `SQLiteOpenHelper.onConfigure` only.** Anywhere else
    is a silent no-op (inside a transaction) or a throw (`setForeignKeyConstraintsEnabled`
    mid-transaction). Every `ON DELETE CASCADE` is decorative until this is done.
11. **`PRAGMA integrity_check` does not check foreign keys.** It covers page/index/
    UNIQUE/NOT NULL structure. `PRAGMA foreign_key_check` is the only thing that finds FK
    violations. Run both, after COMMIT, never inside the migration transaction.
12. **The offline flavor's gate is the merged manifest and the runtime classpath, not an
    import scan.** `android.net.Uri` and `java.net.URI` do no I/O; a package-level import
    ban breaks every MediaStore/SAF call in the app. Gates: `verifyOfflineManifest`,
    `verifyOfflineRuntimeClasspath`, then a *targeted* FQCN denylist — in that order of
    authority.
13. **Never the standalone word `Synced`.** Four operating modes, four different
    promises. `Syncthing · Replicated local folder`, never `Synced by Foto Xplorr`.
14. **Never encode state in colour alone.** Pair every state with shape, label or icon.
15. **Phase C of a migration is forward-only.** The user writes to the live catalogue
    throughout a backfill; restoring the pre-migration backup to "fix" a failed batch
    silently discards everything they did since. Resume from `migration_progress`.
16. **`zipalign -P 16`, never `-p`.** At target 36 the app must support 16 KB page
    sizes; `-p` silently downgrades correct 16 KB alignment to 4 KB and native libraries
    stop loading on 16 KB devices. Verify with `zipalign -c -P 16 -v 4`.
