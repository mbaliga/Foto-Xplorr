# Foto Xlorr

Foto Xlorr is a local-first, open-source Android gallery designed for fast browsing, spatial exploration, and user-controlled AI.

## Product principles

- No Foto Xlorr backend, account, telemetry, or mandatory cloud service.
- The ordinary gallery remains fully useful with AI disabled.
- AI is user-supplied: local GGUF models, OpenAI-compatible endpoints, or explicitly configured cloud providers using the user's own key.
- File operations are explicit and reversible where Android permits.
- Tags, groups, captions, and other catalog metadata do not modify image bytes by default.
- Syncthing and similar tools integrate through ordinary user-selected folders rather than a proprietary sync service.
- Local encrypted catalog and media backups are supported through a documented, portable format.

## Planned views

1. Fast grid and album views.
2. Chronological timeline and an experimental 3D timeline.
3. Map view using photo location metadata.
4. 3D terrain view for photos with elevation data and a user-provided/downloaded terrain dataset.
5. Compass view that uses device orientation to arrange geolocated photo clusters by bearing.

## Reality of format support

Android does not natively decode every image format. Foto Xlorr therefore uses an extensible decoder registry: platform decoders first, dedicated SVG and animated-image decoders, then isolated native codecs for additional formats. “All image types” is treated as a continuously tested compatibility target, not an honest claim that every historical, proprietary, malformed, or undocumented format can be decoded.

## Repository status

This repository is being initialized. See `docs/product-spec.md`, `docs/architecture.md`, and `docs/build-plan.md` for the implementation baseline.

## License

A project license has not yet been selected. No source release should be tagged until the license and third-party codec policy are locked.
