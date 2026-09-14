.PHONY: shellcheck trivy

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
trivy:
	trivy fs --scanners vuln,license --severity HIGH,CRITICAL --exit-code 1 .

# No `check`/`build`/`test` target yet: there is no Go driver code to build
# or test until Phase 3 (PLAN.md). Adding an empty-bodied `check` now would
# just be a name with nothing behind it; it gets added once there is
# something for it to gate.
