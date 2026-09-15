.PHONY: shellcheck trivy fetch go-build go-vet go-test go-netcheck test-docs \
	python-venv python-lint python-typecheck python-netcheck python-test \
	typescript-deps typescript-build typescript-build-test typescript-lint \
	typescript-typecheck typescript-netcheck typescript-test

# ShellCheck every tracked shell script. git ls-files enumerates them so a
# new script needs no Makefile change (mirrors san-db-ox's own `make
# shellcheck`, .claude/rules/testing.md).
shellcheck:
	shellcheck $(shell git ls-files '*.sh')

# Vulnerability + license scan, same options as san-db-ox's CI job
# (.claude/rules/testing.md): MIT/BSD/Apache-2.0 etc. are allowed, GPL-family
# licenses fail the build; source-level copy-paste detection is left to human
# review. Patch versions of the scanner/action are intentionally not pinned
# elsewhere (freshness of the CVE database matters more than build
# reproducibility here) -- this target just runs the same scan locally.
# --skip-dirs bin excludes the san-db-ox binary that `make fetch` places
# there: it is upstream's own release artifact, scanned by upstream's own
# CI, not source this repository owns.
trivy:
	trivy fs --scanners vuln,license --severity HIGH,CRITICAL --exit-code 1 --skip-dirs bin .

# Downloads the san-db-ox binary this repo's tests run against, into bin/
# (gitignored). Idempotent -- re-running skips the download if bin/san-db-ox
# already matches the expected checksum. Set SAN_DB_OX_BIN to use a locally
# built binary instead (.claude/rules/testing.md).
fetch:
	@scripts/fetch-san-db-ox.sh

# Opt-in verification for docs/usage/*.md's copy-pasteable command examples
# (.claude/rules/testing.md: "接続方法のドキュメントは実測できるものだけを
# 検証対象にする"). Only extracts and runs bash blocks marked with a
# preceding "<!-- doctest -->" comment -- unmarked examples (SSH, socat,
# TLS, Docker) are deliberately left to manual, one-time verification when
# they're written, same line upstream's own tests/docs.sh draws.
test-docs: fetch
	@scripts/test-docs.sh

# Go targets are prefixed go- (.claude/rules/directory-structure.md: language
# directories don't depend on each other, and Python/TypeScript will get
# their own prefixed targets later rather than reusing generic names).

go-build:
	cd go && go build ./...

go-vet:
	cd go && go vet ./...

# Runs both the unit tests (codec/transport, no binary needed) and the
# integration + conformance suite (sandbox package, needs a real san-db-ox
# binary -- hence the `fetch` dependency). Tests that can't find one skip
# individually rather than failing (.claude/rules/testing.md), so `go-test`
# still works if `fetch` is skipped and SAN_DB_OX_BIN is set instead.
# -race is on by default: Client and the direct transport both run a
# background goroutine (response reader, stderr drain) alongside the
# caller, and that concurrency is exactly what a race detector is for.
go-test: fetch
	cd go && go test -race ./...

# Mechanically confirms the codec layer stays pure (.claude/rules/
# architecture.md: "コーデック層はネットワークに依存しないことを検証可能に
# する"), the same idea as san-db-ox's own `make netcheck`. Only codec is
# checked -- transport is exactly the layer allowed to know about I/O.
go-netcheck:
	@bad=$$(cd go && go list -deps ./sandbox/internal/codec | grep -E '^(net|net/http)$$'); \
	if [ -n "$$bad" ]; then \
		echo "sandbox/internal/codec must not depend on net/net-http, but depends on:"; \
		echo "$$bad"; \
		exit 1; \
	fi

# Python targets are prefixed python- (.claude/rules/directory-structure.md,
# same reasoning as go-: language directories don't depend on each other).
# All of them depend on a venv holding the dev extras (pytest/ruff/mypy) --
# runtime dependencies are zero, matching the Go driver.

PYTHON ?= python3
PY_VENV := python/.venv
PY := $(PY_VENV)/bin/python

python-venv: $(PY_VENV)/pyvenv.cfg

$(PY_VENV)/pyvenv.cfg: python/pyproject.toml
	$(PYTHON) -m venv $(PY_VENV)
	$(PY) -m pip install --upgrade pip
	$(PY) -m pip install -e './python[dev]'
	@touch $@

python-lint: python-venv
	$(PY) -m ruff check python
	$(PY) -m ruff format --check python

python-typecheck: python-venv
	$(PY) -m mypy --config-file python/pyproject.toml python/src python/tests python/netcheck.py

# Same idea as go-netcheck, but the naive `python -c "import san_db_ox._codec"`
# doesn't work here: importing the _codec submodule first runs the package's
# __init__.py, which re-exports the client and pulls in subprocess/socket
# transitively. netcheck.py loads _codec.py in isolation (without running
# __init__.py) to sidestep that, then checks sys.modules for anything
# network- or process-related (.claude/rules/architecture.md).
python-netcheck: python-venv
	$(PY) python/netcheck.py

# fetch dependency mirrors go-test: individual tests skip (not fail) when no
# san-db-ox binary is found (.claude/rules/testing.md), so python-test still
# works if fetch is skipped and SAN_DB_OX_BIN is set instead.
python-test: fetch python-venv
	$(PY) -m pytest python/tests

# TypeScript targets are prefixed typescript- (.claude/rules/
# directory-structure.md, same reasoning as go-/python-). Runtime
# dependencies are zero, matching Go and Python; only dev tooling
# (typescript/@biomejs/biome/@types/node) is installed.

TS_DIR := typescript
NPM ?= npm

typescript-deps: $(TS_DIR)/node_modules/.package-lock.json

# npm ci writes node_modules/.package-lock.json itself, which doubles as the
# stamp file -- the same pattern as $(PY_VENV)/pyvenv.cfg above.
$(TS_DIR)/node_modules/.package-lock.json: $(TS_DIR)/package-lock.json $(TS_DIR)/package.json
	cd $(TS_DIR) && $(NPM) ci
	@touch $@

# The published artifact: src -> dist, with .d.ts declarations.
typescript-build: typescript-deps
	cd $(TS_DIR) && ./node_modules/.bin/tsc -p tsconfig.build.json

# src+test -> build-test, run directly with node --test (no declarations).
# Node 22.12's TypeScript type-stripping is still experimental, so tests are
# compiled ahead of time rather than run with --experimental-strip-types.
typescript-build-test: typescript-deps
	cd $(TS_DIR) && ./node_modules/.bin/tsc -p tsconfig.test.json

typescript-lint: typescript-deps
	cd $(TS_DIR) && ./node_modules/.bin/biome ci .

# Compiling test/ (not just src/) is what makes the `// @ts-expect-error`
# assertions in client.test.ts load-bearing: SocketClient must NOT type-check
# as having overwrite/exitCode, and this is the step that would catch it.
typescript-typecheck: typescript-deps
	cd $(TS_DIR) && ./node_modules/.bin/tsc -p tsconfig.test.json --noEmit

# Same idea as go-netcheck/python-netcheck (.claude/rules/architecture.md):
# confirms src/codec.ts stays free of node:net/node:child_process/etc, both
# by import allowlist and by a runtime module-load-graph diff. Depends on
# typescript-build because it inspects the built dist/codec.js.
typescript-netcheck: typescript-build
	cd $(TS_DIR) && node netcheck.mjs

# fetch dependency mirrors go-test/python-test: individual tests skip (not
# fail) when no san-db-ox binary is found (.claude/rules/testing.md).
# The glob (not a bare directory) is required: `node --test <dir>` tries to
# require() the directory itself rather than discovering files under it.
# match.js (test/match.ts's build output) is deliberately not *.test.js, so
# the glob doesn't collect it as a test file -- mirrors why Python named its
# equivalent _conformance_support.py.
typescript-test: fetch typescript-build-test
	cd $(TS_DIR) && node --test 'build-test/test/**/*.test.js'
