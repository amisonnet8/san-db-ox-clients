# PLAN

実装計画・進捗管理。セッションをまたぐたびに「現在地」を確認・更新すること。

## 開発フェーズ

1. **①リポジトリ骨格**: 完了。
2. **②conformance の確立**: 完了。
3. **③Go ドライバ: コーデック層＋直結トランスポート【現在地】**: `go/sandbox/`
   パッケージ。JSON Lines の符号化・復号、値の表現（BLOB/REAL/64bit整数）、
   子プロセス起動によるトランスポート。**起動コマンドを差し替え可能に
   するところまで**——これが済めば SSH/Docker 経由の接続もこの層のまま
   通る（`.claude/rules/architecture.md`、`.claude/rules/connectivity.md`）。
4. **④Go ドライバ: ソケットトランスポート（オプション層）**: socat 等で
   外付けされた TCP/UNIX ドメインソケットへの接続。`overwrite` 等、
   直結でしか成立しない API を型で分離する。
5. **⑤ドキュメント・配布**: `go/vX.Y.Z` タグでのリリース運用、README
   本文の執筆、`docs/usage/connecting_ja.md`（`.claude/rules/connectivity.md`
   の実例をコマンド付きで肉付けしたもの）。
6. **⑥以降 他言語への展開**: Python → TypeScript →（需要を見て
   Rust / JVM(Java・Kotlin) / Ruby / C#(.NET) / PHP / C）。

## 言語の優先順位の根拠

SanDBox の主なユースケース（CI/CDでの使い捨てテストDB、他プログラムとの
stdio結合、SQL学習サンドボックス）を軸に検討した結果:

- **Go** — 本体自身がGo製。移植コストが最小で、リファレンス実装として
  最初に据える。
- **Python** — CI/CDスクリプト・データ分析・テストコードでの「使い捨て
  DB」需要が最も広い。pytest の fixture としての組み込みが特に刺さる。
- **TypeScript/Node.js** — CI/CDツーリング・Webのテストコードでの需要が
  大きく、`child_process` でのパイプ操作も標準で扱いやすい。

この3言語で「CI/CD と結合テスト」という主要ユースケースの大部分をカバー
できるため、初期スコープとする。4番目以降（Rust/JVM/Ruby/C#/PHP/C）は
需要を見ながら追加を検討する。

## 現在地

**フェーズ①・②完了。フェーズ③（Go ドライバ）着手前。**

フェーズ①の成果（`scripts/fetch-san-db-ox.sh`・`.gitignore`・
`README.md`/`README_ja.md`・`.github/workflows/test.yml`・`Makefile` の
`fetch` ターゲット等）は前セッションで完了。

**フェーズ②（conformance の確立）で行ったこと:**

- 本体 `v0.1.0` バイナリを手動で叩き、`conformance/README_ja.md` の
  ケース形式を実装（＝実際のケースファイル作成）を通じて検証した。
- 追加したケース（既存の `query-roundtrip.json` に加えて5本）:
  - `real-and-integer-representation.json` — REAL の小数点表記
    （`88.0`）、`+Inf`/`-Inf` の生テキスト（`9e999`/`-9e999`、
    `1e308 * 10` 等のオーバーフローで再現）、NaN の `null` 化
    （`Inf - Inf` 等で再現。SQLite の数学関数は NaN を自前で `NULL` に
    変換するため `sqrt(-1)` 等では確認できないと判明）、int64 の
    最大・最小値の往復。
  - `error-codes.json` — `sqlite_error`/`bad_request`/`unsupported_op`/
    `io_error` を実機で確認して収録（`read_only` は別ケース）。
  - `read-only-mode.json` — **`--read-only` 下のエラーコードは2段階に
    分かれることを実機で確認して収録。** `exec` 経由の書き込みSQLは
    SQLite 自身の `PRAGMA query_only` 拒否で `sqlite_error` になり、
    `overwrite`/`load`/`snapshot` のような **op レベルの書き込みだけ** が
    `read_only` になる。上流仕様書（`docs/spec/san-db-ox_spec_ja.md`
    §2・§7）でも明記されている区別であり、矛盾ではない
    （`.claude/rules/testing.md` 側の文言も後日この区別を明記する形に
    書き換え済み——下記参照）。
  - `introspection.json` — `tables`（アルファベット順）・`schema`
    （作成順、アルファベット順ではない）・`schema` の `table` 指定・
    `dump`/`dump` の `pattern` 指定を実機の出力に合わせて収録。
    `inspect` は **自プロセスが自分の実行ファイルに埋め込んで起動した
    データの状態だけを見る**（`exec`/`load` で変更した実行時の DB
    状態には反応しない）ことを実機で確認し、`source` フィールドは
    起動時の argv[0] に依存し不安定なため `expect` から意図的に外した。
  - `params-large-integer-roundtrip.json` — 当初 `known_failing` 付きで
    収録（下記バグ②のため v0.1.0 では必ず失敗）。**v0.1.1 で修正確認後、
    `known_failing` を削除済み**（下記）。
- **`conformance/README_ja.md` に `known_failing` の規約を新設。** 本体
  側のバグで現時点では失敗するとわかっているケースを、「今の壊れた
  挙動」に期待値を合わせて緑にするのではなく、本来あるべき期待値の
  まま記録しておくための仕組み。

**本体 (`san-db-ox`) の実装バグを2件発見 → 上流へ Issue 起票 → `v0.1.1`
で修正確認まで完了した。** いずれもプロトコルの設計判断ではなく実装
バグであり、`CLAUDE.md` の方針に従いこのリポジトリ側で独自に回避せず、
1つの Issue にまとめて報告した:
**[amisonnet8/san-db-ox#1](https://github.com/amisonnet8/san-db-ox/issues/1)**
（クローズ済み）。

1. **`exec`/`query` で `sql` を省略するとプロセスがクラッシュする
   （`exec` のみ）。** `v0.1.0` では `opExec`（`stdio.go:261`）が nil の
   `sql.Result` に対して無条件で `RowsAffected()` を呼び、nil pointer
   dereference で panic（exit code 2、接続断）していた。
2. **`params` 経由の64bit整数（2^53超え）が `REAL` にサイレント破損する。**
   `v0.1.0` では `INSERT ... VALUES (?)` に
   `params:[9223372036854775807]` を渡すと `typeof(n)` が `integer`
   ではなく `real` になり、値も丸められて永続的に破損していた。
   `.claude/rules/protocol.md` が明記する「64bit整数を倍精度浮動小数点へ
   デコードしない」契約に本体自身が違反していたケース。

**`v0.1.1`（`compare/v0.1.0...v0.1.1`）で両方とも修正され、本リポジトリ
側で実機に対して独立に再現・確認した。**

- バグ①: `exec`/`query` とも `sql` 省略時に
  `{"code":"bad_request","message":"missing required field: sql"}` を
  返すようになり、クラッシュしない。`error-codes.json` に両方とも通常の
  ケースとして追加した。
- バグ②: `params:[9223372036854775807]` が `typeof(n) = "integer"` の
  まま正しく往復することを確認。**確認には注意が必要だった** ——
  最初 `jq -c` でケースファイルを読み直して再生したところ失敗したが、
  これは本体の regression ではなく **`jq` 自身が
  `9223372036854775807` を `9223372036854776000` に丸めていた**
  ことが原因（`conformance/README_ja.md` が警告する「ケースファイル
  自体のパースにも64bit精度が要る」の実例）。Go の `json.Number`
  （`UseNumber()`）でケースファイルを読み直す小さなプログラムで再検証し、
  正しく修正されていることを確認した。`params-large-integer-roundtrip.json`
  の `known_failing` は削除済み。
- Issue のクローズコメントには「本リポジトリの conformance が green に
  なるまでは open のままにする」とあったが、実際には既に close 済み
  だった（矛盾には気付いたが、コメント内容を鵜呑みにせず上記のとおり
  独立に再現・確認する方針で進めた）。

**追従タグを `.claude/rules/protocol.md`・`scripts/fetch-san-db-ox.sh`
とも `v0.1.1` に更新済み。** `protocol` 番号は `1` のまま変わらず
（バグ修正のみで、プロトコル設計自体の変更ではないため、CLAUDE.md の
「まず本体の仕様書を直す」手順は不要と判断した）。

**フェーズ②の残作業も完了した。** `.claude/rules/testing.md` の
`--read-only` 節を、実機で確認した2段階の区別
（`overwrite`/`load`/`snapshot` は `read_only`、`exec` の書き込みSQLは
`sqlite_error`）を明記する形に書き換えた。あわせて `snapshot` op も
`--read-only` 下で実際に `read_only` で拒否され、ファイルが作られない
ことを実機で確認し、`read-only-mode.json` にステップを追加した。
`testing.md` 冒頭の追従タグの直書き（`(現在 v0.1.0)`）も、`protocol.md`
との二重管理を避けるため削除した。

`go/` は空のディレクトリのまま（フェーズ③で解消）。

**次はフェーズ③（Go ドライバ: コーデック層＋直結トランスポート）。**

## GitHub リポジトリ設定（決定事項、リポジトリ作成時に設定）

リポジトリ作成時に GitHub の Description・Topics へそのまま設定すること。

**Description:**
> Client libraries for SanDBox — talk to it over stdio from any language.

**Topics:**
`database` `sqlite` `go` `golang` `client-library` `database-driver`
`stdio` `json-lines` `subprocess` `database-testing` `integration-testing`
`sdk`

本体（san-db-ox）の Topics（`database` `rdbms` `sqlite` `go` `golang`
`in-memory-database` `embedded-database` `single-binary` `sql-sandbox`
`portable` `zero-config` `cli`）のうち、`database`・`sqlite`・`go`・`golang`
のみ意図的に揃えた。`single-binary`・`sql-sandbox`・`embedded-database`・
`in-memory-database`・`zero-config`・`cli`・`rdbms` は本体そのものの性質
（単一バイナリ・組み込みDB・CLI）であり、薄いラッパーである clients
リポジトリには当てはまらないため除外した。

## 未確認事項（実装前に決める・確かめる）

- devcontainer に `sshd` が入っていないため、SSH 直結の実地確認
  （`.claude/rules/connectivity.md`）は `openssh-client` の範囲でのみ
  行った。ローカルの sshd を使った end-to-end 確認は、実際にドライバの
  SSH 経路を実装する際に別途行うこと。

## 保留事項

- **`PostToolUse` フックが未設定。** Go ドライバ着手（フェーズ③）で
  `Makefile` にビルドターゲットが入った時点で提案する（本体と同じ運用）。
- **`go/` が空のため git に現れない。** ドライバ着手（フェーズ③）で解消。
- **PyPI / npm のパッケージ名の予約状況が未確認。** フェーズ⑥（Python）・
  それ以降（TypeScript）で確認する。
- **devcontainer.json 反映待ちリスト**: 現行コンテナはリビルドせずに
  開発を進める方針（都度手動でインストール・設定して進め、区切りでまとめて
  `devcontainer.json` へ反映する）。session内で手動インストール・設定を
  行った場合は、忘れずにここへ追記すること。
  - **`gh`（GitHub CLI）はこのセッションで `postCreate.sh` に直接反映
    済み**（`.devcontainer/postCreate.sh` 参照）。次回リビルド時は自動で
    入るため、このリストには残さない。
