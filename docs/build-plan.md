# Foto Xlorr build plan

Status: ordered implementation plan

This plan is dependency-driven rather than calendar-driven. A milestone exits only when its acceptance gates pass.

## P0 — Repository and vertical slice

Deliverables:

- Android application skeleton and CI.
- Full or selected-photo access flow.
- Bounded `MediaStore` scan.
- Adaptive grid and chronological timeline.
- Platform viewer with animated drawable playback.
- Bearing math unit tests.
- Product, architecture, format, privacy, and reference documentation.

Exit gates:

- Unit tests, lint, and debug assembly pass in CI.
- App starts without network access.
- Denied, partial, and full photo-access states are represented honestly.
- No broad all-files permission, analytics SDK, cloud key, or hidden service is present.

## P1 — Dependable core gallery

Deliverables:

- Durable Room catalog with migrations.
- Incremental pagination and `ContentObserver` reconciliation.
- Folder and source views.
- Viewer zoom/pan, next/previous navigation, metadata/details, share, and animation controls.
- Multi-select and selection persistence.
- Copy, move, rename, trash, restore where available, and permanent delete flows.
- Per-item error reporting and conflict handling.
- Tags, albums, manual groups, stacks, and representative selection.
- Search by filename, folder, date, format, dimensions, tag, album, and group.

Exit gates:

- No source item is deleted before a cross-provider move has been verified.
- Process death during scanning or operations does not corrupt the catalog.
- A partial batch result never appears as complete success.
- Large-library scrolling and selection are benchmarked on low-memory hardware.
- Accessibility checks pass for canonical non-spatial journeys.

## P2 — Extended sources, formats, and portability

Deliverables:

- User-selected SAF tree sources with persisted grants and revocation UX.
- Decoder registry and capability reporting.
- Managed SVG rendering.
- Prioritized native codec modules after license/security review.
- Compatibility test corpus and malformed-file tests.
- XMP sidecar import/export for selected catalog fields.
- Portable catalog export.
- Encrypted backup and restore preview after a cryptographic ADR and review.
- Syncthing integration guide based on a user-selected sidecar/backup directory.

Exit gates:

- Each claimed format has fixtures, expected behavior, and a known limitations entry.
- Native codecs are isolated, resource-bounded, and fuzzed.
- Backup is verified before success and can be restored on a clean device.
- Credentials and Keystore material are absent from exports.
- Revoked SAF access fails safely without deleting catalog organization.

## P3 — Spatial foundation

Deliverables:

- Location/altitude/direction extraction with provenance and confidence.
- Permission-on-use flow for unredacted media location and optional current location.
- Offline-capable 2D map adapter and explicit package import/download.
- Map clustering, date filtering, and unlocated-photo queue.
- Lifecycle-aware rotation-vector sensor pipeline.
- Compass clusters relative to current, selected, or photo/group anchor.
- Calibration, smoothing, reduced-motion, and list fallback.
- Spatial correction UI for user-supplied coordinates and altitude.

Exit gates:

- No network request occurs merely by entering map or compass mode.
- Unknown coordinates or direction are never fabricated.
- Compass placement unit tests cover dateline, poles, wraparound, and display rotation.
- Sensor work stops when the view is backgrounded.
- Map and compass have accessible non-motion alternatives.

## P4 — 3D visualization

Deliverables:

- Alternate 3D timeline renderer over the existing timeline query model.
- Terrain dataset interface, import/download manifest, and license tracking.
- Terrain mesh renderer with level of detail and bounded memory.
- Photo markers/cards with altitude provenance.
- GPU capability detection and 2D fallback.
- Spatial group drill-down and smooth transition back to canonical views.

Exit gates:

- 3D is optional and does not fork catalog or selection logic.
- Unsupported or constrained devices get a complete 2D experience.
- Terrain packages are user-controlled and removable.
- Rendering survives large clusters without decoding original images at scene resolution.
- Motion and flashing behavior respect accessibility preferences.

## P5 — User-controlled AI

Deliverables:

- Stable provider and capability API.
- Local GGUF model import, validation, storage accounting, and removal.
- Multimodal model/projection pairing where required.
- OpenAI-compatible endpoint adapter with user base URL/model/key.
- Optional cloud adapters only after explicit disclosure and policy review.
- Caption, embedding, semantic search, OCR, tag suggestion, group suggestion, and duplicate explanation jobs.
- Review queue, provenance, user correction, and rejection memory.
- Charging/thermal/battery/data controls and cancellation.

Exit gates:

- The app remains fully usable with no provider configured.
- A text-only model is never presented as photo-capable.
- Networked jobs clearly identify what will leave the device and where.
- Keys are not logged, exported, committed, or included in backups.
- Model output cannot silently move/delete files or overwrite original metadata.
- AI jobs are reproducible enough to show provider, model, settings, and source assets.

## P6 — Release hardening

Deliverables:

- License and third-party notices.
- Threat-model review and native dependency SBOM.
- Database and backup migration policy.
- Performance/macrobenchmark suite.
- Crash recovery, low-storage, revoked-permission, and interrupted-operation testing.
- Large-screen/foldable layouts and accessibility audit.
- Reproducible signed release process without repository secrets leaking into forks.
- User documentation and privacy disclosure matching actual behavior.

Exit gates:

- All release claims map to automated or documented manual evidence.
- No unresolved critical/high security finding.
- Upgrade and restore tests cover every supported schema.
- A release artifact can be built from the tagged source using documented tooling.

## Work that must not be combined prematurely

- Do not introduce native codecs before the isolated decoder contract and corpus exist.
- Do not implement terrain on top of an assumed map-library feature; keep the terrain renderer independent.
- Do not add cloud AI before the provider policy, disclosure, and key-storage tests exist.
- Do not store groups as folders or make organization depend on file mutation.
- Do not ship a custom encrypted container merely to check a roadmap box.
- Do not spend the first implementation pass polishing 3D while core file operations remain unreliable.
