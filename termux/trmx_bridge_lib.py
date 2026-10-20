#!/usr/bin/env python3
"""trmx_bridge_lib — policy + tool/service registries for the TRMX bridge (stdlib only).

Factored out of `trmx-bridge.py` (audit §7-H2 module split) so the daemon
file stays reviewable: path/executable policy (PROTOCOL §6.1), the tool
registry (§7.2 — bundled + user schemas and argv synthesis) and the
service registry (§14). The classes below moved byte-for-byte; the bridge
imports these names back, so its module namespace — and the wire
behavior — are unchanged.

This module must NOT import the bridge: collaborators it needs (job
manager, store, event bus) arrive by constructor injection and are only
named in (lazy) annotations. It lives beside trmx-bridge.py in both
layouts (repo termux/, installed ~/.trmx/), which is why
Path(__file__).parent lookups here (e.g. the bundled agent binary)
resolve exactly as they did inside the bridge.
"""

from __future__ import annotations

import asyncio
import datetime as dt
import json
import os
import re
import time
import urllib.parse
from pathlib import Path


TERMINAL_STATES = {"COMPLETED", "FAILED", "CANCELLED", "LOST"}
ACTIVE_STATES = {"QUEUED", "STARTING", "RUNNING", "CANCELLING"}
IDEM_TTL_S = 86400  # idempotency-key dedup window (PROTOCOL §3.3)


def now_iso() -> str:
    return dt.datetime.now(dt.timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def is_termux() -> bool:
    return bool(os.environ.get("TERMUX_VERSION")) or Path("/data/data/com.termux/files/usr").is_dir()


class BridgeError(Exception):
    """Typed error -> PROTOCOL §10 error envelope."""

    def __init__(self, status: int, code: str, message: str, field: str | None = None, details=None):
        super().__init__(message)
        self.status, self.code, self.message = status, code, message
        self.field, self.details = field, details

    def body(self) -> dict:
        err = {"code": self.code, "message": self.message, "field": self.field, "details": self.details}
        return {"error": err}


# --------------------------------------------------------------------------- policy

class Policy:
    """Path & executable policy (PROTOCOL §6.1 subset; full hardening in Phase 10).

    STRICT on Termux: executables must resolve inside $PREFIX/bin or
    ~/.trmx/bin; cwd must be an existing directory inside the roots.
    Dev-lenient elsewhere (sandbox/CI): PATH lookup + any existing cwd,
    so the test suite can run on ordinary Linux. See ADR-004.
    """

    def __init__(self, home: Path):
        self.termux = is_termux()
        self.user_home = Path.home()
        self.prefix = Path("/data/data/com.termux/files/usr") if self.termux else None
        self.trmx_bin = home / "bin"
        self.roots = [self.user_home, self.user_home / "storage"]
        self.deny_write = [self.prefix] if self.prefix else []

    def resolve_binary(self, name: str) -> str | None:
        if not name or "/" in name or name.startswith("."):
            return None
        dirs: list[Path] = [self.trmx_bin]
        if self.termux:
            dirs.append(self.prefix / "bin")
        else:
            dirs += [Path(p) for p in os.environ.get("PATH", "").split(":") if p]
        for d in dirs:
            p = d / name
            if p.is_file() and os.access(p, os.X_OK):
                return str(p)
        return None

    # ---- file endpoints (PROTOCOL §6.1) ---------------------------------

    def file_roots(self) -> list[Path]:
        # realpath()-ed at call time (spec: resolved at policy load; we
        # resolve per request — same effect, tolerates dirs appearing later)
        out: list[Path] = []
        for r in self.roots:
            if not r.exists():
                continue
            out.append(r.resolve())
            if r.name == "storage":
                # termux-setup-storage on modern Android makes ~/storage a
                # REAL directory whose children (downloads, shared, dcim, ...)
                # are symlinks into shared storage. Resolving the storage
                # dir alone therefore never contains a file beneath it --
                # only known children targeting /storage/emulated/ are roots
                # (device-found 2026-10-19: every shared-storage path arg
                # was PATH_DENIED while `ls ~/storage/...` worked fine).
                try:
                    for child in sorted(r.iterdir()):
                        if child.name not in {
                            "shared", "downloads", "dcim", "pictures", "music", "movies",
                            "audiobooks", "documents", "external", "media",
                        } or not child.is_dir():
                            continue
                        target = child.resolve()
                        if Path("/storage/emulated") in target.parents:
                            out.append(target)
                except OSError:
                    pass
        return out

    def resolve_in_roots(self, raw: str, for_write: bool = False) -> Path:
        """Expand a user path and enforce §6.1 containment.

        A path is allowed iff its realpath lies under a resolved root. For
        not-yet-existing paths (mkdir/touch/upload dest) the PARENT's
        realpath decides — realpath of a missing leaf is undefined.
        """
        if not isinstance(raw, str) or not raw:
            raise BridgeError(400, "VALIDATION_FAILED", "path must be a non-empty string",
                              field="path")
        expanded = Path(os.path.normpath(os.path.expanduser(raw)))
        if not expanded.is_absolute():
            raise BridgeError(400, "VALIDATION_FAILED",
                              "path must be absolute or ~-prefixed", field="path")
        # containment per §6.1: REALPATH decides (blocks ~/../… escapes and
        # links pointing outside the roots)…
        real = expanded.resolve()
        probe = real if real.exists() else real.parent
        roots = self.file_roots()
        if not any(probe == r or r in probe.parents for r in roots):
            raise BridgeError(403, "PATH_DENIED",
                              f"path outside policy roots: {raw}", field="path")
        if for_write and self.prefix:
            if probe == self.prefix or self.prefix in probe.parents:
                raise BridgeError(403, "PATH_DENIED",
                                  "$PREFIX is never writable via the API", field="path")
        # …but the OPERATION acts on the normalized path itself, so e.g.
        # deleting a symlink removes the LINK, never its target (on-device
        # lesson from the Phase 7 test suite, 2026-09-08)
        return expanded

    def display_path(self, abs_path: Path) -> str:
        try:
            rel = abs_path.relative_to(self.user_home)
            return "~" if str(rel) == "." else "~/" + str(rel)
        except ValueError:
            return str(abs_path)

    def check_cwd(self, raw: str) -> Path:
        expanded = Path(os.path.expanduser(raw)).resolve()
        if not expanded.exists():
            raise BridgeError(404, "PATH_NOT_FOUND", f"cwd does not exist: {raw}", field="cwd")
        if not expanded.is_dir():
            raise BridgeError(400, "NOT_A_DIRECTORY", f"cwd is not a directory: {raw}", field="cwd")
        if self.termux:
            real_roots = self.file_roots()
            if not any(expanded == r or r in expanded.parents for r in real_roots):
                raise BridgeError(403, "PATH_DENIED", f"cwd outside policy roots: {raw}", field="cwd")
        return expanded



# --------------------------------------------------------------------------- tool registry


BUNDLED_TOOL_SCHEMAS: list[dict] = [
    # ---- developer / VCS -------------------------------------------------
    {
        "id": "git-clone",
        "name": "Git Clone (VCS)",
        "description": "Clone a repository into a new directory.",
        "binary": "git",
        "pkg": "git",
        "risk_tier": "confirm",          # fetching + hooks = real code from elsewhere
        "progress_regex": r"Receiving objects:\s+(\d+)%",
        "fixed_argv": ["clone", "--progress"],
        "args": [
            {"name": "url", "label": "Repository URL", "type": "url", "required": True,
             "help": "https:// or git:// URL to clone."},
            {"name": "dir", "label": "Target directory", "type": "path", "required": False,
             "help": "Where to clone (created if missing); defaults to the repo name."},
        ],
        "examples": [
            {"label": "Clone a public repo",
             "args": {"url": "https://github.com/octocat/Hello-World"}},
        ],
    },
    # ---- scripting / AI ----------------------------------------------------
    {
        "id": "python-run",
        "name": "Python Runner (scripting & AI)",
        "description": "Run a Python script — utilities, data processing, local model calls, anything.",
        "binary": "python",
        "risk_tier": "confirm",          # arbitrary code execution, by design
        "fixed_argv": [],
        "args": [
            {"name": "script", "label": "Script file", "type": "path", "path_kind": "file",
             "required": True, "help": "The .py file to run (pick it in the file browser)."},
            {"name": "arg", "label": "Script argument", "type": "string", "required": False,
             "help": "Optional single argument passed to the script."},
        ],
        "examples": [],
    },
    {
        "id": "ai-schema-builder",
        "name": "AI Schema Builder",
        "description": "Describe a command-line tool in plain words; the local LLM wrapper (trmx-ai) drafts a validated schema and installs it to ~/.trmx/tools/. Rescan the toolbox afterwards.",
        "binary": "trmx-ai",           # ~/.trmx/bin — resolve_binary probes it first
        "risk_tier": "safe",           # fixed pipeline; the GENERATED tool is pinned to "confirm" by the wrapper (ADR-014)
        "fixed_argv": [],
        "args": [
            {"name": "description", "label": "What should the tool do?", "type": "string",
             "required": True,
             "help": "Name the binary and what it should do, in plain words."},
            {"name": "model", "label": "Model override", "type": "string",
             "required": False, "pattern": r"^[\w.-]{1,64}$",
             "argv": ["--model", "{value}"]},
        ],
        "examples": [],
    },
    # ---- system / network --------------------------------------------------
    {
        "id": "http-server",
        "name": "Local HTTP Server",
        "description": "Serve a folder over HTTP only on this device (loopback). Runs until stopped — cancel the job to stop the server.",
        "binary": "python",
        "risk_tier": "confirm",
        "progress_regex": r"(Serving HTTP on .+ port \d+)",   # port-binding line → detail
        "fixed_argv": ["-m", "http.server", "--bind", "127.0.0.1"],
        "args": [
            {"name": "port", "label": "Port", "type": "int", "min": 1024, "max": 65535,
             "default": 8000, "required": False},
            {"name": "dir", "label": "Folder to serve", "type": "path", "path_kind": "dir",
             "default": "~/downloads", "required": False, "argv": ["--directory", "{value}"]},
        ],
        "examples": [
            {"label": "Serve downloads on 8000", "args": {"port": 8000, "dir": "~/downloads"}},
        ],
    },
    # ---- system utility ------------------------------------------------------
    {
        "id": "tar-backup",
        "name": "Archive / Backup (tar.gz)",
        "description": "Create a compressed .tar.gz archive of a file or folder.",
        "binary": "tar",
        "pkg": "tar",
        "risk_tier": "confirm",          # overwrites the archive target
        "fixed_argv": ["-czf"],
        "args": [
            {"name": "archive", "label": "Archive file (.tar.gz)", "type": "path",
             "required": True, "help": "Output archive — created/overwritten."},
            {"name": "source", "label": "What to archive", "type": "path",
             "required": True, "help": "File or folder to pack."},
        ],
        "examples": [],
    },
    # ---- media (kept: real, heavily used tools — but no longer the whole story)
    # yt-dlp: audio extraction selects bestaudio instead of a video format.
    {
        "id": "yt-dlp",
        "name": "Video Downloader (yt-dlp)",
        "description": "Download videos from thousands of sites.",
        "binary": "yt-dlp",
        "pkg": "yt-dlp",          # Termux package hint (app-side install button)
        "risk_tier": "safe",
        "progress_regex": r"\[download\]\s+(\d{1,3}(?:\.\d+)?)%",
        "fixed_argv": ["--newline", "--no-playlist"],
        "args": [
            {"name": "url", "label": "Video URL", "type": "url", "required": True,
             "help": "The page or direct video URL."},
            {"name": "format", "label": "Quality", "type": "enum", "required": False,
             "enum": ["mp4", "mkv", "best"], "default": "mp4", "argv": ["-f", "{value}"]},
            {"name": "audio", "label": "Audio only", "type": "bool", "default": False,
             "argv": ["-f", "bestaudio", "-x"]},
            {"name": "rate", "label": "Rate limit", "type": "string", "required": False,
             "pattern": r"^\d+[KM]$", "argv": ["-r", "{value}"]},
            {"name": "outdir", "label": "Output folder", "type": "path", "path_kind": "dir",
             "default": "~/downloads", "argv": ["-P", "{value}"], "mkdir": True},
        ],
        "examples": [
            {"label": "MP4, best quality",
             "args": {"url": "https://example.com/watch?v=xyz", "format": "mp4"}},
        ],
    },
    {
        "id": "ffmpeg",
        "name": "Convert / Transcode (ffmpeg)",
        "description": "Re-encode video and audio files.",
        "binary": "ffmpeg",
        "pkg": "ffmpeg",
        "risk_tier": "confirm",
        "progress_regex": r"time=(\d{2}:\d{2}:\d{2}\.\d{2})",
        "fixed_argv": ["-hide_banner", "-nostdin"],
        "args": [
            {"name": "input", "label": "Input file", "type": "path", "path_kind": "file",
             "required": True, "help": "The file to convert."},
            {"name": "output", "label": "Output file", "type": "path", "path_kind": "file",
             "required": True, "help": "Where to write the result."},
            {"name": "preset", "label": "Speed preset", "type": "enum", "required": False,
             "enum": ["veryfast", "fast", "medium", "slow"], "default": "medium",
             "argv": ["-preset", "{value}"]},
            {"name": "crf", "label": "Quality (CRF)", "type": "int", "required": False,
             "min": 0, "max": 51, "default": 23, "argv": ["-crf", "{value}"]},
            {"name": "scale", "label": "Scale to (e.g. 1280x720)", "type": "string",
             "required": False, "pattern": r"^\d{2,5}x\d{2,5}$", "argv": ["-vf", "scale={value}"]},
            {"name": "audio_only", "label": "Strip video (audio only)", "type": "bool",
             "default": False, "argv": ["-vn"]},
        ],
        "examples": [
            {"label": "Compress to 720p",
             "args": {"input": "~/downloads/in.mp4", "output": "~/downloads/out.mp4",
                      "scale": "1280x720"}},
        ],
    },
    {
        "id": "aria2c",
        "name": "Fast Downloader (aria2c)",
        "description": "Multi-connection downloads over HTTP(S)/FTP.",
        "binary": "aria2c",
        "pkg": "aria2",
        "risk_tier": "safe",
        "progress_regex": r"\((\d{1,3})%\)",
        "fixed_argv": [],
        "args": [
            {"name": "url", "label": "Download URL", "type": "url", "required": True},
            {"name": "outdir", "label": "Output folder", "type": "path", "path_kind": "dir",
             "default": "~/downloads", "argv": ["-d", "{value}"], "mkdir": True},
            {"name": "connections", "label": "Connections per server", "type": "int",
             "required": False, "min": 1, "max": 16, "default": 4,
             "argv": ["-x", "{value}"]},
            {"name": "filename", "label": "Save as (optional)", "type": "string",
             "required": False, "pattern": r"^[^/\\]+$", "argv": ["-o", "{value}"]},
        ],
        "examples": [
            {"label": "Quick fetch",
             "args": {"url": "https://example.com/file.zip"}},
        ],
    },
]

TOOL_ARG_TYPES = ("string", "int", "float", "bool", "enum", "path", "url")


class ToolRegistry:
    """Bundled + user tool schemas (PROTOCOL §7.2) and availability probing.

    Merge precedence: user schemas (~/.trmx/tools/*.json, alphabetical)
    override bundled schemas with the same id. Malformed user schemas are
    skipped and reported via system/info.features.tool_schema_errors;
    a malformed BUNDLED schema is our bug and fails loud.
    """

    def __init__(self, home: Path, policy: Policy):
        self.home = home
        self.policy = policy
        self.schema_errors: list[str] = []
        self.schemas: dict[str, dict] = {}
        self._probe: dict[str, dict] = {}
        self._load()

    # -- loading ---------------------------------------------------------

    def _load(self) -> None:
        self.schema_errors = []
        merged: dict[str, dict] = {}
        sources: list[tuple[str, dict]] = [("bundled", s) for s in BUNDLED_TOOL_SCHEMAS]
        udir = self.home / "tools"
        if udir.is_dir():
            for p in sorted(udir.glob("*.json")):
                try:
                    sources.append(("user:" + p.name, json.loads(p.read_text(encoding="utf-8"))))
                except Exception as e:  # noqa: BLE001
                    self.schema_errors.append(f"user:{p.name}: not valid JSON: {e}")
        for origin, schema in sources:
            try:
                self._validate_schema(schema)
            except Exception as e:  # noqa: BLE001
                if origin == "bundled":
                    raise
                self.schema_errors.append(f"{origin}: {e}")
                continue
            merged[schema["id"]] = schema
        # The agent runner: bundled tool with a bridge-computed path. It is
        # launched by the bridge itself (POST /v1/agent/tasks) — internal,
        # not a toolbox entry the app offers for manual submits.
        agent_schema = {
            "id": "agent",
            "name": "Agent task runner (internal)",
            "description": "Runs trmx-agent for one agent task. Internal — "
                           "submitted by POST /v1/agent/tasks only.",
            "binary": "python3",
            "fixed_argv": [str(Path(__file__).resolve().parent / "trmx-agent")],
            "risk_tier": "confirm",
            "args": [
                {"name": "task_id", "label": "Task ID", "type": "string",
                 "required": True, "help": "agent task id",
                 "argv": ["--task", "{value}"]},
            ],
        }
        self._validate_schema(agent_schema)  # bundled: fail loud
        merged["agent"] = agent_schema
        self.schemas = merged

    @staticmethod
    def _validate_schema(schema: dict) -> None:
        if not isinstance(schema, dict):
            raise ValueError("schema must be a JSON object")
        tid = schema.get("id")
        if not isinstance(tid, str) or not tid or "/" in tid:
            raise ValueError("id must be a non-empty string without '/'")
        binary = schema.get("binary")
        if not isinstance(binary, str) or not binary or "/" in binary or binary.startswith("."):
            raise ValueError("binary must be a bare executable name")
        if schema.get("risk_tier", "safe") not in ("safe", "confirm", "destructive"):
            raise ValueError("risk_tier must be safe|confirm|destructive")
        fixed = schema.get("fixed_argv", [])
        if not isinstance(fixed, list) or not all(isinstance(x, str) for x in fixed):
            raise ValueError("fixed_argv must be a list of strings")
        args = schema.get("args", [])
        if not isinstance(args, list):
            raise ValueError("args must be a list")
        names = set()
        for a in args:
            if not isinstance(a, dict) or not isinstance(a.get("name"), str) or not a["name"]:
                raise ValueError("each arg needs a non-empty name")
            if a["name"] in names:
                raise ValueError(f"duplicate arg name '{a['name']}'")
            names.add(a["name"])
            t = a.get("type", "string")
            if t not in TOOL_ARG_TYPES:
                raise ValueError(f"arg '{a['name']}': bad type '{t}'")
            tmpl = a.get("argv")
            if tmpl is not None and (not isinstance(tmpl, list)
                                     or not all(isinstance(x, str) for x in tmpl)):
                raise ValueError(f"arg '{a['name']}': argv must be a list of strings")
            if t == "enum" and not isinstance(a.get("enum"), list) or t == "enum" and not a.get("enum"):
                raise ValueError(f"arg '{a['name']}': enum needs a non-empty enum list")
            if t == "bool":
                if tmpl and any("{value}" in x for x in tmpl):
                    raise ValueError(f"bool arg '{a['name']}': argv may not contain {{value}}")
            elif tmpl is not None:
                if sum(x.count("{value}") for x in tmpl) != 1:
                    raise ValueError(f"arg '{a['name']}': argv must contain {{value}} exactly once")
            if "mkdir" in a:
                if a.get("path_kind") != "dir":
                    raise ValueError(f"arg '{a['name']}': mkdir requires path_kind 'dir'")
                if not isinstance(a["mkdir"], bool):
                    raise ValueError(f"arg '{a['name']}': mkdir must be a boolean")
            if a.get("pattern"):
                try:
                    re.compile(a["pattern"])
                except re.error as e:
                    raise ValueError(f"arg '{a['name']}': bad pattern: {e}") from e
        if schema.get("progress_regex"):
            try:
                re.compile(schema["progress_regex"])
            except re.error as e:
                raise ValueError(f"bad progress_regex: {e}") from e

    # -- access ----------------------------------------------------------

    def get(self, tool_id: str) -> dict | None:
        return self.schemas.get(tool_id)

    def list_status(self) -> list[dict]:
        return [self._status(t) for t in sorted(self.schemas)]

    def _status(self, tid: str) -> dict:
        p = self._probe.get(tid, {})
        return {"schema": self.schemas[tid],
                "installed": bool(p.get("installed")),
                "version": p.get("version")}

    async def ensure_probed(self) -> list[dict]:
        for tid in sorted(self.schemas):
            if tid not in self._probe:
                await self._probe_one(tid)
        return self.list_status()

    async def refresh(self) -> list[dict]:
        # re-read bundled + user schema files first: a schema dropped into
        # ~/.trmx/tools/ (by hand, or by an AI schema builder) appears after
        # a refresh — no bridge restart needed (ADR-009 addendum).
        self._load()
        for tid in sorted(self.schemas):
            await self._probe_one(tid)
        return self.list_status()

    async def _probe_one(self, tid: str) -> dict:
        schema = self.schemas[tid]
        binary = self.policy.resolve_binary(schema["binary"])
        version = None
        if binary:
            version = await self._capture_version(binary)
        self._probe[tid] = {"installed": binary is not None, "version": version}
        return self._probe[tid]

    async def _capture_version(self, binary: str) -> str | None:
        """`<binary> --version`, first line, 10 s cap (PROTOCOL §7.1)."""
        try:
            proc = await asyncio.create_subprocess_exec(
                binary, "--version",
                stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.DEVNULL)
            try:
                out, _ = await asyncio.wait_for(proc.communicate(), timeout=10)
            except asyncio.TimeoutError:
                proc.kill()
                await proc.wait()
                return None
            lines = out.decode("utf-8", "replace").strip().splitlines()
            return lines[0][:120] if lines else None
        except Exception:  # noqa: BLE001
            return None

    # -- argv synthesis (PROTOCOL §7.2 rules, bridge-enforced) ----------

    def synth_argv(self, schema: dict, args) -> list[str]:
        if args is None:
            args = {}
        if not isinstance(args, dict):
            raise BridgeError(400, "ARG_INVALID", "args must be an object", field="args")
        known = {a["name"]: a for a in schema.get("args", [])}
        for k in args:
            if k not in known:
                raise BridgeError(400, "ARG_INVALID", f"unknown arg '{k}'", field=k)
        tokens: list[str] = []
        for a in schema.get("args", []):
            name = a["name"]
            if name not in args:
                if a.get("required"):
                    raise BridgeError(400, "ARG_INVALID", f"required arg '{name}' is missing",
                                      field=name)
                continue
            value = self._validate_arg(a, args[name])
            if schema["id"] == "yt-dlp" and name == "format" and args.get("audio") is True:
                continue  # audio's bestaudio selector replaces the video format
            if a.get("type") == "bool" and value is False:
                continue                # bool False -> contributes nothing (§7.2)
            tokens.extend(self._argv_tokens(a, value))
        return tokens

    def _validate_arg(self, a: dict, value) -> object:
        name, t = a["name"], a.get("type", "string")

        def bad(msg: str) -> None:
            raise BridgeError(400, "ARG_INVALID", f"arg '{name}': {msg}", field=name)

        if t == "bool":
            if not isinstance(value, bool):
                bad("must be true or false")
            return value
        if isinstance(value, bool):
            bad("must not be a boolean")
        if t == "int":
            if not isinstance(value, int):
                bad("must be an integer")
            if ("min" in a and value < a["min"]) or ("max" in a and value > a["max"]):
                bad(f"must be between {a.get('min')} and {a.get('max')}")
            return value
        if t == "float":
            if not isinstance(value, (int, float)):
                bad("must be a number")
            v = float(value)
            if ("min" in a and v < a["min"]) or ("max" in a and v > a["max"]):
                bad(f"must be between {a.get('min')} and {a.get('max')}")
            return v
        if t == "enum":
            if value not in a.get("enum", []):
                bad(f"must be one of {a.get('enum')}")
            return value
        if not isinstance(value, str) or value == "":
            bad("must be a non-empty string")
        if t == "url":
            u = urllib.parse.urlparse(value)
            if u.scheme not in ("http", "https") or not u.netloc:
                bad("must be an http(s):// URL")
        if a.get("pattern") and not re.fullmatch(a["pattern"], value):
            bad(f"must match pattern {a['pattern']}")
        if t == "path":
            return self._resolve_path_arg(a, value)
        return value

    def _resolve_path_arg(self, a: dict, raw: str) -> str:
        """Path args are resolved against the §6.1 policy and substituted as
        ABSOLUTE paths — argv is exec'd without a shell, so '~' would never
        expand (ADR-009).

        `mkdir: true` (§7.2, dir args only — output folders like yt-dlp's
        -P / aria2c's -d): a missing directory is CREATED (parents too,
        `mkdir -p` semantics) instead of failing PATH_NOT_FOUND. Creation
        resolves with WRITE semantics, so it stays inside the §6.1 writable
        roots; input dirs (e.g. http-server's serve folder) keep the
        existence check."""
        name = a["name"]
        auto_mkdir = bool(a.get("mkdir"))
        try:
            resolved = self.policy.resolve_in_roots(
                raw, for_write=auto_mkdir or a.get("path_kind") != "dir")
        except BridgeError as e:
            raise BridgeError(e.status, e.code, f"arg '{name}': {e.message}", field=name) from e
        if a.get("path_kind") == "dir" and not resolved.is_dir():
            if not auto_mkdir:
                raise BridgeError(404, "PATH_NOT_FOUND",
                                  f"arg '{name}': directory does not exist: {raw}", field=name)
            try:
                resolved.mkdir(parents=True, exist_ok=True)
            except OSError as e:
                raise BridgeError(400, "VALIDATION_FAILED",
                                  f"arg '{name}': cannot create directory {raw}: {e}",
                                  field=name) from e
        return str(resolved)

    @staticmethod
    def _argv_tokens(a: dict, value) -> list[str]:
        tmpl = a.get("argv")
        if not tmpl:                     # positional: the value itself
            return [str(value)]
        if a.get("type") == "bool":
            return list(tmpl)            # tokens iff true
        return [tok.replace("{value}", str(value)) for tok in tmpl]


# --------------------------------------------------------------------------- service registry (protocol 1.1)

SERVICE_ID_RE = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,63}")


# -- service liveness + port probes (§3-M1)

def _pgid_alive(pgid) -> bool:
    """Signal-0 liveness probe for a process group."""
    try:
        os.killpg(pgid, 0)
    except (OSError, TypeError):   # dead pgid, or pgid None
        return False
    return bool(pgid)


def _port_holder(port: int, job: dict | None, store: Store):
    """(port_open, kind, holder_job_id) via /proc; kind ∈ self/other_job/
    unknown; port_open None when /proc is unreadable."""
    inodes = set()
    for name in ("tcp", "tcp6"):
        try:
            lines = Path(f"/proc/net/{name}").read_text().splitlines()[1:]
        except OSError:
            lines = []
        for line in lines:
            p = line.split()
            if len(p) > 9 and p[3] == "0A" \
                    and p[1].rsplit(":", 1)[1] == "%04X" % port:
                inodes.add(p[9])
    if not inodes:
        return (None if not Path("/proc/net/tcp").exists() else False), "unknown", None
    for j in store.active_jobs():
        if not j["pid"]:
            continue
        try:
            fds = list((Path("/proc") / str(j["pid"]) / "fd").iterdir())
        except OSError:
            continue
        for f in fds:
            try:
                tgt = os.readlink(f)
            except OSError:
                continue
            if tgt.startswith("socket:[") and tgt[8:-1] in inodes:
                if job and j["job_id"] == job["job_id"]:
                    return True, "self", None
                return True, "other_job", j["job_id"]
    return True, "unknown", None


class ServiceRegistry:
    """Named long-running services (protocol 1.1, PROTOCOL §14).

    A service is a PERSISTENT DEFINITION (tool + fixed args + autostart
    flag, ~/.trmx/services/<id>.json) bound at runtime to the job it last
    started. Lifecycle = job lifecycle: start submits a normal tool job,
    stop cancels it (idempotent, §3.5), restart waits for terminal then
    resubmits, status derives from the bound job. Tool-based definitions
    reuse the whole §7.2 synthesis/validation pipeline — services are NOT
    a second execution path (ADR-015).
    """

    def __init__(self, home: Path, registry: ToolRegistry, manager: JobManager,
                 store: Store, bus: EventBus):
        self.home = home
        self.registry = registry
        self.manager = manager
        self.store = store
        self.bus = bus
        self.defs: dict[str, dict] = {}
        self.bindings: dict[str, str] = {}      # service id -> last started job id
        self.errors: list[str] = []
        self._start_locks: dict[str, asyncio.Lock] = {}
        self._load()

    def _load(self) -> None:
        self.defs, self.errors = {}, []
        sdir = self.home / "services"
        sdir.mkdir(parents=True, exist_ok=True)
        for p in sorted(sdir.glob("*.json")):
            # "_" prefix is the reserved state namespace (e.g.
            # _bindings.json) — never a definition (service ids must start
            # with [A-Za-z0-9])
            if p.name.startswith("_"):
                continue
            try:
                d = json.loads(p.read_text(encoding="utf-8"))
                self._validate_def(d)
            except Exception as e:  # noqa: BLE001
                self.errors.append(f"{p.name}: {e}")
                continue
            self.defs[d["id"]] = d
        # audit §5-H1: bindings (service -> job) live on disk, not in RAM
        # only — reconcile() re-links them to surviving jobs at startup
        self._load_bindings()

    def _bindings_path(self) -> Path:
        return self.home / "services" / "_bindings.json"

    def _load_bindings(self) -> None:
        p = self._bindings_path()
        if not p.exists():
            self.bindings = {}
            return
        try:
            raw = json.loads(p.read_text(encoding="utf-8"))
            if not (isinstance(raw, dict)
                    and all(isinstance(k, str) and isinstance(v, str)
                            for k, v in raw.items())):
                raise ValueError("not a string->string map")
            # drop bindings whose definition is gone (file deleted by hand)
            self.bindings = {k: v for k, v in raw.items() if k in self.defs}
        except Exception as e:  # noqa: BLE001
            self.errors.append(f"{p.name}: {e}")
            self.bindings = {}

    def _save_bindings(self) -> None:
        path = self._bindings_path()
        tmp = path.with_suffix(".json.tmp")
        tmp.write_text(json.dumps(self.bindings, indent=2, ensure_ascii=False)
                       + "\n", encoding="utf-8")
        os.replace(tmp, path)

    def _validate_def(self, d: dict) -> None:
        if not isinstance(d, dict):
            raise ValueError("definition must be a JSON object")
        sid = d.get("id")
        if not isinstance(sid, str) or not SERVICE_ID_RE.fullmatch(sid) \
                or sid.startswith("."):
            raise ValueError("id must match [A-Za-z0-9][A-Za-z0-9._-]{0,63}")
        if not isinstance(d.get("name"), str) or not (1 <= len(d["name"]) <= 80):
            raise ValueError("name must be 1..80 chars")
        tool = d.get("tool")
        if not isinstance(tool, str) or not tool:
            raise ValueError("tool is required")
        schema = self.registry.get(tool)
        if schema is None:
            raise BridgeError(400, "TOOL_UNKNOWN",
                              f"no tool schema with id '{tool}'", field="tool")
        args = d.get("args", {})
        if not isinstance(args, dict):
            raise ValueError("args must be an object")
        # dry-run the §7.2 synthesis: catches missing required args,
        # bad types, enum/path violations — the same ARG_INVALID errors
        self.registry.synth_argv(schema, args)
        if not isinstance(d.get("autostart", False), bool):
            raise ValueError("autostart must be a boolean")
        if (p := d.get("port")) is not None and (type(p) is not int or not 1 <= p <= 65535):
            raise ValueError("port must be an int in 1..65535")

    def _write_def(self, d: dict) -> None:
        path = self.home / "services" / f"{d['id']}.json"
        tmp = path.with_suffix(".json.tmp")
        tmp.write_text(json.dumps(d, indent=2, ensure_ascii=False) + "\n",
                       encoding="utf-8")
        os.replace(tmp, path)

    # -- queries ----------------------------------------------------------

    def status(self, sid: str) -> dict | None:
        d = self.defs.get(sid)
        if d is None:
            return None
        job_id = self.bindings.get(sid)
        job = self.store.get_job(job_id) if job_id else None
        state = "running" if (job and job["status"] in ACTIVE_STATES) else "stopped"
        port = d.get("port")
        if type(port) is not int or not 1 <= port <= 65535:
            port = d.get("args", {}).get("port")
            if type(port) is not int or not 1 <= port <= 65535:
                port = None
        pid_alive = port_open = port_holder = diagnosis = detail = None
        if job and job["status"] in ACTIVE_STATES:
            pid_alive = _pgid_alive(job.get("pgid") or job.get("pid"))
            if not pid_alive:
                diagnosis = "process_dead"
                detail = f"{job_id} {job['status']} in DB, pgid {job.get('pgid')} gone"
        if port is not None:
            port_open, port_holder, holder_job = _port_holder(port, job, self.store)
            if port_open and port_holder != "self" and diagnosis is None:
                diagnosis = f"port_in_use_by_{port_holder}"
                who = (f"job {holder_job}" if port_holder == "other_job"
                       else "an unknown process")
                detail = f"port {port} is held by {who}"
                if job and job["status"] in TERMINAL_STATES:
                    detail += f" — last run ended {job['status']}; bind conflict likely"
        return {
            "id": d["id"], "name": d["name"], "tool": d["tool"],
            "args": d.get("args", {}),
            "autostart": bool(d.get("autostart", False)),
            "created_at": d.get("created_at"),
            "state": state,
            "job_id": job_id if state == "running" else None,
            "last_job_id": job_id,
            "last_status": job["status"] if job else None,
            "last_exit_code": job["exit_code"] if job else None,
            "port": port, "pid_alive": pid_alive, "port_open": port_open,
            "port_holder": port_holder, "diagnosis": diagnosis,
            "diagnosis_detail": detail,
        }

    def list_status(self) -> list[dict]:
        return [self.status(sid) for sid in sorted(self.defs)]

    # -- mutations ----------------------------------------------------------

    def create(self, body: dict) -> dict:
        d = dict(body)
        try:
            self._validate_def(d)
        except ValueError as e:
            raise BridgeError(400, "VALIDATION_FAILED", str(e),
                              field="definition") from e
        sid = d["id"]
        if sid in self.defs and self.status(sid)["state"] == "running":
            raise BridgeError(409, "SERVICE_RUNNING",
                              f"service '{sid}' is running — stop it before replacing it")
        clean = {"id": sid, "name": d["name"], "tool": d["tool"],
                 "args": d.get("args", {}), "autostart": bool(d.get("autostart", False)),
                 "port": d.get("port"), "created_at": now_iso()}
        self._write_def(clean)
        self.defs[sid] = clean          # binding (last job) survives a replace
        self.store.audit("service.create", f"{sid} (tool {clean['tool']})")
        st = self.status(sid)
        self.bus.publish("service.updated", st)
        return st

    def delete(self, sid: str) -> dict:
        if sid not in self.defs:
            raise BridgeError(404, "SERVICE_NOT_FOUND", f"no service with id '{sid}'")
        if self.status(sid)["state"] == "running":
            raise BridgeError(409, "SERVICE_RUNNING",
                              f"service '{sid}' is running — stop it before deleting it")
        (self.home / "services" / f"{sid}.json").unlink(missing_ok=True)
        del self.defs[sid]
        self.bindings.pop(sid, None)
        self._save_bindings()
        self.store.audit("service.delete", sid)
        self.bus.publish("service.updated", {"id": sid, "deleted": True})
        return {"ok": True, "id": sid}

    async def start(self, sid: str) -> dict:
        d = self.defs.get(sid)
        if d is None:
            raise BridgeError(404, "SERVICE_NOT_FOUND", f"no service with id '{sid}'")
        # §3-M9: 409 check + submit must be atomic per service
        async with self._start_locks.setdefault(sid, asyncio.Lock()):
            if self.status(sid)["state"] == "running":
                raise BridgeError(409, "SERVICE_RUNNING",
                                  f"service '{sid}' is already running "
                                  f"(job {self.bindings.get(sid)})")
            job, _ = await self.manager.submit({"name": f"service: {d['name']}",
                                                "type": "tool", "tool": d["tool"],
                                                "args": d.get("args", {}), "cwd": "~"})
            self.bindings[sid] = job["job_id"]
            self._save_bindings()
            self.store.audit("service.start", f"{sid} -> {job['job_id']}")
            st = self.status(sid)
            self.bus.publish("service.updated", st)
            return st

    async def stop(self, sid: str) -> dict:
        if sid not in self.defs:
            raise BridgeError(404, "SERVICE_NOT_FOUND", f"no service with id '{sid}'")
        job_id = self.bindings.get(sid)
        if job_id:
            # cancel is idempotent on terminal jobs (§3.5) — stopping a
            # stopped service stays 200, never a client-side race error
            await self.manager.cancel(job_id, None, False)
        self.store.audit("service.stop", f"{sid} (job {job_id or 'none'})")
        st = self.status(sid)
        self.bus.publish("service.updated", st)
        return st

    async def restart(self, sid: str) -> dict:
        if sid not in self.defs:
            raise BridgeError(404, "SERVICE_NOT_FOUND", f"no service with id '{sid}'")
        job_id = self.bindings.get(sid)
        if job_id:
            await self.manager.cancel(job_id, None, False)
            deadline = time.time() + 30
            while time.time() < deadline:
                job = self.store.get_job(job_id)
                if not job or job["status"] in TERMINAL_STATES:
                    break
                await asyncio.sleep(0.1)
            else:
                raise BridgeError(500, "INTERNAL",
                                  f"service '{sid}' did not stop within 30s")
        self.store.audit("service.restart", sid)
        return await self.start(sid)

    def set_autostart(self, sid: str, enabled: bool) -> dict:
        d = self.defs.get(sid)
        if d is None:
            raise BridgeError(404, "SERVICE_NOT_FOUND", f"no service with id '{sid}'")
        d["autostart"] = enabled
        self._write_def(d)
        self.store.audit("service.autostart", f"{sid} = {enabled}")
        st = self.status(sid)
        self.bus.publish("service.updated", st)
        return st

    async def autostart_boot(self) -> None:
        """Launch autostart services after boot reconciliation. Best-effort
        per service: a failure is audited and skipped, never fatal."""
        for sid, d in sorted(self.defs.items()):
            if not d.get("autostart"):
                continue
            try:
                if self.status(sid)["state"] == "running":
                    continue
                await self.start(sid)
            except BridgeError as e:
                self.store.audit("service.autostart", f"{sid} FAILED: {e.message}")


# --------------------------------------------------------------------------- AI backend config (protocol 1.1)

# Mirrors trmx-ai's defaults/template (docs/AI-CONFIG.md). The bridge never
# interprets the config — it only reads/validates/writes the file for the
# app; trmx-ai remains the sole consumer.
AI_PROVIDERS = ("openai", "groq", "gemini", "openai_compatible")
AI_DEFAULT_CONFIG = {
    "mode": "cli",
    "cli": {"command": ["ollama", "run", "llama3.2"], "timeout_s": 180},
    "http_api": {"provider": "groq", "endpoint": "", "api_key": "",
                 "api_key_env": "GROQ_API_KEY", "model": "llama-3.3-70b-versatile",
                 "timeout_s": 120},
}


