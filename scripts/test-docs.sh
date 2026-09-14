#!/usr/bin/env bash
set -euo pipefail

# Opt-in doc verification, shared across all languages
# (.claude/rules/testing.md: "本体の tests/docs.sh と同じオプトイン方式").
# Only fenced bash blocks immediately preceded by an HTML comment
# containing "doctest" are extracted and run -- everything else in
# docs/usage/*.md (SSH, socat, TLS, Docker examples) is intentionally left
# unmarked and is not touched by this script.

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${REPO_ROOT}"

status=0
found=0

while IFS= read -r -d '' doc; do
  # Print the file with line numbers, find each "<!-- doctest -->" marker,
  # and pull out the very next fenced block (```...```` ... ````) that
  # follows it.
  block=""
  in_marker=0
  in_fence=0
  while IFS= read -r line; do
    if [[ "${line}" == *"<!-- doctest -->"* ]]; then
      in_marker=1
      continue
    fi
    if [[ "${in_marker}" -eq 1 && "${in_fence}" -eq 0 ]]; then
      if [[ "${line}" == '```'* ]]; then
        in_fence=1
        block=""
        continue
      fi
      # Marker wasn't immediately followed by a fence -- give up on it.
      in_marker=0
      continue
    fi
    if [[ "${in_fence}" -eq 1 ]]; then
      if [[ "${line}" == '```' ]]; then
        in_fence=0
        in_marker=0
        found=$((found + 1))
        echo "=== ${doc}: doctest block ${found} ==="
        echo "${block}"
        echo "--- output ---"
        if ! output="$(bash -c "${block}" 2>&1)"; then
          echo "${output}"
          echo "FAILED: block exited non-zero"
          status=1
          continue
        fi
        echo "${output}"
        if ! grep -q '"protocol":1' <<<"${output}"; then
          echo "FAILED: expected a hello line with \"protocol\":1 in the output"
          status=1
          continue
        fi
        if ! grep -q '"ok":true' <<<"${output}"; then
          echo "FAILED: expected an \"ok\":true response in the output"
          status=1
          continue
        fi
        echo "OK"
        continue
      fi
      block="${block}${line}
"
    fi
  done < "${doc}"
done < <(find docs/usage -name '*.md' -print0 2>/dev/null)

if [[ "${found}" -eq 0 ]]; then
  echo "test-docs: no <!-- doctest --> blocks found under docs/usage" >&2
  exit 1
fi

exit "${status}"
