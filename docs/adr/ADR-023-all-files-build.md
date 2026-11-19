# ADR-023 — An "all files" build (WP3.13)

**Status:** PROPOSED (recommendation: do not build in this wave)
**Date:** 11 Nov 2026

## Context

Plan WP3.13: an optional flavour dimension adding `MANAGE_EXTERNAL_STORAGE` for direct file walks and `/mnt/media_rw` access, for IzzyOnDroid/F-Droid only; Play must never get it. Today's flavours are `offline` and `connect` (`app/src/offline`, `app/src/connect`); the owner sideloads Offline and has a Connect APK from CI.

## Options

A. Do nothing; SAF trees cover folders and USB drives, at the cost of slower listing and no path access.
B. Add a third flavour dimension and CI artifact. Doubles the verification matrix (`verifyOfflineManifest`, `verifyOfflineRuntimeClasspath`, lint, tests per flavour) for a permission the owner has not asked to use.

## Proposed decision

A. Revisit only if a device test of the SAF indexer (50k files, USB) shows listing too slow to use. The SAF design keeps every file access behind interfaces (`DirectoryLister`, `FileAccess`), so a direct-path implementation can be added later without touching the gallery.
