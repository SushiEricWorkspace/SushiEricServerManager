# 更新ガイド

Managerの自動更新と、古いManagerからサーバーのデータを守る仕組みの設計をまとめる。更新処理や互換ファイルを実装・変更するときに読む。
ここに書く内容は設計であり、実装は[実装の分割](#実装の分割)の各Issueで行う。実装が入った箇所は、実装に合わせてこのドキュメントを更新する。

## 更新の流れ

```text
起動 → 最新版の確認 → 成果物のダウンロード → SHA-256の検証
     → Updaterの起動 → Managerの終了 → Updaterが置換 → Managerの再起動
```

- オンラインモードの起動時だけ更新を確認する。オフラインモードは確認しない。
- 確認に失敗した場合（インターネット未接続、GitHubの障害、タイムアウトなど）は、ログを残してそのまま起動する。更新が確認できないことを理由に起動を止めない。
- 確認の待ち時間は5秒とする。
- 現在の版以下の版は更新対象にしない。版の比較は、ドット区切りの数値を先頭から順に比べる。

## 最新版の確認とダウンロード

`update.json`は使わない。GitHubのReleases APIの最新のRelease（`GET /repos/SushiEricWorkspace/SushiEricServerManager/releases/latest`）から、次を取得する。

| 項目 | 用途 |
|---|---|
| `tag_name`（`vX.Y.Z`） | 最新版。先頭の`v`を除いて比較する |
| `assets[].name` | OSごとの成果物の選択 |
| `assets[].browser_download_url` | ダウンロード先 |
| `assets[].digest`（`sha256:…`） | SHA-256の検証 |
| `body` | 変更内容の表示 |

### 成果物の名前

成果物は次の名前に統一する。Managerはこの名前でOSごとの成果物を選ぶ。

| OS | 名前 |
|---|---|
| Windows | `SushiEricServerManager-<版>-Windows-Installer.exe` |
| macOS | `SushiEricServerManager-<版>-macOS-Installer.dmg` |

### ダウンロードと検証

- 保存先は、アプリのデータ領域の`updates/<版>/`とする。
- 進捗は、受信したバイト数と`Content-Length`から求めて画面に表示する。
- ダウンロード後にSHA-256を計算し、`digest`と比べる。一致しない場合はファイルを削除し、エラーを表示して更新を中止する。起動は続ける。
- 対応する成果物がない、または`digest`がないRelease（検証できない成果物）は自動更新しない。ダウンロードページを開く案内だけを表示する。

### 更新のUI

1. 更新を見つけたら、現在の版、最新版、変更内容を表示する。
2. ダウンロードの進捗と、検証中であることを表示する。
3. 検証が終わったら「再起動して更新」を表示する。

「後で」を選べるのは、サーバーの互換ファイルが求める最小版を満たしている場合だけである。満たしていない場合は、更新を必須にする（[サーバー側の防壁](#サーバー側の防壁)を参照）。

## Updater

Updaterは、ManagerがOSごとのスクリプトを一時ディレクトリに生成して起動する。Javaは使わない。置換対象のJavaランタイムをUpdater自身が使うと、Windowsではファイルのロックで置換できなくなるためである。

共通の手順は次のとおり。

1. Managerのプロセスが終了するまで待つ。単一起動のロックの解放も、この終了で行われる。
2. 成果物を使って旧版を置換する。
3. 結果を`update-result.json`へ書く。
4. 新しい版のManagerを起動する。

| OS | スクリプト | 置換の方法 |
|---|---|---|
| Windows | PowerShell | ダウンロードしたexeを静かに実行し、同じ場所へ上書きインストールする |
| macOS | sh | dmgをマウントして`.app`を現在の場所へコピーし、アンマウントする |

- 置換に失敗した場合は、旧版のままManagerを起動する。次回起動時に`update-result.json`を読み、失敗したことと原因を表示する。
- Javaでダウンロードしたファイルにはmacの隔離属性が付かないため、Gatekeeperには止められない。
- Windowsのインストーラーはユーザー単位でインストールするため、管理者権限は不要である。
- exeを静かに実行する引数は、実装前に実機で確認する。

## 版の管理

- アプリの版は`gradle.properties`の`appVersion`の1か所だけに書く。
- `AppVersion.CURRENT`は、この値からビルド時に生成する。
- jpackageに渡す版（macOSでは先頭を1以上にする必要がある）は、`appVersion`から導出する。
- 成果物名は[成果物の名前](#成果物の名前)に従う。

## サーバー側の防壁

目的は、古いManagerが、データ形式が変わったサーバーのデータをうっかり書き換えることを防ぐことである。悪意のある利用者は想定しない。

ManagerのファイルはSFTPで書き込むため、Management APIのトークンでは止められない。また、Management APIはサーバーの起動が必要であり、停止中のサーバーのデータも編集できなくなる。そのため、防壁は**サーバーのデータ領域に置く互換ファイル**だけで行う。

### 互換ファイル

Modが起動時に、Modの設定ディレクトリ（`config/SushiEricServerMod/`）へ`manager-compat.json`を書き出す。パスの定義は、Commonの`SushiEricDataDirectory`に追加する。

```json
{
  "minManagerVersion": "0.3.0",
  "modVersion": "0.5.0"
}
```

- `minManagerVersion`: このModのデータを書き込める、Managerの最小の版。
- `modVersion`: ファイルを書き出したModの版。

Modは、`minManagerVersion`を自分のコードの定数として持つ。データ形式を変更して古いManagerでは壊れる場合に、この定数を上げる。

### Managerの動作

Managerは、SFTPで接続したあと、書き込みを行う前に`manager-compat.json`を読む。

| 状態 | 動作 |
|---|---|
| 自分の版が`minManagerVersion`以上 | 通常どおり書き込める |
| 自分の版が`minManagerVersion`未満 | 読み取り専用モードにする。「更新が必要です」を表示し、更新を必須にする |
| ファイルがない | 判断できないため、書き込める。互換ファイルがないことを画面に表示する |
| ファイルを読めない、または解析できない | 書き込める。ログに警告を残し、画面に表示する |

読み取り専用モードでは、閲覧はできるが、データの保存、削除、名前の変更、アップロードはできない。

### 限界

- 互換ファイルを知らない古いManagerは止められない。防壁は、この仕組みを持つ版以降のManagerだけに働く。
- データ形式の版を上げたときは、Modが起動時に未知の形式を検出してログに残し、読み込まないようにする。これはサーバーの実行を守るためであり、ファイルの書き込み自体は止められない。
- SSHユーザーを読み取り専用にして、書き込みをModへの申請に限る方法は確実だが、サーバー停止中に編集できなくなるため採用しない。必要になった場合は、別のIssueとして検討する。

## 0.2.2以前からの移行

0.2.2は、`update.json`を取得できない場合に起動を中止する。`update.json`をすぐに廃止すると、0.2.2の利用者が起動できなくなる。

- 新しい更新の仕組みを含む最初のReleaseには、`update.json`も1回だけ添付する。内容は、そのReleaseの成果物へのURLである。
- 以降のReleaseでは、`update.json`を添付しない。

## 実装の分割

設計に基づき、次のIssueに分けて実装する。

| Issue | 内容 |
|---|---|
| SushiEricServerManager#167 | 版の一元化と成果物名の統一（`update.json`の移行用の添付を含む） |
| SushiEricServerManager#168 | 更新確認とダウンロード（Releases API、SHA-256、進捗UI、失敗時の継続） |
| SushiEricServerManager#169 | Updater（Windows） |
| SushiEricServerManager#170 | Updater（macOS） |
| Common#88 | 互換ファイル`manager-compat.json`のパス定義 |
| SushiEricServerMod#504 | 起動時の互換ファイルの書き出し |
| SushiEricServerManager#171 | 互換ファイルの確認と、読み取り専用モード |
