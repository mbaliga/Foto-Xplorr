# iOS: what only the owner can verify

Nothing below has been run. Evidence labels: `NDV` = NEEDS-DEVICE-VALIDATION.

## Owner actions first
1. **Names:** add the `com.asoc.fotoz` row to `NAMES.md` (plan rule R11; that file is outside this repo), or change the id in `ios/Config/Identity.xcconfig` (the only place it lives).
2. **Apple Developer Program** enrolment; create the App ID, a distribution certificate and an App Store provisioning profile; add the `apple-signing` GitHub **environment** with required reviewers and the secrets listed in the comment inside `.github/workflows/ios.yml` (`APPLE_TEAM_ID`, `APPLE_DIST_CERT_P12_BASE64`, `APPLE_DIST_CERT_PASSWORD`, `APPLE_PROVISIONING_PROFILE_BASE64`, `APPLE_PROFILE_NAME`; TestFlight also `ASC_KEY_ID`, `ASC_ISSUER_ID`, `ASC_KEY_P8_BASE64`).
3. **Private submodules:** hosted runners need a read token for `hyle-design-system` and `shared-libraries` (repo secret `SUBMODULE_TOKEN`), or the macOS lane's checkout fails. macOS minutes on a private repo are billed at a multiple.
4. Decide Q15 (delivery to the iPad Pro M4: TestFlight vs ad-hoc), Q21 (Photos library vs Files folders; v1 here is Photos library only), Q22 (iCloud Backup stance).

## First CI run: expect to fix these
The macOS lane has never run. Likely first-run issues: Kotlin 2.1.0 vs the newest Xcode 26.x (the plan's OQ-38 ruling points to Kotlin 2.2.21; if K/N 2.1.0 rejects the SDK, bump Kotlin repo-wide with the constellation toolchain upgrade), XcodeGen output path for the framework (`ios/core/build/XCFrameworks/release/FotozCore.xcframework`), the Swift name of the Kotlin object (`FotozBridge`, via `@ObjCName`), simulator selection, `brew install xcodegen` drift.

## Checks on a simulator / device (NDV)
| # | Check | Expect |
|---|---|---|
| 1 | First launch, tap "Choose what Fotoz can see" | System sheet; both "Limited" and "Full" work |
| 2 | Limited access | Banner "Showing only the N photos you selected" + Change opens the system picker; list updates after |
| 3 | Deny, then reopen | "No access" screen with working Settings button |
| 4 | Grid | Photos appear newest first under month headers; scrolling a large library stays smooth (**unmeasured**: per-asset PHAssetResource lookups at load, and the whole library is re-encoded to JSON per search; measure on a 20k+ library) |
| 5 | Search `folder:<an album> type:video August 2025`, `not category:screenshot` | Chips show what was understood; results match; empty query shows everything |
| 6 | iCloud-only photo (Optimise Storage on) | "iCloud" mark in grid, "Not on this device" in viewer, **no network traffic** (check with a proxy / Network instrument) |
| 7 | Viewer: swipe, pinch, video play, details sheet | Works; details list the Kotlin core's format capabilities |
| 8 | iPad: landscape, multitasking window sizes | Grid adapts; no clipped chrome |
| 9 | About screen | Offline statement and the Apple crash-report exception are shown |
| 10 | Archive, then Xcode Privacy Report | **Check whether the Kotlin/Native runtime uses any "required reason" API** (e.g. boot-time / file-timestamp calls); if so add reason codes to `PrivacyInfo.xcprivacy` (currently empty = UNVERIFIED) |
| 11 | Instruments / Network profiler over a session | Zero outbound connections |
| 12 | Edge gestures / system gestures | The Android room-edge gestures were not ported; confirm nothing here conflicts with back-swipe / Control Center |

## App Review risks to think about before submission
Photos-library apps need a clear usage string (present). The BYOK-AI and Connect features of the Android app are intentionally absent, so guidelines 2.5.2 / 4.2.3 / 5.1.2 (plan WP10.9) do not apply to this v1. Minimum-functionality (4.2) risk: a read-only gallery over Apple Photos is thin; the search language is the differentiator.
