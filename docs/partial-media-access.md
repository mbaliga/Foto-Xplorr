# Partial media access (FX-004)

## Current state — verified in source, 10 Aug 2026

- `READ_MEDIA_VISUAL_USER_SELECTED` **is declared** (`app/src/main/AndroidManifest.xml:25`)
  and included in the permission request on SDK 34+. The app is therefore **not** in the
  system compatibility mode whose grants are revoked on backgrounding — the failure shape
  FX-ISP-001 warned about ("the indexer intermittently loses the library") is not live.
- `hasMediaPermission()` treats *any* granted media permission as access, so a
  partial-selection grant indexes and shows exactly the selected items. Nothing crashes,
  and un-selected media is simply absent.

## Reselection UX

The Media settings tab now detects Android 14+'s partial grant, names the limited mode,
and offers **Manage selected photos and videos**. It reopens the platform permission
sheet with the same image/video/selected-media request used at onboarding, allowing the
user to expand or replace the selection without leaving the app. Grant state is
re-read on `ON_RESUME`, including after a change made in system Settings.

- The catalogue must never read "the library shrank" from a partial grant. This
  holds *implicitly* (the indexer only sees what MediaStore exposes and the delta path
  never sweeps unseen items — the `replaceAll` trap), but WP2's mark-and-sweep
  generalisation must carry the same rule explicitly: **an unselected item is
  unavailable, not deleted** (the same invariant WP3's FX-045 states for revoked SAF
  grants).

The degraded mode is therefore functional and no longer dead-ended. The exact number of
selected items is deliberately not claimed: the persisted catalogue may contain entries
that are temporarily unavailable under the current grant, so `assets.size` is not a
trustworthy permission-scope count until WP2 makes visibility explicit.

## `[OWNER]` device checks (no emulator here can stand in for these)

1. Fresh install → grant **"Select photos"** with a handful of items → the grid shows
   exactly those; no error surface; banner counts only those.
2. Background the app, kill it, relaunch → the same items are still there (the
   compatibility-mode revocation bug would empty the grid here).
3. Add more photos via **system Settings → Apps → Foto Xplorr → Photos and videos** →
   relaunch → delta scan picks up the additions and says "Added N new items".
4. Reduce the selection in system Settings → relaunch → removed items disappear from the
   grid **without** any "deleted" messaging, and favourites/tags on them survive a
   re-grant (they key on MediaStore id).
