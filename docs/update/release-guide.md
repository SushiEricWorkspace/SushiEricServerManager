# リリースガイド

Managerの版の上げ方と、GitHub Releasesへ置く成果物の作り方をまとめる。リリースを作るとき、版に関わる設定を変更するときに読む。
自動更新が成果物を選ぶ仕組みと設計は[更新ガイド](update-guide.md)を参照する。

## 版の管理

アプリの版は`gradle.properties`の`appVersion`の1か所だけに書く。`X.Y.Z`（数字3つ）の形式でなければ、ビルドは失敗する。

次の値は`appVersion`から作られる。手で書かない。

| 値 | 作られ方 |
|---|---|
| `AppVersion.CURRENT` | ビルド時に`generateAppVersion`タスクが`build/generated/source/appVersion/`へ生成する。ソースには含めない |
| 成果物の名前 | `SushiEricServerManager-<appVersion>-Windows-Installer.exe`と`SushiEricServerManager-<appVersion>-macOS-Installer.dmg` |
| jpackageの版 | `appVersion`の先頭の数字に1を足した値。`0.2.2`なら`1.2.2` |

jpackageの版で先頭に1を足すのは、macOSのjpackageが先頭の数字に0を許さないためである。常に1を足すことで、`appVersion`が`1.0.0`以降になってもパッケージの版が減らない。

版を上げるときは、`appVersion`だけを変更する。

## 成果物の作成

成果物は、対応するOS上で作る。Windows用はWindows、macOS用はMacで実行する。
どちらも、正式版のCommonを使うため`-PcommonReleaseVersion=<version>`（`v`を付けない）が必要である。

| OS | コマンド |
|---|---|
| Windows | `./gradlew releaseWindowsInstaller -PcommonReleaseVersion=<version>` |
| macOS | `./gradlew releaseMacDmg -PcommonReleaseVersion=<version>` |

出力先は`build/release-installer/`で、ファイル名は上の表の名前になる。

## Releaseの作成

1. `appVersion`を上げたコミットをマージする。
2. タグ`v<appVersion>`のReleaseを作る。
3. 成果物（WindowsのexeとmacOSのdmg）を添付する。成果物のSHA-256は、GitHubが付ける`digest`を自動更新が検証に使う。
4. 変更内容をReleaseの本文に書く。自動更新はこの本文を更新時に表示する。

成果物の名前は、自動更新がOSごとに成果物を選ぶ規則である。名前を変えない。

## 移行用のupdate.json

0.2.2は、最新のReleaseの`update.json`を取得できないと起動を中止する。そのため、新しい更新の仕組みを含む**最初のRelease**には、`update.json`も添付する。以降のReleaseには添付しない。

1. 上のとおり成果物を作る。
2. 次のコマンドで移行用の`update.json`を作る。

   ```text
   ./gradlew generateTransitionalUpdateJson -PupdateNotes="変更内容1|変更内容2"
   ```

   `-PupdateNotes`は省略できる。複数の変更内容は`|`で区切る。
3. `build/release-installer/update.json`を、Releaseの成果物として添付する。

生成される`update.json`は、`appVersion`の版と、そのReleaseの成果物へのURLを含む。URLはタグ`v<appVersion>`と、上の成果物の名前から作られる。そのため、タグと成果物の名前が違うと、0.2.2が正しい成果物を開けない。

添付したあとは、次を確認する。

- `https://github.com/SushiEricWorkspace/SushiEricServerManager/releases/latest/download/update.json`が取得できる。
- `update.json`の2つのURLが、Releaseの成果物に対応している。

## インストーラーの手動実行の注意

WindowsのインストーラーをUpdaterを使わずに手動で実行して上書きすると、インストール先と同じフォルダにあるManagerの設定とデータ（`config.json`、`profiles.json`、`ssh`、`offline`、`autosave`など）が削除される。詳しくは[更新ガイド](update-guide.md#データの保護)を参照する。
検証などで手動実行する前には、`%LOCALAPPDATA%\SushiEricServerManager`のデータをバックアップする。Updaterによる更新では、データは自動で退避・復元される。
