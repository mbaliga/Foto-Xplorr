#!/usr/bin/env bash
# WP1.9: docs/DEPENDENCIES.md and docs/MODEL_LICENSES.md must match what the build really uses.
#
#   scripts/verify-governance-docs.sh              check the real tree (no Gradle; about a second)
#   scripts/verify-governance-docs.sh --self-test  prove the checks fail on a deliberately bad fixture
#
# 1. Every Gradle coordinate ("group:name") declared in gradle/libs.versions.toml and in a module
#    build file (any configuration call: implementation/api/compileOnly/runtimeOnly with any
#    prefix such as test/debug/androidTest/connect, coreLibraryDesugaring, lintChecks,
#    androidTestUtil, annotationProcessor, ksp.../kapt..., and platform(...)) must appear in
#    docs/DEPENDENCIES.md as `group:name` followed by a backtick or ":<version>" -- an exact match,
#    so a documented `g:name-extra` does not cover `g:name`. Project dependencies and
#    kotlin("test") are not coordinates; resolutionStrategy.force(...) is not a declaration.
# 2. Every model the app ships or fetches must have a row in docs/MODEL_LICENSES.md:
#      - each file under app/src/*/assets      -> the row names `assets/<path>`
#      - each ModelCatalogue spec id           -> the row names `<id>`
#      - each com.google.mlkit / mediapipe artifact declared in a build file -> `group:name`
#      - a model-like, font or audio file (tflite, task, onnx, gguf, ttf, otf, wav, mp3, ogg, ...) or
#        anything in a res/raw folder, anywhere in app/src outside tests -> the row names its file name
#    A stray model, font or sound in the source tree therefore fails until it is documented.
#    Model needles are exact too (a backtick must follow; ":<version>" also for a library).
#
# Under `set -euo pipefail` a grep that finds nothing would end the script with no word of why, so
# every extractor grep that may legitimately find nothing goes through `g` (exit 1 = no match = ok),
# a real error keeps grep's own message, and the ERR trap names the line that stopped the script.
set -Eeuo pipefail
trap '[[ $BASH_COMMAND == return* ]] || echo "verify-governance-docs: stopped at line $LINENO: $BASH_COMMAND" >&2' ERR
cd "$(dirname "$0")/.."

# g: grep where "no match" (exit 1) is not an error; exit 2 (unreadable file, bad regex) still is.
g() { grep "$@" || [ "$?" -eq 1 ]; }

# build_files: the module build files that exist. A glob matching nothing (a module removed) is
# skipped; the app build file must exist, or the check would silently cover nothing.
build_files() {
  local f
  [ -f app/build.gradle.kts ] || { echo "verify-governance-docs: app/build.gradle.kts missing (run from the repo root)" >&2; return 1; }
  for f in app/build.gradle.kts core/*/build.gradle.kts feature/*/build.gradle.kts ut/*/build.gradle.kts benchmarks/build.gradle.kts; do
    [ -f "$f" ] && printf '%s\n' "$f"
  done
  return 0
}

# A dependency-declaring call: the configuration name (any prefix + a known configuration suffix,
# or ksp/kapt + anything), optionally quoted ("connectImplementation"), then "(" and an optional
# platform(/enforcedPlatform( around the quoted "group:name". A bare platform("...") also counts.
CONF_NAME='([A-Za-z]*([Ii]mplementation|[Aa]pi|[Cc]ompileOnly|[Rr]untimeOnly|[Aa]nnotationProcessor|[Dd]esugaring|[Ll]intChecks|[Ll]intPublish|[Tt]estUtil|[Pp]latform)|(ksp|kapt)[A-Za-z]*)'
DEP_LINE_RE='(^|[^A-Za-z0-9_.])"?'"$CONF_NAME"'"?[[:space:]]*\([[:space:]]*((enforcedP|p)latform\([[:space:]]*)?"[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+'

# coords_from_tree: "group:name" per line, sorted unique, from the toml and the build files.
coords_from_tree() {
  {
    # [libraries] entries: foo = { group = "g", name = "n", ... }
    [ -f gradle/libs.versions.toml ] || { echo "verify-governance-docs: gradle/libs.versions.toml missing" >&2; return 1; }
    awk '/^\[libraries\]/{on=1; next} /^\[/{on=0} on && /group *= *"/ {
           g=$0; sub(/.*group *= *"/, "", g); sub(/".*/, "", g)
           n=$0; sub(/.*name *= *"/, "", n); sub(/".*/, "", n)
           print g ":" n }' gradle/libs.versions.toml
    # Quoted group:name[:version] on dependency lines of the build files.
    local files; files="$(build_files)"
    # shellcheck disable=SC2086
    g -hE "$DEP_LINE_RE" $files \
      | g -vE '^\s*(//|\*)' \
      | g -oE '"[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+(:[^"]*)?"' \
      | tr -d '"' | awk -F: '{print $1 ":" $2}'
  } | sort -u
}

# doc_mentions <needle> <doc> <boundary-chars>: true when the doc has `needle immediately followed
# by one of the boundary characters (no regex, so dots and dashes in the needle are literal).
doc_mentions() {
  awk -v n="\`$1" -v b="$3" '{
      s = $0
      while ((i = index(s, n)) > 0) {
        c = substr(s, i + length(n), 1)
        if (c != "" && index(b, c) > 0) { found = 1; exit }
        s = substr(s, i + 1)
      }
    } END { exit(found ? 0 : 1) }' "$2"
}

# missing_from_doc <coords-file> <doc> <what>: prints each coordinate with no `coord` mention.
missing_from_doc() {
  local coords="$1" doc="$2" line missing=0
  while IFS= read -r line; do
    [ -z "$line" ] && continue
    if ! doc_mentions "$line" "$doc" '`:'; then
      echo "  $line"; missing=1
    fi
  done < "$coords"
  return "$missing"
}

# model_items_from_tree: "kind<TAB>needle" lines.
model_items_from_tree() {
  local f files
  find app/src -path '*/assets/*' -type f ! -path 'app/src/test*' ! -path 'app/src/androidTest*' 2>/dev/null | sort | while read -r f; do
    printf 'asset\tassets/%s\n' "$(echo "$f" | sed -E 's#^app/src/[^/]+/assets/##')"
  done
  local catalogue=app/src/main/java/com/fotoxplorr/app/services/ModelCatalogue.kt
  [ -f "$catalogue" ] || { echo "verify-governance-docs: $catalogue missing; if the catalogue moved, update this script" >&2; return 1; }
  g -oE 'id = "[^"]+"' "$catalogue" \
    | sed -E 's/id = "([^"]+)"/catalogue\t\1/'
  files="$(build_files)"
  # shellcheck disable=SC2086
  g -hoE '"com\.google\.(mlkit|mediapipe):[A-Za-z0-9_.-]+' $files \
    | tr -d '"' | sort -u | sed -E 's/^/library\t/'
  find app/src -type f \( -iname '*.tflite' -o -iname '*.task' -o -iname '*.onnx' -o -iname '*.gguf' -o -iname '*.tfl' -o -iname '*.ort' -o -iname '*.pte' -o -iname '*.mlmodel' \
      -o -iname '*.ttf' -o -iname '*.otf' -o -iname '*.woff' -o -iname '*.woff2' -o -iname '*.wav' -o -iname '*.mp3' -o -iname '*.ogg' -o -path '*/res/raw/*' \) \
    ! -path 'app/src/test*' ! -path 'app/src/androidTest*' 2>/dev/null | sort | while read -r f; do
    printf 'file\t%s\n' "$(basename "$f")"
  done
}

# missing_models <items-file> <doc>
missing_models() {
  local items="$1" doc="$2" kind needle missing=0
  while IFS=$'\t' read -r kind needle; do
    [ -z "$needle" ] && continue
    local b='`'; [ "$kind" = library ] && b='`:'
    if ! doc_mentions "$needle" "$doc" "$b"; then
      echo "  [$kind] $needle"; missing=1
    fi
  done < "$items"
  return "$missing"
}

check_tree() {
  local rc=0 tmp; tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' RETURN
  [ -f docs/DEPENDENCIES.md ] || { echo "verify-governance-docs: docs/DEPENDENCIES.md missing" >&2; return 1; }
  [ -f docs/MODEL_LICENSES.md ] || { echo "verify-governance-docs: docs/MODEL_LICENSES.md missing" >&2; return 1; }
  coords_from_tree > "$tmp/coords"
  [ -s "$tmp/coords" ] || { echo "verify-governance-docs: found no Gradle coordinates at all; the extractor is broken" >&2; return 1; }
  local out
  if ! out="$(missing_from_doc "$tmp/coords" docs/DEPENDENCIES.md)"; then
    echo "verify-governance-docs: Gradle coordinates with no row in docs/DEPENDENCIES.md:" >&2
    echo "$out" >&2; rc=1
  fi
  model_items_from_tree > "$tmp/models"
  if ! out="$(missing_models "$tmp/models" docs/MODEL_LICENSES.md)"; then
    echo "verify-governance-docs: models/data with no row in docs/MODEL_LICENSES.md:" >&2
    echo "$out" >&2; rc=1
  fi
  if [ "$rc" -eq 0 ]; then
    echo "governance-docs OK: $(wc -l < "$tmp/coords") coordinates in DEPENDENCIES.md, $(wc -l < "$tmp/models") model/data/font/sound items in MODEL_LICENSES.md"
  fi
  return "$rc"
}

self_test() {
  local t fails=0; t="$(mktemp -d)"; trap 'rm -rf "$t"' RETURN
  # the extractor sees what it should, in a scratch tree
  mkdir -p "$t/app/src/main/assets/x" "$t/app/src/main/java/com/fotoxplorr/app/services" "$t/gradle" "$t/core/a"
  printf '[libraries]\nfoo = { group = "org.foo", name = "foo-core", version.ref = "v" }\n[plugins]\np = { id = "x" }\n' > "$t/gradle/libs.versions.toml"
  printf 'dependencies {\n  implementation("com.bar:bar:1.0")\n  "connectImplementation"("com.google.mlkit:translate:17.0.3")\n  implementation(project(":core:a"))\n  // implementation("com.commented:out:1")\n  testRuntimeOnly("t.r:vintage:5")\n  debugRuntimeOnly("d.r:dbg:1")\n  testCompileOnly("t.c:tco:1")\n  debugApi("d.a:dapi:1")\n  coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")\n  lintChecks("l.c:lint:1")\n  androidTestUtil("a.t:orchestrator:1")\n  ksp("k.s:proc:1")\n  kspTest("k.t:proc:1")\n  kapt("k.a:proc:1")\n  annotationProcessor("a.p:proc:1")\n  val bom = platform("b.o:bom:1")\n  implementation(enforcedPlatform("e.p:bom:1"))\n}\nconfigurations.all { resolutionStrategy.force("f.o:forced:1") }\n' > "$t/app/build.gradle.kts"
  printf 'dependencies { api(libs.foo); implementation(kotlin("test")) }\n' > "$t/core/a/build.gradle.kts"
  printf 'x' > "$t/app/src/main/assets/x/model.tflite"
  mkdir -p "$t/app/src/main/res/font" "$t/app/src/main/res/raw"; printf 'x' > "$t/app/src/main/res/font/f.ttf"; printf 'x' > "$t/app/src/main/res/raw/tick.bin"
  printf 'val A = ModelSpec(\n id = "my-model",\n)\n' > "$t/app/src/main/java/com/fotoxplorr/app/services/ModelCatalogue.kt"
  (
    cd "$t"
    coords_from_tree > coords
    for want in org.foo:foo-core com.bar:bar com.google.mlkit:translate t.r:vintage d.r:dbg t.c:tco d.a:dapi \
        com.android.tools:desugar_jdk_libs l.c:lint a.t:orchestrator k.s:proc k.t:proc k.a:proc a.p:proc b.o:bom e.p:bom; do
      grep -qx "$want" coords || { echo "SELF-TEST FAILED: extractor missed $want"; exit 1; }
    done
    if grep -q 'commented' coords; then echo "SELF-TEST FAILED: extractor read a comment"; exit 1; fi
    if grep -q 'project\|kotlin' coords; then echo "SELF-TEST FAILED: extractor took a project/kotlin() dep"; exit 1; fi
    if grep -q 'forced' coords; then echo "SELF-TEST FAILED: extractor took resolutionStrategy.force"; exit 1; fi
    model_items_from_tree > models
    for want in 'asset	assets/x/model.tflite' 'catalogue	my-model' 'library	com.google.mlkit:translate' 'file	model.tflite' 'file	f.ttf' 'file	tick.bin'; do
      grep -qxF "$want" models || { echo "SELF-TEST FAILED: model extractor missed '$want'"; cat models; exit 1; }
    done
  ) || fails=$((fails + 1))

  # a doc missing a dependency FAILS; a complete doc passes
  printf 'org.foo:foo-core\ncom.bar:bar\n' > "$t/c.txt"
  printf '| `org.foo:foo-core` | 1 |\n' > "$t/bad-deps.md"
  if missing_from_doc "$t/c.txt" "$t/bad-deps.md" >/dev/null; then
    echo "SELF-TEST FAILED: a doc missing com.bar:bar passed"; fails=$((fails + 1))
  else echo "self-test ok: undocumented dependency fails"; fi
  printf '| `org.foo:foo-core` | 1 |\n| `com.bar:bar:1.0` | 1 |\n' > "$t/good-deps.md"
  if missing_from_doc "$t/c.txt" "$t/good-deps.md" >/dev/null; then
    echo "self-test ok: complete dependency doc passes"
  else echo "SELF-TEST FAILED: a complete doc failed"; fails=$((fails + 1)); fi

  # exact match: a documented longer coordinate with the same prefix does not cover the shorter one
  printf 'com.bar:bar\n' > "$t/c1.txt"
  printf '| `com.bar:bar-extra` | 1 |\n| `com.bar:barista:2` | 1 |\n' > "$t/prefix-deps.md"
  if missing_from_doc "$t/c1.txt" "$t/prefix-deps.md" >/dev/null; then
    echo "SELF-TEST FAILED: \`com.bar:bar-extra\` covered com.bar:bar"; fails=$((fails + 1))
  else echo "self-test ok: a longer documented coordinate does not cover a prefix of it"; fi
  printf 'catalogue\timage-embedder\n' > "$t/m1.txt"
  printf '| `image-embedder-mobilenet-v3-small` | b |\n' > "$t/prefix-models.md"
  if missing_models "$t/m1.txt" "$t/prefix-models.md" >/dev/null; then
    echo "SELF-TEST FAILED: a longer documented model id covered image-embedder"; fails=$((fails + 1))
  else echo "self-test ok: a longer documented model id does not cover a prefix of it"; fi

  # a tree with no benchmarks/ut modules and no ML Kit/MediaPipe/catalogue id still runs to the end
  # (no silent abort under pipefail), and a missing catalogue file is an explicit error
  local u; u="$t/minimal"
  mkdir -p "$u/app/src/main/java/com/fotoxplorr/app/services" "$u/gradle"
  printf '[libraries]\n' > "$u/gradle/libs.versions.toml"
  printf 'dependencies { implementation("only:one:1") }\n' > "$u/app/build.gradle.kts"
  printf '// no specs\n' > "$u/app/src/main/java/com/fotoxplorr/app/services/ModelCatalogue.kt"
  if (cd "$u" && coords_from_tree > coords && grep -qx 'only:one' coords && model_items_from_tree > models && [ ! -s models ]); then
    echo "self-test ok: a tree with no optional modules and no models runs to the end"
  else echo "SELF-TEST FAILED: the minimal tree aborted or extracted the wrong thing"; fails=$((fails + 1)); fi
  rm "$u/app/src/main/java/com/fotoxplorr/app/services/ModelCatalogue.kt"
  local err; err="$( (cd "$u" && model_items_from_tree) 2>&1 >/dev/null || true)"
  if echo "$err" | grep -q 'ModelCatalogue.kt missing'; then echo "self-test ok: a missing catalogue file is named"
  else echo "SELF-TEST FAILED: a missing catalogue file gave no message ($err)"; fails=$((fails + 1)); fi

  printf 'asset\tassets/x/model.tflite\ncatalogue\tmy-model\n' > "$t/m.txt"
  printf '| `assets/x/model.tflite` | a |\n' > "$t/bad-models.md"
  if missing_models "$t/m.txt" "$t/bad-models.md" >/dev/null; then
    echo "SELF-TEST FAILED: a doc missing my-model passed"; fails=$((fails + 1))
  else echo "self-test ok: undocumented model fails"; fi
  printf '| `assets/x/model.tflite` | a |\n| `my-model` | b |\n' > "$t/good-models.md"
  if missing_models "$t/m.txt" "$t/good-models.md" >/dev/null; then
    echo "self-test ok: complete model doc passes"
  else echo "SELF-TEST FAILED: a complete model doc failed"; fails=$((fails + 1)); fi

  if [ "$fails" -ne 0 ]; then echo "verify-governance-docs self-test: $fails FAILED" >&2; return 1; fi
  echo "verify-governance-docs self-test OK"
}

case "${1:-}" in
  --self-test) self_test ;;
  "") check_tree ;;
  *) echo "verify-governance-docs: unknown argument '$1'" >&2; exit 2 ;;
esac
