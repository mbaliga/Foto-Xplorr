#!/usr/bin/env bash
# WP1.9: docs/DEPENDENCIES.md and docs/MODEL_LICENSES.md must match what the build really uses.
#
#   scripts/verify-governance-docs.sh              check the real tree (no Gradle; about a second)
#   scripts/verify-governance-docs.sh --self-test  prove the checks fail on a deliberately bad fixture
#
# 1. Every Gradle coordinate ("group:name") declared in gradle/libs.versions.toml and in a module
#    build file (implementation/api/compileOnly/ksp/test.../connectImplementation/platform(...))
#    must appear in docs/DEPENDENCIES.md as `group:name` (the backticked coordinate, optionally
#    with a version after it). Project dependencies and kotlin("test") are not coordinates.
# 2. Every model the app ships or fetches must have a row in docs/MODEL_LICENSES.md:
#      - each file under app/src/*/assets      -> the row names `assets/<path>`
#      - each ModelCatalogue spec id           -> the row names `<id>`
#      - each com.google.mlkit / mediapipe artifact declared in a build file -> `group:name`
#      - a model-like, font or audio file (tflite, task, onnx, gguf, ttf, otf, wav, mp3, ogg, ...) or
#        anything in a res/raw folder, anywhere in app/src outside tests -> the row names its file name
#    A stray model, font or sound in the source tree therefore fails until it is documented.
set -euo pipefail
cd "$(dirname "$0")/.."

BUILD_FILES_GLOB='app/build.gradle.kts core/*/build.gradle.kts feature/*/build.gradle.kts ut/*/build.gradle.kts benchmarks/build.gradle.kts'

# coords_from_tree: "group:name" per line, sorted unique, from the toml and the build files.
coords_from_tree() {
  {
    # [libraries] entries: foo = { group = "g", name = "n", ... }
    awk '/^\[libraries\]/{on=1; next} /^\[/{on=0} on && /group *= *"/ {
           g=$0; sub(/.*group *= *"/, "", g); sub(/".*/, "", g)
           n=$0; sub(/.*name *= *"/, "", n); sub(/".*/, "", n)
           print g ":" n }' gradle/libs.versions.toml
    # Quoted group:name[:version] on dependency lines of the build files.
    # shellcheck disable=SC2086
    grep -hE '(implementation|api|compileOnly|runtimeOnly|ksp|kapt|Implementation|platform)\b[^=]*\(?"[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+' $BUILD_FILES_GLOB 2>/dev/null \
      | grep -vE '^\s*(//|\*)' \
      | grep -oE '"[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+(:[^"]*)?"' \
      | tr -d '"' | awk -F: '{print $1 ":" $2}'
  } | sort -u
}

# missing_from_doc <coords-file> <doc> <what>: prints each coordinate with no `coord` mention.
missing_from_doc() {
  local coords="$1" doc="$2" line missing=0
  while IFS= read -r line; do
    [ -z "$line" ] && continue
    if ! grep -qF -- "\`$line" "$doc"; then
      echo "  $line"; missing=1
    fi
  done < "$coords"
  return "$missing"
}

# model_items_from_tree: "kind<TAB>needle" lines.
model_items_from_tree() {
  local f
  find app/src -path '*/assets/*' -type f ! -path 'app/src/test*' ! -path 'app/src/androidTest*' 2>/dev/null | sort | while read -r f; do
    printf 'asset\tassets/%s\n' "$(echo "$f" | sed -E 's#^app/src/[^/]+/assets/##')"
  done
  grep -oE 'id = "[^"]+"' app/src/main/java/com/fotoxplorr/app/services/ModelCatalogue.kt 2>/dev/null \
    | sed -E 's/id = "([^"]+)"/catalogue\t\1/'
  # shellcheck disable=SC2086
  grep -hoE '"com\.google\.(mlkit|mediapipe):[A-Za-z0-9_.-]+' $BUILD_FILES_GLOB 2>/dev/null \
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
    if ! grep -qF -- "\`$needle" "$doc"; then
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
  printf 'dependencies {\n  implementation("com.bar:bar:1.0")\n  "connectImplementation"("com.google.mlkit:translate:17.0.3")\n  implementation(project(":core:a"))\n  // implementation("com.commented:out:1")\n}\n' > "$t/app/build.gradle.kts"
  printf 'dependencies { api(libs.foo); implementation(kotlin("test")) }\n' > "$t/core/a/build.gradle.kts"
  printf 'x' > "$t/app/src/main/assets/x/model.tflite"
  mkdir -p "$t/app/src/main/res/font" "$t/app/src/main/res/raw"; printf 'x' > "$t/app/src/main/res/font/f.ttf"; printf 'x' > "$t/app/src/main/res/raw/tick.bin"
  printf 'val A = ModelSpec(\n id = "my-model",\n)\n' > "$t/app/src/main/java/com/fotoxplorr/app/services/ModelCatalogue.kt"
  (
    cd "$t"
    coords_from_tree > coords
    for want in org.foo:foo-core com.bar:bar com.google.mlkit:translate; do
      grep -qx "$want" coords || { echo "SELF-TEST FAILED: extractor missed $want"; exit 1; }
    done
    if grep -q 'commented' coords; then echo "SELF-TEST FAILED: extractor read a comment"; exit 1; fi
    if grep -q 'project\|kotlin' coords; then echo "SELF-TEST FAILED: extractor took a project/kotlin() dep"; exit 1; fi
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
