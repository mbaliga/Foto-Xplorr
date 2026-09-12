# JVM performance baseline (FX-005)

**Captured:** 10 Aug 2026 · branch `claude/fotoz-ui-interactions-bxvgbw` · JDK 21,
Gradle 8.14.3, `org.xerial:sqlite-jdbc:3.45.1.0` · cloud Linux container (no device).

**How to regenerate:** `FX_PERF=true ./gradlew :app:testDebugUnitTest --tests
"com.fotoxplorr.app.gallery.ProjectionPerfBaselineTest"` — the table prints between
`FX_PERF_BASELINE_BEGIN/END` markers in the test's system-out. Median of 5 runs after 2
warmups, over the same `SyntheticCatalogue` the FX-003 goldens use.

**What these numbers are:** a regression tripwire for WP2 — the exit gate holds
projection and query wall time within 10% of this file *on the same class of machine*.
They are **not device numbers**: a mid-tier phone runs this code roughly 2–4× slower.
Device numbers come from the Macrobenchmark module (`benchmarks/`, `[OWNER]`-run).

| Catalogue | everydayAssets | 11 smart albums | 9 destinations | sortAssets ×4 | timelineStops | SQLite readAll |
|---|---|---|---|---|---|---|
| 10,000 | 13 ms | 52 ms | 31 ms | 9 ms | 3 ms | 74 ms |
| 100,000 | 44 ms | 579 ms | 359 ms | 85 ms | 14 ms | 427 ms |
| 500,000 | 413 ms | 4,397 ms | 2,505 ms | 625 ms | 65 ms | 1,887 ms |

## Reading the table honestly

- **The 100k p95-150 ms target is already gone at 100k for anything beyond the single
  everyday projection.** Computing all eleven smart-album projections (which
  `smartAlbumSummaries` does for the rail/albums surfaces) costs ~579 ms on a fast JVM —
  call it 1.5–2.5 s on a phone — and the nine destination projections ~359 ms. This is
  the cost of the full-materialisation design RECON finding 3 describes, and it is why
  paging (and incremental projection) is a WP2 ticket, not an optimisation to sprinkle
  later.
- `SQLite readAll` times the storage engine + query shape (`date_taken DESC,
  date_modified DESC, id DESC`, every column materialised) over JDBC. The Android
  cursor/binder stack adds overhead on top; treat 427 ms @ 100k as a floor, not an
  estimate.
- `timelineStops` is cheap everywhere — the scrubber is not the problem.
- Nothing here measures Compose recomposition, image decode, or memory pressure. A 500k
  in-memory catalogue is ~hundreds of MB of `MediaAsset` objects before any projection
  copies; the JVM heap for this test had to be raised to 4 GB to run at all, which is
  itself a finding about the current architecture.

## Device benchmarks (`[OWNER]`, unrun)

`benchmarks/` contains a Macrobenchmark module (startup, grid scroll, room transition).
It is deliberately **not** wired into `settings.gradle.kts` — it cannot run in this
environment, and an unrunnable module in the build graph is configuration cost for
nothing. To enable on a machine with a device attached, follow `benchmarks/README.md`
(one `include` line), run, and record the numbers here.
