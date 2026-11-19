# ADR-021 — Dependencies for network sources and cast (WP3.9, 3.12)

**Status:** PROPOSED (owner decision needed; recommendation is to add none now)
**Date:** 11 Nov 2026

## Context

The plan names smbj (SMB), sshj (SFTP), the MinIO SDK or a SigV4 client (S3), UPnPCast (DLNA) and Ktor CIO or NanoHTTPD (a local HTTP server for cast). Standing rules: zero new Gradle dependencies without an approved ADR; the Offline flavor has no network code or network-capable library; every dependency needs a row in `docs/DEPENDENCIES.md`. What exists: `sync/` already speaks WebDAV (`WebDavProvider`, PROPFIND), S3 with SigV4 (`S3CompatibleProvider`, `SigV4`), Drive and Dropbox, over a `HttpTransport` seam whose only real implementation is OkHttp in `:feature:ai-remote`. Those providers are write-only (`SyncProvider` has stat/list/put, no read).

## Options per item

| Item | Option | Cost |
|---|---|---|
| WebDAV, S3 as a read source | Add a `get(path, range)` to the existing transport and write `Source` adapters in `src/connect` | No dependency. Recommended. |
| Immich | Hand-written minimal REST client on the same transport (no generated code) | No dependency; needs a real server to verify. |
| SMB | smbj (Apache-2.0) | New dependency, large, crypto providers, R8 rules. Recommend: not now. |
| SFTP | sshj (Apache-2.0) | New dependency plus BouncyCastle-class transitive weight. Not now. |
| DLNA/cast | UPnPCast (MIT) plus a local HTTP server | Two dependencies and an open port on the phone. Not now. |
| Chromecast | CASTV2 library, licence unverified | Not now. |

## Proposed decision

Add nothing in this wave. Build WebDAV and S3 read sources on the existing transport after the SAF source works, Connect flavor only, tested with a fake transport as `sync/` is. Revisit SMB, SFTP and cast as separate ADRs with the owner's explicit yes per library, each with its size, licence file, network-capable flag and the offline gates' result.

## Consequences

Network sources are limited to WebDAV/Nextcloud, S3-compatible (including MinIO servers) and, later, Immich. A NAS reachable only by SMB is reached through the system Files app (an SAF provider) in the meantime.
