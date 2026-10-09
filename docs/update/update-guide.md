# 更新ガイド

Managerの自動更新と、古いManagerからサーバーのデータを守る仕組みの設計をまとめる。更新処理や互換ファイルを実装・変更するときに読む。
ここに書く内容は設計であり、実装は[実装の分割](#実装の分割)の各Issueで行う。実装が入った箇所は、実装に合わせてこのドキュメントを更新する。
実装済みの範囲は、最新版の確認、ダウンロード、SHA-256の検証、進捗の表示と、Windowsでの置換と再起動である。macOSのUpdaterと互換ファイルは未実装である。
ソースコードは`src/main/kotlin/io/github/sushiericworkspace/sushiericservermanager/`を基準にした相対パスで示す。

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

### 確認の結果

`update/UpdateChecker.kt`の`UpdateChecker.check()`が、最新のReleaseと実行中の版（`AppVersion.CURRENT`）を比べて、次のいずれかを返す。
最新のReleaseの取得は`update/ReleaseSource.kt`の`GitHubReleaseSource`が行う。

| 結果 | 条件 | 動作 |
|---|---|---|
| `UpToDate` | 最新のReleaseの版が、実行中の版以下 | 何も表示せず起動する |
| `Automatic` | 新しい版があり、OSの成果物があり、`digest`が`sha256:`と16進数64桁の形式 | ダウンロードと検証ができる |
| `Manual` | 新しい版はあるが、OSの成果物がない、`digest`が使えない、または自動更新に対応しないOS（Windows、macOS以外） | ダウンロードページを案内する |

タグが`vX.Y.Z`形式でない場合は、確認の失敗として扱う。

### ダウンロードと検証

`update/UpdateDownloader.kt`の`UpdateDownloader`が行う。

- 保存先は、アプリのデータ領域の`updates/<版>/<成果物名>`である（`config/FilePath.kt`の`UPDATES_DIR`）。
- ダウンロード中は`<成果物名>.part`へ書き、SHA-256の検証に成功した場合だけ成果物の名前へ変更する。
- 進捗は、受信したバイト数と、Releaseに記録された成果物のサイズから求める。
- SHA-256が一致しない場合は、ファイルを削除して`ChecksumMismatchException`を送出する。画面にエラーを表示して更新を中止し、起動は続ける。
- 保存先に検証済みの成果物が既にある場合は、再ダウンロードせずにそのファイルを使う。壊れている場合は、ダウンロードし直して置き換える。
- 通信が途中で失敗した場合と、中止した場合は、`.part`を残さない。

### 起動の流れ

`app/ApplicationFlow.kt`の`prepareOnline`が、オンラインモードの起動時に`app/StartupCoordinator.kt`で更新を確認する。

- 確認に失敗した場合は、警告のログを残して、そのまま起動する。
- 更新があった場合は、更新のダイアログ（`ui/dialog/UpdateDialog.kt`）を表示し、閉じられたあとに通常の起動を続ける。

### 更新のUI

`ui/dialog/UpdateDialog.kt`の`UpdateDialog`が表示する。

1. 更新を見つけたら、現在の版、最新版、変更内容（Releaseの本文）を表示する。
2. `Automatic`の場合は「ダウンロード」を押すと、進捗バーでダウンロードの進捗を表示し、続けてSHA-256を検証する。ダウンロード中は「中止」を押せる。
3. 完了したら、検証に成功したことと、成果物の保存先を表示する。「閉じる」で通常の起動を続ける。
4. `Manual`の場合は、自動更新できない理由とリリースページへのリンクを表示する。

検証が終わったあと、Updaterを使える環境（`update/Updater.kt`の`Updaters.forCurrentOs()`がnullでない場合）では、「再起動して更新」を表示する。
押すとUpdaterを起動してManagerを終了する。使えない環境では、完了メッセージを表示し、そのまま起動する。
現在のUpdaterはWindowsのインストールされたアプリだけで使える。macOSと、`gradlew run`などの開発中の実行では使えない。
サーバーの互換ファイルが求める最小版を満たしていない場合に、更新を必須にする制御（「後で」を選べない）も未実装で、現在は常に閉じて起動を続けられる。

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

### Windows

実装は`update/WindowsUpdater.kt`と、スクリプト`resources/updater/update-windows.ps1`である。

- ManagerがスクリプトをBOM付きのUTF-8で一時ディレクトリへ書き出し、`powershell.exe`で起動する。値（インストーラーのパス、プロセスID、データのディレクトリなど）は、スクリプトへ埋め込まず、引数として渡す。
- インストールされたManagerかどうかは、jpackageの起動ファイルが設定するシステムプロパティ`jpackage.app-path`で判断する。起動ファイルは再起動にも使う。
- jpackageのexeは、引数を`msiexec`へ渡す。`/qn`を指定すると、画面を出さずに上書きインストールする。終了コード0、1641、3010を成功として扱う。
- 上書きインストールが成功するには、パッケージの版が上がっている必要がある。同じ版では上書きされない（[リリースガイド](release-guide.md#版の管理)の、`appVersion`とパッケージの版の関係を参照する）。

### データの保護

jpackageのMSIは、上書き更新のときに**インストール先のフォルダ全体を削除する**。Managerの設定とデータ（`config.json`、`profiles.json`、`ssh`、`offline`、`autosave`など）は、インストール先と同じフォルダ（Windowsでは`%LOCALAPPDATA%\SushiEricServerManager`）にあるため、そのままでは更新のたびに消える。
これは、Updaterを使わずにインストーラーを手動で実行した場合も同じである。

Updaterは、次のようにデータを守る。

1. インストールの前に、`app`、`runtime`、`updates`、`lock`、起動ファイル以外のすべてを、一時ディレクトリへ退避する。
2. インストールの成功と失敗のどちらでも、インストールのあとで退避したデータを戻す。戻したあとに、退避した各項目が存在することを確認する。
3. 復元に失敗した場合は、退避したデータを削除せず、結果の`message`に退避先を書く。更新の結果は失敗として扱う。

インストール先をデータのフォルダと分ければ、この退避は不要になる。分ける場合は、既存の利用者の移行を別途検討する。

### 更新の結果

Updaterは、`update-result.json`をデータのディレクトリへ書く（復元のあとに書く）。`update/UpdateResult.kt`の`UpdateResult`と同じ形式である。

| 項目 | 内容 |
|---|---|
| `success` | 更新に成功し、データも復元できた場合は`true` |
| `version` | 更新しようとした版 |
| `exitCode` | インストーラーの終了コード。実行できなかった場合は`null` |
| `message` | 失敗した場合の原因。成功した場合は`null` |
| `timestamp` | 結果を書いた時刻（ISO-8601） |

Managerは、起動のたびに結果を読み、あれば成功と失敗をダイアログで表示して、ファイルを削除する（`app/MainApp.kt`の`reportPreviousUpdate`）。
あわせて、実行中の版以下のダウンロード済みの成果物（`updates/<版>/`）を削除する。

## 版の管理

- アプリの版は`gradle.properties`の`appVersion`の1か所だけに書く。
- `AppVersion.CURRENT`は、この値からビルド時に生成する。
- jpackageに渡す版（macOSでは先頭を1以上にする必要がある）は、`appVersion`から導出する。
- 成果物名は[成果物の名前](#成果物の名前)に従う。
- 版の上げ方と成果物の作り方は[リリースガイド](release-guide.md)を参照する。

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
- 生成と添付の手順は、[リリースガイド](release-guide.md#移行用のupdatejson)を参照する。

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
