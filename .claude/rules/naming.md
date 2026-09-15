# 命名規則

## 言語をまたいで語幹を揃える

op 名は、各言語の命名慣習（camelCase/snake_case 等）へ写像するだけにし、
**語そのものは変えない**。

`query` / `exec` / `snapshot` / `load` / `inspect` / `tables` / `schema` /
`dump` / `overwrite` / `close`（`protocol.md` の op 一覧参照）。

例えば Go なら `Query`/`Exec`/`Snapshot`、Python なら `query`/`exec`/
`snapshot` のように、大文字化・区切り文字の変換だけを行う。「`exec` は
紛らわしいから `execute` にする」のような**言語ごとの言い換えはしない**。
本体 naming.md の「レイヤー間で名前を揃える」を、このリポジトリでは
**「言語間で名前を揃える」へ読み替えたもの**——同じ操作が言語によって
別の語で呼ばれると、複数言語を横断してプロトコルの動作を確認する際に
対応が取れなくなる。

## 製品名の3段階表記

本体 naming.md の表記をそのまま継承する。**上の段が使える場面では必ず
上の段を使う。**

| 優先 | 表記 | 使う場面 |
| :--- | :--- | :--- |
| 第1 | `san-db-ox` | 制約が無い全ての場面 |
| 第2 | `san_db_ox` | ハイフンが使えない場面（Python の import 名等） |
| 第3 | `sandbox` | 上記2つが長さ等の理由で使えない場面 |

`sandbox` は一般名詞と衝突しやすいため、識別子として使う場面は最小限に
とどめる（例: Go の package 名は `sandbox` 1語で許容するが、CLI や環境
変数では避ける）。

## 各言語のモジュール／パッケージ名

他言語を追加する際にこの表へ追記する。

| 言語 | ディレクトリ | モジュール／パッケージ | タグ |
| :--- | :--- | :--- | :--- |
| Go | `go/`（`go.mod` のルート）、実体は `go/sandbox/` | module `github.com/amisonnet8/san-db-ox-clients/go`、package `sandbox` | `go/vX.Y.Z` |
| Python | `python/`（`pyproject.toml` のルート）、実体は `python/src/san_db_ox/` | 配布名（PyPI）`san-db-ox-client`、import 名 `san_db_ox`（第2段表記。ハイフンは Python の識別子に使えない） | `python/vX.Y.Z` |
| TypeScript | `typescript/`（`package.json` のルート）、実体は `typescript/src/` | npm パッケージ `@amisonnet8/san-db-ox-client`（スコープ付き。PyPI で `san-db-ox`→`sandbox` の正規化により衝突した問題圏に、スコープを切ることで最初から入らない） | `typescript/vX.Y.Z` |

**`go/` を直接パッケージにしない。** import パス末尾が `go` になると
呼び出し側のコードで名前が推測できず、しかも `go` は Go の予約語でもある。
1段掘って `go/sandbox/` を実パッケージにすることで、利用側は
`import "github.com/amisonnet8/san-db-ox-clients/go/sandbox"` の上で
`sandbox.Open(...)` のように自然に書ける。

サブディレクトリモジュールの Go のタグ規則にも従う——`go/` 配下のモジュール
に対するリリースタグは `go/vX.Y.Z` の形式にする（プレフィックス無しの
`vX.Y.Z` ではモジュールパスと対応が取れない）。詳細は `distribution.md`。

## 環境変数

本体の第2段表記（`_` 区切り）を踏襲する。本体バイナリのパスを上書きする
環境変数は `SAN_DB_OX_BIN`（`testing.md` 参照）。
