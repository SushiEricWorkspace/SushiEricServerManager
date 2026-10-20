# Minecraft描画基盤の固定コードと素材gate

## 現在の範囲

`ComponentSegments`はDataModelの表示用`TextComponent`を`TextSegment`へ変換し、色・5装飾・font・childrenの継承と改行を保持する。クリック・hover・insertionは渡さない。翻訳等の未解決Componentは推測展開せず拒否する。固定Adventure 4.17には明示shadowColor APIがないため、その入力への独自拡張は行わない。

`MinecraftRenderResources`は6個のfont resourceの存在とSHA-256を初期化前に検査する基盤であり、現在の`PreviewCanvas`には接続していない。実画像/OTFは同梱しておらず、実素材による描画は未検証。

## 固定artifactの生成

正本は[minecraft-library/textの固定commit](https://github.com/minecraft-library/text/tree/63e3bfd67c11c6b11288ae00de590c6f6fe5aa40)。実取得した元座標は`com.github.minecraft-library:text:63e3bfd67c11c6b11288ae00de590c6f6fe5aa40`で、READMEの`minecraft-text`とは異なる。READMEの`ColorSegment`はこの固定版にはなく、実APIは`TextSegment`/`LineSegment`を使う。

Managerは独立座標`io.github.sushiericworkspace.thirdparty:minecraft-text:63e3bfd67c11c6b11288ae00de590c6f6fe5aa40-classpath.2`を使う。source/patch/JAR/POM/moduleの固定値は`gradle/minecraft-text.properties`を正とする。`verifyMinecraftText`がコンパイル前に解決したJAR/POM/moduleを照合し、元JitPack版textとの混在を拒否する。

開発時だけ固定ソースarchiveを取得し、同じtask内で生成する。primaryや他taskのMavenは使用しない。以下の`<JDK21>`/`<JDK25>`は各OSの既存JDK絶対パス、`<repo>`は担当Managerの絶対パスとする。環境変数`JAVA_HOME`とGradleのtoolchainを指定し、taskのOS lock内でworker1/最大2GiBを使用する。

```text
# 固定codeload archiveをbuild/minecraft-text/source.zipへ保存する（アプリ実行時には行わない）
https://codeload.github.com/minecraft-library/text/zip/63e3bfd67c11c6b11288ae00de590c6f6fe5aa40

python3.12 -B scripts/prepare-minecraft-text.py build/minecraft-text/source.zip build/minecraft-text/source
# JAVA_HOME=<JDK21>
./gradlew -p build/minecraft-text/source -I <repo>/build/minecraft-text/source/manager-artifact.init.gradle publishManagerTextPublicationToManagerTextRepository -PmanagerTextRepository=<repo>/build/minecraft-text/repository --max-workers=1 "-Dorg.gradle.jvmargs=-Xmx2g"
# JAVA_HOME=<JDK25>、DataModelも自身taskの固定Java21成果を完全座標で指定する
./gradlew verifyMinecraftText -PminecraftTextRepository=<repo>/build/minecraft-text/repository -PdataModelDevelopmentRepository=<task専用Maven> -PdataModelDevelopmentCoordinate=<完全Editor座標>
```

生成先が既存なら上書きしない。archive hash不一致も拒否する。新しい生成物は別の領域/座標に保全し、既存artifactの置換で不一致を隠さない。`.classpath.2`は生成NOTICE/POMの改行をLFへ固定した版で、`.classpath.1`と全class bytesは同一。

局所patchは`MinecraftFont.java`の対象版を1.21.11にし、**Vanilla.resolveFontの自動bootstrapと外部cache直接採用だけを除く**。同梱ファイルを既存cacheへ展開する`getPath`契約は維持する。欠落は即時失敗し、IO例外の一次原因を保持する。IO途中のmaterializationファイルが残ってもfallbackとして採用しない。

これはartifact全体の外部利用禁止を意味しない。`Color.loadSidecarNamed`/`loadTtfBytes`の外部cache tierと明示用`ToolingFonts.class`は残る。任意fontから`getOrLoad`へ接続する前に、許容fontと素材manifest/Colorの解決経路を別途確定する。現在はその接続を行わない。

元Apache-2.0の`LICENSE.md`/`COPYRIGHT.md`と変更内容の`MANAGER-NOTICE.txt`をartifactの`META-INF/minecraft-text`へ保持する。元repoのsource/branch/historyは変更しない。

## 純検証

`ComponentSegmentsTest`/`MinecraftRenderResourcesTest`は実font/Stageなしで変換とresource検査を確認する。人工byte resourceのhash検査を描画成功とは扱わない。

`python3.12 -B scripts/verify-minecraft-text.py <JAR> <依存JAR絶対パスを1行ずつ記したfile> <JDK21> <新しい検証領域>`は専用homeのchild JVMで欠落/外部cache存在/IO失敗を確認する。生成marker、一次例外、原cache不変範囲、数値exitを記録する。検証領域を再利用して原本を上書きしない。

## 同梱素材の未確定事項

| 候補 | 想定配置・対応 | 確定に必要なもの |
| --- | --- | --- |
| 1.21.11アイテム画像 | `resources/minecraft/1.21.11`。D1の`itemDetail.vanillaId`/内部ID/外観に対応するmanifest | 素材セットの範囲、出典、同梱根拠、生成手順とhash、第三者表記 |
| Minecraft Regular/Bold/Italic/BoldItalic/Galactic/Illageralt OTF | `resources/fonts/Minecraft-*.otf`の6個 | 同梱根拠、1.21.11生成元、generator固定版、全hash、credits |

コードのApache許諾とMojang由来素材の条件は別である。[固定COPYRIGHT](https://github.com/minecraft-library/text/blob/63e3bfd67c11c6b11288ae00de590c6f6fe5aa40/COPYRIGHT.md)の「Minecraft Font Glyphs」は元glyphの権利と生成fontがrepoに含まれないことを説明し、EULA/Usage Guidelinesへの適合を利用者側の確認事項としている。

[公式EULA](https://www.minecraft.net/en-us/eula)の「what you can and can’t do with Minecraft software and content」はcontentの配布条件を定める。[公式Usage Guidelines](https://www.minecraft.net/en-us/usage-guidelines)の「Essential guidelines」「Commercial use」は、無料で共有する場合を含め使用条件を定める。これらを本ツールへの画像/生成fontの包括同梱許諾と推定しない。

素材選択の判断候補は次のとおり。いずれも未採用である。

- 公式由来の1.21.11素材: 対象と同梱根拠・必要表記を確定して採用する。権限未確定のまま取得/同梱しない。
- 条件が明確な独自/第三者素材: 各ライセンスの同梱条件を確認し、Minecraft 1.21.11の対応表示というIssue目的との適合を判断する。異なる素材を黙ってfallbackにしない。
- 利用者自身の開発fixture: 製品resource/配布物と分離した最小試験には使えるが、製品同梱と両OS描画の完成条件は満たさない。

素材gate確定後にrenderer/PreviewCanvas接続、ネットワークなしの日本語・装飾・影・HiDPI描画、FXML/両OS実Stageを別途検証する。現基盤を製品描画完成とは扱わない。
