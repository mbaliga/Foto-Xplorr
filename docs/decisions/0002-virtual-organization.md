# ADR 0002: Groups, albums, stacks, and tags are virtual by default

Status: accepted

## Context

The product needs organization richer than folders, while users also need physical move/copy operations.

## Decision

Folders represent storage. Albums, groups, stacks, and tags live in the Foto Xlorr catalog and do not move or rewrite originals by default. Optional XMP sidecar export can make selected metadata portable.

## Consequences

- One asset can belong to many groups/albums/tags.
- The UI must clearly distinguish “add to group” from “move to folder.”
- Catalog backup is essential because organization is not inherently stored in the original file.
- Sidecar merge and identity rules need tests before external synchronization is advertised.
