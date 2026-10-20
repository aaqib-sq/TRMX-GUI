#!/usr/bin/env python3
"""Phase 7 file-endpoint tests — PROTOCOL.md §6 against a real bridge.

Covers: §6.1 path policy (realpath containment), §6.2 list/stat + paging,
§6.3 operations incl. the recursive-delete confirm interlock, §6.4 ranged
download, §6.5 streaming atomic upload (sha256 verify, overwrite, 411).

Run:  python3 termux/tests/test_files.py   (or tests/run_tests.sh)
stdlib only (unittest, http.client, socket) — ADR-002 rule.
"""

from __future__ import annotations

import http.client
import json
import os
import socket
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys_imported = False
import sys  # noqa: E402

from test_bridge import BridgeProc, free_port  # noqa: E402  (shared harness)


class TestFiles(unittest.TestCase):
    """Owns its own bridge; file fixtures live under the REAL user home
    (the §6.1 roots are [~ , ~/storage] — same policy on CI and Termux)."""

    home: str = ""
    port: int = 0
    bridge: BridgeProc
    token: str = ""
    files_root: Path  # the throwaway fixture tree under Path.home()

    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.mkdtemp(prefix="trmx-files-home-")
        cls.home = os.path.join(cls.tmp, "trmx-home")
        os.makedirs(cls.home, exist_ok=True)
        (Path(cls.home) / "bridge.json").write_text(json.dumps({}))
        cls.port = free_port()
        cls.bridge = BridgeProc(cls.home, cls.port)
        cls.bridge.start()
        cls.token = cls.bridge.token()
        # fixture tree under the real home so the path policy is exercised
        cls.files_root = Path(tempfile.mkdtemp(prefix="trmx-files-", dir=str(Path.home())))
        (cls.files_root / "music").mkdir()
        (cls.files_root / "notes.txt").write_bytes(b"hello trmx files\n")
        (cls.files_root / "music" / "track.mp3").write_bytes(b"\x00\x01\x02" * 1000)
        (cls.files_root / "latest.txt").symlink_to(cls.files_root / "notes.txt")

    @classmethod
    def tearDownClass(cls):
        cls.bridge.term()
        cls.bridge.close()
        import shutil
        shutil.rmtree(cls.files_root, ignore_errors=True)
        shutil.rmtree(cls.tmp, ignore_errors=True)

    # ---- helpers ---------------------------------------------------------

    @classmethod
    def http(cls, method, path, body=None, headers=None):
        conn = http.client.HTTPConnection("127.0.0.1", cls.port, timeout=15)
        hdrs = {"X-TRMX-Protocol": "1", "Connection": "close",
                "Authorization": f"Bearer {cls.token}"}
        if body is not None:
            body = json.dumps(body).encode()
            hdrs["Content-Type"] = "application/json"
        hdrs.update(headers or {})
        conn.request(method, path, body=body, headers=hdrs)
        resp = conn.getresponse()
        raw = resp.read()
        conn.close()
        return resp.status, dict(resp.getheaders()), raw

    @classmethod
    def put_raw(cls, path, data: bytes, sha256: str | None = None, set_length=True):
        """Raw PUT with exact header control (for upload edge cases)."""
        conn = http.client.HTTPConnection("127.0.0.1", cls.port, timeout=15)
        hdrs = {"X-TRMX-Protocol": "1", "Connection": "close",
                "Authorization": f"Bearer {cls.token}"}
        if sha256:
            hdrs["X-TRMX-Sha256"] = sha256
        if set_length:
            hdrs["Content-Length"] = str(len(data))
        conn.request("PUT", path, body=data if set_length else None, headers=hdrs)
        resp = conn.getresponse()
        raw = resp.read()
        conn.close()
        return resp.status, dict(resp.getheaders()), raw

    def q(self, rel: str) -> str:
        """Query path for a fixture entry, in the wire's ~-prefixed form."""
        rel_home = str(self.files_root.relative_to(Path.home()))
        return f"~/{rel_home}/{rel}"

    # ---- §6.1 path policy --------------------------------------------------

    def test_01_traversal_denied(self):
        status, _, raw = self.http("GET", "/v1/files?path=/etc/passwd")
        self.assertEqual(status, 403, raw)
        self.assertEqual(json.loads(raw)["error"]["code"], "PATH_DENIED")
        status, _, raw = self.http("GET", "/v1/files?path=~/../../etc")
        self.assertIn(status, (403, 404), raw)  # resolves outside roots either way
        if status == 403:
            self.assertEqual(json.loads(raw)["error"]["code"], "PATH_DENIED")

    def test_02_missing_path_404(self):
        status, _, raw = self.http("GET", "/v1/files?path=~/trmx-files-nope-xyz")
        self.assertEqual(status, 404, raw)
        self.assertEqual(json.loads(raw)["error"]["code"], "PATH_NOT_FOUND")

    # ---- §6.2 list / stat ---------------------------------------------------

    def test_03_list_shapes_match_contract(self):
        status, _, raw = self.http("GET", f"/v1/files?path={self.q('')[:-1]}")
        # q('') ends with '/'; strip it for the dir itself
        self.assertEqual(status, 200, raw)
        obj = json.loads(raw)
        self.assertTrue(obj["path"].endswith(self.files_root.name), obj["path"])
        by_name = {e["name"]: e for e in obj["entries"]}
        self.assertIn("notes.txt", by_name)
        self.assertIn("music", by_name)
        self.assertIn("latest.txt", by_name)
        note = by_name["notes.txt"]
        self.assertEqual(set(note), {"name", "type", "size", "mtime", "mode", "target"})
        self.assertEqual(note["type"], "file")
        self.assertEqual(note["size"], len(b"hello trmx files\n"))
        self.assertTrue(note["mtime"].endswith("Z") and "T" in note["mtime"])
        self.assertTrue(note["mode"].startswith("-"))
        self.assertEqual(by_name["music"]["type"], "dir")
        sym = by_name["latest.txt"]
        self.assertEqual(sym["type"], "symlink")
        self.assertTrue(sym["target"].endswith("notes.txt"), sym)

    def test_04_stat_returns_the_entry_itself(self):
        status, _, raw = self.http("GET", f"/v1/files?path={self.q('notes.txt')}&stat=1")
        self.assertEqual(status, 200, raw)
        obj = json.loads(raw)
        self.assertEqual(obj["entry"]["name"], "notes.txt")
        self.assertEqual(obj["entry"]["type"], "file")

    def test_05_paging_at_1000(self):
        page_dir = self.files_root / "page"
        page_dir.mkdir()
        for i in range(1005):
            (page_dir / f"f{i:04d}").write_bytes(b"x")
        try:
            rel = str(page_dir.relative_to(Path.home()))
            status, _, raw = self.http("GET", f"/v1/files?path=~/{rel}")
            obj = json.loads(raw)
            self.assertEqual(status, 200)
            self.assertEqual(len(obj["entries"]), 1000)
            self.assertEqual(obj["next_offset"], 1000)
            status, _, raw = self.http("GET", f"/v1/files?path=~/{rel}&offset=1000")
            obj2 = json.loads(raw)
            self.assertEqual(len(obj2["entries"]), 5)
            self.assertNotIn("next_offset", obj2)
        finally:
            import shutil
            shutil.rmtree(page_dir)

    # ---- §6.3 operations ------------------------------------------------------

    def test_10_mkdir_touch_rename(self):
        status, _, raw = self.http("POST", "/v1/files", {"op": "mkdir", "path": self.q("newdir")})
        self.assertEqual(status, 200, raw)
        self.assertEqual(json.loads(raw), {"ok": True})
        # exists -> 409
        status, _, raw = self.http("POST", "/v1/files", {"op": "mkdir", "path": self.q("newdir")})
        self.assertEqual(status, 409)
        self.assertEqual(json.loads(raw)["error"]["code"], "PATH_EXISTS")
        # missing parent -> 404
        status, _, raw = self.http("POST", "/v1/files",
                                   {"op": "mkdir", "path": self.q("nope/child")})
        self.assertEqual(status, 404)
        # touch + rename
        self.http("POST", "/v1/files", {"op": "touch", "path": self.q("newdir/a.txt")})
        status, _, raw = self.http("POST", "/v1/files",
                                   {"op": "rename", "path": self.q("newdir/a.txt"),
                                    "new_name": "b.txt"})
        self.assertEqual(status, 200, raw)
        self.assertTrue((self.files_root / "newdir" / "b.txt").exists())
        # bad new_name -> 400
        status, _, raw = self.http("POST", "/v1/files",
                                   {"op": "rename", "path": self.q("newdir/b.txt"),
                                    "new_name": "evil/../x"})
        self.assertEqual(status, 400)

    def test_11_move_copy(self):
        self.http("POST", "/v1/files", {"op": "mkdir", "path": self.q("d1")})
        self.http("POST", "/v1/files", {"op": "mkdir", "path": self.q("d2")})
        self.http("POST", "/v1/files", {"op": "touch", "path": self.q("d1/x.txt")})
        status, _, raw = self.http("POST", "/v1/files",
                                   {"op": "copy", "path": self.q("d1/x.txt"),
                                    "dest_dir": self.q("d2")})
        self.assertEqual(status, 200, raw)
        self.assertEqual(json.loads(raw)["entries_moved"], 1)
        self.assertTrue((self.files_root / "d2" / "x.txt").exists())
        self.http("POST", "/v1/files", {"op": "mkdir", "path": self.q("d3")})
        status, _, raw = self.http("POST", "/v1/files",
                                   {"op": "move", "path": self.q("d1/x.txt"),
                                    "dest_dir": self.q("d3")})
        self.assertEqual(status, 200, raw)
        self.assertFalse((self.files_root / "d1" / "x.txt").exists())
        self.assertTrue((self.files_root / "d3" / "x.txt").exists())
        # dest exists -> 409
        status, _, raw = self.http("POST", "/v1/files",
                                   {"op": "copy", "path": self.q("d2/x.txt"),
                                    "dest_dir": self.q("d2")})
        self.assertEqual(status, 409)
        # dest_dir missing -> 404
        status, _, raw = self.http("POST", "/v1/files",
                                   {"op": "move", "path": self.q("d2/x.txt"),
                                    "dest_dir": self.q("missing-dir")})
        self.assertEqual(status, 404)

    def test_12_delete_interlock(self):
        self.http("POST", "/v1/files", {"op": "mkdir", "path": self.q("todelete")})
        self.http("POST", "/v1/files", {"op": "touch", "path": self.q("todelete/child.txt")})
        # non-recursive delete of non-empty dir -> PATH_NOT_EMPTY
        status, _, raw = self.http("POST", "/v1/files", {"op": "delete", "path": self.q("todelete")})
        self.assertEqual(status, 409)   # §10 registry: PATH_NOT_EMPTY is 409
        self.assertEqual(json.loads(raw)["error"]["code"], "PATH_NOT_EMPTY")
        # recursive without confirm -> CONFIRM_REQUIRED
        status, _, raw = self.http("POST", "/v1/files",
                                   {"op": "delete", "path": self.q("todelete"), "recursive": True})
        self.assertEqual(status, 400)
        self.assertEqual(json.loads(raw)["error"]["code"], "CONFIRM_REQUIRED")
        # with confirm -> gone
        status, _, raw = self.http("POST", "/v1/files",
                                   {"op": "delete", "path": self.q("todelete"),
                                    "recursive": True, "confirm": True})
        self.assertEqual(status, 200, raw)
        self.assertFalse((self.files_root / "todelete").exists())
        # symlink delete removes the link, not the target
        status, _, raw = self.http("POST", "/v1/files", {"op": "delete", "path": self.q("latest.txt")})
        self.assertEqual(status, 200, raw)
        self.assertTrue((self.files_root / "notes.txt").exists())

    def test_13_write_outside_roots_denied(self):
        status, _, raw = self.http("POST", "/v1/files",
                                   {"op": "mkdir", "path": "/etc/trmx-evil"})
        self.assertEqual(status, 403, raw)
        self.assertEqual(json.loads(raw)["error"]["code"], "PATH_DENIED")

    # ---- §6.4 download ----------------------------------------------------------

    def test_20_download_full(self):
        status, hdrs, raw = self.http("GET", f"/v1/files/content?path={self.q('notes.txt')}")
        self.assertEqual(status, 200, raw)
        self.assertEqual(raw, b"hello trmx files\n")
        self.assertEqual(hdrs.get("Content-Type"), "application/octet-stream")
        self.assertEqual(hdrs.get("Accept-Ranges"), "bytes")
        self.assertEqual(int(hdrs.get("Content-Length", -1)), len(raw))

    def test_21_download_ranges(self):
        base = f"/v1/files/content?path={self.q('music/track.mp3')}"
        status, hdrs, raw = self.http("GET", base, headers={"Range": "bytes=0-9"})
        self.assertEqual(status, 206)
        self.assertEqual(hdrs.get("Content-Range"), f"bytes 0-9/3000")
        self.assertEqual(raw, b"\x00\x01\x02" * 3 + b"\x00")
        status, hdrs, raw = self.http("GET", base, headers={"Range": "bytes=2990-"})
        self.assertEqual(status, 206)
        self.assertEqual(len(raw), 10)
        status, hdrs, raw = self.http("GET", base, headers={"Range": "bytes=-10"})
        self.assertEqual(status, 206)
        self.assertEqual(len(raw), 10)
        # beyond the end
        status, hdrs, raw = self.http("GET", base, headers={"Range": "bytes=9999-"})
        self.assertEqual(status, 416)
        self.assertEqual(hdrs.get("Content-Range"), "bytes */3000")
        # malformed
        status, _, _raw = self.http("GET", base, headers={"Range": "bytes=5-2"})
        self.assertEqual(status, 416)

    def test_22_download_dir_rejected(self):
        status, _, raw = self.http("GET", f"/v1/files/content?path={self.q('music')}")
        self.assertEqual(status, 400)
        self.assertEqual(json.loads(raw)["error"]["code"], "NOT_A_FILE")

    # ---- §6.5 upload ------------------------------------------------------------

    def test_30_upload_atomic_with_checksum(self):
        import hashlib
        status, _, raw = self.http("POST", "/v1/files",
                                   {"op": "mkdir", "path": self.q("uploads")})
        self.assertEqual(status, 200, raw)
        data = b"uploaded bytes " * 100
        dest = self.q("uploads/new.bin")
        status, _, raw = self.put_raw(
            f"/v1/files/content?path={dest}", data,
            sha256=hashlib.sha256(data).hexdigest())
        self.assertEqual(status, 200, raw)
        self.assertEqual(json.loads(raw), {"ok": True})
        self.assertEqual((self.files_root / "uploads" / "new.bin").read_bytes(), data)
        # no partial-file or temp litter
        self.assertEqual([p.name for p in (self.files_root / "uploads").iterdir()], ["new.bin"])

    def test_31_upload_overwrite_rules(self):
        dest = self.q("notes.txt")
        status, _, raw = self.put_raw(f"/v1/files/content?path={dest}", b"zzz")
        self.assertEqual(status, 409, raw)
        self.assertEqual(json.loads(raw)["error"]["code"], "PATH_EXISTS")
        status, _, raw = self.put_raw(f"/v1/files/content?path={dest}&overwrite=1", b"zzz")
        self.assertEqual(status, 200, raw)
        self.assertEqual((self.files_root / "notes.txt").read_bytes(), b"zzz")

    def test_32_upload_checksum_mismatch(self):
        dest = self.q("bad.bin")
        status, _, raw = self.put_raw(
            f"/v1/files/content?path={dest}", b"data",
            sha256="0" * 64)
        self.assertEqual(status, 422, raw)
        self.assertEqual(json.loads(raw)["error"]["code"], "CHECKSUM_MISMATCH")
        self.assertFalse((self.files_root / "bad.bin").exists())

    def test_33_upload_requires_content_length(self):
        s = socket.create_connection(("127.0.0.1", self.port), timeout=10)
        req = (f"PUT /v1/files/content?path={self.q('nolen.bin')} HTTP/1.1\r\n"
               f"Host: t\r\nAuthorization: Bearer {self.token}\r\n"
               "X-TRMX-Protocol: 1\r\nConnection: close\r\n\r\n")
        s.sendall(req.encode())
        data = s.recv(65536).decode("iso-8859-1")
        s.close()
        self.assertIn(" 411 ", data.split("\r\n")[0], data[:200])

    def test_34_upload_missing_parent_404(self):
        status, _, raw = self.put_raw(f"/v1/files/content?path={self.q('no-dir/x.bin')}", b"x")
        self.assertEqual(status, 404, raw)


class TestStorageSymlinkRoots(unittest.TestCase):
    """Regression (device, 2026-10-19): on modern Android, ~/storage is a
    REAL directory whose children are symlinks into shared storage
    (termux-setup-storage). The §6.1 roots must include each child's
    resolved target, or every shared-storage path arg is PATH_DENIED
    even though the file is right there."""

    def _policy(self, home: Path):
        import importlib.util
        spec = importlib.util.spec_from_file_location(
            "trmx_bridge", HERE.parent / "trmx-bridge.py")
        mod = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(mod)
        policy = mod.Policy(home / ".trmx")
        policy.user_home = home
        policy.roots = [home, home / "storage"]
        return mod, policy

    def test_child_symlink_target_counts_as_root(self):
        with tempfile.TemporaryDirectory() as td:
            base = Path(td)
            home = base / "home"; home.mkdir()
            (home / "storage").mkdir()
            shared_dl = base / "shared" / "Download"; shared_dl.mkdir(parents=True)
            (shared_dl / "model.gguf").write_bytes(b"gguf")
            (home / "storage" / "downloads").symlink_to(shared_dl, target_is_directory=True)
            mod, policy = self._policy(home)
            # Simulate Android's resolved target without requiring /storage
            # to exist on the Linux test host.
            from unittest.mock import patch
            resolve = Path.resolve
            link = home / "storage" / "downloads"
            android_dl = Path("/storage/emulated/0/Download")
            with patch.object(Path, "resolve", autospec=True,
                              side_effect=lambda p: android_dl if p == link else resolve(p)):
                self.assertIn(android_dl, policy.file_roots())

    def test_outside_still_denied(self):
        with tempfile.TemporaryDirectory() as td:
            base = Path(td)
            home = base / "home"; home.mkdir()
            (home / "storage").mkdir()
            outside = base / "outside.txt"; outside.write_text("x")
            mod, policy = self._policy(home)
            with self.assertRaises(mod.BridgeError) as cm:
                policy.resolve_in_roots(str(outside))
            self.assertEqual(cm.exception.status, 403)

    def test_hostile_planted_symlink_rejected(self):
        for name in ("evil", "downloads"):
            with self.subTest(name=name), tempfile.TemporaryDirectory() as td:
                home = Path(td) / "home"
                (home / "storage").mkdir(parents=True)
                (home / "storage" / name).symlink_to("/", target_is_directory=True)
                mod, policy = self._policy(home)
                self.assertNotIn(Path("/"), policy.file_roots())
                with self.assertRaises(mod.BridgeError) as cm:
                    policy.resolve_in_roots(str(home / "storage" / name / "etc/passwd"))
                self.assertEqual(cm.exception.status, 403)

    def test_device_home_fixture_layout(self):
        # audit §7-M5: the canonical fake device home (device_home.py)
        # must keep the real Termux shape this class's regressions came
        # from — ~/storage a REAL dir, its children symlinks into
        # shared storage, bridge state under ~/.trmx, $PREFIX a sibling.
        from device_home import STORAGE_LINKS, make_device_home
        with tempfile.TemporaryDirectory() as td:
            dev = make_device_home(Path(td))
            self.assertTrue(dev.home.is_dir() and dev.prefix.is_dir())
            self.assertEqual(dev.home.parent, dev.prefix.parent)
            storage = dev.home / "storage"
            self.assertTrue(storage.is_dir())
            self.assertFalse(storage.is_symlink(),
                             "~/storage itself must be a real directory")
            for name in STORAGE_LINKS:
                link = dev.storage_link(name)
                self.assertTrue(link.is_symlink(), f"{name} must be a symlink")
                self.assertTrue(link.resolve().is_dir(),
                                f"{name} target must exist")
            for d in ("logs", "tools", "bin", "services"):
                self.assertTrue((dev.trmx_home / d).is_dir(), f".trmx/{d} missing")
            self.assertTrue((dev.prefix / "bin").is_dir())

    def test_device_home_all_links_become_roots(self):
        # Every ~/storage child the fixture builds is a known
        # termux-setup-storage name, so with the device resolve targets
        # (/storage/emulated/0/…) each one must become a policy root —
        # not just downloads, which the older tests covered.
        from device_home import STORAGE_LINKS, make_device_home
        from unittest.mock import patch
        with tempfile.TemporaryDirectory() as td:
            dev = make_device_home(Path(td))
            mod, policy = self._policy(dev.home)
            policy.user_home = dev.home
            policy.roots = [dev.home, dev.home / "storage"]
            resolve = Path.resolve
            android = {dev.storage_link(name): Path("/storage/emulated/0") / folder
                       if folder else Path("/storage/emulated/0")
                       for name, folder in STORAGE_LINKS.items()}
            with patch.object(Path, "resolve", autospec=True,
                              side_effect=lambda p: android.get(p, resolve(p))):
                roots = policy.file_roots()
            for target in android.values():
                self.assertIn(target, roots)

    def test_unknown_name_under_shared_storage_rejected(self):
        with tempfile.TemporaryDirectory() as td:
            home = Path(td) / "home"
            (home / "storage").mkdir(parents=True)
            link = home / "storage" / "evil"
            link.symlink_to("/", target_is_directory=True)
            mod, policy = self._policy(home)
            from unittest.mock import patch
            resolve = Path.resolve
            target = Path("/storage/emulated/0")
            with patch.object(Path, "resolve", autospec=True,
                              side_effect=lambda p: target if p == link else resolve(p)):
                self.assertNotIn(target, policy.file_roots())


if __name__ == "__main__":
    print(f"bridge under test: {Path(BridgeProc.__module__ and '.') or ''}"
          f"{Path(HERE).parent / 'trmx-bridge.py'}")
    unittest.main(verbosity=2)
