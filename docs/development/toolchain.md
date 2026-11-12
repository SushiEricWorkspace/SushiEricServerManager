# 開発環境

Managerの本体と`development`は、JDK 25、JavaFX 27、Gradle 9.3.0、Kotlin JVM/serialization 2.3.21を使用します。
Java・Kotlinの出力は25で揃え、Gradle daemonは`gradle/gradle-daemon-jvm.properties`で25へ固定しています。
JDK 25と、依存SushiEricDataModelのビルドに使用するJDK 21をインストールしてください。
SushiEricDataModelとModのJava 21互換性は維持します。

## タスクworktreeでの依存と検証

固定commitのSushiEricDataModelを同じタスクへdetached HEADで登録し、タスク専用Mavenへ`publishDevelopment`します。
発行された`build/development-publication.properties`の`editor`座標と、対象JAR・POMのSHA-256を記録してください。
共有Mavenや他タスクの生成物は使用しません。同じタスクのGradle実行は管理スクリプトのOS lockで直列化します。

```text
gradlew build :development:build installDist :development:installDist verifyReleaseDistribution -PdataModelDevelopmentRepository=<タスク専用Maven絶対パス> -PdataModelDevelopmentCoordinate=io.github.sushiericworkspace:sushieric-datamodel-editor-dev:<固定dev版>
```

通常配布の`build/install/SushiEricServerManager/lib`へ開発モジュールが混入していないことを`verifyReleaseDistribution`で確認します。
開発配布は`development/build/install/SushiEricServerManager-Development`です。
JavaFXのFXMLロードと既存Editorの主要操作は関連テストで確認し、Windows・macOSそれぞれの実アプリ起動も別に記録します。
実機・遠隔操作の許可を得て、専用appdataで確認してください。管理接続の合成試験と実Mod接続の検証条件は[接続ガイド](../managed/connection-guide.md#自動検証と実機の分離)を参照します。

## パッケージとCI

インストーラー作成の手動workflowはJDK 21と25を導入し、DataModelのdaemonを21、Managerのdaemonとjpackageを25にします。
WindowsはWiX 3.14を使用します。`prepareWindowsInstallerResources`は、実行中JDKのWiX 3テンプレート`main.wxs`に空のデータ親ディレクトリの削除定義を補います。
対応する`ComponentGroupRef`とディレクトリIDの生成規則はJDKの`WixAppImageFragmentBuilder`と一致する必要があります。
パッケージ作成・workflowの実行は依頼された場合だけ行います。[リリースガイド](../update/release-guide.md)を参照してください。

## Windows検証結果（2026-10-18）

Windows 11 x64、Temurin 25.0.4+7で本体・developmentのbuild/test、両installDist、通常配布検査、WiXテンプレート補正を確認しました。
本体620件は609成功・11skip、development45件は44成功・実Mod1skipです。Python bridge24件も成功しました。
本体のskipはmacOS固有8件、別名パスのOS限定1件、nativeRobot未指定1件、実SFTP未指定1件です。
生成classは本体850件・development44件が全てmajor 69、固定DataModelはmajor 65です。

固定依存はSushiEricDataModelの`93220ce834ab7957f9d480eaec27cfb76ee1052d`です。
タスク専用Mavenへ発行したEditor座標は次のとおりです。

```text
io.github.sushiericworkspace:sushieric-datamodel-editor-dev:0.1.0-dev.20261018011758878.2c151f03-5cb1-412a-8614-596f2ab30b5c
JAR SHA-256: 42c43a2cb700dac7f2fde594e94ff5eb8c3d1cada565de740f89b8a52f1d7cb3
POM SHA-256: c9bb68faa24307328ddf49bf848c76ffd05e24ce76fd7373414f490b944f981d
```

通常版・開発版を専用appdataで順次起動し、既存FXMLの動作モード選択・オフラインHOME・鉱石Editorを実表示しました。
通常版で確認用鉱石を作成し、硬度12.5の編集・差分保存・Editor再生成後の再読込・終了を確認しました。
開発版で同じタスク専用データを読み、硬度27.0へ変更して差分保存しました。
前半の画面・編集操作はcomputer-use、開発版の再読込・終了は検証用Java agentから`Platform.runLater`、`Button.fire`、`CloseRequest`を使いました。
自動経路では実Stageの値27.0、Editor再生成後の再読込、終了コード0を確認しています。製品APIやソースへ検証用の変更は追加していません。

実SFTP・実Mod接続、jpackage包装、workflow実行は未実施です。macOSの結果は次節に記載します。

## macOS検証結果（2026-10-18）

macOS 15.7.7 arm64、OpenJDK 25.0.1で本体・developmentのbuild/test、両installDist、通常配布検査が成功しました。
本体620件は598成功・22skip、development45件は44成功・実Mod1skipです。Python bridge24件も成功しました。
本体のskipは既存のWindows限定21件と実SFTP未指定1件です。Gradle daemonの実ログはjavaVersion=25、生成classは両プロジェクトでmajor 69でした。

同じ固定commitのDataModelをMacのタスク専用MavenへJava 21のまま発行しました。

```text
io.github.sushiericworkspace:sushieric-datamodel-editor-dev:0.1.0-dev.20261018014938277.53b4a9f4-b7ee-4e15-af84-eaa384745126
JAR SHA-256: 6a4ba3b972570a9f76b29e85d2de59d964971291a1a346b490e2c5560f69fc70
POM SHA-256: 84215e2b2a21e46c773ae988821b4c7b81eb5faefb60730cb6c753fd68d88c0b
```

Mac生成の通常・開発installDistをそれぞれ専用user.homeで起動し、検証用Java agentからJavaFXの実Stageを自動操作しました。
`Platform.runLater`、既存Sceneのショートカット処理、`Button.fire`、`CloseRequest`を使い、起動FXMLとオフラインHOME、新規鉱石作成、硬度25.27（通常版）・27.25（開発版）の編集・差分保存、Editor再生成後の再読込を確認しています。
アイテム・ショップ・レシピも実Stageの表示状態、FXML/CSSとレイアウトの生成、終了を確認しました。両アプリの終了コードは0です。
初回ハーネスでは初期読込前の操作で待機がタイムアウトしたため、初期読込完了を待つ条件を追加して再確認しました。製品ソースの修正は不要でした。

WindowsとMacで14ソースファイルのhashを照合し、Windowsの生成物やMavenをMacへ流用していません。
実SFTP・実Mod接続、jpackage包装、workflow実行は未実施です。
