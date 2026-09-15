# 接続方法（実例集）

*[English](connecting.md) | **日本語***

SanDBox（`san-db-ox --serve-stdio`）へ接続する方法を、実際にコピーして
使えるコマンド例とともにまとめたもの。直結（子プロセスとして起動する）と、
socat 等で外付けされたソケットへの接続の2種類がある。SanDBox 自身は
ネットワーク待受を一切持たない設計なので、外部公開時のセキュリティは
socat・SSH 側の設定で作り込む必要がある——このドキュメントが各パターンの
「どう安全にするか」も含めて説明する。

**`<!-- doctest -->` が直前に付いているコマンド例だけ、`make test-docs`
で実際に動かして検証している。** それ以外（SSH・socat・TLS・Docker の
例）は、鍵・証明書・待受プロセスの用意が要り CI 環境で毎回再現するコストが
見合わないため自動検証の対象外だが、このドキュメントを書く際にすべて手元
で実際に動かして確認済み（検証内容はそれぞれの節に記載）。

## 直結

### ローカル

<!-- doctest -->
```bash
make fetch  # bin/san-db-ox を用意する（初回のみ）
printf '%s\n' '{"op":"query","sql":"SELECT 1"}' \
  | bin/san-db-ox --serve-stdio \
  | jq -c .
```

出力（1行目が hello、2行目が `query` の応答）:

```
{"product":"SanDBox","protocol":1,"version":"v0.1.1"}
{"ok":true,"columns":["1"],"rows":[[1]]}
```

Go ドライバからは、この子プロセス起動を `sandbox.Open` に委ねるだけでよい。

```go
c, err := sandbox.Open(ctx, "bin/san-db-ox", []string{"--serve-stdio"})
```

Python ドライバの `connect` も同様。

```python
c = san_db_ox.connect("bin/san-db-ox", ["--serve-stdio"])
```

TypeScript ドライバの `connect` も同様。

```typescript
const c = await connect("bin/san-db-ox", ["--serve-stdio"]);
```

Rust ドライバの `connect` も同様。

```rust
let mut c = san_db_ox_client::connect("bin/san-db-ox", &["--serve-stdio"])?;
```

### SSH リモートコマンド直結

サーバ側に手を加えず、単に `ssh` 経由で `san-db-ox --serve-stdio` を
起動するだけならこう:

```bash
ssh user@host san-db-ox --serve-stdio
```

socat も TLS 証明書もポート開放も要らず、認証と暗号化を SSH にそのまま
乗せられる——SanDBox がネットワーク待受を持たない設計を最も素直に活かせる
経路であり、外部公開が要る場面ではまずこれを検討する。

Go ドライバも、起動するコマンドと引数を差し替えるだけで同じトランス
ポートを使い回せる（SSH 専用のコンストラクタは存在しない）:

```go
c, err := sandbox.Open(ctx, "ssh", []string{"user@host", "san-db-ox", "--serve-stdio"})
```

Python ドライバも同様に SSH 専用のコンストラクタは持たない。

```python
c = san_db_ox.connect("ssh", ["user@host", "san-db-ox", "--serve-stdio"])
```

TypeScript ドライバも同様。

```typescript
const c = await connect("ssh", ["user@host", "san-db-ox", "--serve-stdio"]);
```

Rust ドライバも同様。

```rust
let mut c = san_db_ox_client::connect("ssh", &["user@host", "san-db-ox", "--serve-stdio"])?;
```

### SSH forced command（推奨経路）

`authorized_keys` に1行加えるだけで、認証・認可・接続元制限・読み取り
専用の強制が揃う。

```
restrict,command="/usr/local/bin/san-db-ox --read-only --serve-stdio" ssh-ed25519 AAAA... sandbox-readonly
```

- **`command=`** — この鍵で接続した場合、クライアントが何を要求しても
  このコマンドだけが実行される。クライアントが送ろうとしたコマンドは
  `SSH_ORIGINAL_COMMAND` 環境変数に入るだけで実行されないので、
  クライアント側はコマンドを何も指定しなくてよい。
- **`restrict`**（OpenSSH 7.2 以降） — ポートフォワード・エージェント
  転送・PTY 割り当て・X11 転送等をすべて無効化する。この鍵では SanDBox に
  繋ぐこと以外できなくなる。
- **`from=`** を先頭に足せば、接続元を CIDR やホスト名パターンでも
  絞れる。

```bash
ssh -i sandbox-readonly-key user@host
```

**実際にローカルの sshd に対して検証した内容**（`openssh-server` は
devcontainer に導入済み。テストのたびに `sudo /usr/sbin/sshd -p <port>`
で手動起動する運用とし、常駐はさせていない）:

- クライアントが `ssh user@host 'rm -rf /'` のように任意のコマンドを
  送っても、実行されるのは forced command（`san-db-ox --serve-stdio`）
  だけであることを確認した。
- `restrict` により、同じ鍵でのポートフォワード要求が
  `administratively prohibited` としてサーバ側に拒否されることを
  `ssh -v` のログで確認した（クライアント側のローカルリスナー自体は
  常に開くため、フォワードが効いているかはリスナーの有無ではなく実際に
  データが通るかで判定する必要がある）。
- forced command を実際の `san-db-ox --read-only --serve-stdio` にして
  hello 行・`query` 応答が正しく返ることを確認した。
- Go ドライバの `sandbox.Open(ctx, "ssh", []string{...})` が、この
  forced command 経路に対しても他の起動コマンドと同じように使えることを
  確認した（SSH 専用のコードパスは存在しない）。
- Python ドライバの `san_db_ox.connect("ssh", [...])` についても同様に
  確認した。
- TypeScript ドライバの `connect("ssh", [...])` についても同様に確認
  した。クライアントが任意のコマンド（`ssh ... 'rm -rf /'`）を送っても
  forced command だけが実行されること、forced command の `--read-only`
  が実際に効いていること（`snapshot()` が `read_only` コードで拒否
  される）の両方を確認した。
- Rust ドライバの `connect("ssh", &[...])` についても、hello 行・
  `query` の往復が正しく返ることと、forced command の `--read-only` が
  実際に効いていること（`snapshot()` が `read_only` コードで拒否
  される）を確認した。

**読み書き両方を許す鍵と読み取り専用の鍵は、`authorized_keys` の別エントリ
（別の鍵ペア）として分けて発行すること。** 1つの鍵に両方の権限を持たせて
呼び出し側の判断に委ねない。

### Docker

```bash
docker run -i --rm <image> --serve-stdio
```

```go
c, err := sandbox.Open(ctx, "docker", []string{"run", "-i", "--rm", image, "--serve-stdio"})
```

```python
c = san_db_ox.connect("docker", ["run", "-i", "--rm", image, "--serve-stdio"])
```

```typescript
const c = await connect("docker", ["run", "-i", "--rm", image, "--serve-stdio"]);
```

```rust
let mut c = san_db_ox_client::connect("docker", &["run", "-i", "--rm", image, "--serve-stdio"])?;
```

### Kubernetes

```bash
kubectl exec -i <pod> -- san-db-ox --serve-stdio
```

```go
c, err := sandbox.Open(ctx, "kubectl", []string{"exec", "-i", pod, "--", "san-db-ox", "--serve-stdio"})
```

```python
c = san_db_ox.connect("kubectl", ["exec", "-i", pod, "--", "san-db-ox", "--serve-stdio"])
```

```typescript
const c = await connect("kubectl", ["exec", "-i", pod, "--", "san-db-ox", "--serve-stdio"]);
```

```rust
let mut c = san_db_ox_client::connect("kubectl", &["exec", "-i", pod, "--", "san-db-ox", "--serve-stdio"])?;
```

## socat 経由（ソケット）

socat を挟むと、直結では出せない TCP/UNIX ソケットとしてクライアントに
見せられる。ただし2点、直結には無い前提が付いてくる。

- **接続ごとに別プロセス・別DBになる。** これは socat の `fork`
  オプション自身の挙動（接続を受けるたびに新しい子プロセスを起動する）で
  あり、SanDBox 側に「複数クライアントで1つの DB を共有する」仕組みは
  無い。1つの DB を複数クライアントで扱いたい用途には向かない。
- **認証が一切存在しない。** 到達できる者は誰でも任意の SQL を実行
  できる。以下の TLS/接続元制限のいずれかと必ず組み合わせること。

**自身の実行ファイルを上書きする `overwrite` op は socat 経由では
使わない。** 複数の子プロセスが同じ実行ファイルパスへ同時に書きに行く
リスクがあるため（Go ドライバでは、そもそも `*SocketClient` に
`Overwrite` メソッド自体が無く、型の時点で呼べない。Rust ドライバの
`SocketClient` にも `overwrite` メソッドは無く、同じ理由でコンパイル
エラーになる）。

### 素の TCP/UNIX ソケット

```bash
# UNIX ドメインソケット
socat UNIX-LISTEN:/tmp/sandbox.sock,fork EXEC:"san-db-ox --read-only --serve-stdio"

# TCP（bind=127.0.0.1 で外部からは繋がらないようにする。次の節も参照）
socat TCP-LISTEN:5432,fork,bind=127.0.0.1 EXEC:"san-db-ox --read-only --serve-stdio"
```

```go
c, err := sandbox.OpenSocket(ctx, "unix", "/tmp/sandbox.sock")
// または
c, err := sandbox.OpenSocket(ctx, "tcp", "127.0.0.1:5432")
```

```python
c = san_db_ox.connect_unix("/tmp/sandbox.sock")
# または
c = san_db_ox.connect_tcp("127.0.0.1", 5432)
```

```typescript
const c = await connectUnix("/tmp/sandbox.sock");
// または
const c2 = await connectTcp("127.0.0.1", 5432);
```

```rust
let mut c = san_db_ox_client::connect_unix("/tmp/sandbox.sock")?;
// または
let mut c2 = san_db_ox_client::connect_tcp(("127.0.0.1", 5432))?;
```

### TLS/mTLS（クライアント証明書による認証）

socat 自身に認証機構は無いため、外部公開するなら TLS のクライアント
証明書検証を事実上の認証として使う。以下は自己署名の CA・サーバ証明書・
クライアント証明書一式をその場で作る例（動作確認用。実運用では組織の CA
を使う）。

```bash
# CA
openssl req -x509 -newkey rsa:2048 -nodes -days 365 \
  -keyout ca-key.pem -out ca.pem -subj "/CN=my-ca"

# サーバ証明書 -- SAN が無いと最近の TLS クライアント（Go の
# crypto/tls を含む）が「legacy Common Name field」として拒否するので、
# 必ず subjectAltName を付ける。
cat > server-ext.cnf <<'EOF'
subjectAltName = DNS:sandbox.example.com,IP:127.0.0.1
EOF
openssl req -newkey rsa:2048 -nodes \
  -keyout server-key.pem -out server-req.pem -subj "/CN=sandbox.example.com"
openssl x509 -req -in server-req.pem -CA ca.pem -CAkey ca-key.pem \
  -CAcreateserial -out server-cert.pem -days 365 -extfile server-ext.cnf
cat server-key.pem server-cert.pem > server.pem

# クライアント証明書
openssl req -newkey rsa:2048 -nodes \
  -keyout client-key.pem -out client-req.pem -subj "/CN=my-client"
openssl x509 -req -in client-req.pem -CA ca.pem -CAkey ca-key.pem \
  -CAcreateserial -out client-cert.pem -days 365
cat client-key.pem client-cert.pem > client.pem
```

```bash
socat OPENSSL-LISTEN:5432,fork,cert=server.pem,cafile=ca.pem,verify=1 \
      EXEC:"san-db-ox --read-only --serve-stdio"
```

**`verify=1`（既定値）によるクライアント証明書の検証が、事実上の認証
機構になる。** `verify=0` にして暗号化だけ有効にすると「盗聴はされないが
誰でも繋げる」状態になり、SanDBox 自身に認証機構が無い以上、無防備な状態
とほぼ変わらない。**`verify` を無効化しないこと。**

**実際に検証した内容**: 正しい `client.pem` での接続は通り hello 行が
読める一方、CA チェーンに繋がらない別の自己署名証明書（クライアント証明書
とは無関係な単独の証明書）での接続は `SSL_accept(): certificate verify
failed` としてサーバ側に拒否されることを確認した。

Go ドライバは TLS 専用のコンストラクタを持たない代わりに、
`crypto/tls.Dial` の結果をそのまま渡せる（`OpenSocketConn`）:

```go
cert, err := tls.LoadX509KeyPair("client-cert.pem", "client-key.pem")
pool := x509.NewCertPool()
pool.AppendCertsFromPEM(caPEM) // ca.pem の中身

nc, err := tls.Dial("tcp", "host:5432", &tls.Config{
    RootCAs:      pool,
    Certificates: []tls.Certificate{cert},
    ServerName:   "sandbox.example.com", // サーバ証明書の SAN と一致させる
})
c, err := sandbox.OpenSocketConn(ctx, nc)
```

この組み合わせ（`tls.Dial` → `OpenSocketConn`）を実際に動かし、hello 行・
`query` の往復を確認済み。

Python ドライバも同じ形——TLS 専用のコンストラクタは持たず、
`ssl.SSLContext.wrap_socket` の戻り値をそのまま `connect_socket` に渡す。

```python
ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
ctx.load_verify_locations("ca.pem")
ctx.load_cert_chain("client-cert.pem", "client-key.pem")

raw = socket.create_connection(("host", 5432))
tls_sock = ctx.wrap_socket(raw, server_hostname="sandbox.example.com")
c = san_db_ox.connect_socket(tls_sock)
```

同じ socat 待受に対してこの組み合わせも実際に動かし、hello 行・`query`
の往復を確認済み。CA チェーン外のクライアント証明書がサーバ側で拒否
される（`SSL_accept(): certificate verify failed`）ことも、どちらの
ドライバで接続しても同じ結果になることを確認した——この検証は socat
側だけで完結している。

TypeScript ドライバも同じ形——TLS 専用のコンストラクタは持たず、
`tls.connect` の戻り値（`Duplex` として受け取るため、ドライバ自体は
`node:tls` を一切 import しない）をそのまま `connectSocket` に渡す。

```typescript
import { connect as tlsConnect } from "node:tls";
import { readFileSync } from "node:fs";

const socket = tlsConnect({
  host: "host",
  port: 5432,
  ca: readFileSync("ca.pem"),
  cert: readFileSync("client-cert.pem"),
  key: readFileSync("client-key.pem"),
  servername: "sandbox.example.com",
});
const c = await connectSocket(socket);
```

この組み合わせ（`tls.connect` → `connectSocket`）も同じ socat 待受に対して
実際に動かし、hello 行・`query` の往復と、CA チェーン外のクライアント
証明書が同じく `SSL_accept(): certificate verify failed` でサーバ側から
拒否されることの両方を確認済み——この検証は socat 側だけで完結している
ため、他の2言語と同じ結果になるのは偶然ではなく想定どおり。

Rust の標準ライブラリには TLS が一切無いため、このドライバは TLS の型を
名指しすることすらできない。継ぎ目は他の3言語と同じ形——`connect_socket`
が `Read + Write` を実装する何でも受け取るので、呼び出し側が
`rustls`（下記の例）や `native-tls` を持ち込めばよく、このクレート自体は
どちらにも依存しない。

```rust
// rustls とその証明書ローダは呼び出し側の依存物。このドライバは TLS
// クレートに一切依存せず、TLS 専用のコンストラクタも持たない
// —— Read + Write を実装するものであれば何でもよい。
let tcp = std::net::TcpStream::connect("host:5432")?;
// 必須: connect_socket は任意のストリームに対して自分でこれを設定
// できないため、これが以降のすべての読み取りを束縛する
// （rustls/native-tls の read は下位ソケットへ委譲される）。
tcp.set_read_timeout(Some(std::time::Duration::from_secs(30)))?;

let mut roots = rustls::RootCertStore::empty();
roots.add_parsable_certificates(load_certs("ca.pem")?);
let config = rustls::ClientConfig::builder()
    .with_root_certificates(roots)
    .with_client_auth_cert(load_certs("client-cert.pem")?, load_key("client-key.pem")?)?;
let conn = rustls::ClientConnection::new(
    std::sync::Arc::new(config),
    "sandbox.example.com".try_into()?, // サーバ証明書の SAN と一致させる
)?;

let mut c = san_db_ox_client::connect_socket(
    rustls::StreamOwned::new(conn, tcp),
    SocketOptions::new(),
)?;
```

この組み合わせ（`rustls::StreamOwned` → `connect_socket`）も同じ socat
待受に対して実際に動かし、hello 行・`query` の往復と、CA チェーン外の
クライアント証明書が他の3言語と同じく `SSL_accept(): certificate verify
failed` でサーバ側から拒否されることを確認した。加えて、TLS でラップする
前の `TcpStream` に設定した read timeout が、TLS 越しのドライバの読み取り
を実際に束縛することも確認した——これは Rust に固有の主張であるため、
仮定せず実測した（`TcpStream` に 200ms を設定し、わざと遅いクエリに対して
約255msで `Error::Timeout` が返ることを確認）。

### 接続元IP アドレスの制限

```bash
# ループバック限定（もっとも安全。SSH ポートフォワードと組み合わせる前提）
socat TCP-LISTEN:5432,fork,bind=127.0.0.1 EXEC:"san-db-ox --read-only --serve-stdio"

# 許可する送信元を CIDR で絞る
socat TCP-LISTEN:5432,fork,range=10.0.0.0/8 EXEC:"san-db-ox --read-only --serve-stdio"
```

**実際に検証した内容**: `bind=127.0.0.1` を付けると `0.0.0.0` ではなく
`127.0.0.1` にだけ listen することを `ss -tln` で確認した。`range=` は
範囲外の送信元（`range=10.0.0.0/8` に対する `127.0.0.1` からの接続）が
`refusing connection from ... due to range option` として即座に拒否され、
範囲内（`range=127.0.0.0/8`）なら通常どおり通ることを確認した。

`tcpwrap[=<name>]`（`/etc/hosts.allow`/`/etc/hosts.deny` による接続元
制御との連携）というオプションもあるが、システム全体の設定ファイルを
書き換える必要があるため実測はしていない。

**socat のオプションだけに頼らず、OS のファイアウォール（`iptables`/
`nftables`/クラウドのセキュリティグループ等）を最終防衛線として併用する
こと。** socat の起動コマンドを1箇所書き間違えただけで公開範囲が広がる、
という事故はコマンドライン1行の設定にはつきものであり、外側にもう1段の
制限を持たせておく。

### `--read-only` との併用

このドキュメントの例はすべて `--read-only` を付けている。外部公開する
SanDBox は、これを基本の構成とすること。書き込みを許す外部公開が必要な
場合は、上記の TLS クライアント認証と組み合わせ、かつ接続元を最大限
絞ること。

## SSH のその他の使い方（補助）

- **ポートフォワード** — socat をループバック限定（`bind=127.0.0.1`）で
  立て、クライアント側から `ssh -L 15432:127.0.0.1:5432 user@host` で
  トンネルを通す。SSH が暗号化・認証を担い、socat 自体はループバック
  限定なので外部からは直接見えない。
- **SSH 自体の失敗と SanDBox 側のエラーは区別が付かない場合がある** —
  SSH 自体の失敗（接続不能・ホスト鍵不一致・認証失敗等）は終了コード
  `255` を返すが、リモートコマンド（`san-db-ox`）自身が `255` で終了
  した場合と区別が付かない。終了コードだけで「SanDBox 側のエラーだ」と
  断定せず、stderr の内容と合わせて判断すること。
