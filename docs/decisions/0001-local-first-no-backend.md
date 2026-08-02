# ADR 0001: Local-first operation with no Foto Xlorr backend

Status: accepted

## Context

The product must manage highly private media without requiring an account, hosted service, or proprietary backup platform.

## Decision

Core scanning, cataloging, viewing, organization, file operations, spatial metadata, and local backup run on the device. Network access is feature-scoped and opt-in. Remote AI uses user-configured endpoints/credentials directly; Foto Xlorr does not proxy the traffic. External synchronization works through user-managed folders and tools such as Syncthing.

## Consequences

- The app must handle migrations, indexing, search, backup, and recovery locally.
- Cross-device sync is not magically conflict-free; sidecar and backup formats need documented identifiers and merge behavior.
- Optional network features require explicit disclosure and may never be hidden dependencies of core browsing.
- Support cannot rely on server-side repair or telemetry.
