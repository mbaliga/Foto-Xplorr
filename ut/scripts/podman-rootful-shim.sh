#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Installs a `podman` wrapper first on PATH that runs the real podman ROOTFUL (sudo -E) and drops the
# `--uidmap`/`--gidmap` flag pairs Clickable adds for rootless Podman.
#   ut/scripts/podman-rootful-shim.sh <dir>     writes <dir>/podman (real podman resolved now, before <dir> is on PATH)
#   ut/scripts/podman-rootful-shim.sh --self-test   exercises the wrapper against a fake podman (no Podman needed)
# Why: Clickable's 24.04 images contain files owned by container ID 32011; a rootless pull dies with
# "container ID 32011 cannot be mapped to a host ID" on the hosted runner (run 44162373234, both legs), and
# storage.conf tweaks did not help. Rootful podman can unpack them. Under rootful, the uidmap/gidmap pairs
# (meant to map the runner user onto container root) would make the mounted build tree unwritable, so they go;
# `--user <runner uid>` stays, and the host uid is the runner's.
set -euo pipefail

write_shim() { # <dir> <real-podman>
  mkdir -p "$1"
  {
    echo '#!/usr/bin/env bash'
    echo 'set -euo pipefail'
    echo "real=$(printf '%q' "$2")"
    cat <<'EOS'
args=()
while [ "$#" -gt 0 ]; do
  case "$1" in
    --uidmap|--gidmap) shift 2 || true ;;
    --uidmap=*|--gidmap=*) shift ;;
    *) args+=("$1"); shift ;;
  esac
done
exec sudo -E "$real" "${args[@]}"
EOS
  } > "$1/podman"
  chmod +x "$1/podman"
}

if [ "${1:-}" = "--self-test" ]; then
  t="$(mktemp -d)"; trap 'rm -rf "$t"' EXIT
  mkdir "$t/bin"
  cat > "$t/fakepodman" <<'EOS'
#!/usr/bin/env bash
for a in "$@"; do printf '[%s]\n' "$a"; done
EOS
  cat > "$t/bin/sudo" <<'EOS'
#!/usr/bin/env bash
[ "$1" = "-E" ] && shift
exec "$@"
EOS
  chmod +x "$t/fakepodman" "$t/bin/sudo"
  write_shim "$t/shim" "$t/fakepodman"
  got="$(PATH="$t/bin:$PATH" "$t/shim/podman" run -v "/a b:/a b:Z" -e 'X=it'"'"'s "q"' --user 1001 \
    --uidmap 1001:0:1 --uidmap 0:1:1001 --gidmap 1001:0:1 --gidmap 0:1:1001 --uidmap=5:5:5 -w /w --rm img bash -c 'echo "hi there"')"
  want='[run]
[-v]
[/a b:/a b:Z]
[-e]
[X=it'"'"'s "q"]
[--user]
[1001]
[-w]
[/w]
[--rm]
[img]
[bash]
[-c]
[echo "hi there"]'
  [ "$got" = "$want" ] || { echo "podman shim self-test FAILED"; diff <(echo "$want") <(echo "$got") || true; exit 1; }
  echo "podman shim self-test passed"
  exit 0
fi

dir="${1:?usage: podman-rootful-shim.sh <dir> | --self-test}"
real="$(command -v podman)" || { echo "podman not found" >&2; exit 1; }
write_shim "$dir" "$real"
echo "podman shim: $dir/podman -> sudo -E $real"
