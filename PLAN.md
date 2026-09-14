# PLAN

実装計画・進捗管理。セッションをまたぐたびに「現在地」を確認・更新すること。

## 開発フェーズ

1. **①リポジトリ骨格**: 完了。
2. **②conformance の確立**: 完了。
3. **③Go ドライバ: コーデック層＋直結トランスポート**: 完了。
4. **④Go ドライバ: ソケットトランスポート（オプション層）**: 完了。
5. **⑤ドキュメント・配布**: 完了。
6. **⑥以降 他言語への展開【現在地】**: Python → TypeScript →（需要を見て
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

**フェーズ①〜⑤完了。フェーズ⑥（他言語への展開、Python から）着手前。**

### フェーズ①・②の要約

- フェーズ①: メタドキュメント一式・`scripts/fetch-san-db-ox.sh`・CI
  （`shellcheck`/`trivy`）・README 骨格を作成。
- フェーズ②: conformance ケース6本（BLOB・REAL/NaN/Inf/大整数・エラー
  コード5種・`--read-only`の2段階拒否・`tables`/`schema`/`dump`/
  `inspect`）を実機で確認して収録し、`known_failing` 規約を新設。この
  過程で本体 `v0.1.0` の実装バグを2件発見し
  ([amisonnet8/san-db-ox#1](https://github.com/amisonnet8/san-db-ox/issues/1)、
  クローズ済み)、`v0.1.1` での修正を独立に再現・確認して追従タグを
  更新した。詳細な経緯（バグの原因・`jq` の精度丸めで一度誤検知した話・
  `testing.md` の `--read-only` 記述修正）は git log 参照
  （コミット `46cf6f7`〜`ce60306`）。

### フェーズ③（Go ドライバ: コーデック層＋直結トランスポート）で行ったこと

`go/sandbox/` パッケージを作成（`naming.md` の module
`github.com/amisonnet8/san-db-ox-clients/go` / package `sandbox`）。
**外部依存ゼロ**（標準ライブラリのみ、`go.sum` 不要）。

- **`internal/codec/`** — I/O を一切知らない純粋な符号化・復号層。
  - `codec.go`: hello 行・リクエスト（1本の flat な struct。本体の
    パニックトレースで見えた `stdioRequest` 同様、op によらず1つの
    struct に全フィールドを `omitempty` で持たせる形）・レスポンス
    envelope（`ok`/`error`）の符号化・復号。
  - `value.go`: BLOB（1要素配列・Base64）・REAL（`88.0` 形式、
    `9e999`/`-9e999` の±Inf判定、NaN→`nil`）・64bit整数（`int64` 直接、
    `float64` を経由しない）の相互変換。**`params` の数値バインドは
    JSON トークンの小数点有無で決まる**ことを実機で確認した
    （`params:[88, 88.0]` → `typeof` が `integer`/`real` に分かれる）。
    これに合わせ、Go の `float64` パラメータは `encoding/json` の既定
    （`88.0`→`"88"`）を上書きして必ず小数点/指数を残す独自フォーマッタを
    実装。**`±Inf`/`NaN` を `params` に送ることは実機でも
    `bad_request`（`value out of range`）になると確認済み**——Go の
    `json.Marshal` 自身も Inf/NaN を拒否するのと同じ制約なので、
    エンコード時に明示的なエラーとして弾く（独自の回避策ではなく、
    双方が既に持つ制約をそのまま反映しただけ）。
  - `response.go`: op ごとのレスポンスフィールドをデコードする関数群。
- **`internal/transport/`** — 直結トランスポート。**起動コマンド
  （`name string, args []string`）は呼び出し元が指定**
  （`architecture.md` の要求どおり、SSH/Docker 専用コンストラクタは
  作っていない）。stderr は常にバックグラウンドで drain（既定
  `io.Discard`、`WithStderr` で差し替え可）。`Close` は段階的シャット
  ダウン（stdin close → 待機 → SIGTERM → 待機 → SIGKILL）を実装。
- **`sandbox` パッケージ（公開API）** — `Open(ctx, name, args, opts...)`
  が hello 行を読み `protocol` 番号を検証。op 名は `naming.md` どおり
  1:1（`Query`/`Exec`/`Snapshot`/`Load`/`Inspect`/`Tables`/`Schema`/
  `Dump`/`Overwrite`/`Close`）。**`Overwrite` は `Client`（直結専用）
  にのみ生えており**、ソケット越しの型が別に生える phase ④以降でも
  そちらには持たせない設計にしてある（`architecture.md` の「直結でしか
  成立しないAPIは型で区別する」への対応。現状は直結しか無いので型の
  分離が自明に成り立っている）。`Client` はゴルーチン1本
  （読み取りループ→チャネル）＋mutexで直列化し、`id` の無いプロトコルの
  「応答は常に順序通り」という前提を、呼び出し側に競合させない形で
  実装している。
- **`make go-netcheck`** — `go list -deps ./sandbox/internal/codec` に
  `net`/`net/http` が含まれないことを機械的に検証（`architecture.md`）。
  現状クリーン。
- **テスト**（`go-test`、`-race` 常時有効）:
  - `internal/codec`: I/O 無しの表引きテスト（値の符号化・復号・
    往復、レスポンス envelope、大きな `last_insert_id` の精度）。
  - `internal/transport`: `cat`/`sh` を fixture にした素の配管テスト
    （stdin→stdout・stderr drain 中の非ブロック・3種の Close 経路
    ——stdin close で即終了・SIGTERM への段階的エスカレーション）。
  - `sandbox`（実機 `bin/san-db-ox` に対する統合テスト、
    `SAN_DB_OX_BIN`/`bin/san-db-ox` が無ければ個別に skip）:
    hello・BLOB/大整数往復・REAL表現・エラーコード・`--read-only`の
    2段階拒否・snapshot→load・inspectの自プロセス限定性・
    tables/schema/dump・Close・**Overwrite**（共有の `bin/san-db-ox`
    を汚さないよう一時ディレクトリへコピーしたバイナリに対して実行）。
  - **`TestConformanceSuite`** — `conformance/cases/*.json` を
    `internal/codec`/`internal/transport` 経由（公開APIを介さず）で
    実行する、Go 版の conformance ランナー。ケース形式どおり
    `expect` は「オブジェクトは部分一致・それ以外は完全一致」を
    再帰的に適用し比較（`match_test.go` に意味論だけを独立検証する
    単体テストも用意）。`known_failing` はケースを実行はするが値の
    不一致は `t.Logf` に留める一方、読み取り失敗やタイムアウトは
    xfail扱いにせず `t.Fatalf` する（「バグの種類が変わった」ことに
    気付けるようにする、という `README_ja.md` の意図どおり）。
    6ケース全て（既存の `params-large-integer-roundtrip.json` の
    `known_failing` 解除後の姿を含め）green。
- **`Makefile`**: `go-build`/`go-vet`/`go-test`（`fetch` 依存）/
  `go-netcheck` を追加。**`go-test` は `-race` を既定で付ける**
  （`Client`・直結トランスポートとも内部でゴルーチンを使うため）。
- **CI**（`.github/workflows/test.yml`）: `go` ジョブを追加
  （`ubuntu-latest` 限定、`actions/setup-go` → `go-build`/`go-vet`/
  `go-netcheck`/`go-test`）。マトリクス化はフェーズ⑤以降で検討。

**`CLAUDE.md` の定めどおり `PostToolUse` フックを提案し、承認を得て
導入済み**（詳細は下記「保留事項」参照）。

### フェーズ④（Go ドライバ: ソケットトランスポート＋直結専用APIの型分離）で行ったこと

`.claude/rules/architecture.md` が要求する2点——ソケットトランスポートの
追加と、直結でしか成立しない API（`overwrite`・終了コード）の型による
分離——を実装した。

- **`internal/transport/conn.go`（新設）**: `Conn`
  インターフェース（`io.Reader`＋`io.Writer`＋`Close(timeout)`）。
  `Direct`（`Reader()`/`Writer()` メソッドを廃止し、`Read`/`Write` を
  自身に直接実装する形へ変更）と、新設の `Socket` の両方がこれを満たす。
- **`internal/transport/socket.go`（新設）**: `Socket`（`net.Conn` の
  ラッパー）。`DialSocket(ctx, network, address)`（`net.Dial` と同じ
  語彙の `"tcp"`/`"unix"`）に加え、呼び出し元が用意した `net.Conn`
  （`crypto/tls.Dial` の戻り値等）をそのまま包める `NewSocket(nc)` を
  用意——TLS 専用のコンストラクタを増やさずに mTLS
  （`connectivity.md`）に対応できる。`Close(timeout)` に段階的
  エスカレーションは無い（`Direct` と非対称——ここには回収すべき
  子プロセスも、読むべき stderr も無いことをコメントで明記）。
- **`sandbox` パッケージの再構成**: `Client` が抱えていた
  hello検証・読み取りループ・`call`の直列化・op別メソッドを、非公開の
  `session` 型（`transport.Conn` を保持）へ移動。公開インターフェース
  `sandbox.Conn`（`Query`/`Exec`/`Snapshot`/`Load`/`Inspect`/`Tables`/
  `Schema`/`Dump`/`Close`）を新設し、`*Client`・`*SocketClient` が
  `*session` を埋め込むことでこれを満たす。**`Overwrite`・`ExitCode` は
  `Conn` に含めず `Client` にのみ実装**——`SocketClient` からは
  コンパイル時に呼べない（`architecture.md` の「呼び出せるが実行時に
  失敗する形にしない」への対応）。
- **`sandbox/socket.go`（新設）**: `OpenSocket(ctx, network, address)`・
  `OpenSocketConn(ctx, nc)`。ドキュメントコメントに `connectivity.md`・
  本体仕様書 §8 の前提（接続ごとに別プロセス・別DB、認証が無い、
  `--read-only`＋TLS クライアント認証を外部公開の前提とする）をそのまま
  記載。
- **テスト**:
  - `internal/transport/socket_test.go`: TCP・UNIX ソケットへの
    `DialSocket`、`NewSocket` で包んだ `net.Pipe`、`Close` が
    ブロック中の `Read` を解除すること。
  - `sandbox/socket_test.go`:
    - `TestSocketClientOverBridge` — Go 製の最小ブリッジ（`net.Listen`
      → 接続ごとに `transport.StartDirect` → `io.Copy` で双方向に
      橋渡し。socat 相当の役割を外部ツール無しで CI 内に持ち込む）。
      TCP・UNIX 両方で hello・`Exec`/`Query` の往復・`Close` を確認。
    - `TestSocketClientViaSocat` — 実機の `socat
      UNIX-LISTEN:…,fork EXEC:"…--serve-stdio"` に対する接続確認。
      `socat` が `PATH` に無ければ `t.Skip`（devcontainer には
      `postCreate.sh` で導入済みなので通常は実行される）。
    - `TestSocketClientOmitsDirectOnlyAPIs` — `*SocketClient` が
      `Overwrite`/`ExitCode` を実装しないことを型アサーションで検証
      （実装が誤って昇格させた場合に検知するガード）。
  - 既存の `sandbox_test.go`（`*Client` 経由）・
    `conformance_test.go`（`internal/transport`/`internal/codec` 直接
    利用）は無変更で green のまま——リファクタが振る舞いを変えていない
    ことの裏付け。
- **`go doc ./sandbox Client` と `SocketClient` を比較し**、
  `Overwrite`/`ExitCode` が `Client` 側にしか現れないことを確認した。
- **手動疎通確認**: `socat UNIX-LISTEN:…,fork EXEC:"bin/san-db-ox
  --read-only --serve-stdio"` に対して、ドライバを介さない素の `socat`
  一行でも hello 行（`protocol:1`）が読めることを確認——CLAUDE.md の
  「ドライバは前提条件ではない」がソケット経路でも成り立つことの実地
  確認。
- **副次的な修正**: `README.md`/`README_ja.md` の「追従しているタグ」
  行が `v0.1.0` のまま `protocol.md`（`v0.1.1`）と食い違っていたのを
  発見し削除。`distribution.md` が README に明記させたいのは
  `protocol` 番号でありタグそのものではないため、タグは
  `protocol.md` への参照に一本化した（`testing.md` のタグ二重管理を
  削除したフェーズ②と同じ判断）。

`Makefile`・CI（`.github/workflows/test.yml`）は無変更——`go-test` が
新規テストをそのまま拾い、`go-netcheck` は引き続き codec 層のみを
検査する（transport 層が `net` に依存するのは `architecture.md` が
想定している姿そのもの）。

### フェーズ⑤（ドキュメント・配布）で行ったこと

- **`.devcontainer/postCreate.sh` に `openssh-server` を恒久導入。**
  `openssh-client` だけでは forced command のようなサーバ側の設定を
  実機検証できないため。sshd は常駐させず、検証のたびに手動起動する
  運用。
- **`docs/usage/connecting_ja.md`（新規）**: `connectivity.md` の判断を
  実際にコピーして使えるコマンド例に肉付け。直結（ローカル・SSH・
  forced command・Docker・Kubernetes）と socat 経由（素のソケット・
  TLS/mTLS・接続元IP制限）の両方を、対応する
  `sandbox.Open`/`OpenSocket`/`OpenSocketConn` の呼び出しと併記。
  - **SSH forced command・socat の TLS/mTLS・IP 制限は、このセッション
    内で実際に動かして確認した**（`testing.md`「検証できない例ほど、
    書いた時点で手元で一度実行して確かめること」）。forced command が
    クライアントの指定を無視すること、`restrict` がポートフォワードを
    `administratively prohibited` として拒否すること（ローカル
    リスナーの有無ではなく実際にデータが通るかで判定）、`verify=1` が
    CA チェーン外の証明書を拒否し正しい証明書は通すこと、
    `bind=127.0.0.1` が実際に listen アドレスを制限すること、`range=`
    が範囲外の送信元を拒否することを、それぞれ実際のコマンドで確認
    済み。
  - **副産物の発見**: TLS サーバ証明書に `subjectAltName` が無いと、
    Go の `crypto/tls`（含む最近の TLS クライアント全般）が
    「legacy Common Name field」として拒否する。ドキュメントの証明書
    生成例は最初から SAN 付きにして、この罠を踏まないようにした。
- **`scripts/test-docs.sh`（新規）**: `docs/usage/*.md` から
  `<!-- doctest -->` が直前に付いた bash ブロックだけを抽出して実行する、
  全言語共有の唯一のスクリプト（本体の `tests/docs.sh` と同じオプトイン
  方式、`testing.md` の要求）。現状は「ローカル直結」の1ブロックのみが
  対象。`Makefile` に `test-docs`（`fetch` 依存）、CI に独立した `docs`
  ジョブ（Go ツールチェーンに依存させない）を追加。
- **`go/README.md`（新規、英語のみ）**: `distribution.md`「protocol 番号を
  各言語の README に明記する」を満たす実体。`go get`
  コマンド（サブディレクトリモジュールのタグ規則どおり、`go/` 接頭辞は
  付けない）・`Open`/`OpenSocket`/`OpenSocketConn` のクイックスタート・
  `connecting_ja.md` へのリンクを含む。pkg.go.dev が表示する実体になる。
- **ルート `README.md`/`README_ja.md`**: 「言語別の状況」表の Go を
  「準備中」から `go/` への参照に更新。「接続方法」節から
  `docs/usage/connecting_ja.md` へリンク（英語版 `connecting.md` は
  CLAUDE.md の日英順序ルールにより今回は作らない）。
- **`go/v0.1.0` をローカルでタグ付け**（注釈付きタグ。**push は
  ユーザーが実施**）。

**この作業でやらなかったこと**: `docs/usage/connecting.md`（英語版、
日英順序ルールにより先送り）、`tcpwrap` の実測（システム全体の
`/etc/hosts.allow`/`deny` を書き換える必要があるため紹介のみに留めた）、
他言語（Python 等）の README 新設。

**次はフェーズ⑥（他言語への展開、Python から）。**

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

- ~~`PostToolUse` フックが未設定~~ → **導入済み。** `go/**/*.go`・
  `go/go.mod`・`go/go.sum` への Edit/Write 後に `make go-build` を
  自動実行するフックを `.claude/settings.json` に追加（実際に発火する
  ことをセンチネルファイルで確認済み）。
- **PyPI / npm のパッケージ名の予約状況が未確認。** フェーズ⑥（Python）・
  それ以降（TypeScript）で確認する。
- **devcontainer.json 反映待ちリスト**: 現行コンテナはリビルドせずに
  開発を進める方針（都度手動でインストール・設定して進め、区切りでまとめて
  `devcontainer.json` へ反映する）。session内で手動インストール・設定を
  行った場合は、忘れずにここへ追記すること。
  - **`gh`（GitHub CLI）はこのセッションで `postCreate.sh` に直接反映
    済み**（`.devcontainer/postCreate.sh` 参照）。次回リビルド時は自動で
    入るため、このリストには残さない。
