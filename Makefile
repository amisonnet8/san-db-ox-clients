.PHONY: shellcheck trivy fetch

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

# No `check`/`build`/`test` target yet: there is no Go driver code to build
# or test until Phase 3 (PLAN.md). Adding an empty-bodied `check` now would
# just be a name with nothing behind it; it gets added once there is
# something for it to gate.
