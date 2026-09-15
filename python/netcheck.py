#!/usr/bin/env python3
"""Confirms san_db_ox._codec stays free of networking/subprocess imports
(.claude/rules/architecture.md: "コーデック層はネットワークに依存しない
ことを検証可能にする"), the same idea as san-db-ox's own `make netcheck`
and this repo's `make go-netcheck`.

Importing san_db_ox._codec the naive way (`import san_db_ox._codec`) would
first run san_db_ox/__init__.py, which re-exports the client and pulls in
subprocess/socket transitively -- so this script loads _codec.py in
isolation via a synthetic package instead, sidestepping __init__.py
entirely.
"""

from __future__ import annotations

import ast
import importlib
import pathlib
import sys
import types

SRC = pathlib.Path(__file__).parent / "src" / "san_db_ox"
CODEC_FILE = SRC / "_codec.py"

# Deliberately small and explicit: any new top-level import in _codec.py
# must be a conscious decision (and an update to this set), not something
# that slips in unnoticed during a refactor.
ALLOWED_ROOTS = {
    "__future__",
    "base64",
    "binascii",
    "collections",
    "dataclasses",
    "json",
    "math",
    "typing",
}

# Checked independently of ALLOWED_ROOTS above: even if _codec.py's own
# import statements look fine, something they import could transitively
# pull one of these in.
FORBIDDEN_ROOTS = {
    "socket",
    "_socket",
    "ssl",
    "_ssl",
    "subprocess",
    "_posixsubprocess",
    "http",
    "urllib",
    "asyncio",
    "selectors",
    "multiprocessing",
}


def check_static_imports() -> list[str]:
    tree = ast.parse(CODEC_FILE.read_text(encoding="utf-8"), filename=str(CODEC_FILE))
    bad: list[str] = []
    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            for alias in node.names:
                root = alias.name.split(".")[0]
                if root not in ALLOWED_ROOTS:
                    bad.append(f"import {alias.name} (line {node.lineno})")
        elif isinstance(node, ast.ImportFrom):
            if node.module is None:  # relative "from . import x"
                continue
            root = node.module.split(".")[0]
            if root not in ALLOWED_ROOTS:
                bad.append(f"from {node.module} import ... (line {node.lineno})")
    return bad


def check_dynamic_imports() -> list[str]:
    # Only modules newly loaded by importing _codec count -- this script's
    # own top-level imports (e.g. pathlib, which drags in urllib.parse for
    # as_uri()) are already in sys.modules beforehand and aren't _codec's
    # doing.
    before = set(sys.modules)

    pkg_name = "_san_db_ox_netcheck_probe"
    pkg = types.ModuleType(pkg_name)
    pkg.__path__ = [str(SRC)]
    sys.modules[pkg_name] = pkg
    importlib.import_module(f"{pkg_name}._codec")

    newly_loaded = set(sys.modules) - before
    return sorted(
        name for name in newly_loaded if name.split(".")[0] in FORBIDDEN_ROOTS
    )


def main() -> int:
    problems: list[str] = []

    bad_imports = check_static_imports()
    if bad_imports:
        problems.append(
            "_codec.py imports modules outside the allowed set "
            f"({sorted(ALLOWED_ROOTS)}):\n  " + "\n  ".join(bad_imports)
        )

    bad_modules = check_dynamic_imports()
    if bad_modules:
        problems.append(
            "loading san_db_ox._codec in isolation pulled in forbidden "
            f"modules: {bad_modules}"
        )

    if problems:
        print("python-netcheck FAILED:\n\n" + "\n\n".join(problems), file=sys.stderr)
        return 1
    print("python-netcheck: san_db_ox._codec is free of networking/subprocess imports")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
