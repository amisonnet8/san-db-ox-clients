# conformance（言語非依存のプロトコル適合テスト）

「hello行が読める」「query が往復する」「BLOB/REAL/巨大整数の表現が正しい」
といった検証を、言語ごとに書き直さないための仕組み。リクエストと期待
レスポンスのペアを JSON で1箇所に置き、各言語のテストがそれを読んで
実行する。

本体（san-db-ox）の `tests/docs.sh` と同じ思想——実バイナリに対する実測
検証——を、多言語のドライバへ横展開したもの。詳細な仕様は
`.claude/rules/protocol.md`（このリポジトリが追従している本体のタグ・
プロトコルバージョン）を参照。

## ケース形式

`cases/` 配下の各 JSON ファイルが1ケース。

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

- **`args`** — サーバ（本体バイナリ）の起動オプション（例:
  `["--read-only"]`）。1ケースにつきプロセスを1つ起動し、`steps` を順に
  実行する。ステップ間でプロセスは再起動しない（トランザクションや
  `--read-only` の状態がステップをまたいで持続することを前提にできる）。
- **`steps[].request`** — 送信するリクエストをそのまま JSON で書く。
  `id` は省略してよい（`.claude/rules/protocol.md` のとおり、応答は
  リクエストと同じ順序で返るため対応付けの必要が無い）。
- **`steps[].expect`** — **部分一致。** 指定したキーだけを照合し、応答に
  無い/異なるキーがあっても、指定していないキーの値までは問わない。応答に
  将来フィールドが増えても既存ケースを壊さないための設計。
- **エラーケースは `code` だけを照合し、`message` は照合しない。** 本体の
  エラーメッセージの文言は変わりうるが、`code`（`sqlite_error` 等）は
  安定した契約である（`.claude/rules/protocol.md` 参照）。

  ```json
  { "request": {"op": "query", "sql": "SELECT * FROM nope"},
    "expect": {"ok": false, "error": {"code": "sqlite_error"}} }
  ```

## `expect_raw`（テキスト表現そのものが仕様である項目)

REAL の `88.0`（`88` ではない）や NaN/±Inf のリテラル（`null`/`9e999`/
`-9e999`）は、JSON にパースした後の値だけを比較すると検証にならない
（パース後は `88.0` も `88` も同じ数値になってしまうため）。こうした
ケースでは `expect` に加えて `expect_raw` を持たせ、**応答の生の1行**に
対する部分文字列一致で照合する。

```json
{ "request": {"op": "query", "sql": "SELECT 88.0"},
  "expect": {"ok": true},
  "expect_raw": ["88.0"] }
```

`expect_raw` は「この文字列が応答行に含まれていること」を1つ以上並べた
配列とする。

## 実装上の注意（ケースファイル自体のパース）

**ケースファイルの読み込みにも 64bit 整数の精度が要る場合がある。** 2^53
を超える整数を含むケースを書いた場合、ランナー側の JSON パーサが素朴な
実装（`double` へデコードするもの）だと、**ケースファイルを読んだ時点で
既に値が壊れる**。ランナーは、本体の応答を読む際と同じ厳密さ（Go なら
`json.Number` 等）でケースファイル自体も読むこと。これは他の言語の
テストフレームワークを流用する際にも当てはまる conformance 方式に固有の
罠であり、見落としやすい。

## ランナーの実装

**共通のランナー実装は作らない。** 各言語が自分のテストフレームワークの
上でケースを読み込み、実行する。理由は2つ:

- 言語ごとのテストフレームワーク（Go の `testing`、Python の `pytest` 等）
  に載せた方が、失敗時の表示・デバッグ体験が良い。
- ランナー自体を共通化すると、そのランナーを書く言語への依存が全ドライバに
  波及する（`.claude/rules/directory-structure.md` の「言語ディレクトリ
  同士は互いに依存しない」という原則と衝突する）。

各言語のランナーは、`args` で指定された起動オプションを付けて本体バイナリ
（`.claude/rules/testing.md` の入手方法で取得したもの）を起動し、`steps`
を順に送受信して `expect`/`expect_raw` と照合する、という同じ手続きを
それぞれの言語で実装する。
