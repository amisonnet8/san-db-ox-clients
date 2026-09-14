# PLAN

実装計画・進捗管理。セッションをまたぐたびに「現在地」を確認・更新すること。

## 開発フェーズ

1. **①リポジトリ骨格**: 完了。
2. **②conformance の確立【現在地】**: ケース形式（`conformance/README_ja.md`）を
   実装を通じて確定させ、最初のケース群を作る。
3. **③Go ドライバ: コーデック層＋直結トランスポート**: `go/sandbox/`
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

**フェーズ①完了、フェーズ②着手。**

フェーズ①の成果（`scripts/fetch-san-db-ox.sh`・`.gitignore`・
`README.md`/`README_ja.md`・`.github/workflows/test.yml`・`Makefile` の
`fetch` ターゲット等）は前セッションで完了。`bin/san-db-ox --serve-stdio`
に対する hello 行・`exec`/`query` の実測、`make shellcheck`・`make trivy`
の通過も確認済み。

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
    `io_error` の4種を実機で確認して収録（`read_only` は別ケース）。
  - `read-only-mode.json` — **`--read-only` 下のエラーコードは2段階に
    分かれることを実機で確認して収録。** `exec` 経由の書き込みSQLは
    SQLite 自身の `PRAGMA query_only` 拒否で `sqlite_error` になり、
    `overwrite`/`load` のような **op レベルの書き込みだけ** が
    `read_only` になる。上流仕様書（`docs/spec/san-db-ox_spec_ja.md`
    §2・§7）でも明記されている区別であり、矛盾ではない。ただし
    `.claude/rules/testing.md` の現在の文言はこの区別を書いておらず
    誤解を招くため、後で正確化する（下記「未確認事項」参照）。
  - `introspection.json` — `tables`（アルファベット順）・`schema`
    （作成順、アルファベット順ではない）・`schema` の `table` 指定・
    `dump`/`dump` の `pattern` 指定を実機の出力に合わせて収録。
    `inspect` は **自プロセスが自分の実行ファイルに埋め込んで起動した
    データの状態だけを見る**（`exec`/`load` で変更した実行時の DB
    状態には反応しない）ことを実機で確認し、`source` フィールドは
    起動時の argv[0] に依存し不安定なため `expect` から意図的に外した。
  - `params-large-integer-roundtrip.json` — **`known_failing` 付き。**
    下記のバグ②により、現在の `v0.1.0` に対しては必ず失敗する。
- **`conformance/README_ja.md` に `known_failing` の規約を新設。** 本体
  側のバグで現時点では失敗するとわかっているケースを、「今の壊れた
  挙動」に期待値を合わせて緑にするのではなく、本来あるべき期待値の
  まま記録しておくための仕組み。

**本体 (`san-db-ox` v0.1.0) の実装バグを2件発見し、上流へ Issue 起票
済み。** いずれもプロトコルの設計判断ではなく実装バグであり、
`CLAUDE.md` の方針に従いこのリポジトリ側で独自に回避せず、1つの Issue
にまとめて報告した:
**[amisonnet8/san-db-ox#1](https://github.com/amisonnet8/san-db-ox/issues/1)**。

1. **`exec` で `sql` を省略するとプロセスがクラッシュする。**
   `bad_request` を返すべきところ、`opExec`（`stdio.go:261`）が nil の
   `sql.Result` に対して無条件で `RowsAffected()` を呼び、nil pointer
   dereference で panic（exit code 2、接続断）。`query` を同条件で送っても
   クラッシュせず空クエリとして処理される点から `exec` 固有と判明。
   **conformance ケース化は見送った** — 現在のケース形式は「1ステップ＝
   1つの JSON 応答行」を前提にしており、「このステップでプロセスが
   クラッシュする」を表現する手段がない。修正されて通常の `bad_request`
   応答になった時点で、`error-codes.json` に通常のケースとして追加する。
2. **`params` 経由の64bit整数（2^53超え）が `REAL` にサイレント破損する。**
   `INSERT ... VALUES (?)` に `params:[9223372036854775807]` を渡すと
   `typeof(n)` が `integer` ではなく `real` になり、値も丸められて
   永続的に破損する。同じ値を SQL リテラルで書けば正しく `integer` に
   なるため、`params` の受信デコード処理（`encoding/json` を
   `UseNumber()` なしで使っている可能性が高い）に限定した問題と判明。
   `.claude/rules/protocol.md` が明記する「64bit整数を倍精度浮動小数点へ
   デコードしない」契約に**本体自身が違反**しているケース。
   `params-large-integer-roundtrip.json` として `known_failing` 付きで
   収録済み（上記）。

`params-large-integer-roundtrip.json` の `known_failing` は、実際の
Issue URL（上記 #1）に差し替え済み。

`go/` は空のディレクトリのまま（フェーズ③で解消）。

**次はフェーズ②の残り（`.claude/rules/testing.md` の `--read-only`
記述の正確化、下記「未確認事項」参照）を終えるか、フェーズ③
（Go ドライバ）へ進む。**

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
- **`.claude/rules/testing.md` の `--read-only` の記述を正確化する。**
  現在の文言「書き込み操作を...送った際に `read_only` エラーコードで
  拒否されることを確認する」は、`exec` 経由の SQL レベルの書き込みが
  `sqlite_error` になる点（上記「現在地」参照）に触れておらず、素直に
  読むと誤ったテストを書きうる。次にこのファイルを触る際に「`exec` の
  書き込みSQLは `sqlite_error`、`overwrite`/`load` 等の op レベルの
  書き込みは `read_only`」の区別を明記する。**これはこのリポジトリ自身の
  テスト方針の文言修正であり、上流の仕様変更や CLAUDE.md の「まず本体の
  仕様書を直す」手順は不要**（上流仕様書は既にこの区別を明記しており、
  矛盾は無いため）。

## 保留事項

- **`PostToolUse` フックが未設定。** Go ドライバ着手（フェーズ③）で
  `Makefile` にビルドターゲットが入った時点で提案する（本体と同じ運用）。
- **`go/` が空のため git に現れない。** ドライバ着手（フェーズ③）で解消。
- **PyPI / npm のパッケージ名の予約状況が未確認。** フェーズ⑥（Python）・
  それ以降（TypeScript）で確認する。
- **`exec` の `sql` 省略時クラッシュ（バグ①、
  [amisonnet8/san-db-ox#1](https://github.com/amisonnet8/san-db-ox/issues/1)）
  のケース化を保留。** 現在の conformance ケース形式は「1ステップ＝1つの
  JSON 応答行」を前提にしており、プロセスクラッシュを期待値として表現
  する手段がない。上流で `bad_request` を返すよう修正されたら、
  `error-codes.json` に通常のケースとして追加する。
- **`params-large-integer-roundtrip.json`（バグ②）の `known_failing` を
  外す。** 上流 Issue #1 の修正がリリースされ、追従タグ
  （`.claude/rules/protocol.md`）を更新したタイミングで、このケースが
  green になることを確認して `known_failing` フィールドを削除する。
- **devcontainer.json 反映待ちリスト**: 現行コンテナはリビルドせずに
  開発を進める方針（都度手動でインストール・設定して進め、区切りでまとめて
  `devcontainer.json` へ反映する）。session内で手動インストール・設定を
  行った場合は、忘れずにここへ追記すること。
  - **`gh`（GitHub CLI）はこのセッションで `postCreate.sh` に直接反映
    済み**（`.devcontainer/postCreate.sh` 参照）。次回リビルド時は自動で
    入るため、このリストには残さない。
