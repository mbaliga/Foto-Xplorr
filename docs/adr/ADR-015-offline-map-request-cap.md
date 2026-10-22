# ADR-015 — Offline map downloads: how the provider's request limit is applied

**Status:** accepted for the Connect build; unverified on a device (see "What is not known").
**Date:** 22 Oct 2026
**Scope of this record:** the one place the app gives the map library an HTTP client
(`feature/ai-remote/.../MapRequestCap.kt`) and the compile-only declaration behind it. The wider
decision (source, caps, regions, UI) is ADR-016.

## Context

Owner feedback #23 asks for a one-tap download of a map region. The only tile source whose written
terms allow an app to download for offline use is OpenFreeMap. The terms as read on 22 Oct 2026:
about 3,000 tiles or 50 MB per download, at most 4 requests in flight, and a User-Agent that names
the app. The app's caps (2,500 tiles, 45 MB) sit below those numbers on purpose
(`OfflineMapLimits`). OpenStreetMap's own tile servers forbid this use and are not used.

The map library (MapLibre 13.0.2, Connect only) downloads regions with its own `OfflineManager`.
Its HTTP layer allows 10 to 20 requests per host and has one public hook,
`HttpRequestUtil.setOkHttpClient(okhttp3.Call.Factory)`. A download therefore needs that hook to
keep to 4.

## Decision

1. The cap is applied through that hook, from `MapRequestCap.install(4, "Fotoz")`, only while a
   region is being downloaded, and removed by `MapRequestCap.restore()` when the last download stops.
   Live panning is never throttled. The installed client has one dispatcher limited to 4 calls in
   total and per host, and appends `Fotoz` to the library's own User-Agent.
2. The hook's parameter type is an OkHttp type. The map library declares OkHttp with runtime scope
   only (checked in its Gradle module metadata), so `:app` cannot compile a call to it. Rather than
   give `:app` an OkHttp dependency, the single wrapper lives in `:feature:ai-remote`, which already
   owns OkHttp, and that module takes `compileOnly("org.maplibre.gl:android-sdk:13.0.2")`. The
   artifact is the one `:app` already has as `connectImplementation`; the declaration adds nothing to
   any resolved classpath (compile-only), so `verifyOfflineRuntimeClasspath` is unaffected, and no
   Offline code can reach the class.
3. The cap starts the map library itself (`MapLibre.getInstance`, idempotent) before its first call
   into the HTTP hook. The library's HTTP class reads the application context when it first loads and,
   read from its bytecode, fails permanently for the process if the library has not been started: the
   probe made when the Offline maps pane opens on a cold process would then both report "unavailable"
   and break the live map's own requests. If the library cannot be started, `install` returns false
   and the hook is not touched.
4. A release build offers no download until `MapLibreRegionEngine.CAP_VERIFIED_ON_DEVICE` is set,
   which is done only after the check under "What is not known" has been made. Debug builds are
   allowed, so the check can be done. Until then the pane says downloads are unavailable and the cap
   is not even probed.
5. A build where the cap cannot be applied (the hook missing, the client refused, a link error)
   offers no download. The engine reports `throttleInstalled = false`, the Download and Resume
   buttons are disabled, and `resume` refuses with `THROTTLE_NOT_INSTALLED` and fetches nothing.
   The app does not fall back to downloading without the cap.

## What is not known

- Whether the native downloader sends its requests through this client at all. This is read from
  bytecode, not run. (`libmaplibre.so` registers a Java `HTTPRequest` peer and has no libcurl or cronet
  strings, which points to the Java OkHttp path, but that is not proof.) To check: run a debug build
  behind a logging proxy, download an area, and confirm no more than 4 requests are ever in flight and
  that the User-Agent ends in `Fotoz`; then set `CAP_VERIFIED_ON_DEVICE` to true. If it does not, the cap is not in force and the download must not ship; the
  next step is then to ask OpenFreeMap for written permission, not to work around the limit.
- Whether `OkHttp` 5.x (resolved for the app through `:feature:ai-remote`) satisfies the map
  library's calls, which were compiled against 4.12. The live map already depends on this.

## Consequences

- `:feature:ai-remote` now compiles against MapLibre but never links it unless the Connect build
  loads `MapRequestCap`.
- Re-read the provider's terms before every release; a larger allowance needs their written
  permission.
