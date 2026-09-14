# conformance (language-agnostic protocol conformance tests)

*[日本語](README_ja.md) | **English***

A mechanism so checks like "the hello line can be read," "a query
round-trips," and "BLOB/REAL/large-integer representations are correct"
don't have to be rewritten per language. Request/expected-response pairs
live in one place as JSON, and each language's tests read and run them.

Same idea as upstream (san-db-ox)'s own `tests/docs.sh` -- verification
against the real binary -- spread across multiple language drivers. See
`.claude/rules/protocol.md` (the upstream tag and protocol version this
repository tracks) for the detailed spec.

## Case format

Each JSON file under `cases/` is one case.

```json
{
  "name": "query-roundtrip",
  "description": "A BLOB value round-trips through params and back unchanged.",
  "args": [],
  "steps": [
    { "request": {"op": "exec", "sql": "CREATE TABLE t(b BLOB)"},
      "expect": {"ok": true} },
    { "request": {"op": "exec", "sql": "INSERT INTO t VALUES (?)", "params": [["aGk="]]},
      "expect": {"ok": true, "rows_affected": 1} },
    { "request": {"op": "query", "sql": "SELECT b FROM t"},
      "expect": {"ok": true, "columns": ["b"], "rows": [[["aGk="]]]} }
  ]
}
```

- **`args`** -- the server's (upstream binary's) startup options (e.g.
  `["--read-only"]`). One process is started per case, and `steps` run
  against it in order. The process is not restarted between steps, so a
  case can rely on transaction state or `--read-only` persisting across
  steps.
- **`steps[].request`** -- the request to send, written verbatim as
  JSON. `id` may be omitted (per `.claude/rules/protocol.md`, responses
  always come back in request order, so there's nothing to correlate).
- **`steps[].expect`** -- **partial match.** Only the keys present here
  are checked; keys missing from or differing in the response beyond
  those aren't compared. This lets existing cases keep passing as
  response fields are added in the future.
- **Error cases compare only `code`, never `message`.** Upstream's error
  message wording can change; `code` (`sqlite_error`, etc.) is the
  stable contract (see `.claude/rules/protocol.md`).

  ```json
  { "request": {"op": "query", "sql": "SELECT * FROM nope"},
    "expect": {"ok": false, "error": {"code": "sqlite_error"}} }
  ```

## `expect_raw` (cases where the literal text representation is itself the spec)

Comparing REAL's `88.0` (not `88`) or the NaN/±Inf literals (`null`/
`9e999`/`-9e999`) only after JSON-parsing the value doesn't actually
verify anything -- `88.0` and `88` parse to the same number. For cases
like these, add `expect_raw` alongside `expect` and match it as a
substring against **the raw response line**.

```json
{ "request": {"op": "query", "sql": "SELECT 88.0"},
  "expect": {"ok": true},
  "expect_raw": ["88.0"] }
```

`expect_raw` is an array of one or more strings that must each appear in
the response line.

## `known_failing` (cases that currently fail due to an upstream bug)

**This repository does not work around protocol-related gaps or bugs it
finds on its own** (see `CLAUDE.md`). Even so, while an upstream
implementation bug is waiting on a fix, the reproduction steps for that
bug are still worth recording as a conformance case -- otherwise a fix
can go unnoticed, or land and quietly get left un-cleaned-up. That's
what the `known_failing` marker is for.

```json
{
  "name": "params-large-integer-roundtrip",
  "description": "A 64-bit integer beyond 2^53 passed via params round-trips unchanged.",
  "known_failing": "https://github.com/amisonnet8/san-db-ox/issues/NN -- large integers in params get corrupted into REAL",
  "args": [],
  "steps": [ ... ]
}
```

- **The value is a string** (not a boolean). Always include a link to
  the upstream issue and a one-line description of what's broken, so the
  case file alone explains why it's expected to fail.
- **A case marked `known_failing` still keeps the correct expected
  values** (`expect`/`expect_raw`) it should have once fixed -- don't
  bend the expectations to match the currently-broken behavior. The
  point is that the case turns green on its own the moment the bug is
  fixed, with no further edits needed.
- **Each language's runner still executes a `known_failing` case, but
  doesn't count its failure against the suite as a whole** (xfail-style).
  Running it regardless means a change in failure mode -- a response
  that's no longer even parseable, or a timeout -- still gets noticed.
- **Once a fix is confirmed, remove the `known_failing` field.** Forget
  this and the case's failure keeps getting silently swallowed even
  after the fix; do this cleanup alongside updating the tracked upstream
  tag (`.claude/rules/protocol.md`) once the fix ships.

## Implementation note (parsing the case files themselves)

**Reading the case files can itself need 64-bit integer precision.** If
a case includes an integer beyond 2^53, a runner using a naive JSON
parser (one that decodes numbers as `double`) will already have
corrupted the value **just by reading the case file** -- before the case
is ever run. A runner needs to read case files with the same rigor it
uses for upstream's responses (e.g. `json.Number` in Go). This is a
pitfall specific to this shared-case-file approach and easy to miss when
reusing another language's test framework.

## Runner implementation

**No shared runner implementation is provided.** Each language loads and
runs cases on top of its own test framework. Two reasons:

- Running on each language's own test framework (Go's `testing`,
  Python's `pytest`, etc.) gives a better failure display and debugging
  experience.
- Sharing a runner implementation would make every driver depend on
  whatever language that runner is written in, conflicting with the
  principle that language directories don't depend on each other (see
  `.claude/rules/directory-structure.md`).

Each language's runner implements the same procedure: start the upstream
binary (obtained per `.claude/rules/testing.md`) with the startup options
given in `args`, send and receive `steps` in order, and match each
response against `expect`/`expect_raw`.
