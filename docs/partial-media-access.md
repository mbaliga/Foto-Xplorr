# Partial media access (FX-004)

## Current state — verified in source, 10 Aug 2026

- `READ_MEDIA_VISUAL_USER_SELECTED` **is declared** (`app/src/main/AndroidManifest.xml:25`)
  and included in the permission request on SDK 34+. The app is therefore **not** in the
  system compatibility mode whose grants are revoked on backgrounding — the failure shape
  FX-ISP-001 warned about ("the indexer intermittently loses the library") is not live.
- `hasMediaPermission()` treats *any* granted media permission as access, so a
  partial-selection grant indexes and shows exactly the selected items. Nothing crashes,
  and un-selected media is simply absent.

## The honest gap

There is **no re-selection UX**. Android's model expects an app to offer a
"manage selected photos" affordance that reopens the system selection sheet
(`MediaStore.ACTION_USER_SELECT_IMAGES_FOR_APP` / the photo-picker-managed flow); Foto
Xplorr's only path today is re-triggering the full permission dialog from onboarding.
Consequences of the current behaviour:

- A user who granted partial access and later wants to add photos has no in-app door;
  they must go through system Settings.
- The catalogue must never read "the library shrank" from a partial grant. Today this
  holds *implicitly* (the indexer only sees what MediaStore exposes and the delta path
  never sweeps unseen items — the `replaceAll` trap), but WP2's mark-and-sweep
  generalisation must carry the same rule explicitly: **an unselected item is
  unavailable, not deleted** (the same invariant WP3's FX-045 states for revoked SAF
  grants).

**Follow-up ticket (post-WP1, not silently absorbed here):** a "Manage selected photos"
affordance in the Photos destination's empty/degraded state and in settings, plus copy
that names the mode ("Foto Xplorr can see 214 selected photos"). Until then the degraded
mode is functional but dead-ended.

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
