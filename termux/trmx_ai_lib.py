#!/usr/bin/env python3
"""trmx_ai_lib — shared LLM-backend machinery for TRMX tools (stdlib only).

Factored out of `trmx-ai` (ADR-014) so both `trmx-ai` (schema writer) and
`trmx-agent` (task loop) drive the SAME backends from the SAME `~/.trmx/ai.json`
config. The backend is interchangeable; the trust rules are not:

- LLM output is UNTRUSTED: never eval'd, only parsed as data; callers
  validate with strict rules and size caps, and fail loud on any doubt.
- API keys are never logged or echoed (see `redact_url`).

Backend modes (see docs/AI-CONFIG.md):
  1. "cli"      — offline/local: any CLI-wrapped model runner, the prompt
                   appended as ONE argv element (no shell).
  2. "http_api" — cloud: openai | groq | gemini | openai_compatible via
                   stdlib urllib only.
"""

from __future__ import annotations

import json
import os
import re
import socket
import subprocess
import sys
import tempfile
import urllib.error
import urllib.request
from pathlib import Path

TRMX_AI_LIB_VERSION = "0.1.0"
DEFAULT_COMMAND = ["ollama", "run", "llama3.2"]
DEFAULT_TIMEOUT_S = 180
DEFAULT_HTTP_TIMEOUT_S = 120
MAX_LLM_BYTES = 65536          # cap on model text we are willing to parse
MAX_HTTP_BYTES = 1024 * 1024   # cap on HTTP response body
MAX_REPLY_TOKENS = 512         # per-step reply cap, every http provider

VALID_PROVIDERS = ("openai", "groq", "gemini", "openai_compatible")
PROVIDER_ENDPOINTS = {
    "openai": "https://api.openai.com/v1/chat/completions",
    "groq": "https://api.groq.com/openai/v1/chat/completions",
    # gemini's endpoint embeds the model — constructed at resolve time
    "gemini": "https://generativelanguage.googleapis.com/v1beta/models",
}
DEFAULT_KEY_ENV = {
    "openai": "OPENAI_API_KEY",
    "groq": "GROQ_API_KEY",
    "gemini": "GEMINI_API_KEY",
    # openai_compatible: no convention — set api_key or api_key_env
}

# Written to ~/.trmx/ai.json on first run (mode cli) so users have a
# documented, one-flip-ready config to edit. Annotated in docs/AI-CONFIG.md.
CONFIG_TEMPLATE = {
    "_help": [
        "TRMX AI backend configuration. Full guide: docs/AI-CONFIG.md",
        "mode: 'cli' = offline/local model runner | 'http_api' = cloud API.",
        "Unknown keys are ignored. Keep this file private (chmod 600).",
    ],
    "mode": "cli",
    "cli": {
        "command": ["ollama", "run", "llama3.2"],
        "timeout_s": 180,
    },
    "http_api": {
        "provider": "groq",
        "endpoint": "",
        "api_key": "",
        "api_key_env": "GROQ_API_KEY",
        "model": "llama-3.3-70b-versatile",
        "timeout_s": 120,
    },
}


# ---------------------------------------------------------------- helpers

def die(code: int, msg: str) -> "None":
    print(f"trmx: {msg}", file=sys.stderr)
    sys.exit(code)


def home_dir() -> Path:
    return Path(os.environ.get("TRMX_HOME", str(Path.home() / ".trmx")))


def redact_url(url: str) -> str:
    """Never echo key material that may be embedded in a URL."""
    return re.sub(r"([?&](?:key|api_key|token)=)[^&]+", r"\1***", url)


def write_template_file(path: Path, tool: str = "trmx-ai") -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=path.parent,
                                      prefix=".ai.json-", delete=False)
    try:
        tmp.write(json.dumps(CONFIG_TEMPLATE, indent=2, ensure_ascii=False) + "\n")
        tmp.close()
        os.replace(tmp.name, path)
        os.chmod(path, 0o600)          # it may hold an API key later
    except BaseException:
        try:
            os.unlink(tmp.name)
        except OSError:
            pass
        raise
    print(f"{tool}: wrote default config template to {path} — edit it to "
          "switch to a cloud backend (docs/AI-CONFIG.md)", file=sys.stderr)


def load_config(write_template: bool, tool: str = "trmx-ai") -> dict:
    """Read ~/.trmx/ai.json; on first run write the template. Returns {} if
    the file does not exist (and no template was requested)."""
    path = home_dir() / "ai.json"
    if not path.is_file():
        if write_template:
            write_template_file(path, tool)
        return {}
    try:
        cfg = json.loads(path.read_text(encoding="utf-8"))
    except Exception as e:  # noqa: BLE001
        die(3, f"{path}: not valid JSON: {e}")
    if not isinstance(cfg, dict):
        die(3, f"{path}: must be a JSON object")
    return cfg


def resolve_mode(cfg: dict) -> str:
    """Mode with the same legacy/implicit rules trmx-ai always used."""
    mode = cfg.get("mode")
    if mode is None:
        # legacy/implicit: flat {"command": ...} is cli; an http_api block
        # (with or without "mode") is http_api
        mode = "http_api" if ("http_api" in cfg or "provider" in cfg) else "cli"
    if mode not in ("cli", "http_api"):
        die(3, f"ai.json: 'mode' must be 'cli' or 'http_api' — got {mode!r}")
    return mode


# -------------------------------------------------- backend resolution

def _pos_timeout(value, default: int, what: str) -> int:
    if value is None:
        return default
    if not isinstance(value, (int, float)) or value <= 0:
        die(3, f"ai.json: {what} must be a positive number")
    return int(value)


def resolve_cli(cfg: dict, model: str | None) -> tuple[list[str], int]:
    """cli mode: structured 'cli' block, legacy flat keys, or defaults."""
    block = cfg.get("cli") if isinstance(cfg.get("cli"), dict) else {}
    if not isinstance(block, dict):
        die(3, "ai.json: 'cli' must be a JSON object")
    command = block.get("command") or cfg.get("command") or list(DEFAULT_COMMAND)
    if not isinstance(command, list) or not command or \
            not all(isinstance(x, str) for x in command):
        die(3, "ai.json: cli 'command' must be a non-empty list of strings")
    command = list(command)
    timeout = _pos_timeout(block.get("timeout_s", cfg.get("timeout_s")),
                           DEFAULT_TIMEOUT_S, "cli timeout_s")
    if model:                     # --model overrides the configured model
        if len(command) >= 3 and command[0] == "ollama":
            command[2] = model
        else:
            die(3, "--model needs an 'ollama' backend in ai.json "
                   "(other backends: set the model inside 'command')")
    return command, timeout


def _resolve_api_key(block: dict, provider: str) -> str:
    key = block.get("api_key")
    if isinstance(key, str) and key.strip():
        return key.strip()
    env_name = block.get("api_key_env") or DEFAULT_KEY_ENV.get(provider, "")
    if isinstance(env_name, str) and env_name:
        val = os.environ.get(env_name, "")
        if val.strip():
            return val.strip()
    hint = f" (or export {env_name})" if env_name else ""
    die(3, f"ai.json: http_api has no API key for provider '{provider}' — "
           f"set 'api_key' or 'api_key_env'{hint}")


def resolve_http(cfg: dict, model: str | None) -> dict:
    """http_api mode: validate the block, resolve key/endpoint/model."""
    block = cfg.get("http_api")
    if not isinstance(block, dict):
        die(3, "ai.json: mode 'http_api' needs an 'http_api' object")
    provider = block.get("provider")
    if provider not in VALID_PROVIDERS:
        die(3, f"ai.json: http_api.provider must be one of "
               f"{', '.join(VALID_PROVIDERS)} — got {provider!r}")
    model = model or block.get("model")
    if not isinstance(model, str) or not model.strip():
        die(3, "ai.json: http_api 'model' is required (e.g. "
               "'llama-3.3-70b-versatile' on groq)")
    model = model.strip()
    endpoint = block.get("endpoint")
    if endpoint is not None and not isinstance(endpoint, str):
        die(3, "ai.json: http_api 'endpoint' must be a string")
    if provider == "gemini":
        url = endpoint or f"{PROVIDER_ENDPOINTS['gemini']}/{model}:generateContent"
    elif provider == "openai_compatible":
        if not endpoint:
            die(3, "ai.json: provider 'openai_compatible' needs an explicit "
                   "'endpoint' (any OpenAI-shaped /chat/completions URL)")
        url = endpoint
    else:
        url = endpoint or PROVIDER_ENDPOINTS[provider]
    return {
        "provider": provider,
        "url": url,
        "key": _resolve_api_key(block, provider),
        "model": model,
        "timeout": _pos_timeout(block.get("timeout_s"), DEFAULT_HTTP_TIMEOUT_S,
                                "http_api timeout_s"),
    }


# ------------------------------------------------------ backend execution

def run_cli(command: list[str], prompt: str, timeout: int) -> str:
    try:
        proc = subprocess.run(command + [prompt], capture_output=True,
                              timeout=timeout)
    except FileNotFoundError:
        die(3, f"backend '{command[0]}' not found — install it (e.g. "
               "pkg install ollama) or switch ai.json to http_api mode "
               "(docs/AI-CONFIG.md)")
    except subprocess.TimeoutExpired:
        die(3, f"backend timed out after {timeout}s — try a smaller model "
               "or raise timeout_s in ~/.trmx/ai.json")
    if proc.returncode != 0:
        tail = proc.stderr.decode("utf-8", "replace")[-500:]
        die(3, f"backend exited {proc.returncode}: {tail.strip()}")
    return proc.stdout.decode("utf-8", "replace")


def _extract_text(payload, provider: str) -> str:
    """Pull the model's text out of a provider response (as data)."""
    if provider == "gemini":
        try:
            parts = payload["candidates"][0]["content"]["parts"]
            text = "".join(p.get("text", "") for p in parts if isinstance(p, dict))
        except (KeyError, IndexError, TypeError, AttributeError):
            die(3, "gemini response shape unexpected — check provider/model/"
                   "endpoint (candidates[0].content.parts missing)")
    else:
        try:
            text = payload["choices"][0]["message"]["content"]
            if not isinstance(text, str):
                raise TypeError("content is not a string")
        except (KeyError, IndexError, TypeError):
            die(3, "openai-compatible response shape unexpected — check "
                   "provider/model/endpoint (choices[0].message.content missing)")
    return text


def call_http(spec: dict, prompt: str) -> str:
    """POST the prompt; stdlib only; never echoes the key."""
    if spec["provider"] == "gemini":
        headers = {"Content-Type": "application/json",
                   "x-goog-api-key": spec["key"]}
        body = {"contents": [{"parts": [{"text": prompt}]}],
                # Same reply cap as the openai-shape path below
                # (audit §2-H4): an uncapped Gemini step rambles just
                # as long, and on a paid key it costs money too.
                "generationConfig": {"maxOutputTokens": MAX_REPLY_TOKENS}}
    else:   # openai / groq / openai_compatible share the chat shape
        headers = {"Content-Type": "application/json",
                   "Authorization": f"Bearer {spec['key']}"}
        body = {"model": spec["model"],
                "messages": [{"role": "user", "content": prompt}],
                # Cap the reply: an uncapped small model rambles for
                # hundreds of tokens per agent step (626 seen on device),
                # and every token is seconds on a phone CPU.
                "max_tokens": MAX_REPLY_TOKENS}
    req = urllib.request.Request(
        spec["url"], data=json.dumps(body).encode("utf-8"),
        headers=headers, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=spec["timeout"]) as r:
            raw = r.read(MAX_HTTP_BYTES + 1)
    except urllib.error.HTTPError as e:
        try:
            detail = e.read(2000).decode("utf-8", "replace")
        except Exception:  # noqa: BLE001
            detail = ""
        die(3, f"HTTP {e.code} from {redact_url(spec['url'])}: "
               f"{detail[:300].strip()}")
    except urllib.error.URLError as e:
        reason = getattr(e, "reason", e)
        die(3, f"cannot reach {redact_url(spec['url'])}: {reason}")
    except (TimeoutError, socket.timeout):
        die(3, f"HTTP backend timed out after {spec['timeout']}s — raise "
               "timeout_s in ~/.trmx/ai.json or try a smaller model")
    if len(raw) > MAX_HTTP_BYTES:
        die(3, "HTTP response larger than 1 MiB — refusing")
    try:
        payload = json.loads(raw.decode("utf-8", "replace"))
    except Exception as e:  # noqa: BLE001
        die(3, f"endpoint returned non-JSON: {e}")
    if not isinstance(payload, dict):
        die(3, "endpoint returned a non-object JSON body")
    return _extract_text(payload, spec["provider"])


def call_backend(cfg: dict, model: str | None, prompt: str,
                 tool: str = "trmx") -> tuple[str, dict]:
    """One prompt → model text, on the configured backend.

    Returns (text, {"mode": ..., "model": ...}) — the model label is for
    display (so the user sees what is answering). Raises SystemExit via
    die() on config/backend problems, exactly like trmx-ai.
    """
    mode = resolve_mode(cfg)
    if mode == "cli":
        command, timeout = resolve_cli(cfg, model)
        print(f"{tool}: calling {command[0]} … (this can take a minute)",
              file=sys.stderr)
        label = " ".join(command[:2])
        return run_cli(command, prompt, timeout), {"mode": "cli", "model": label}
    spec = resolve_http(cfg, model)
    print(f"{tool}: calling {spec['provider']} via "
          f"{redact_url(spec['url'])} …", file=sys.stderr)
    return call_http(spec, prompt), {"mode": "http_api",
                                     "model": f"{spec['provider']}/{spec['model']}"}


# --------------------------------------------- untrusted-output handling

def _balanced_blocks(text: str) -> list[tuple[int, int]]:
    """(start, end) spans of every top-level balanced {...} block.

    String-aware exactly like the original first-block scan: braces
    inside a JSON string do not move the depth. Quote state is only
    tracked inside a block — prose quotes before/between blocks are
    ignored, as they were when only the first block was scanned.
    """
    spans: list[tuple[int, int]] = []
    depth = 0
    start = -1
    in_str = False
    esc = False
    for i, c in enumerate(text):
        if depth == 0:
            if c == "{":
                start, depth = i, 1
            continue
        if in_str:
            if esc:
                esc = False
            elif c == "\\":
                esc = True
            elif c == '"':
                in_str = False
            continue
        if c == '"':
            in_str = True
        elif c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
            if depth == 0:
                spans.append((start, i + 1))
    return spans


def _parse_block(text: str, span: tuple[int, int]) -> dict:
    """Parse one balanced block with the original error wording."""
    try:
        obj = json.loads(text[span[0]:span[1]])
    except Exception as e:  # noqa: BLE001
        raise ValueError(f"model JSON does not parse: {e}") from e
    if not isinstance(obj, dict):
        raise ValueError("model output is not a JSON object")
    return obj


def extract_json(text: str, validator=None) -> dict:
    """One JSON object out of untrusted model text.

    Without a validator: the first balanced, string-aware {...} block
    (the original behaviour) — fences/prose tolerated, but the JSON
    itself must parse; anything else is invalid.

    With a validator (a callable that raises ValueError to reject a
    candidate): every balanced block is parsed and the LAST one the
    validator accepts wins. A small model that chats around its JSON
    often emits a prose/example object first and the real action last,
    and the first block used to be executed as-is (audit §2-M3). If NO
    block validates, fall back to the first block exactly as above, so
    the caller sees the same object/error it would have seen before.
    """
    text = text[:MAX_LLM_BYTES]
    if "{" not in text:
        raise ValueError("no JSON object found in the model output")
    spans = _balanced_blocks(text)
    if not spans:
        raise ValueError("unbalanced braces in the model output")
    if validator is None:
        return _parse_block(text, spans[0])
    chosen = None
    for span in spans:
        try:
            obj = _parse_block(text, span)
            validator(obj)
        except ValueError:
            continue
        chosen = obj
    if chosen is not None:
        return chosen
    return _parse_block(text, spans[0])
