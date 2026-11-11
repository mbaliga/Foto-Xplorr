# ADR-019 — The Nooz model catalogue: what Fotoz consumes and what does not fit

**Status:** accepted; nothing in it has run on a device.
**Date:** 3 Nov 2026
**Scope:** how Services > Models on this phone gets its list, which parts of the Nooz catalogue Fotoz uses, where Nooz and Fotoz disagree, and the option to share the parser.

## Context

Owner feedback #8: the on-device "one click model catalogue" was hidden. Nooz (the sibling project) publishes a **data-only** `ai-catalogue/` (`models.json`, `free-tiers.json`). Fotoz reads it instead of inventing a second format.

## Decision — consumer contract

- `services/catalogue/NoozCatalogue.kt` (+ `MiniJson`) parses the files; `CataloguePolicy` decides what may be offered and verifies a finished download; `CatalogueStore` holds the cache.
- A **snapshot is bundled** in `assets/ai-catalogue/` (refreshed by `scripts/refresh-ai-catalogue.sh`), so the screen works offline.
- The only network use is an explicit **"Update catalogue" tap, Connect flavor only**. The address is pinned to a **Nooz commit** (`CatalogueStore.BASE_URL`, currently `c396e9a2d022ac28e7ce0a238c33ffc602afc7cf`), never a branch, so a Nooz change cannot alter what Fotoz fetches. The cache is written atomically; a failed fetch keeps what was there.
- Missing fields mean unknown (null), never zero. Size and sha256 are checked after a download **only when the catalogue states them**.
- Kinds: `LLM_GGUF`, `SD_IMAGE`, `TTS_ONNX`, `UNKNOWN`. Fotoz has **no runtime** for any of them, so LLM and image models are shown collapsed with **Get disabled**, TTS is hidden (no audio), and nothing is offered for a kind that cannot be run. Free-tier lines for remote AI providers are shown as information.

## The mismatch

Nooz's schema has no vision kind. The one model Fotoz can download and run, its MobileNet look-alike embedder, therefore cannot come from the catalogue: it stays in Fotoz's own `ModelCatalogue` (`ModelSpec`), and it has **no published checksum**. The screen shows the SHA-256 it computed locally and never calls a model "verified". Suggested addition to Nooz's schema: kind `VISION_TFLITE` with task, input size and licence fields, plus a sha256 for each file.

## Option — lift the parser into Shared-Libraries-asoc

The parser and policy have no Android dependency except the cache path. They could move to the shared library (owned by another repo; not touched in batch 9) so Nooz and Fotoz share one reader. Not done: it needs the schema decision above first.

## Consequences

Fotoz offers no model download it cannot use; the catalogue is advice, not a promise; Offline flavor keeps the bundled snapshot and "import a file".
