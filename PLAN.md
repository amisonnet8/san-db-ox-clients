# PLAN

実装計画・進捗管理。セッションをまたぐたびに「現在地」を確認・更新すること。

## 開発フェーズ

1. **①リポジトリ骨格【現在地】**: メタドキュメント一式（CLAUDE.md、
   `.claude/rules/`、devcontainer、Makefile の骨格）は用意済み。残るのは
   `.github/workflows/test.yml`・`README.md`/`README_ja.md`・`.gitignore`・
   `scripts/fetch-san-db-ox.sh` を作り、CI が green になるところまで。
2. **②conformance の確立**: ケース形式（`conformance/README_ja.md`）を
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

**フェーズ①着手。** メタドキュメント一式（`CLAUDE.md`、`.claude/rules/`
7本、`.devcontainer/`、`Makefile`、`LICENSE`、`.gitattributes`、
`conformance/` の枠組み）を作成した。`.claude/settings.json` と
`.vscode/settings.json` はこのセッション以前に配置済み。

`go/` は空のディレクトリとして作成した（git は空ディレクトリを追跡しない
ため、コミット対象としては現れない。フェーズ③で実体が入ると解消する）。

まだ手を付けていないもの: `.github/workflows/`・`README.md`/`README_ja.md`・
`.gitignore`・`scripts/fetch-san-db-ox.sh`。ドライバの実装コードは意図的に
書いていない——最初に何を置くかは別途方針を決めてから着手する。

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

- `conformance/` のケース形式の細部（`expect_raw` の具体的な照合方法、
  ケースファイル自体を arbitrary precision で読む実装方法）は、フェーズ②
  で最初のケースを実装しながら確定させる。
- devcontainer に `sshd` が入っていないため、SSH 直結の実地確認
  （`.claude/rules/connectivity.md`）は `openssh-client` の範囲でのみ
  行った。ローカルの sshd を使った end-to-end 確認は、実際にドライバの
  SSH 経路を実装する際に別途行うこと。

## 保留事項

- **`PostToolUse` フックが未設定。** Go ドライバ着手（フェーズ③）で
  `Makefile` にビルドターゲットが入った時点で提案する（本体と同じ運用）。
- **`go/` が空のため git に現れない。** ドライバ着手（フェーズ③）で解消。
- **conformance ケース形式の細部が未確定。** フェーズ②で確定する。
- **PyPI / npm のパッケージ名の予約状況が未確認。** フェーズ⑥（Python）・
  それ以降（TypeScript）で確認する。
- **devcontainer.json 反映待ちリスト**: 現行コンテナはリビルドせずに
  開発を進める方針（都度手動でインストール・設定して進め、区切りでまとめて
  `devcontainer.json` へ反映する）。session内で手動インストール・設定を
  行った場合は、忘れずにここへ追記すること。
  - （なし）
