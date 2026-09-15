# PLAN

実装計画・進捗管理。セッションをまたぐたびに「現在地」を確認・更新すること。

## 開発フェーズ

1. **①リポジトリ骨格**: 完了。
2. **②conformance の確立**: 完了。
3. **③Go ドライバ: コーデック層＋直結トランスポート**: 完了。
4. **④Go ドライバ: ソケットトランスポート（オプション層）**: 完了。
5. **⑤ドキュメント・配布**: 完了。
6. **⑥ Python ドライバ**: 完了。
7. **⑦ TypeScript ドライバ**: 完了。
8. **⑧ Rust ドライバ**: 完了。
9. **⑨ Java ドライバ【現在地】**: 実装完了。Maven Central への publish は
   ③配布・公開（ユーザー作業）待ち。
10. **⑩以降 他言語への展開**: 需要を見て Kotlin / Ruby / C#(.NET) / PHP /
    C から検討する。

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
需要を見ながら追加を検討する。Rust はフェーズ⑧、Java はフェーズ⑨として、
いずれもユーザー指定で先に着手した（2026-09-15）。

## 現在地

**フェーズ①〜⑨完了。Go・Python・TypeScript・Rust・Java の5言語すべてが
実装・テスト・ドキュメント整備済み。フェーズ⑩（6番目の言語）着手前——
需要を見て検討する。**

### 公開ページ一覧

各言語のパッケージレジストリ上の公開ページ（実機で200応答を確認済み、
2026-09-15）。

| 言語 | 公開ページ |
| :--- | :--- |
| Go | https://pkg.go.dev/github.com/amisonnet8/san-db-ox-clients/go/sandbox |
| Python | https://pypi.org/project/san-db-ox-client/ |
| TypeScript | https://www.npmjs.com/package/@amisonnet8/san-db-ox-client |
| Rust | https://crates.io/crates/san-db-ox-client |

TypeScript の公開（2026-09-15）: `npm publish --access public` で
`@amisonnet8/san-db-ox-client@0.1.0` を公開。npm の Granular Access
Token 発行時、**「Bypass two-factor authentication」チェックボックス
（デフォルトでオフ）を入れ忘れると `403 Forbidden` になる**点で詰まった
（教訓として記録）。公開直後、レジストリの一部 CDN エッジで数分間
`404` が返るキャッシュ遅延も観測されたが、実体の公開自体は成功していた
（`npm publish` の応答・確認メール・別エッジからの直接確認で確定）。

Rust の公開（2026-09-15）: `cargo login` でトークンを設定した後
`cargo publish`（事前に `cargo publish --dry-run` でパッケージング内容
（28ファイル、165.8KiB/圧縮45.8KiB）とビルド・検証を確認済み）で
`san-db-ox-client@0.1.0` を公開。npm・PyPI のような2FA・スコープ関連の
詰まりどころは無く、一度で成功した。crates.io API
（`https://crates.io/api/v1/crates/san-db-ox-client`）で実機確認済み。

### 開発の進め方（フェーズ⑥で確定した方針）

新しい言語ドライバへの展開は「①計画→②実装（一気に実施）→③配布・公開
（ユーザー作業を含むことがある）」の3段階で進める（ユーザー指定、
2026-09-15）。フェーズ⑦・⑧以降もこれに従う。

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

### フェーズ⑥（Python ドライバ）で行ったこと

Go ドライバをリファレンス実装として、`python/` 配下に Python ドライバを
新規作成した。**外部依存ゼロ**（標準ライブラリのみ）。

- **確定した方針**（ユーザー承認、2026-09-15）: 同期 API のみ（asyncio
  版は作らない）／import 名 `san_db_ox`／最低 Python
  3.10／標準 `venv` + `pip`（uv は使わない）／開発依存は
  pytest・ruff・mypy のみ。
- **PyPI 配布名は当初案 `san-db-ox` から `san-db-ox-client` に変更した
  （公開時に判明、後述）。**
- **`python/src/san_db_ox/`** — `naming.md` の表に追記した構成
  （`_codec.py`・`_transport.py`・`_client.py`・`__init__.py`・
  `py.typed`）。
  - **`_codec.py`**: I/O を一切知らない純粋層。Go の codec 層と1対1対応
    するが、Python 固有の単純化がいくつかある——Python の `json.loads`
    は数値トークンの小数点有無で int/float を自然に分けるため、Go が
    自前で持つ数値トークン解釈（`decodeNumber`）は不要。有限 float の
    `repr()` は必ず `.`/`e` を含むため、Go の「小数点を必ず残す独自
    フォーマッタ」も不要（ただし自明でないためコードにコメントを残した）。
    一方 Python 固有で追加が要った検証: **`bool` を params から明示的に
    拒否**（`isinstance(True, int)` が真であるため、int チェックより前に
    弾く）、**int の int64 範囲外を明示的に拒否**（Python の int は
    多倍長で自動では弾かれない）。例外階層は `SanDBoxError`（基底）・
    `ResponseError`（`.code`/`.message`、コード定数5種）・
    `ProtocolError`・`SanDBoxTimeoutError`。
  - **`_transport.py`**: `DirectTransport`（`subprocess.Popen`）・
    `SocketTransport`（`socket`）。**行読み取りスレッドをここに置く**
    設計にした（Go は session 層に置く）——ブロック中の read を解除する
    方法がトランスポートごとに違う（直結はプロセスを終了させる、ソケットは
    `shutdown()`）ため。`queue.Queue(maxsize=1)` で Go の `chan` の
    バックプレッシャを再現し、終端（EOF/エラー）は sticky な属性として
    保持（`queue.Queue` に `close()` が無いため）。段階的 Close は
    `stdin.close()` → `wait` → `terminate()` → `wait` → `kill()`。
    stderr drain は既定 `DEVNULL`、シンク指定時のみ `PIPE` + drain
    スレッド。**`Popen` のパイプはバッファ付きのため `write()` の直後に
    必ず `flush()`** が必要（Go の生パイプには無い制約）。
  - **`_client.py`**: `_Session`（hello 検証・直列化・call/response）を
    `Client`（直結）・`SocketClient`（ソケット）が保持。**`overwrite`・
    `exit_code` は `Client` にのみ実装**（`SocketClient` には存在しない。
    mypy の属性チェックとテストの両方で保証）。`Connection`
    （`typing.Protocol`）が Go の `Conn` インターフェースに対応。
    タイムアウトは `_UNSET` センチネルで「未指定＝接続時の既定値」と
    「明示 `None`＝無期限」を区別。`with ... as c:` に対応（`__enter__`
    は `TypeVar` で自己型を返し、サブクラスの属性が mypy から見えるように
    した）。
- **`python/netcheck.py`（`make python-netcheck`）**: `go-netcheck` の
  Python 版。**素朴に `import san_db_ox._codec` すると親の `__init__.py`
  が先に走り `_client` 経由で `subprocess`/`socket` を引き込んでしまう**
  ため、`__path__` を持つ合成パッケージ経由で `_codec.py` だけを
  `__init__.py` を実行せずに単独ロードし、`sys.modules` の新規追加分
  （読み込み前後の差分）に禁止モジュールが無いことを検証。静的側は
  `ast` で `_codec.py` 自身の import をホワイトリストと照合。
- **テスト**（`python/tests/`、pytest）: Go のテストファイル群と1対1で
  対応。`test_codec.py`（バイナリ不要）・`test_transport.py`（`cat`/`sh`
  fixture、stderr 洪水・段階的 Close・SIGTERM エスカレーション）・
  `test_client.py`（実バイナリ、hello・値の往復・エラーコード・
  `--read-only` の2段階拒否・snapshot/load・inspect・close の冪等性・
  overwrite・呼び出しタイムアウト時のセッション破棄・`SocketClient` に
  `overwrite`/`exit_code` が無いこと）・`test_socket.py`（`SocketTransport`
  の dial/wrap/close、in-process ブリッジ経由の `SocketClient`、実機
  socat）・`_conformance_support.py` + `test_match.py` + `test_conformance.py`
  （後述）。
  - **conformance ランナー**: Go は `json.RawMessage` + `json.Number` で
    数値のリテラルトークンを保持するが、Python は `json.loads` の
    `parse_int`/`parse_float` フックが**数値トークンの元テキストを
    そのまま**渡してくるため、これを `Num`（`NamedTuple`、`str` の
    サブクラスにはしない——`Num("88") == "88"` を許すと文字列の期待値と
    誤って一致してしまうため）に包んで同じ効果を得た。`dumps_literal()`
    で `Num` を含む構造をそのままの文字列で再直列化し、Go の
    `json.RawMessage` 転送と同じ忠実さでリクエストを送信。`match_json()`
    が `matchJSON`/`match_test.go` の意味論（オブジェクトは部分一致、
    それ以外は完全一致、数値はリテラルトークン比較）を再現。
    `known_failing` は `pytest.mark.xfail` を使わず（xfail はトランス
    ポート層の失敗まで飲み込んでしまうため）、値の不一致だけを
    握りつぶし、read/write エラー・タイムアウト・JSON パース失敗は
    `known_failing` の有無に関係なく即 fail する Go と同じ設計。6ケース
    全て `known_failing` 無しで green。
  - **実装中に踏んだ罠（2件）**: (1) `_LineReader` を使わない socat 代替
    ブリッジ実装で `BufferedReader.read(n)` を使うと `n` バイト貯まるまで
    ブロックし、短い hello 行が届かず接続がタイムアウトする——`read1(n)`
    （単発の生読み取りで即座に返す）に直す必要があった。(2) `netcheck.py`
    自身が `pathlib` を import すると `urllib.parse` が連鎖的に
    `sys.modules` に載り、素朴な「読み込み後の `sys.modules` 全体」検査が
    誤検知する——読み込み前後の**差分**だけを見るように修正。
  - 全101テスト green（`make fetch && make python-test`）、
    `make python-lint`・`make python-typecheck`（strict）・
    `make python-netcheck` も green。
- **実機検証（このセッション内で実施、`docs/usage/connecting*.md` に
  記載）**: ローカル直結・SSH forced command・socat 経由の TLS/mTLS
  （正しいクライアント証明書での接続成功、CA チェーン外の証明書が
  `SSL_accept(): certificate verify failed` でサーバ側から拒否される
  ことの両方）を、一時的な sshd・socat・自己署名証明書一式を用意して
  Python ドライバに対して実際に確認した。Go 側の既存の検証記録
  （`connecting.md`）に Python の確認結果を追記する形にした。
- **`Makefile`**: `python-venv`/`python-lint`/`python-typecheck`/
  `python-netcheck`/`python-test`（`fetch` 依存）を追加。`go-` と対称の
  `python-` プレフィックス。
- **CI（`.github/workflows/test.yml`）**: `python` ジョブを追加
  （`ubuntu-latest`、`actions/setup-python`、`python-version` は
  `["3.10", "3.13"]` のマトリクス——`go-version-file` に相当する
  「宣言した下限を実行時に守れているか」の保証が Python 側に無いため、
  下限と最新の両方を実行して確かめる）。
- **`.devcontainer/devcontainer.json`**: 公式 feature
  `ghcr.io/devcontainers/features/python:1`（`version: "3.11"`）を追加。
  VS Code 拡張に `ms-python.python`・`charliermarsh.ruff`、
  `python.defaultInterpreterPath` を設定。**このセッションでは
  `sudo apt-get install python3 python3-venv` により手動導入した状態で
  進めた**（`gh` の前例と同様、devcontainer.json は今回のうちに反映し、
  次回リビルド時に自動で入るようにした）。
- **利用者向けドキュメント**（`.claude/`・`CLAUDE.md`・`PLAN.md` を
  一切参照しない境界を維持）: `python/README.md`（`go/README.md` が
  雛形）、ルート `README.md`/`README_ja.md` の言語表、
  `docs/usage/connecting.md`/`connecting_ja.md` の6箇所（ローカル・SSH
  リモートコマンド・Docker・Kubernetes・素のソケット・TLS/mTLS）に
  Python のコード例を Go の例と併記。
- **内部ルールの更新**: `naming.md`・`distribution.md` に Python の行を
  追記（PyPI 名の予約状況を確認済みにしたため「フェーズ⑥で確定」の
  記述を確定値に置き換え）。

**この作業でやらなかったこと**: `python/` の日本語 README（`go/README.md`
と同じく英語のみ、日英順序ルールの対象外——ルート README とは異なり言語
ディレクトリ配下の README は元から英語のみの方針）、
`devcontainer-lock.json` の再生成（`devcontainer` CLI が無く手動更新は
ハッシュを捏造することになるため、次回実際にコンテナをリビルドする
タイミングで自動生成させる）。

### PyPI への公開（フェーズ⑥の続き、ユーザー作業）で判明した2点

公開作業はユーザー自身が `python -m build` / `twine upload` で実施。
その過程で、実装時には想定していなかった問題が2つ見つかり、両方とも
このリポジトリ側の設定で解消した。

- **`hatchling>=1.32` は `Metadata-Version: 2.5` を出力し、PyPI/TestPyPI
  （Warehouse）がこれを `400 Bad Request` で拒否する。** バイナリサーチで
  特定（`1.31.0` は `2.4` を出力しアップロード可、`1.32.0` から `2.5` に
  変わる）。`python/pyproject.toml` の `[build-system] requires` を
  `hatchling<1.32` に固定して解消。Warehouse が `2.5` に対応した時点で
  この上限は見直すこと。
- **配布名 `san-db-ox` は PyPI に登録できない。** PyPI のタイポスクワッ
  ティング対策（記号を除去して既存プロジェクト名と比較する）により、
  `san-db-ox` は記号を除くと `sandbox` と完全一致し、これは既存の別
  プロジェクトのため `"The name 'san-db-ox' is too similar to an
  existing project"` として拒否される（TestPyPI・本番PyPI 両方で再現）。
  **配布名を `san-db-ox-client` に変更して解消**（`sandboxclient` は
  衝突しない）。**import 名 `san_db_ox` は変更していない**——PyPI の
  配布名と import 名は独立しているため、`pip install san-db-ox-client`
  → `import san_db_ox` という形になる。`python/pyproject.toml`・
  `python/README.md`・`naming.md` を修正済み。
  - **教訓**（今後 PyPI に配布名を登録する全言語で踏む可能性がある）:
    候補名の**完全一致**が PyPI 未登録であることの確認だけでは不十分。
    **記号を除去した形**（ハイフン・アンダースコア・ピリオドを取り除いた
    小文字列）が既存プロジェクトと衝突しないかも確認すること。
    `naming.md` の製品名3段階表記で `sandbox` を意図的に避けている
    まさにその理由（一般名詞との衝突）が、記号除去後の比較という形で
    そのまま踏み抜かれた形。TypeScript（npm）でも同様の配布名確認時に
    この観点を忘れないこと。

**公開結果**: https://pypi.org/project/san-db-ox-client/ （`0.1.0`、
`san_db_ox_client-0.1.0-py3-none-any.whl` / `.tar.gz`）。
`pip install san-db-ox-client` で導入し `import san_db_ox` で使う。

### フェーズ⑦（TypeScript ドライバ）で行ったこと

計画を一度 `~/.claude` 配下に立てた状態で devcontainer をリビルドし
（Node 22 feature を追加するため）計画が消失する事故が発生（`/workspaces`
以外はリビルドで全消去される——教訓としてメモリに保存済み）。計画を
`typescript-drifting-pnueli.md` として立て直し、Go/Python ドライバの構造
（`_codec.py`/`_transport.py`/`_client.py`、`go/sandbox/`）を参照しながら
一気に実装した。

**確定した方針**（ユーザー確定、再検討しない）:
- npm パッケージ名 `@amisonnet8/san-db-ox-client`（スコープ付き）。PyPI で
  `san-db-ox`→`sandbox` 正規化により踏んだ名前衝突の問題圏に、スコープを
  切ることで最初から入らない。
- ESM のみ（`"type":"module"`）。Node >=22.12 は CJS 側から `require()` で
  ESM を読めるため、CJS 利用者も困らない。
- 値の対応: SQLite INTEGER↔`bigint` / REAL↔`number` / TEXT↔`string` /
  BLOB↔`Uint8Array` / NULL↔`null`。双方向で曖昧さがない設計。
  `[42n]` は INTEGER、`[42]` は REAL を束縛する。
- Lint/Format は Biome（devcontainer に `biomejs.biome` 拡張が既に入って
  いたため。ESLint/Prettier は使わない）。
- テストランナーは `node:test` + `node:assert/strict`（依存ゼロの方針。
  Node 22.12 では TypeScript の型剥がしが実験的フラグ付きのため、
  `tsc` でコンパイルしてから JS として実行する）。

**数値の忠実性**（プロトコル契約「64bit整数を倍精度へデコードしない」の
実装の核）: `JSON.parse` の reviver が受け取る第3引数 `context.source`
（トークンの原文）を使い、`.`/`e` を含めば `Number()`（REAL）、含まなければ
`BigInt()`（INTEGER）に振り分ける。これが Go の `json.Number`・Python の
`parse_int`/`parse_float` フックの TypeScript での対応物。conformance
ランナー側は `JSON.rawJSON()` でトークンをそのまま保持する別ポリシーを
使い、`JSON.stringify` がそのボックスをそのまま再出力してくれるため、
Python の `dumps_literal` 相当の自前シリアライザが不要になった（実機で
`9223372036854775807`・`88.0`・`9e999` の byte-for-byte 往復を確認済み）。
`JSON.rawJSON`/`JSON.isRawJSON`/reviver 第3引数は TypeScript 5.9.3 の
`lib.es5.d.ts` に型定義が無かったため、`src/json-raw.d.ts` に ambient 宣言
を追加した。

**トランスポート**: 行分割は `node:readline` を使わず自前実装（単独 `\r`
での誤分割・上限バイト数の契約が表現できない・ストリームのフロー制御を
奪う、という3点で不適合だったため）。`LineReader` は Python の
`queue.Queue(maxsize=1)` を直訳した1スロットのバックプレッシャ。
`DirectTransport` の段階的 close（stdin→SIGTERM→SIGKILL）は Node の
`exitCode`/`signalCode` 分離を吸収して Python の `Popen.poll()` 規約
（シグナル死は `-15` 等）に揃えた。`SocketTransport` は `connectTcp`/
`connectUnix` に加え、`connectSocket(stream: Duplex)` が TLS の継ぎ目
（ドライバは `node:tls` を一切 import しない）。

**呼び出しの直列化とタイムアウト**: Node にロックが無いため `Session`
内の promise チェーンで代用。タイムアウト・abort 時は1つの pending read
を中断する手段が無いため、Python と同じく「二度と使えない接続」を代償に
バックグラウンドで `transport.close()` を開始する。Python との違いは
その teardown promise を保持し、後続の `close()` が必ず await する点——
これにより `node --test` が生きた子プロセスを抱えたまま終了することが
無くなる。

**netcheck**: `netcheck.mjs` が (a) `typescript` パッケージの
`ts.preProcessFile` で `src/codec.ts` の import を静的検査（相対 import
以外は全て失格）、(b) ビルド済み `dist/codec.js` を子プロセスで import し
`process.moduleLoadList` の差分を検査、(c) 同じ検査を `node:net` 自身に
対して行う陽性対照（`process.moduleLoadList` が未文書なので、検知機構
自体が壊れていないかを確認する）の3段構え。意図的に `node:child_process`
を import させて両方の検査が確実に落ちることも確認済み。

**実装中に踏んだ罠（2件）**:
1. **CPU張り付き事故**: `transport.test.ts` の `MAX_LINE_BYTES` 超過検証
   テストに `sh -c "yes | tr -d '\n'"`（自然終了しない無限出力パイプ
   ライン）を使ったところ、`LineReader` 側の実バグ（terminal 状態に
   なった後もストリームを読み続け `#pending` が際限なく伸びる）と重なり、
   バックグラウンド実行中のテストコマンドが実質無限ループになって
   ユーザーが環境ごと強制再起動する事態になった。`#onData`/`#terminate`
   に「terminal 後は何もしない」ガードを追加し、テスト側も `head -c` で
   有限出力に変更。教訓をメモリに保存し、以後バックグラウンドコマンドは
   `timeout` を付けて実行する運用に変更した。
2. `SocketClient` に `#socket` フィールドを型レベルの区別のためだけに
   持たせようとしたが、Biome の `noUnusedPrivateClassMembers` が正しく
   検出。`overwrite`/`exitCode` を宣言していないこと自体で型安全性は
   十分に達成されているため、未使用フィールドは削除した（過剰な設計を
   避ける）。

**テスト**: `codec.test.ts`(47)・`match.test.ts`(10)・`transport.test.ts`
(9)・`client.test.ts`(17)・`socket.test.ts`(8)・`conformance.test.ts`(7、
6ケース＋非空検証) の計98件、`make typescript-test` で全緑
（`node --test 'build-test/test/**/*.test.js'` ——`node --test <dir>` は
ディレクトリを `require()` しようとして失敗するため、glob 必須）。
conformance の6ケースは `known_failing` 無しで全通過（v0.1.1 で解消済みの
ため）。`node --test` は `--test-force-exit` 無しで自力終了することを
確認済み（生きた子プロセスを残していないことの担保）。

**実機検証**: SSH forced command 経由の `connect("ssh", [...])`（任意
コマンド送信の無視・forced `--read-only` の実効性の両方）と、socat
`OPENSSL-LISTEN` 越しの mTLS（`tls.connect` → `connectSocket`、正しい
クライアント証明書での成功、CAチェーン外証明書の `SSL_accept():
certificate verify failed` 拒否）の両方を、Go・Python と同じ手順で
TypeScript ドライバに対しても実施し確認した。

**Makefile / CI**: `typescript-deps`（`npm ci`、`node_modules/
.package-lock.json` をスタンプファイルにした Python の `pyvenv.cfg` と
同型パターン）/`typescript-build`/`typescript-build-test`/
`typescript-lint`/`typescript-typecheck`/`typescript-netcheck`/
`typescript-test` を追加。CI は `typescript` ジョブを追加し、Node
`["22.12","24"]` をマトリクス化（`engines` は npm が強制しないため、
宣言した下限を実際に実行して初めて保証される——Python の
`requires-python` マトリクスと同じ論拠）。

**利用者向けドキュメント**（`.claude/`・`CLAUDE.md`・`PLAN.md` を一切
参照しない）: ルート `README.md`/`README_ja.md` の言語表を `available`/
`利用可能` に更新。`typescript/README.md` を `python/README.md` と同じ
節構成で新規作成（英語のみ。TS固有の注意として INTEGER↔`bigint` と
`JSON.stringify` が bigint で例外を投げる点を明記）。
`docs/usage/connecting.md`/`connecting_ja.md` の6箇所（Local・SSH
リモートコマンド・Docker・Kubernetes・素のソケット・TLS/mTLS）に
TypeScript の例を追加（計12編集）し、SSH forced command・TLS/mTLS の
「実際に検証した内容」にも TypeScript の確認結果を追記した。

**内部ルールの更新**: `.claude/rules/naming.md`・`distribution.md` に
TypeScript の行を追加（タグ規則 `typescript/vX.Y.Z`）。

**この作業でやらなかったこと**: npm への実際の publish（③配布・公開は
スコープ外、ユーザー作業として後続——PyPI と同じ進め方）。

`.claude/settings.json` の `PostToolUse` フックへの TypeScript 用分岐
追加は提案してユーザー承認を得て適用済み（`*typescript/*.ts`・
`*typescript/package.json` 編集後に `make typescript-typecheck` を自動
実行。Go の `go-build`・Python の lint+typecheck と同じパターン。
シミュレーションで実際に発火することと、無関係なファイルでは発火しない
ことの両方を確認済み）。

### フェーズ⑧（Rust ドライバ）で行ったこと

ユーザー指定で4番目の言語に Rust を採用（「次はRust対応をお願いします」、
2026-09-15）。Go/Python/TypeScript と決定的に違う点が1つ——**標準
ライブラリに JSON が無い**。既存3言語が `encoding/json`+`json.Number`・
`json`+`parse_int`・`JSON.parse` reviver+`JSON.rawJSON` で無償に得ていた
「64bit整数と REAL の数値トークン忠実性」を自前設計する必要があった。

**確定した方針**（ユーザー確定、再検討しない）:
- crates.io パッケージ名 `san-db-ox-client`（未登録を実機確認済み。
  `serde` を陽性対照にして404が本物であることまで確認）。
- **JSON・Base64 とも自前実装、実行時依存ゼロ**（serde/serde_json/base64
  いずれも使わない）。既存3言語の「Runtime dependencies: none」を保つ。
- **同期のみ**（`std::process`/`std::net`/`std::os::unix::net` のみ。
  tokio 等の非同期ランタイムは持ち込まない）。
- `edition = "2024"`、MSRV `1.85`。CI マトリクスは `["1.85","stable"]`。

**数値の忠実性**: 自前 JSON パーサが数値トークンを `NumberToken(String)`
として原文のまま保持する設計にしたことで、他3言語が標準ライブラリの
フックで得ていた性質を最初から持つ。`.`/`e`/`E` を含めば REAL(`f64`)、
含まなければ INTEGER(`i64`)、範囲外は `Error::Protocol`
（Go の `ParseInt` 失敗に相当）。送出側の `real_token()` は Rust の
`{:?}`（Debug）が常に `.` か指数を持つ性質を使い、Go の `formatReal`・
TypeScript の `realToken` に相当するガードを実装（`88.0`→`"88.0"`、
`-0.0`→`"-0.0"`、TypeScript が要した `Object.is(v,-0)` 特別扱いは不要）。
Rust の `f64::from_str` は範囲外を `±inf` へ飽和するだけで Go のような
`ErrRange` 同時返却が無いため、その簡略化を単体テストで固定した。

**トランスポート**: `std` にはパイプの読み取りタイムアウトが無いため、
直結（`DirectTransport`）は読み取りスレッド＋`sync_channel(1)` 方式
（Python の `Queue(maxsize=1)`・TypeScript の1スロット pause/resume の
直訳）。ソケット（`SocketTransport`）はスレッド無しのインライン framer
方式に変更——決め手は TLS の継ぎ目で、同期 Rust の TLS ストリーム
（`rustls::StreamOwned` 等）は split も `try_clone` もできないため、
スレッド化すると継ぎ目が `Arc<Mutex<S>>`（デッドロックする）か
`from_halves`（TLS で使えず継ぎ目の意味が消える）のどちらかに潰れる。
`std` には SIGTERM を送る手段が無い（`Child::kill()` は SIGKILL のみ）
ため、`kill(2)` シンボルを自前宣言する10行の `unsafe`（クレート内で
ここ1箇所、`#![deny(unsafe_code)]`＋codec は `#![forbid(unsafe_code)]`
で他を封じる）で段階的 close（stdin close→SIGTERM→SIGKILL）を実装。
`Child::wait()` にもタイムアウトが無いため `try_wait()` のポーリングで
束縛した（待機スレッド方式だと pid 再利用と `kill` が競合しうるため）。

**呼び出しの直列化**: 他3言語がロック／promiseチェーンで実現していた
直列化が、Rust では全メソッドを `&mut self` にするだけでコンパイル時
保証になった（`&mut Client` を2つ同時に持てない）。副作用として
`Client`/`SocketClient` は `mpsc::Receiver` を含むため自動的に
`Send + !Sync` になり、他3言語が散文で書いていた「並行利用は安全でない」
をコンパイラが強制する形になった。

**netcheck**: Rust には std のモジュール単位の依存グラフが無い（std は
常にリンクされる）ため、`tests/netcheck.rs` に自前の静的スキャナを実装
——コメント除去後に `use` パスの展開（グループ化された import の
評価漏れを防ぐ）＋全文の禁止プレフィックス検索の二重チェック、
そして TypeScript の `node:net` 陽性対照と同じ発想で
`src/transport/direct.rs` に対する陽性対照（禁止パスが1件以上検出
されなければ失格）を実装した。`#![no_std]` ワークスペースメンバで
コンパイラに強制させる代案も検討したが、ルールが求めるのは「ネット
ワーク／プロセス禁止」であって「std 禁止」ではないこと、codec の全行が
`alloc::` 系の綴りを強いられる恒久的な摩擦になることから見送った。

**直結専用APIの型分離**: `overwrite()`/`exit_code()` を `Client` にだけ
置き `SocketClient` には存在させない設計自体は Go と同じだが、それが
壊れていないことの検証に TypeScript の `// @ts-expect-error` に相当する
`compile_fail` doctest を採用（rustdoc に「この例は意図的にコンパイル
できません」と**表示される**ため不可視な `@ts-expect-error` より優れる）。
CI マトリクス（1.85/stable）間で診断文言が割れるため `trybuild` は
不採用。`compile_fail` 自体が無関係なタイプミスでも通ってしまう弱点への
対策として、禁止要素1つだけが違う「通るはずの」対照ブロックを対で
用意した（`SocketClient::overwrite`・`SocketClient::exit_code`・
`Value::from(true)` の3対）。`cargo test --all-targets` は doctest を
黙って skip するため、`make rust-test` は `cargo test --doc` を
別ステップで必ず実行する。

**実装中に踏んだ罠（2件）**:
1. **`Session::close()` が close 応答を読み捨てていたことによる
   デッドロック**: `close` op を送るだけで応答を読まずに
   `transport.close()` を呼んでいたところ、直結トランスポートの読み取り
   スレッドが「誰も受け取らない応答行」を1スロットの `sync_channel` へ
   `send()` しようとして永久ブロックした。さらに `ThreadedReader::join()`
   の実装が、期限付きの busy-wait がタイムアウトした後も無条件で
   `h.join()`（無期限）してしまうバグと重なり、テストが数十秒単位で
   ハングした。修正は2箇所——(a) `Session::close()` で close 応答を
   実際に読み捨てる（これによりスレッドが正常に EOF まで進んで自力で
   終了する）、(b) `ThreadedReader::join()` は期限超過後は `h.join()`
   せず諦める（`Receiver` が直後に drop されることで送信側が
   disconnect エラーで解放される——Python の daemon スレッド＋
   `Thread.join(timeout=)` が同じ状況で達成している挙動と同じ）。
   strace でのタイムスタンプ付きトレースにより「syscall では無く
   ユーザ空間の待機で止まっている」ことを特定し、フェーズ⑦の教訓
   （バックグラウンドコマンドは `timeout` で囲む）に従って原因調査した。
2. base64 の自前デコードで、TypeScript が `Buffer.from(s,"base64")` の
   寛容さ（不正文字を黙って捨てる）を実機確認していたのと同じ罠を
   踏まないよう、最初から厳格デコード（アルファベット外の文字・パディング
   位置・長さの4の倍数チェック）で実装し、それぞれ個別にテストで固定した
   （実装段階の罠であり手戻りは無し）。

**テスト**: ライブラリ内テスト85件（`codec`/`transport`/`tests::match_`/
`tests::conformance`）＋結合テスト `tests/client.rs`(17)・
`tests/socket.rs`(4)・`tests/netcheck.rs`(5)＋doctest 8件
（`cargo test --doc`）の計119件、`make rust-test` で stable・1.85 の
両ツールチェーンで全緑。conformance の6ケースは `known_failing` 無しで
全通過。netcheck は意図的に `std::process::Command` を codec へ混入させて
失格すること、コメント除去を無効化して陽性対照が失格することの両方を
実機で確認済み。

**実機検証**: SSH forced command 経由の `connect("ssh", &[...])`（hello・
`query` 往復、forced `--read-only` の実効性）と、socat `OPENSSL-LISTEN`
越しの mTLS（`rustls::StreamOwned` → `connect_socket`、正しいクライアント
証明書での成功、CAチェーン外証明書の `SSL_accept(): certificate verify
failed` 拒否）を、Go・Python・TypeScript と同じ手順で実施。加えて Rust
固有の主張——TLS でラップする前の `TcpStream` に設定した read timeout が
実際にドライバの読み取りを束縛すること——も実測した（200ms のタイムアウト
設定で、わざと遅いクエリに対し約255msで `Error::Timeout` が返ることを
確認）。なお openssl のデフォルトの `x509 -req` 署名はクライアント証明書を
X.509 v1 にするため rustls（aws-lc-rs バックエンド）が
`UnsupportedCertVersion` で拒否する現象に遭遇——検証用の証明書生成にのみ
`-extfile` で `basicConstraints`/`extendedKeyUsage` を追加して解消した
（本ドキュメントが共有する CA/サーバ/クライアント証明書生成コマンド自体は
変更していない。他3言語では顕在化しなかった Rust 側 TLS スタックの
厳密さによるものと見られる）。

**Makefile / CI**: `rust-build`/`rust-fmt`/`rust-lint`（clippy）/
`rust-netcheck`/`rust-test` を追加（実行時・開発時依存ともゼロなので
`python-venv`/`typescript-deps` のようなスタンプファイル式インストール
手順は不要）。CI は `rust` ジョブを追加し `["1.85","stable"]` を
マトリクス化（`rust-version`(MSRV) は cargo が強制しないため、宣言した
下限を実際に実行して初めて保証される）。ツールチェーン導入は
サードパーティアクションを使わず `rustup` 2行（devcontainer.json の
「公式 features のみ」と同じ線引き）。

**利用者向けドキュメント**（`.claude/`・`CLAUDE.md`・`PLAN.md` を一切
参照しない）: ルート `README.md`/`README_ja.md` の言語表に Rust 行を
追加。`rust/README.md` を `python/README.md` と同じ節構成で新規作成
（英語のみ。Rust 固有の注意として `bool` が param にならずコンパイル
エラーになる点、`Drop` が接続を閉じるため `with`/`defer` 相当が不要な点
を明記）。`docs/usage/connecting.md`/`connecting_ja.md` の6箇所
（Local・SSH リモートコマンド・Docker・Kubernetes・素のソケット・
TLS/mTLS）に Rust の例を追加（計12編集）し、TLS 節には Rust の std に
TLS が無いことを踏まえた専用の説明（`connect_socket` が TLS の継ぎ目に
なる旨）を追加、SSH forced command・TLS/mTLS の「実際に検証した内容」
にも Rust の確認結果を追記した。

**内部ルールの更新**: `.claude/rules/naming.md`・`distribution.md` に
Rust の行を追加（タグ規則 `rust/vX.Y.Z`）。`.devcontainer/devcontainer.json`
に公式 `ghcr.io/devcontainers/features/rust:1`（`profile: default`、
clippy/rustfmt 込み）と `rust-lang.rust-analyzer` 拡張・
`rust-analyzer.linkedProjects` 設定を追加（下記「devcontainer.json
反映待ちリスト」参照）。

`.claude/settings.json` の `PostToolUse` フックへの Rust 用分岐追加は
提案してユーザー承認を得て適用済み（`*rust/*.rs`・`*rust/Cargo.toml`
編集後に `make rust-build` を自動実行。Go の `go-build` と同じパターン。
シミュレーションで実際に発火することと、無関係なファイルでは発火しない
ことの両方を確認済み）。

crates.io への publish（③配布・公開、ユーザー作業）も完了した
（`cargo login` → `cargo publish`。詳細は「公開ページ一覧」直下参照）。
これでフェーズ⑧（Rustドライバ）が完全に完了し、Go・Python・TypeScript・
Rust の4言語すべてが実装・公開済みになった。

### フェーズ⑨（Java ドライバ）で行ったこと

ユーザー指定で5番目の言語に Java を採用（「次はJava対応に進んでください」、
2026-09-15）。Java は Rust と同じ「標準ライブラリに JSON が無い」言語
だが、Rust が苦しんだ残り3つの問題（Base64・SIGTERM・std の TLS 不在）は
Java では消える——`java.util.Base64` が標準にある、`Process.destroy()` が
SIGTERM そのもの、`javax.net.ssl.SSLSocket` が `java.net.Socket` の
subclass として標準にある。フェーズ⑧で書いた JSON コーデックの設計
（`NumberToken` が原文トークンを保持する方式）を移植し、トランスポート層は
むしろ簡素になった。

**確定した方針**（ユーザー確定、再検討しない）:
- 最低 Java 版 17・CI マトリクス `["17","25"]`（既存の「下限＋現行」
  パターンと同形）。
- ビルドツールは **Maven**（wrapper 無し、`mvn` は PATH から）。
- 値の表現は **`Object` ベース**（`null`/`Long`/`Double`/`String`/
  `byte[]`）。Go の `[]any`、Python/TS の union と同じで4言語中3言語と
  揃う。
- **JSON は自前実装、実行時依存ゼロ**（フェーズ⑧の設計を移植）。Base64は
  `java.util.Base64` を使うが、**パディング欠落を黙って受理する**という
  標準デコーダの寛容さ（`Base64.getDecoder().decode("QQ")` が成功する）を
  前置の長さチェックで閉じ、他4言語と同じ厳格さに揃えた
  （TypeScript の `Buffer.from` で踏んだのと同種の罠）。
- テスト依存は **JUnit 5 のみ**（`test` スコープ。Rust の「dev-dependencies
  もゼロ」は Java では達成不能だが、`test` スコープは推移しないので
  「Runtime dependencies: none」は保たれる）。

**Java 固有に発見した最大の設計課題: Java の可視性には Rust の
`pub(crate)` に相当する粒度が無い。** package-private はパッケージ単位で
閉じており、階層に関わらず「1つ上のパッケージへも見える」といった仕組みが
無い。conformance/match テストは codec の `Json` 木と transport の
`DirectTransport` の両方に生のアクセスが必要（型付き API では作れない
不正リクエストを直接送るため）だが、Java ではこの2つを跨いで見せる中間の
可視性が作れない。**解決策として `io.github.amisonnet8.sandbox.internal.codec`
／`internal.transport` パッケージを新設し、内部専用であることをパッケージ名
自体で示しつつ public にした**（OkHttp の `okhttp3.internal.*` と同じ
慣習）。計画時点では単に `codec`/`transport` という名前を想定していたが、
実装中にこの制約に気づいて `internal.` を挟む設計に変更した——ユーザーに
確認を要する製品判断ではなく実装詳細の変更のため、その場で決めて進めた。

**数値の忠実性**: フェーズ⑧の設計をそのまま移植。`NumberToken(String raw)`
が線上のトークンをそのまま保持し、`.`/`e`/`E` を含めば REAL(`Double`)、
含まなければ INTEGER(`Long`)。送出側の `realToken(double)` は
`Double.toString` が常に `.` か指数を含む性質を使い、Go の `formatReal`・
Rust の `real_token` に相当するガードを実装（末尾のガードは保険）。
`Double.parseDouble("9e999")` が `Infinity` に飽和するだけで Go のような
`ErrRange` 同時返却が無い点も Rust と同じく簡略化された。

**トランスポート**: 直結（`DirectTransport`）は読み取りスレッド＋
`ArrayBlockingQueue(1)` 方式（Rust の `sync_channel(1)` の直訳）。
`Thread.join(long)` は Python と同じ本物の期限付き join のため、Rust の
`ThreadedReader::join` が踏んだ「期限超過後に無期限 join へ落ちる」バグ
自体は Java では起きない。ソケット（`SocketTransport`）はスレッド無しの
インライン framer 方式で、**Java は Rust より TLS の継ぎ目が強い**——
`javax.net.ssl.SSLSocket extends java.net.Socket` なので、`connectSocket`
がそのまま TLS を受け、**read timeout をドライバ自身が呼び出しのたびに
`Socket.setSoTimeout` で設定できる**（Rust が「強制できない唯一の契約」と
書いていた穴がここには無い）。読み取りタイムアウトの機構が経路ごとに
異なる点は Java 固有の複雑さで、直結は読み取りスレッド、TCP/TLS は
`Socket.setSoTimeout`、UNIX ドメインソケットは `SO_TIMEOUT` が存在しない
ため非ブロッキング `SocketChannel` ＋ `Selector` という3種類の機構を
`TimedByteSource` インターフェース1つに集約した。

Rust で苦労した3点（SIGTERM・`wait()` の期限・stderr 排出）は Java では
消える——`Process.destroy()` が Unix では SIGTERM そのもの、
`Process.waitFor(long, TimeUnit)` が標準で期限付き、
`ProcessBuilder.redirectError(Redirect.DISCARD)` でスレッドすら要らない。
ただし**残った1点が Java 固有の罠**: シグナルで死んだ子プロセスの終了
コードの規約が5言語で異なり、Python/Rust は `-signum`、Go は `-1`、
**Java（OpenJDK の POSIX 実装）は `128 + signum`**（SIGTERM なら `143`、
SIGKILL なら `137`。シェルの `$?` と同じ慣習）として `Process.exitValue()`
が返す。これは `DirectTransportTest` の SIGTERM/SIGKILL エスカレーション
テスト（`exitCode()==143`・`==137`）で実機確認済み。Java の規約のまま
返すと決め（`-signum` への変換はしない）、同じ JVM 上の
`Process.exitValue()` と食い違わないようにした。

**例外設計**: `internal.codec.CodecException`・`internal.transport.
TransportException` はいずれも非チェック例外とし、公開境界（`Session`・
`SanDbOx`）でのみ公開のチェック例外（`SanDbOxException` とその
サブクラス `ResponseException`/`ProtocolViolationException`/
`ReadTimeoutException`/`ConnectionClosedException`）へ変換する。
**実装中に見つけた罠**: `Session.query()` 等で
`call(new RequestBuilder("query").field("sql", sql).params(params))` の
ように `RequestBuilder` を**呼び出し側の引数式として**組み立てていたため、
`.params(params)` が投げる `CodecException`（非有限 REAL・`Boolean` 拒否
等）が `call()` メソッド本体に入る前——つまり `call()` 内の try/catch を
一切経由せずに送出され、`usage.ClientTest` の実機テストで生の内部例外が
外に漏れていることが発覚した。修正は `call()` の引数を
`Supplier<RequestBuilder>` に変え、リクエスト構築そのものを `call()`
自身の try ブロック内で評価するようにしたこと。加えて `SanDbOx.connect*`
の4メソッドも、接続確立時点（`DirectTransport.start`/`SocketTransport.
connectTcp` 等）で投げる `TransportException` を素通りさせていたバグが
あり、`Session.translate()` を package-private に開放して同様に修正した。
——「内部層は非チェック例外、公開境界だけが変換する」という設計は、
**変換ロジックを呼び出す場所を型システムが強制してくれない**ため、
境界を1箇所でも通し忘れると即座に漏れる、という教訓（Rust なら
`Result` の伝播漏れはコンパイルエラーになるところ、Java の非チェック例外
は黙って素通りする）。

**netcheck**: `jdeps`（JDK 同梱の依存解析ツール）を
`java.util.spi.ToolProvider.findFirst("jdeps")` でサブプロセス無しに
呼び出し、`internal.codec` パッケージのコンパイル済みクラスを解析。
Rust のテキスト走査と違い**本物の依存グラフ**が取れる。禁止プレフィックス
（`java.net.`/`java.nio.channels.`/`java.nio.file.`/`java.lang.Process`/
`java.lang.Thread`/`java.io.`/`java.util.concurrent.`）との照合と、
`internal.transport` への陽性対照（1件以上の検出を要求）を実装。
`jdeps` が見つからない場合は skip ではなく fail にした。

**直結専用APIの型分離**: `overwrite()`/`exitCode()` を `SanDbOxClient`
にだけ置き `SocketClient` には存在させない設計自体は他4言語と同じだが、
それが壊れていないことの検証に **`javax.tools.JavaCompiler`**（JDK
同梱・追加依存ゼロ）でテスト時にスニペットをコンパイルし失敗を assert する
`CompileFailTest` を実装（Rust の `compile_fail` doctest・TypeScript の
`// @ts-expect-error` の Java 版）。Rust と異なり `Object` ベースの値
表現を採ったため、`Boolean` param の拒否はコンパイル時には縛れず実行時
エラーになる——Go と同じ制約であり、テストとREADMEの両方に明記した。

**実装中に踏んだ罠（上記2件に加えてもう1件）**: `usage/SocketTest`（UNIX
ドメインソケット経由で本体プロセスとのブリッジを構築する結合テスト）を
最初 `java.nio.channels.Channels.newInputStream`/`newOutputStream` で
書いたところ、双方向に同時ポンピングすると**書き込みが成功を返すのに
相手に届かない**という現象が起き、テストがタイムアウトでハングした。
単純な単発の読み書きでは再現せず、2本のスレッドが同じ `SocketChannel` を
別方向へ同時にポンピングする構成で初めて再現する、この JDK での
`Channels` ラッパの実装依存の不具合と見られる。`internal.transport.
ChannelSource` が既に使っていた生の `ByteBuffer` 直接読み書きに切り替えて
解消した（`SocketTransportTest` の UNIX 経路の単体テストは最初から
この方式で書いていたため影響を受けていなかった）。

**実機検証**: SSH forced command 経由の `SanDbOx.connect("ssh", ...)`
（非 root・カスタムポートで一時 sshd を起動。hello・`query` 往復、
任意コマンド送信の無視、強制 `--read-only` の実効性）と、socat
`OPENSSL-LISTEN` 越しの mTLS（`SSLSocket` → `connectSocket`、正しい
クライアント証明書での成功、CA チェーン外証明書の拒否、駆動レベルの
タイムアウト設定が TLS 越しの読み取りを実際に束縛すること）を、他4言語と
同じ手順で実施。**TLS 1.3 特有の発見**: `SSLSocket.startHandshake()` が
例外を投げずに完了しても、それだけではサーバが証明書を受理した証拠には
ならない——TLS 1.3 はクライアント側のハンドシェイク完了とサーバ側の
非同期拒否アラート処理の間に競合があり、拒否は1テンポ遅れて最初の実際の
読み取り時に現れる。`connectSocket` 自身が最初に行う hello 行の読み取り
がこの検証を担っており、`SanDbOxException`（`Received fatal alert:
unknown_ca` を包む）が投げられることを繰り返し実行して確認した。
Java は他3言語と違い PEM 証明書を直接読めないため、検証には
`openssl pkcs12 -export`/`keytool -importcert` による PKCS#12 キーストア
への変換手順を挟んだ（この変換手順は `docs/usage/connecting*.md` にも
明記した）。

**テスト**: 単体テスト62件（`internal.codec`/`internal.transport`）＋
結合テスト `usage.ClientTest`(18)・`usage.SocketTest`(4)＋
`ConformanceTest`(7)・`MatchTest`(8)・`NetcheckTest`(2)・
`CompileFailTest`(4)の計111件、`make java-test` で 17・25 の両方の
JDK で全緑（`JAVA_HOME` を切り替えて2回実行して確認）。netcheck は
意図的に `java.net.Socket` 参照を `internal.codec` へ混入させて失格
すること、陽性対照の対象パッケージを存在しないものに変えて陽性対照自体が
失格すること、`CompileFailTest` は `SocketClient` に `overwrite()` を
一時的に生やして失格することの、3種類すべてを実機で確認済み。javadoc は
`-Xdoclint:all,-missing`（タグ欠落は無視しつつ壊れた `{@link}` 等の構文は
検出）＋ `failOnWarnings=true` で、壊れた `{@link}` を一時的に仕込んで
ビルドが実際に失敗することも確認した。

**Makefile / CI**: `java-build`/`java-javadoc`/`java-netcheck`/
`java-test` を追加（実行時依存ゼロ、テスト依存は JUnit 5 のみなので
`python-venv`/`typescript-deps` のようなインストール手順・スタンプ
ファイルは不要）。CI は `java` ジョブを追加し `["17","25"]` をマトリクス化
（`actions/setup-java@v5`、`distribution: temurin`）。

**利用者向けドキュメント**（`.claude/`・`CLAUDE.md`・`PLAN.md` を一切
参照しない）: ルート `README.md`/`README_ja.md` の言語表に Java 行を
追加。`java/README.md` を `rust/README.md` と同じ節構成で新規作成
（英語のみ。`Boolean` が param にならず実行時エラーになる点、
`AutoCloseable`/try-with-resources が接続を閉じる点、`exitCode()` が
Java の規約（`128+signum`）で返す点を明記）。
`docs/usage/connecting.md`/`connecting_ja.md` の6箇所（Local・SSH
リモートコマンド・Docker・Kubernetes・素のソケット・TLS/mTLS）に Java
の例を追加（計12編集）し、TLS 節には Java 固有の説明（`SSLSocket
extends Socket` により read timeout をドライバ自身が設定できる、PEM
証明書を直接読めないため PKCS#12 変換が要る）を追加、SSH forced
command・TLS/mTLS の「実際に検証した内容」にも Java の確認結果（TLS 1.3
の競合の発見を含む）を追記した。

**内部ルールの更新**: `.claude/rules/naming.md` に Java の行
（Maven 座標・パッケージ名に第3段表記 `sandbox` を使う理由）、
`.claude/rules/distribution.md` の配布表に
`| Java | Maven Central | java/vX.Y.Z |` を追加。
`.devcontainer/devcontainer.json` に公式
`ghcr.io/devcontainers/features/java:1`（`version: "17"`、
`additionalVersions: "25"`、`jdkDistro: "tem"`、`installMaven: true`。
`additionalVersions` が実在のオプションであることは feature の
`devcontainer-feature.json` を実機確認済み）と `redhat.java`/
`vscjava.vscode-maven` 拡張・`[java]` フォーマッタ設定を追加。

`.claude/settings.json` の `PostToolUse` フックへの Java 用分岐追加は
提案してユーザー承認を得て適用済み（`*java/*.java`・`*java/pom.xml`
編集後に `make java-build` を自動実行。Go/Rust の `*-build` と同じ
パターン。シミュレーションで実際に発火することと、無関係なファイルでは
発火しないことの両方を確認済み）。

**この作業でやらなかったこと**: Maven Central への実際の publish
（③配布・公開はスコープ外、ユーザー作業として後続——GPG鍵・Central
Portal アカウント・`io.github.amisonnet8` namespace 所有確認が必要。
`pom.xml` の `release` プロファイルに source/javadoc jar・GPG署名・
`central-publishing-maven-plugin` を先に仕込み済みなので、ユーザー側は
`mvn -Prelease deploy` 1コマンドで済む状態にしてある）。

**次はフェーズ⑩（6番目の言語）——需要を見て検討する。**

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

- ~~devcontainer に `sshd` が入っていないため……~~ → **解消。**
  フェーズ⑤・⑥・⑦・⑧それぞれで `sshd`（フェーズ⑦以降は非root・
  カスタムポートで一時起動）を立て、forced command 経由の SSH 直結を
  Go・Python・TypeScript・Rust 全ドライバに対して end-to-end で確認済み
  （`docs/usage/connecting.md` の該当節参照）。

## 保留事項

- ~~`PostToolUse` フックが未設定~~ → **導入済み。** `go/**/*.go`・
  `go/go.mod`・`go/go.sum` への Edit/Write 後に `make go-build` を
  自動実行するフックを `.claude/settings.json` に追加（実際に発火する
  ことをセンチネルファイルで確認済み）。
- ~~PyPI / npm のパッケージ名の予約状況が未確認~~ → **両方解消。**
  Python 側: `san-db-ox`・`san-db-ox-client` とも PyPI 未登録を確認し
  `san-db-ox-client` に確定。npm 側: スコープ付き名
  `@amisonnet8/san-db-ox-client` を採用したことで、PyPI で踏んだ
  「記号除去後の正規化による衝突」という問題圏自体に入らない設計にした
  （`naming.md`・`distribution.md` 反映済み）。`amisonnet8` スコープは
  問題なく取得済みで、③配布フェーズ（ユーザー作業）として
  `npm publish --access public` を実施し `@amisonnet8/san-db-ox-client@0.1.0`
  を公開済み（詳細は「公開ページ一覧」直下参照）。
- **devcontainer.json 反映待ちリスト**: 現行コンテナはリビルドせずに
  開発を進める方針（都度手動でインストール・設定して進め、区切りでまとめて
  `devcontainer.json` へ反映する）。session内で手動インストール・設定を
  行った場合は、忘れずにここへ追記すること。
  - **`gh`（GitHub CLI）はこのセッションで `postCreate.sh` に直接反映
    済み**（`.devcontainer/postCreate.sh` 参照）。次回リビルド時は自動で
    入るため、このリストには残さない。
  - **Python（`python3`/`python3-venv`）はフェーズ⑥のセッションで
    `sudo apt-get install` により手動導入し、`devcontainer.json` の
    features にも公式 `ghcr.io/devcontainers/features/python:1` を
    直接反映済み**。ただし `devcontainer-lock.json` は `devcontainer`
    CLI が無く手動更新するとハッシュを捏造することになるため未更新——
    次回実際にコンテナをリビルドするタイミングで自動生成させること。
  - **Rust（rustup 経由の stable + 1.85）はフェーズ⑧のセッションで
    手動導入し、`devcontainer.json` の features にも公式
    `ghcr.io/devcontainers/features/rust:1`（`profile: default`）を
    直接反映済み**。`rust-lang.rust-analyzer` 拡張と
    `rust-analyzer.linkedProjects` 設定も同時に追記した。
    `devcontainer-lock.json` は Python のときと同じ理由で未更新のまま
    ——次回実際にコンテナをリビルドするタイミングで自動生成させること。
  - **Java（apt の openjdk-17-jdk・maven ＋ Temurin 25 の tarball手動展開）
    はフェーズ⑨のセッションで手動導入し、`devcontainer.json` の
    features にも公式 `ghcr.io/devcontainers/features/java:1`
    （`version: "17"`、`additionalVersions: "25"`、`jdkDistro: "tem"`、
    `installMaven: true`）を直接反映済み**。`redhat.java`・
    `vscjava.vscode-maven` 拡張と `[java]` フォーマッタ設定も同時に
    追記した。`devcontainer-lock.json` は同じ理由で未更新のまま——次回
    実際にコンテナをリビルドするタイミングで自動生成させること。
- ~~`PostToolUse` フックへの Rust 用分岐は未提案~~ → **導入済み。**
  `*rust/*.rs`・`*rust/Cargo.toml` への Edit/Write 後に `make rust-build`
  を自動実行するフックを `.claude/settings.json` に追加（実際に発火する
  ことと無関係なファイルでは発火しないことの両方を確認済み）。
- ~~`PostToolUse` フックへの Java 用分岐は未提案~~ → **導入済み。**
  `*java/*.java`・`*java/pom.xml` への Edit/Write 後に `make java-build`
  を自動実行するフックを `.claude/settings.json` に追加（実際に発火する
  ことと無関係なファイルでは発火しないことの両方を確認済み）。
