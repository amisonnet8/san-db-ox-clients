# 接続方法（実例集）

`.claude/rules/connectivity.md` が定める方針・判断根拠を、実際にコピーして
使えるコマンド例に落とし込んだもの。読む前に `connectivity.md` を読むこと
——ここでは「なぜその設定にするか」は繰り返さず、「実際どう打つか」だけを
書く。

**`<!-- doctest -->` が直前に付いているコマンド例だけ、`make test-docs`
（CI の `docs` ジョブ）で実際に動かして検証している。** それ以外（SSH・
socat・TLS・Docker の例）は `.claude/rules/testing.md` の方針により自動
検証の対象外——鍵・証明書・待受プロセスの用意が要り、CI 環境で毎回再現する
コストが見合わないため。その代わり、このドキュメントを書く際にすべて手元
で実際に動かして確認済み。

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

### SSH リモートコマンド直結

サーバ側に手を加えず、単に `ssh` 経由で `san-db-ox --serve-stdio` を
起動するだけならこう:

```bash
ssh user@host san-db-ox --serve-stdio
```

Go ドライバも起動コマンドを差し替えるだけで同じトランスポートを使う
（`.claude/rules/architecture.md`）:

```go
c, err := sandbox.Open(ctx, "ssh", []string{"user@host", "san-db-ox", "--serve-stdio"})
```

### SSH forced command（推奨経路）

`authorized_keys` に1行加えるだけで、認証・認可・接続元制限・読み取り
専用の強制が揃う（`connectivity.md` 参照）。

```
restrict,command="/usr/local/bin/san-db-ox --read-only --serve-stdio" ssh-ed25519 AAAA... sandbox-readonly
```

この鍵で接続する側は、コマンドを何も指定しなくてよい
（`SSH_ORIGINAL_COMMAND` に何を送っても forced command だけが実行される
ため）:

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

読み書き両方を許す鍵と読み取り専用の鍵は、`authorized_keys` の別エントリ
（別の鍵ペア）として分けて発行すること（`connectivity.md` 参照）。

### Docker

```bash
docker run -i --rm <image> --serve-stdio
```

```go
c, err := sandbox.Open(ctx, "docker", []string{"run", "-i", "--rm", image, "--serve-stdio"})
```

### Kubernetes

```bash
kubectl exec -i <pod> -- san-db-ox --serve-stdio
```

```go
c, err := sandbox.Open(ctx, "kubectl", []string{"exec", "-i", pod, "--", "san-db-ox", "--serve-stdio"})
```

## socat 経由（ソケット）

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

### TLS/mTLS（クライアント証明書による認証）

以下は自己署名の CA・サーバ証明書・クライアント証明書一式をその場で
作る例（動作確認用。実運用では組織の CA を使う）。

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

サーバ（`verify=1` が既定値。**`verify=0` にしない**——`connectivity.md`
参照）:

```bash
socat OPENSSL-LISTEN:5432,fork,cert=server.pem,cafile=ca.pem,verify=1 \
      EXEC:"san-db-ox --read-only --serve-stdio"
```

**実際に検証した内容**: 正しい `client.pem` での接続は通り hello 行が
読める一方、CA チェーンに繋がらない別の自己署名証明書（クライアント証明書
とは無関係な単独の証明書）での接続は `SSL_accept(): certificate verify
failed` としてサーバ側に拒否されることを確認した——`verify=1` が実際に
「事実上の認証機構」として機能している。

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

`tcpwrap[=<name>]`（`/etc/hosts.allow`/`/etc/hosts.deny` との連携）は
システム全体の設定ファイルを書き換える必要があるため、ここでは実測せず
`connectivity.md` の記載に留める。

**socat のオプションだけに頼らず、OS のファイアウォールを最終防衛線として
併用すること**（`connectivity.md` 参照）。

### `--read-only` との併用

このドキュメントの例はすべて `--read-only` を付けている。外部公開する
SanDBox は、これを基本の構成とすること（`connectivity.md`「`--read-only`
との併用を前提にする」参照）。書き込みを許す場合は、上記の TLS クライアント
認証と組み合わせ、かつ接続元を最大限絞ること。
