#!/bin/sh
# Regenerate termux/SHA256SUMS — MUST be run whenever trmx-bridge.py, trmx,
# trmx-ai, trmx_ai_lib.py or trmx-agent changes (release tagging also
# regenerates it; see ADR-005).
set -eu
cd "$(dirname "$0")"
sha256sum trmx-bridge.py trmx trmx-ai trmx_ai_lib.py trmx-agent > SHA256SUMS
cat SHA256SUMS
