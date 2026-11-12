# SushiEricServerManager

SushiEricのサーバーデータを編集・管理するデスクトップアプリです。アイテム・鉱石・ショップ商品・レシピの編集、サーバーの監視や履歴の閲覧、戦闘シミュレーターを利用できます。

## アプリを使う方へ

### ダウンロードと起動

[最新版のRelease](https://github.com/SushiEricWorkspace/SushiEricServerManager/releases/latest)から、自分のOSに合うインストーラーをダウンロードしてください。1.0.0の配布物は次の形式です。

| OS | 配布物 |
| --- | --- |
| Windows | `SushiEricServerManager-1.0.0-Windows-Installer.exe` |
| macOS（Apple Silicon / arm64） | `SushiEricServerManager-1.0.0-macOS-arm64-Installer.dmg` |

Windowsはexeを実行してインストールし、スタートメニューまたはショートカットから起動します。macOSはdmgを開いてアプリを配置し、起動します。Javaランタイムは配布物に含まれるため、利用者によるJDKや開発用依存の準備は不要です。Intel Mac向け・Linux向けの配布物はありません。

現在の配布物は署名・公証を行っていないため、OSの警告や制限が表示される場合があります。

起動後に動作モードを選びます。

- **オフライン**：サーバーに接続せず、このPC内のデータを作成・編集します。初めて画面を試す場合もこちらを利用できます。
- **オンライン**：接続するサーバーのプロファイルを設定・選択して利用します。サーバー側の接続環境と権限が必要です。

同じデータ領域での多重起動はできません。起動し直す場合は、開いているManagerを通常終了してください。

### 主な操作

- Homeから各エディターを開き、一覧でデータを選びます。編集内容の保存はエディターの「ファイル」メニューから行います。
- オンラインではDashboard・Server Control・コンソール、入出金・実績・ショップ売買履歴を利用できます。接続先の対応状況によって利用できる機能や書き込みが制限されます。
- Homeの「戦闘シミュレーター」では、装備・技能・現在のHPなどを指定し、攻撃ごとの結果と合計を確認できます。平均ダメージと1回の抽選結果を切り替え、条件を変える前後の結果を比較できます。
- シミュレーターの保存済み装備は名前・公開IDで検索し、「選択を適用・再読込」で反映します。「なし」は選択した部位を未装備にします。編集中のアイテムはItem画面の「シミュレーターへ取込」から渡せます。計算のためのサーバー接続・保存・アップロードは行いません。

シミュレーターの対応範囲・入力・結果の読み方は[画面ガイド](docs/simulator-screen.md)を参照してください。

### 更新とデータの保護

オンラインモードの起動時に最新版を確認します。オフラインモードでは確認しません。配布アプリは画面の案内に従って更新できます。手動で更新する場合は[Release](https://github.com/SushiEricWorkspace/SushiEricServerManager/releases/latest)を利用してください。

設定・オフラインデータ・自動保存などは次のディレクトリに保存されます。大切なデータはバックアップしてください。

| OS | データ領域 |
| --- | --- |
| Windows | `%LOCALAPPDATA%\SushiEricServerManager` |
| macOS | `~/Library/Application Support/SushiEricServerManager` |

Windowsの現在のインストール先は、データ領域内の`app`ディレクトリです。**データ領域直下へインストールした旧版を手動更新する場合は、データを失わないよう、自動更新を使うか事前にバックアップしてください。** 詳細は[更新ガイドのデータ保護](docs/update/update-guide.md#データの保護windows)を参照してください。

## 開発する方へ

### 必要な環境と依存の準備

- JDK 21と同梱のGradle Wrapperを使用します。GUIの起動対象はWindows/macOSです。
- 固定依存の準備にはPython 3.12を使用します。
- DataModel・CombatCore・組織の`.github`リポジトリへの参照権限が必要です。Managerは単独clone直後の`run`では開発用依存を解決できません。

まず[worktree管理ガイド](https://github.com/SushiEricWorkspace/.github/blob/main/scripts/dev_server/worktrees-guide.md)に従い、Managerを変更対象、DataModel・CombatCore・`.github`を固定commitの依存として登録したtaskを用意します。各リポジトリの作業用ファイルとbuild出力は分離します。

そのtask内のManagerから固定依存を明示的に準備します。次はWindows PowerShellの例です。`<...>`は登録したtask・owner・保存先の実際の値へ置き換えてください。

```powershell
py -3.12 -B scripts/prepare-development.py --task-root "<taskの絶対パス>" --owner "<登録owner>" --repository "<Mavenの絶対パス>"
```

成功すると、このManager checkoutにGit対象外の`.manager-development.properties`が生成されます。依存の完全座標とhashを固定する設定で、毎起動の自動発行や最新版の自動選択は行いません。必要な依存・準備の出力・再準備・明示入力は[固定開発依存とManager起動](docs/development-combat-core.md)を参照してください。

### IDE・実行・検証

生成設定のあるManagerをIntelliJ IDEAなどでGradleプロジェクトとして開き、再同期します。IDEと通常のGradle起動は同じ固定依存設定を使います。

```powershell
.\gradlew.bat run
.\gradlew.bat test build verifyReleaseDistribution
```

macOSでは準備コマンドの`py -3.12`を`python3.12`へ、Gradleコマンドの`.\gradlew.bat`を`./gradlew`へ置き換えます。実行前にJDK 21を使用する環境を用意してください。

`verifyReleaseDistribution`は通常配布の依存・内容を検査し、`recordCombatCoreDependency`が実際の依存情報を`build/reports/combat-core-dependencies.properties`へ記録します。準備スクリプトの検証方法も[開発ガイド](docs/development-combat-core.md#検証)に記載しています。

管理テスト環境へ接続する開発専用Managerは`development`サブプロジェクトです。通常アプリとは入口・配布を分離しています。必要な場合だけ[管理接続ガイド](docs/managed/connection-guide.md)に従って利用してください。

### 配布・リリース

アプリの版は`gradle.properties`の`appVersion`で管理します。正式なDataModel/Core、対応OS上のjpackage、成果物名・更新情報の整合が必要です。[リリースガイド](docs/update/release-guide.md)を正本として、正式依存の指定からインストーラー作成・Release添付まで確認してください。