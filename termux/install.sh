#!/bin/sh
# TRMX installer — Phase 3 (docs/decisions/ADR-005).
#
# Redesign v2 (2026-10-18): the ONE-PASTE bootstrap. The user pastes the
# curl line into Termux once; this script does everything else — install,
# the allow-external-apps setting, and the bridge start — because on the
# owner's device the app's RUN_COMMAND intents are accepted but never
# executed (proven across three on-device rounds). The historical note:
# the Android app used to send a RUN_COMMAND intent that ran exactly this
# script inside Termux (docs/CONTROL-PLANE.md op INSTALL).
#
# Modes:
#   install.sh                      remote: fetch files from BASE (default: GitHub raw @ REF)
#   install.sh --source DIR         local:  install from a checkout (dev / tests)
#   install.sh --base URL           override remote base (e.g. a release tag)
#   install.sh --sha256 FILE        override checksum file
#   install.sh --enable-boot        also install the Termux:Boot startup entry
#                                   (opt-in: needs the Termux:Boot app installed)
#
# Properties:
#   - idempotent (safe to re-run; keeps one .old backup per file)
#   - SHA256-verified against mandatory SHA256SUMS (fail closed)
#   - stops the bridge before upgrading and restarts it afterwards
#   - fails loud on any error; never leaves partial files in place (staging + mv)
#
# SHA256SUMS is mandatory: missing sums or a mismatch abort installation.
# It protects against corruption/partial mirrors, not a compromised source.
# The cryptographic anchor for releases will be
# the Android app embedding the pinned hash of what it asks Termux to install
# (Phase 4). Release tagging regenerates SHA256SUMS via gen_checksums.sh.

set -eu

REF="main"
DEFAULT_BASE="https://raw.githubusercontent.com/aaqib-sq/TRMX-GUI/$REF/termux"
FILES="trmx-bridge.py trmx_bridge_lib.py trmx trmx-ai trmx_ai_lib.py trmx-agent"
HOME_DIR="${TRMX_HOME:-$HOME/.trmx}"

SOURCE=""
BASE="$DEFAULT_BASE"
SUMFILE=""
ENABLE_BOOT=0

while [ $# -gt 0 ]; do
  case "$1" in
    --source) SOURCE="${2:?}"; shift 2 ;;
    --base)   BASE="${2:?}"; shift 2 ;;
    --sha256) SUMFILE="${2:?}"; shift 2 ;;
    --enable-boot) ENABLE_BOOT=1; shift ;;
    *) printf 'install.sh: unknown argument: %s\n' "$1" >&2; exit 2 ;;
  esac
done

say() { printf 'install.sh: %s\n' "$*"; }
die() { printf 'install.sh: FAIL: %s\n' "$*" >&2; exit 1; }

command -v curl >/dev/null 2>&1 || die "curl not found (Termux: pkg install curl)"
command -v sha256sum >/dev/null 2>&1 || die "sha256sum not found"

# --- was the bridge running? (restart after upgrade) -----------------------
WAS_RUNNING=0
if [ -x "$HOME_DIR/trmx" ]; then
  if "$HOME_DIR/trmx" status >/dev/null 2>&1; then WAS_RUNNING=1; fi
fi
if [ "$WAS_RUNNING" = 1 ]; then
  say "bridge is running — stopping for upgrade"
  "$HOME_DIR/trmx" stop >/dev/null 2>&1 || true
fi

# --- stage + verify ---------------------------------------------------------
if [ -n "$SOURCE" ]; then
  # resolve to an absolute path: the checksum check below runs inside $STAGE,
  # where a relative --source would no longer resolve
  [ -d "$SOURCE" ] || die "source dir not found: $SOURCE"
  SOURCE="$(cd "$SOURCE" && pwd)"
fi
mkdir -p "$HOME_DIR"
STAGE="$(mktemp -d "$HOME_DIR/.staging.XXXXXX")" || die "cannot create staging dir"
trap 'rm -rf "$STAGE"' EXIT INT TERM

if [ -n "$SOURCE" ]; then
  for f in $FILES; do
    [ -f "$SOURCE/$f" ] || die "missing $SOURCE/$f"
    cp "$SOURCE/$f" "$STAGE/$f"
  done
  if [ -z "$SUMFILE" ] && [ -f "$SOURCE/SHA256SUMS" ]; then SUMFILE="$SOURCE/SHA256SUMS"; fi
else
  for f in $FILES; do
    curl -fsSL "$BASE/$f" -o "$STAGE/$f" || die "fetch failed: $BASE/$f"
  done
  if [ -z "$SUMFILE" ]; then
    if curl -fsSL "$BASE/SHA256SUMS" -o "$STAGE/SHA256SUMS" 2>/dev/null; then
      SUMFILE="$STAGE/SHA256SUMS"
    fi
  fi
fi

[ -n "$SUMFILE" ] && [ -f "$SUMFILE" ] \
  || die "SHA256SUMS missing or unavailable — refusing to install"
for f in $FILES; do
  # Verify every payload, so an incomplete sums file cannot skip a file.
  # shellcheck disable=SC2164
  ( cd "$STAGE" && awk -v file="$f" '$2 == file' "$SUMFILE" | sha256sum -c - ) \
    || die "SHA256 verification failed — refusing to install"
done
say "checksums verified"

# --- install atomically -----------------------------------------------------
# trmx-ai is a TOOL binary: it installs to ~/.trmx/bin/ (the registry's
# resolve_binary probes $TRMX/bin before $PREFIX/bin — ADR-014). The bridge,
# the control CLI and the agent runner stay at the state-dir root
# (the bridge launches trmx-agent by absolute path).
mkdir -p "$HOME_DIR/logs" "$HOME_DIR/tools" "$HOME_DIR/bin"
for f in $FILES; do
  dest="$HOME_DIR/$f"
  [ "$f" = "trmx-ai" ] && dest="$HOME_DIR/bin/$f"
  # trmx-agent runs beside the bridge (launched by absolute path); the shared
  # trmx_ai_lib stays at the state-dir root where both scripts find it
  # (they put their own dir and its parent on sys.path).
  if [ -f "$dest" ]; then cp "$dest" "$dest.old"; fi
  mv "$STAGE/$f" "$dest"
done
chmod 755 "$HOME_DIR/trmx" "$HOME_DIR/trmx-bridge.py" "$HOME_DIR/trmx-agent" "$HOME_DIR/bin/trmx-ai"

# --- manifest ---------------------------------------------------------------
python3 - "$HOME_DIR" <<'PYEOF'
import hashlib, json, os, sys, time
home = sys.argv[1]
files = ["trmx-bridge.py", "trmx_bridge_lib.py", "trmx", "trmx-agent", "trmx_ai_lib.py", "bin/trmx-ai"]
manifest = {
    "installed_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
    "files": {
        f: hashlib.sha256(open(os.path.join(home, f), "rb").read()).hexdigest()
        for f in files
    },
}
old = {}
mpath = os.path.join(home, "installed.json")
if os.path.exists(mpath):
    try:
        old = json.load(open(mpath))
    except ValueError:
        pass
old.update(manifest)
with open(mpath, "w") as fh:
    json.dump(old, fh, indent=2)
    fh.write("\n")
PYEOF

# --- allow-external-apps (redesign v2: no separate file-editing ritual) ----
# Termux must accept external callers for the pair-link hand-off. Do it
# here, in the one place the user already pasted — idempotently.
PROPS="$HOME/.termux/termux.properties"
mkdir -p "$HOME/.termux"
touch "$PROPS"
if grep -q '^allow-external-apps' "$PROPS"; then
  sed -i 's/^allow-external-apps.*/allow-external-apps = true/' "$PROPS"
else
  printf '\nallow-external-apps = true\n' >> "$PROPS"
fi
if command -v termux-reload-settings >/dev/null 2>&1; then
  termux-reload-settings >/dev/null 2>&1 || true
fi
say "termux setting done: allow-external-apps = true"

# --- always leave the bridge RUNNING (the app's Step 2 listens for it) -----
if [ "$WAS_RUNNING" = 1 ]; then
  "$HOME_DIR/trmx" start || die "restart after upgrade failed (see ~/.trmx/bridge.log)"
  say "bridge upgraded and restarted"
else
  "$HOME_DIR/trmx" start || die "first start failed (see ~/.trmx/bridge.log)"
  say "bridge installed and started"
fi

# --- opt-in: survive reboots via Termux:Boot (audit §5-H3) ----------------
if [ "$ENABLE_BOOT" = 1 ]; then
  say "enabling boot autostart (--enable-boot)"
  "$HOME_DIR/trmx" enable-boot || die "enable-boot failed"
fi
say "done"
printf '\n'
say "NEXT: go back to the TRMX app — it finds the bridge by itself."
say "Then tap 'Copy the connect command' in the app, paste it here, Enter."
