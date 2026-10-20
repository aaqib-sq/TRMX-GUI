"""Fake device-home fixture — the real Termux layout, in a temp dir.

Audit §7-M5: the suites fake `HOME` with bare temp dirs, but the bugs
that actually bit on the phone came from the *shape* of a real device:

- Termux home is `/data/data/com.termux/files/home` and the prefix is
  its sibling `…/files/usr` (bin/, var/service/, …).
- `~/storage` (from `termux-setup-storage`) is a REAL directory whose
  children are SYMLINKS into shared storage
  (`/storage/emulated/0/<Name>`) — never a symlink itself, never plain
  subdirectories. Policy §6.1 depends on exactly this shape.
- Shared storage itself uses capitalised folder names (Download, DCIM,
  Pictures, Music, Movies, Documents) that the `~/storage` link names
  (downloads, dcim, …) do NOT spell the same way.
- The bridge's own state lives in `~/.trmx` (bridge.json, logs/, tools/,
  bin/, services/, agent/) and the boot entry in `~/.termux/boot/`.

`make_device_home(base)` builds that tree under `base` and returns a
`DeviceHome`. The shared-storage analogue is `base/shared` standing in
for `/storage/emulated/0`: tests that exercise Policy.file_roots()
must still mock `Path.resolve` for the storage children (the policy
only admits resolved targets under the real `/storage/emulated`
prefix) — the existing TestStorageSymlinkRoots tests show the pattern.
Everything else (layout, names, link-ness) is real, not mocked.
"""

from __future__ import annotations

import shutil
from dataclasses import dataclass, field
from pathlib import Path

# termux-setup-storage link name -> shared-storage folder name. This is
# the exact allowlist Policy.file_roots() admits (trmx_bridge_lib).
STORAGE_LINKS = {
    "shared": "",            # the shared-storage root itself
    "downloads": "Download",
    "dcim": "DCIM",
    "pictures": "Pictures",
    "music": "Music",
    "movies": "Movies",
    "audiobooks": "Audiobooks",
    "documents": "Documents",
    "media": "media",
}


@dataclass
class DeviceHome:
    base: Path               # temp root that owns the whole fake device
    home: Path               # …/files/home   (Termux $HOME)
    prefix: Path             # …/files/usr    (Termux $PREFIX)
    shared: Path             # stands in for /storage/emulated/0
    trmx_home: Path = field(init=False)
    boot_dir: Path = field(init=False)

    def __post_init__(self) -> None:
        self.trmx_home = self.home / ".trmx"
        self.boot_dir = self.home / ".termux" / "boot"

    def storage_link(self, name: str) -> Path:
        return self.home / "storage" / name

    def shared_dir(self, name: str) -> Path:
        """The real (resolved) folder behind a ~/storage link name."""
        folder = STORAGE_LINKS[name]
        return self.shared / folder if folder else self.shared

    def cleanup(self) -> None:
        shutil.rmtree(self.base, ignore_errors=True)


def make_device_home(base: Path) -> DeviceHome:
    """Build the fake device layout under `base` (created if needed)."""
    base = Path(base)
    files = base / "data" / "data" / "com.termux" / "files"
    home = files / "home"
    prefix = files / "usr"
    shared = base / "storage" / "emulated" / "0"

    # $PREFIX skeleton: bin/ is where the allowlist resolves binaries;
    # var/service/ exists only when termux-services is installed, but a
    # real device that runs services has it — include it.
    (prefix / "bin").mkdir(parents=True)
    (prefix / "var" / "service").mkdir(parents=True)

    # Termux home: the user's own real directories.
    (home / "downloads").mkdir(parents=True)
    (home / ".termux").mkdir(parents=True)

    # Bridge state (~/.trmx) as install.sh + the bridge leave it.
    trmx_home = home / ".trmx"
    for d in ("logs", "tools", "bin", "services", "agent/tasks",
              "agent/pending"):
        (trmx_home / d).mkdir(parents=True, exist_ok=True)

    # Shared storage: real directories with the device spellings.
    shared.mkdir(parents=True)
    for folder in STORAGE_LINKS.values():
        if folder:
            (shared / folder).mkdir(parents=True, exist_ok=True)

    # ~/storage: a REAL directory whose children are symlinks into
    # shared storage (termux-setup-storage). This shape — not the
    # targets — is what the fixture exists to pin.
    storage = home / "storage"
    storage.mkdir()
    dev = DeviceHome(base=base, home=home, prefix=prefix, shared=shared)
    for link_name in STORAGE_LINKS:
        dev.storage_link(link_name).symlink_to(
            dev.shared_dir(link_name), target_is_directory=True)
    return dev
