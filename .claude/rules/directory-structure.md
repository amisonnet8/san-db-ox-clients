# ディレクトリ構成

```
san-db-ox-clients/
├── CLAUDE.md
├── PLAN.md
├── Makefile
├── LICENSE                （MIT）
├── .gitattributes         （* text=auto eol=lf）
├── .gitignore
├── README.md / README_ja.md
├── .devcontainer/
├── .vscode/
├── .claude/
│   ├── settings.json
│   └── rules/
├── conformance/           ← 言語非依存のプロトコル適合テスト
├── scripts/               ← 全言語共有のスクリプト（本体バイナリ取得等）
├── docs/                  ← 利用者向けドキュメント
├── go/                    ← Go ドライバ
├── python/                ← （将来）Python ドライバ
├── typescript/            ← （将来）TypeScript ドライバ
└── .github/workflows/
```

## 配置の判断基準

- **トップレベルは言語ごとに1ディレクトリ。** `go/`、以後 `python/`・
  `typescript/`。言語をまたぐ共有物は `conformance/`・`scripts/`・`docs/`
  のみに限る。
- **言語ディレクトリ同士は互いに依存しない。** ある言語のツールチェーンが
  入っていなくても、他の言語のテストが独立して通ること。CI のジョブを
  言語ごとに分けられる状態を保つ。
- 各言語ディレクトリの内部構成は `architecture.md` の2層構造に対応させる。
  Go であれば、コーデックを担うパッケージとトランスポートを担うパッケージを
  分ける。
- **本体リポジトリをこのリポジトリ配下に clone して併用しない。** 本体
  CLAUDE.md が「`san-db-ox-clients` を本体リポジトリ配下に clone して
  併用する運用は行わない」と定めているのと対称の方針。本体は
  `distribution.md` に従って Releases のバイナリとして入手する。

## `conformance/` と `docs/` の役割分担

- **`conformance/`**: 各言語のテストが読み込む、言語非依存のケース定義
  （JSON）。CI が自動実行する検証専用。
- **`docs/`**: 人間が読むための接続方法・使い方のドキュメント。特に
  `connectivity.md` の実例（SSH・socat・TLS 等のコマンド例）はこちらに書く。
  本体の `docs/usage/`（索引的なリファレンス）と同じ位置づけ。

## 別プロジェクトとの関係

本体（`san-db-ox`）は、このリポジトリを clone して併用しない前提で
書かれている（本体 CLAUDE.md 参照）。こちらも同様に、本体を submodule
化したり配下に clone したりしない。本体との唯一の結合点は、プロトコルの
仕様（`protocol.md`）と、実行時に子プロセスとして起動するバイナリ
（`testing.md` の入手方法）である。
