# Fotoz for Ubuntu Touch / Linux (`ut/app`)

A **native Qt Quick shell** over the Fotoz Kotlin core (`libfotozcore.so`, built from `:ut:bridge` by Kotlin/Native). It is *not*
a port of the Android Compose app and shares only the core logic: the database schema, the sync engine, the catalogue queries and
the search parser. Status and evidence per item: `docs/handoff/UT-PORT.md`. What only a device can show: `ut/OWNER_VERIFY.md`.

```
qml/            Main (stack), FolderPage, GridPage, ViewerPage, AboutPage, Theme (neutral tokens, not Hyle)
src/CoreApi     QLibrary binding of the C ABI (loads libfotozcore.so at runtime; a missing core is an on-screen error)
src/Models      FolderModel, AssetModel (paged fotoz_query_page)
src/LibraryController   the object QML uses; scans on a worker thread
tests/          fakecore.cpp (same ABI, canned data), tst_shell (glue + QML), tst_realcore (real core, real files)
manifest.json.in, fotoz.apparmor.in, fotoz.desktop.in, clickable.yaml, assets/icon.svg   click packaging
```

## Build on a Linux desktop (Qt 5.15 or Qt 6)
```
sudo apt install cmake g++ qtbase5-dev qtdeclarative5-dev qtquickcontrols2-5-dev \
     qml-module-qtquick2 qml-module-qtquick-controls2 qml-module-qtquick-layouts qml-module-qtquick-window2 qml-module-qtquick-templates2
./ut/scripts/build-core.sh amd64                       # Gradle + Kotlin/Native -> ut/app/prebuilt/amd64/libfotozcore.so
cmake -S ut/app -B build/ut-app -DFOTOZ_BUILD_TESTS=ON -DFOTOZ_CORE_LIB=$PWD/ut/app/prebuilt/amd64/libfotozcore.so
cmake --build build/ut-app && ctest --test-dir build/ut-app --output-on-failure
FOTOZ_CORE_LIB=$PWD/ut/app/prebuilt/amd64/libfotozcore.so build/ut-app/fotoz      # run it
```
Environment: `FOTOZ_CORE_LIB` (core path override), `FOTOZ_DB` (database path), `FOTOZ_QC2_STYLE` (Quick Controls 2 style).

## Build the click
```
./ut/scripts/build-core.sh arm64        # cross-links the phone core (linuxArm64); CI does this on an x86 runner
cd ut/app && clickable build --arch arm64
```
CI: `.github/workflows/ut.yml` (lane `click`). Needs Docker (Clickable's build images).

## Design rules this directory keeps
- **No network code.** No QtNetwork, no WebEngine, no URL literals, no networking AppArmor group: `ut/scripts/verify-offline.sh`.
- **Honest UI.** A tile Qt cannot decode says `[no preview]`; a video says `[video]`; search says it matches file names only;
  the About page lists what is not done.
- **Read-only on the user's photos.** This build never writes, moves or deletes a user file.
