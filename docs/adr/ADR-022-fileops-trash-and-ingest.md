# ADR-022 — File operations, the app trash on SAF/USB, and ingest (WP3.5, 3.6)

**Status:** PROPOSED (owner decision needed)
**Date:** 11 Nov 2026

## Context

Standing rule: no path deletes or overwrites an original except an explicit, confirmed action; destructive operations go through a trash; writes are temp, verify, replace. MediaStore has a system trash (`createTrashRequest`, `FotoXplorrActivity.kt:675`). A SAF tree or USB drive has none. SAF cannot rename onto an existing name (TRAPS #141) and a provider may rename on clash. The plan says `.fotoz-trash/` plus a manifest, 30-day expiry, restore.

## Proposed decision

1. **Engine in `:core:organize`** (`fileops` package): plans, conflict policy, verification and the journal are pure; the Android seam implements a small `FileAccess` interface for `File` and for SAF documents. Failure-injecting fakes test every step, as `SafeDocumentWrite` is tested.
2. **Copy** writes `name.fotoz-part` beside the target, verifies size and a streaming hash, then renames; an interrupted run leaves only `.fotoz-part` files that the next run recognises and finishes or removes. **Move** within a source uses the provider's native move; across sources it is copy, verify, then trash the original (never a bare delete).
3. **Conflicts**: skip, keep both (`name (2).ext`), or replace. Replace sends the existing file to the trash first; it never overwrites in place. "Apply to all" is a per-run policy.
4. **Trash on SAF/USB**: a visible folder `.fotoz-trash/` at the source root holding the file under a unique name plus `.fotoz-trash/manifest.json` (original path, name, size, time). Other apps may see it; a `.nomedia` file is written inside. **No automatic purge in v1**: the 30-day expiry is computed and shown ("will be removed on ..."), and a purge happens only when the person taps Empty trash. Restore moves the file back, or keeps both on a clash.
5. **Ingest (WP3.6)**: destination template `{yyyy}/{yyyy-MM-dd}` (extends `RenamePattern`), skip duplicates by fingerprint (size plus head/tail hash), optional second destination, verify both, and "delete from card" is offered only after both copies verify and is a separate confirmed step. MTP/PTP uses the platform `android.mtp.MtpDevice` (no dependency); it is device-only and ships behind the same plan/verify core.

## Open for the owner

Auto-purge after 30 days (recommended: off in v1); whether the trash folder may be visible on a user's drive (recommended: yes, with `.nomedia`, because a hidden folder on a drive the person then plugs into a computer is a surprise either way); delete-after-ingest wording.
