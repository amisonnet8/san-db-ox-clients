# san-db-ox-clients

[SanDBox](https://github.com/amisonnet8/san-db-ox)（DBエンジンとデータ
領域を1つの実行ファイル内に保持する、環境構築不要のポータブルな単一
バイナリ RDBMS）を、各プログラミング言語から子プロセスとして起動し、
stdio プロトコルで対話するための、各言語向けの薄いラッパー群。

**ドライバは利便性のためのものであって、利用の前提条件ではない。**
stdio プロトコルは行区切りJSON（JSON Lines）であり、サブプロセスと
パイプを扱える処理系であれば専用ライブラリなしで直接繋がる。ドライバは
その上に乗る薄いラッパーとして、型付けされた API・値の表現の変換・
トランスポートの選択を提供する。

## 追従しているプロトコル

| 項目 | 値 |
| :--- | :--- |
| 本体リポジトリ | [amisonnet8/san-db-ox](https://github.com/amisonnet8/san-db-ox) |
| `protocol` 番号 | `1` |

ドライバのバージョンと本体のバージョンは連動しない。対応関係はこの
`protocol` 番号で表す。追従している本体のタグを含む詳細は
[`.claude/rules/protocol.md`](.claude/rules/protocol.md) を参照。

## 言語別の状況

| 言語 | 状態 |
| :--- | :--- |
| Go | 利用可能——[`go/`](go/) を参照 |
| Python | 予定 |
| TypeScript | 予定 |

## 接続方法

直結（子プロセスの起動コマンドを差し替えることで、ローカル・SSH・
Docker・Kubernetes のいずれにも同じトランスポートで対応する）と、
socat 等で外付けされたソケットへの接続の2種類をサポートする。認証の
無い経路を外部公開する際のセキュリティ設定（SSH forced command・TLS
クライアント認証等）を含め、詳細は
[`.claude/rules/connectivity.md`](.claude/rules/connectivity.md) を
参照。上記すべてのパターンについて、実際にコピーして使えるコマンド例を
[`docs/usage/connecting_ja.md`](docs/usage/connecting_ja.md) に用意した。

## ライセンス

MIT。[LICENSE](LICENSE) を参照。
