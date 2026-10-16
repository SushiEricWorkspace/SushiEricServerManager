# リリースガイド

Managerの版の上げ方と、GitHub Releasesへ置く成果物の作り方をまとめる。リリースを作るとき、版に関わる設定を変更するときに読む。
自動更新が成果物を選ぶ仕組みと設計は[更新ガイド](update-guide.md)を参照する。

## 版の管理

アプリの版は`gradle.properties`の`appVersion`の1か所だけに書く。`X.Y.Z`（数字3つ）の形式でなければ、ビルドは失敗する。

次の値は`appVersion`から作られる。手で書かない。

| 値 | 作られ方 |
|---|---|
| `AppVersion.CURRENT` | ビルド時に`generateAppVersion`タスクが`build/generated/source/appVersion/`へ生成する。ソースには含めない |
| 成果物の名前 | `SushiEricServerManager-<appVersion>-Windows-Installer.exe`と`SushiEricServerManager-<appVersion>-macOS-arm64-Installer.dmg` |
| jpackageの版 | `appVersion`の先頭の数字に1を足した値。`0.2.2`なら`1.2.2` |

jpackageの版で先頭に1を足すのは、macOSのjpackageが先頭の数字に0を許さないためである。常に1を足すことで、`appVersion`が`1.0.0`以降になってもパッケージの版が減らない。

版を上げるときは、`appVersion`だけを変更する。

## 成果物の作成

成果物は、対応するOS上で作る。Windows用はWindows、macOS用はMacで実行する。
どちらも、正式版のSushiEricDataModelとCombatCoreを使うため、両方の正式版と各source commitを指定する。正式版のCoreは同じDataModel正式版へ依存している必要がある。

| OS | コマンド |
|---|---|
| Windows | `./gradlew releaseWindowsInstaller -PdataModelReleaseVersion=<DataModel版> -PcombatCoreReleaseVersion=<Core版> -PcombatCoreDataModelCoordinate=io.github.sushiericworkspace:sushieric-datamodel:<DataModel版> -PdataModelSourceCommit=<DataModel完全commit> -PcombatCoreSourceCommit=<Core完全commit>` |
| macOS | `./gradlew releaseMacDmg -PdataModelReleaseVersion=<DataModel版> -PcombatCoreReleaseVersion=<Core版> -PcombatCoreDataModelCoordinate=io.github.sushiericworkspace:sushieric-datamodel:<DataModel版> -PdataModelSourceCommit=<DataModel完全commit> -PcombatCoreSourceCommit=<Core完全commit>` |

タスクworktreeなどSushiEricDataModelの正式artifactが兄弟配置にない環境では、
`-PdataModelReleaseRepository=<Mavenリポジトリの絶対パス>`で保存先を指定する。

出力先は`build/release-installer/`で、ファイル名は上の表の名前になる。

## GitHub Actionsでの成果物の作成

別のOSの機械を使わずに作るために、GitHub Actionsのworkflow（`.github/workflows/build-installer.yml`）がある。
手動実行（`workflow_dispatch`）だけで動き、push、pull_request、scheduleでは動かない。**人間の明示的な許可なく実行しない**（AIも同様。[AGENTS.md](../../AGENTS.md)を参照する）。

準備として、非公開repoを読み取れるトークンをリポジトリのシークレット`DATA_MODEL_REPO_TOKEN`と`COMBAT_CORE_REPO_TOKEN`へそれぞれ登録する。未登録の場合、workflowはビルドの前に失敗する。

| 入力 | 内容 |
|---|---|
| `os` | 作成するOS。`windows`、`macos`、`both`から選ぶ。既定は`windows` |
| `data_model_ref` | ビルドに使うSushiEricDataModelのブランチまたはタグ。既定は`develop` |
| `data_model_release_version` | SushiEricDataModelの正式版のバージョン（`X.Y.Z`、`v`なし）。正式版を作る場合はCombatCoreと同時に指定 |
| `combat_core_ref` | ビルドに使うCombatCoreのブランチまたはタグ。既定は`develop` |
| `combat_core_release_version` | CombatCoreの正式版のバージョン（`X.Y.Z`、`v`なし）。正式版を作る場合はDataModelと同時に指定 |

両方の正式版を空欄にすると、同じDataModel版を参照する開発DataModel/Core artifactを発行して開発配布を作る。workflowは各source commitとartifactの固定座標をManagerへ渡す。

- 成果物は、実行結果のartifact（`installer-windows`、`installer-macos-arm64`）として3日間保持される。Releaseへの添付は、手動で行う。
- macOSの成果物は、`macos-latest`（Apple Silicon）で作るため、arm64専用である。
- 無料枠を使い切らないよう、必要なOSだけを選ぶ。各ジョブは30分でタイムアウトし、同時に1つしか実行しない。このリポジトリは公開のため、標準のランナーの利用は無料だが、非公開にするとmacOSはLinuxの10倍、Windowsは2倍の分数を消費する。

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

Windowsのインストール先は`%LOCALAPPDATA%\SushiEricServerManager\app`、データ領域はその親である。この配置同士の上書きインストールではデータは保持される。
データ領域直下にインストールされた版から移行する場合は、Updaterを使用するか、事前にデータを別領域へバックアップして完了後に復元する。手動更新ではデータが削除される可能性がある。詳しくは[更新ガイド](update-guide.md#データの保護windows)を参照する。
