# ADR-018 — Optional cloud sync: one-way copies, never deleting

**Status:** accepted; nothing in it has run on a device (`docs/device-test/batch-9-checklist.md`, sections 8 and 9).
**Date:** 3 Nov 2026
**Scope:** what sync is and is not, the providers, credentials, sign-in, the flavor boundary, how protected photos are kept out, and the cleartext decision.

## Context

Owner feedback #8 (batch 9): optional sync to a cloud of the person's choice was "too hidden or non existent". Providers asked for: WebDAV/Nextcloud, S3-compatible, Google Drive, Dropbox, and a folder from the system picker. Fotoz's identity is local-first, so the feature is optional, off by default, and never on by surprise.

## Decision 1 — A one-way mirror of copies, started by a tap

- `sync/` sends **copies** of chosen photos and videos to one destination. It **never deletes and never overwrites** anything at the destination (S3 uses `If-None-Match: *`; adopting a file already there needs a matching hash or mtime). A photo deleted or edited on the phone leaves the remote copy alone.
- It **starts only when the person taps** send, and runs as a foreground job (`ActivityKind.SYNCING`, with Cancel). Wi-Fi only is the default. There is no schedule and no background trigger.
- It is **resumable per file**: a file is marked sent only when the destination confirmed it (size/hash/status); an unconfirmed write is left for the next run and an interrupted file restarts from byte 0 (multipart/resumable uploads continue where the provider allows).
- One destination at a time. The manifest of what was sent is app-private, per destination id.

## Decision 2 — Providers and the flavor boundary

| Provider | Offline flavor | Connect flavor |
|---|---|---|
| Folder (system picker, SAF) | yes | yes |
| WebDAV / Nextcloud / ownCloud | no | yes |
| S3-compatible (B2, R2, MinIO, AWS) | no | yes |
| Google Drive (scope `drive.file`) | no | yes, needs your own client id |
| Dropbox (App folder) | no | yes, needs your own app key |

The Offline flavor contains **no network code**; `sync/SyncTransports` and `sync/OAuthPlatform` have same-FQN stubs there. The folder provider works in both because the system document provider does the transfer (it may be a cloud app's folder; Fotoz does not know or care). `HttpCaller` (the policy layer) sits in `sync/` over a transport interface; the OkHttp-backed transport lives in `feature:ai-remote` (`SyncHttp`), the module that owns OkHttp, and is reached through `src/connect`; the OAuth browser seam lives in `src/connect`. **Zero new Gradle dependencies.**

**Connect-only boundary:** Drive and Dropbox are not "one click". The owner must register their own apps (below); Fotoz ships no client id and no secret, so nobody else's quota or consent screen is involved.

## Decision 3 — Credentials

- WebDAV password and S3 secret key are held in `EncryptedSecretStore` (Android Keystore), ids prefixed `sync:`, the same store as the AI keys. Config files hold addresses and user names only, app-private, never logged. Secrets are passed as `CharArray` and zeroed after use.
- **OAuth (Drive, Dropbox):** authorization code with PKCE in the system browser (Custom Tab when offered, else the ordinary browser; no WebView), with the person's **own client id**. Redirect scheme `com.fotoxplorr.app.oauth` (`OAuthRedirect.SCHEME`, repeated in the Connect manifest and pinned by a test). Only the refresh token is stored (as the destination's secret); sign-out revokes where the provider supports it.
- To register: **Google** — a Cloud project with the Drive API enabled, an OAuth client (type not yet confirmed: the redirect `com.fotoxplorr.app.oauth:/oauth2redirect` must be accepted for it) and the consent screen set to *In production* (in Testing, refresh tokens expire after 7 days). **Dropbox** — a scoped app with *App folder* access, permissions `files.metadata.read`, `files.metadata.write`, `files.content.write`, `account_info.read`, redirect `com.fotoxplorr.app.oauth:/dropbox`.

## Decision 4 — Protected photos never leave (fail closed)

Locked-folder, sensitive, archived and trashed photos are never sent. The engine only sees the **browsable** list (TRAPS #33), fed by `SyncLibraryFeed` in the activity so it is current whichever pane is open, and each file is re-checked by `BrowsableGate` at upload time (a photo locked mid-run is skipped). When the feeder leaves the composition the list is empty and nothing is sent. Nothing in the pane can widen the selection beyond that list.

## Decision 5 — Optional encryption, with its limits

A destination may turn on encrypt-before-upload: PBKDF2-HMAC-SHA256 (`BackupFormat.DEFAULT_ITERATIONS`, 600,000) over a passphrase, the backup's chunked AEAD, files named `<name>.fzs` (`BackupKind.SYNC_FILE`). **There is no recovery**: lose the passphrase and the copies are unreadable. File names and sizes stay visible to the server. Plain copies take the platform's view of a photo, which on Android 10+ may omit GPS; encrypted copies ask for the unredacted original and keep the location (like the encrypted backup). Whether plain copies should carry GPS is an open owner decision (`SyncAndroid.createService`, `includeLocation`).

## Decision 6 — Cleartext in the Connect build (owner decision pending)

Sync to a NAS at `http://192.168.x.x` and a local Ollama for AI both need plain http, which Android (target 28+) refuses unless the app allows it. Android cannot scope cleartext to private address ranges (`domain-config` takes host names only). So `connect/res/xml/network_security_config.xml` sets `cleartextTrafficPermitted="true"` for the **whole Connect build**. The policy sits in code: sync's `HttpCaller` allows plain http only when the person switched on "Allow plain http on my home network" **and** the host is a local address (`HostRules`); the AI client has its own https-or-local check.

- **Trade-off:** anything else in the Connect build that mistakenly uses `http://` is now sent in the clear instead of failing. The Offline build has no such file and no network permission; its statements ("no network permission at all") are unchanged. The Connect build never claims "no cleartext".
- **Alternative:** drop the plain-http option (HTTPS only, no NAS over http, no local Ollama). **The owner has not chosen**; revisit before a store release of the Connect build.

## What is explicitly not built

Two-way sync or restore from the cloud; delete propagation; overwriting or versioning remote files; background or scheduled sync; more than one destination; sync of protected, sensitive, archived or trashed photos; Google Photos, iCloud, OneDrive API (OneDrive works as a SAF folder only); sharing links; a Fotoz-run server. Recovery of a forgotten encryption passphrase.

## What is not known

Every provider is tested against recorded and fake servers only. No real Nextcloud, S3 service, Drive or Dropbox account has been used. The Google client type, behaviour of SAF cloud providers (rename, "still loading"), multipart cancellation on the server and PBKDF2 time on a low-end phone are all unchecked. Play Console: the Connect flavor is not the listed app; if it is ever listed, Data safety and the privacy policy must describe sync.
