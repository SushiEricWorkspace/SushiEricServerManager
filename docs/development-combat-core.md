# CombatCore開発依存

通常ManagerのCombatCore依存はUI非依存の計算確認にも使います。開発時はEditor用DataModel、CombatCore、CombatCoreが使用するMod用DataModelの完全座標を指定し、3成果物を同じtask専用Mavenへ発行します。

```text
-PdataModelDevelopmentCoordinate=io.github.sushiericworkspace:sushieric-datamodel-editor-dev:<version>
-PcombatCoreDevelopmentCoordinate=io.github.sushiericworkspace:sushieric-combat-core-dev:<version>
-PcombatCoreDataModelCoordinate=io.github.sushiericworkspace:sushieric-datamodel-mod-dev:<version>
-PdataModelDevelopmentRepository=<task専用Mavenの絶対パス>
-PcombatCoreDevelopmentRepository=<task専用Mavenの絶対パス>
```

Editor用とMod用DataModelの`<version>`を一致させます。CombatCoreはMod用DataModelを含むartifactで発行し、ManagerではCore経由のDataModel依存を除外してEditor用だけをruntimeへ載せます。除外後も、指定座標、解決済みJAR、JAR内のDataModelクラス重複を`verifyReleaseContents`で検査します。

正式版では`dataModelReleaseVersion`、`combatCoreReleaseVersion`、`combatCoreDataModelCoordinate=io.github.sushiericworkspace:sushieric-datamodel:<version>`を指定し、正式版同士を組み合わせます。開発版と正式版の混在や、CoreとManagerのDataModel版の不一致はGradle設定時に拒否します。

`recordCombatCoreDependency`は解決した座標、成果物SHA-256、`dataModelSourceCommit`と`combatCoreSourceCommit`を`build/reports/combat-core-dependencies.properties`へ記録します。ソースcommitは呼び出し時に完全hashで指定してください。
