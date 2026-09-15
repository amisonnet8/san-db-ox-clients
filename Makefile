.PHONY: shellcheck trivy fetch go-build go-vet go-test go-netcheck test-docs \
	python-venv python-lint python-typecheck python-netcheck python-test

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
