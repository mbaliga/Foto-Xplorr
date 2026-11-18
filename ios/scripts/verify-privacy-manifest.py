#!/usr/bin/env python3
"""Lint ios/Fotoz/PrivacyInfo.xcprivacy with the stdlib (works on Linux; `plutil -lint` is the macOS twin).

Asserts the manifest parses, declares no tracking, no tracking domains, and no collected data types.
It deliberately does NOT assert anything about NSPrivacyAccessedAPITypes: whether the Kotlin/Native
runtime needs reason codes is an owner check on a real archive (ios/OWNER_VERIFY.md).
"""
import plistlib, sys, pathlib

path = pathlib.Path(__file__).resolve().parent.parent / "Fotoz" / "PrivacyInfo.xcprivacy"
with open(path, "rb") as f:
    d = plistlib.load(f)

errors = []
if d.get("NSPrivacyTracking") is not False:
    errors.append("NSPrivacyTracking must be false")
if d.get("NSPrivacyTrackingDomains") != []:
    errors.append("NSPrivacyTrackingDomains must be an empty array")
if d.get("NSPrivacyCollectedDataTypes") != []:
    errors.append("NSPrivacyCollectedDataTypes must be an empty array (Fotoz collects nothing)")
if "NSPrivacyAccessedAPITypes" not in d:
    errors.append("NSPrivacyAccessedAPITypes key missing (use an empty array if none)")

if errors:
    print("verify-privacy-manifest FAILED:\n  " + "\n  ".join(errors), file=sys.stderr)
    sys.exit(1)
print("verify-privacy-manifest OK:", path.name)
