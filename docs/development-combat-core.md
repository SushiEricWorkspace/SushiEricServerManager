# 固定開発依存とManager起動

通常ManagerはEditor用DataModel、CombatCore、Coreが使用するMod用DataModelを固定して使います。IDE同期・通常`:run`・`:development:run`は同じroot設定を読みます。JDK21を使用してください。

## 明示的な準備

Managerを単一変更repo、DataModel/Core/.githubを完全commitのdetached依存として登録したtaskで実行します。人間用primaryのCore branchを切り替えたり、既存の開発artifactからlatestを推測したりしません。

```text
py -3.12 -B scripts/prepare-development.py --task-root <workspace>/worktrees/<task-id> --owner <登録owner> --repository <通常共有Mavenの絶対パス>
```

通常共有Mavenには`<workspace>/.datamodel-dev-repository`を指定できます。処理は登録owner・固定依存clean・source入力を照合し、既存OS lockで同taskと同repositoryの発行を直列化します。DataModelとCoreの`test build publishDevelopment`を順に実行し、正規出力`build/development-publication.properties`の完全座標を使います。UUID付き新規版とpublisherのclaimで既存座標を上書きしません。

6JAR/POM hash、Editor/Mod版一致、Core POMのMod用DataModel、主要Core class、source固定を確認した後だけ、このManager checkoutの`.manager-development.properties`をatomicに確定します。発行・検証ログ/source-lock/receiptはtask rootの`manager-development-<UUID>/`へ保存します。失敗時は直前の正常local設定と、発行途中のartifact/claimを保全します。自動削除・再発行で救済しません。

生成設定はGit対象外で、完全座標3・repository2・source commit2・6artifact hashを固定します。別の一意版を発行しても選択は変わりません。生成設定を他の人間用checkoutへ配置する操作は、その配置先を明示して行ってください。prepare自体は実行元task以外の設定を変更しません。

## 起動と再準備

生成設定のあるcheckoutをIDEのGradle projectとして同期し、既定MainAppKt、通常`:run`、開発用`:development:run`を使います。依存の再発行は毎起動では行いません。設定不足やartifact欠落/hash変更時は、準備エラーを解消してから明示的に再準備します。

```text
./gradlew run
./gradlew :development:run
```

`appVersion`等の既定値は依存modeの指定ではありません。既存の依存7項目のいずれかがGradle propertyとして有効な場合（-P、project/user gradle.properties、環境変数、system propertyを含む）、または正式版入力/正式package task要求時は、local設定を一切読みません。値単位で混ぜず、明示入力の既存契約を保ちます。

## 明示入力と正式版

CI/専用検証等で入力を直接渡す場合は、同じ固定tupleを指定します。

```text
-PdataModelDevelopmentCoordinate=io.github.sushiericworkspace:sushieric-datamodel-editor-dev:<DataModel版>
-PcombatCoreDevelopmentCoordinate=io.github.sushiericworkspace:sushieric-combat-core-dev:<Core版>
-PcombatCoreDataModelCoordinate=io.github.sushiericworkspace:sushieric-datamodel-mod-dev:<同じDataModel版>
-PdataModelDevelopmentRepository=<Maven絶対パス>
-PcombatCoreDevelopmentRepository=<Maven絶対パス>
-PdataModelSourceCommit=<DataModel完全commit>
-PcombatCoreSourceCommit=<Core完全commit>
```

正式版では`dataModelReleaseVersion`、`combatCoreReleaseVersion`、`combatCoreDataModelCoordinate=io.github.sushiericworkspace:sushieric-datamodel:<DataModel版>`を指定します。開発版/正式版やDataModel版の混在は拒否します。

ManagerのruntimeはEditor用DataModelだけを載せ、Core経由のMod用DataModelを除外します。`verifyReleaseContents`/`verifyReleaseDistribution`で重複・依存版・配布を検査し、`recordCombatCoreDependency`が実座標/JAR hashと同じtupleのsource commitを`build/reports/combat-core-dependencies.properties`へ記録します。


## 検証

準備処理の拒否/保全は`py -3.12 -B -m unittest discover -s scripts -p test_prepare_development.py`で確認します。生成設定があるtaskでは`test_development_inputs.py`で実Gradleのtuple選択・record入力・通常/開発entry・設定置換を確認できます。テストはlocal設定と一時的なproject propertyを終了時に復元します。共有artifactや人間用設定は変更しません。

```text
py -3.12 -B -m unittest discover -s scripts -p test_development_inputs.py
./gradlew test build verifyReleaseDistribution
```

Windowsで既存アプリの設定/lockと独立させて起動受入する場合は、既存の`LOCALAPPDATA`環境変数を検証processにだけ指定できます。通常runと開発runの双方で同じ固定入力を使用し、既存アプリを無断終了しません。IDE自体の同期/起動操作とGradle model・Stage確認は区別して記録します。
