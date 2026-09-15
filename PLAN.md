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
8. **⑧以降 他言語への展開【現在地】**: 需要を見て
   Rust / JVM(Java・Kotlin) / Ruby / C#(.NET) / PHP / C から検討する。

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

**フェーズ①〜⑦完了。フェーズ⑧（4番目の言語）着手前——需要を見て検討する。**

### 公開ページ一覧

各言語のパッケージレジストリ上の公開ページ（実機で200応答を確認済み、
2026-09-15）。

| 言語 | 公開ページ |
| :--- | :--- |
| Go | https://pkg.go.dev/github.com/amisonnet8/san-db-ox-clients/go/sandbox |
| Python | https://pypi.org/project/san-db-ox-client/ |
| TypeScript | https://www.npmjs.com/package/@amisonnet8/san-db-ox-client |

TypeScript の公開（2026-09-15）: `npm publish --access public` で
`@amisonnet8/san-db-ox-client@0.1.0` を公開。npm の Granular Access
Token 発行時、**「Bypass two-factor authentication」チェックボックス
（デフォルトでオフ）を入れ忘れると `403 Forbidden` になる**点で詰まった
（教訓として記録）。公開直後、レジストリの一部 CDN エッジで数分間
`404` が返るキャッシュ遅延も観測されたが、実体の公開自体は成功していた
（`npm publish` の応答・確認メール・別エッジからの直接確認で確定）。

### 開発の進め方（フェーズ⑥で確定した方針）

新しい言語ドライバへの展開は「①計画→②実装（一気に実施）→③配布・公開
（ユーザー作業を含むことがある）」の3段階で進める（ユーザー指定、
2026-09-15）。フェーズ⑦以降もこれに従う。

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

**次はフェーズ⑧（4番目の言語）——需要を見て検討する。**

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
  フェーズ⑤・⑥・⑦それぞれで `sshd`（フェーズ⑦では非root・カスタム
  ポートで一時起動）を立て、forced command 経由の SSH 直結を Go・
  Python・TypeScript 全ドライバに対して end-to-end で確認済み
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
