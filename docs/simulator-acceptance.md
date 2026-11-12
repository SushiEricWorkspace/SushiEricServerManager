# シミュレーター統合受入

Manager #268は、独立基準値→Core/Mod実適用→Manager計算→既存Homeから開く専用Stageの取込・詳細・比較を受け入れます。#269/#270の確定成果を使い、IDE機能領域や#248/#249/#253の統合を前提にしません。M10/M11の共通Editor基盤・保存互換・focus修正は対象外です。

## 固定入力と監査

| 対象 | 固定commit |
|---|---|
| Manager画面・取込・比較の基準 | `8172d42eba1995a76cdf999fe144b9ac452b8bde`（#270統合） |
| CombatCore | `0c5b1a4cd431d3394ce01e9f118e1a7d7f4920ab` |
| DataModel | `93220ce834ab7957f9d480eaec27cfb76ee1052d` |
| Mod実処理 | `8a26ca7f12fa8b547645a9ae4a35bcce5b61e6e6` |
| ビルド・export規約 | `.github ac682fd4e03cf8e7267f27c01fa41ba0589f3edd` |

JDK21、Manager JavaFX21.0.1を使用します。task登録・owner・依存detached/clean、OS lock、task専用Mavenの完全座標/JAR・POM hashを照合します。他taskの出力は流用しません。完全座標は`manager268-build-inputs.json`と`build/reports/combat-core-dependencies.properties`、最終実行ごとの件数・skip・source-lock・ログはtask専用`manager268-ui-final-evidence.json`に記録します。

#270統合前のManager基準は`8f43ee936d44225f383e4d76a3f5b27a3f7de92a`です。未追跡の計算受入testとdocをbytes/hashで`manager268-pre-270-ff/`へ保全し、祖先・最新origin/develop・パス衝突なしを確認してFF限定で更新しました。依存pinsは同一です。旧計算build/doc/source-lock/GT証跡は上書きしません。

## 計算とModの受入

`SimulatorCalculationAcceptanceTest`の7件は、公開サービスを順に呼び独立基準が装備・技能→状態付き複数命中→固定比較へ届くことを確認します。Windows/Mac双方で成功済みです。Coreの個別式や抽選エンジンを重複実装しません。

| 交差fixture | 独立基準 |
|---|---|
| COMBAT0/1/100＋Self/All/Parts | 基礎100、装備10/Self1.5/All1.1/Parts1.2。level0=134.5、level1=139.536、level100=1093.5、固定基準134.5は不変 |
| SHARPNESSと元編集値 | ST0・基準100。level1=105、level5=125、差20。元Map変更は既存記録へ影響しない |
| 2矢のパリィCD・期待値 | 各100、CD20。100%パリィは0/100・合計100。50%は周辺50/75・合計125、終端重み1/4・1/4・1/2 |
| 未命中・OH・致死・死亡後 | HP40/OH40へ未命中→100→死亡後。算出100、純HP40、OH40、適用80。貫通後は純HP差0/OH差-40/適用差-40 |
| Sample・行ID | seed123・既存hitIdのReached値は追加/削除/順序/会心確率変更で不変。確率/成立・Added/Removedは別に保持 |
| 未完了・世代 | 取消、65,536評価上限、短剣追撃予約UnsupportedはIncompleteで数値差なし。Staleも基準を変更しない |
| Mod同入力の盾2矢 | Mob→Player各100、HP1000/STR100/ST100/CD20/構えなし。1矢目HP1000/ST95、2矢目HP900/ST95、期限120、Expected合計100 |

固定DM270件/Core209件・統合Mod1530件の単体/build/exportは成功済みです。Mod exportは`runGameTest`を除外し、起動taskをdry-runで拒否しました。Windows bundle `98ddffec4086b58a1a76c0321c6328026598ebdbdda7cf3e0b6592a36c7dc506`に対する許可管理GameTest1回（`sim-01a13fc2-manager268-gt01`）は60件成功・skip0・exit0です。終了保存/drain/pending writer0/所有JVM・子終了/listeners空/永続枠前後不変を照合し、`manager268-gt01/`へ保全しました。snapshotClean=falseのため永続snapshot受入には流用しません。

同入力0/100に加え、既存Mod fixtureのOH30へ20→100（HP1000/OH10→HP910/OH0）、過剰致死後の追加履歴なし、HP/履歴成功後cost不足で間隔/BREAK停止、BREAK開始tick免除→後tick5hit解除を再利用して照合します。pins不変のため追加GTを行いません。

## 画面の最終交差

`SimulatorUiAcceptanceTest`は2件だけ追加し、各件をONLINE/OFFLINEで実行します。「両モード」は接続モードです。厳密期待値を主として、保存済み/編集中装備→COMBAT条件変更→FXML計算→一本ごと/4合計→FXML基準比較を同じ独立入力で通します。

- 保存済み: 明示した一時OFFLINE_DIRへ型付きItemを保存し、実FXMLの取得・再読込ボタンから読みます。ONLINEでもセッションStoreへのI/Oは0です。
- 編集中: 実source StageのItem取込gateからdeepCopy/freezeし、既存WindowManagerを通じ同じSimulator Stageへ渡します。元modelは不変です。未確定入力/Undo/gesture/閉鎖解除等は#270既存fixtureを再利用します。
- 弓の独立値134.5→139.536を2hitへ適用し、各行の4値と合計269→279.072を照合します。Mob OH=0のUI契約を守り、純HP/適用は同値、OHは0です。HP/OHを区別する40/40/80の基準は上の計算fixtureを再利用します。
- stable hitIdは取込・条件変更・比較を通じ不変、同Simulator Stageを維持します。合計Label・各行details・比較各labelのbaseline/current数値も独立値へ照合します。基準を再計算しません。

Windowsの追加2件＋計算7件は9件全成功・failure/error/skip0です。#269/#270既存101件はWindows/Macで成功済みで、期待値/Sample・取消/上限/Unsupported・古通知・元編集/基準保全・実3系統Spinner・FXML表示/終了を再利用します。最終UI交差2件はMacのfull実行でも成功しました。ONLINE/OFFLINE各経路の独立値・元model・同Stage・Remote I/O0を両OSで確認済みです。

## OS別結果と限界

Windowsの最終全test/build/配布検査は成功しました。root731件=720成功/11skip、development45件=30成功/15skip、failure/error0、Simulator110件（既存101＋計算7＋UI2）は全成功・skip0です。実測と新source-lockを`manager268-ui-final-evidence.json`へ保全しています。既成功の固定DM/Core/Mod/GTは同pins・hashで再利用し、理由なく再実行しません。

Mac最終full実行のroot731件は708成功/既存EditorSingleSaveTest.kt:841のfocus待ち失敗1件/22skip/error0で全test/build未合格、developmentは未到達です。fullの新XMLでSimulator110件（既存101＋計算7＋UI2）は全成功・skip0、独立verifyReleaseDistributionはexit0で成功しました。#269/#270の実FXML/Stage主要操作成功も再利用しています。#268計算段階および#269でも同じ失敗を確認済みです。根本原因は未確定で環境由来と断定せず、シミュレーター新規試験成功や配布成功で全build失敗を隠しません。M10/M11へ修正を広げません。

Windowsの既存skipはmacOS限定8・Unix系symlink1・明示nativeRobot1・実SFTP1、development15件は明示実行条件によります。Mac skipは空messageを含む原本を保持し、成功に数えません。初期Mac計算証跡の37skipは原本分類16annotation/4assumption/17未確定を維持します。追加読取でWindowsUpdater6件のOS.isWindows条件を確認、残11件の環境変数/ファイル条件のどれが偽かは未観測です。

Mac原本はOS別task専用領域へ保持し、回収小ZIP/manifest/選択entryのhashと未取得範囲を記録します。#270のPNG本文はMac保存のままで、Windows側未取得です。#270 phase1のdry-run parser空出力は事後確認、phase2は修正parserのnonempty/expected/tasksafe事前selfcheckを確認した範囲です。原本の全量回収やMac全build成功は主張しません。

取込は保存・Remote取得・自動commit/focus移動を行わず、実環境の効果適用もしません。未対応の追撃/連鎖/時間進行/遅延volley/飛翔/固有profile/DPSはUnsupportedとして扱います。実機Minecraftサーバー・管理root・追加GT・jpackage・M10/M11の検証へ範囲を拡大しません。未検証を数値0や成功として表示しません。最終交差の両OS結果を取得し、最終記録の重点反証と公開判断を分けます。既存Mac全build失敗は未合格のまま明記します。


## 最終Mac原本と文書の追補

Mac最終証跡はtask専用`manager268-mac-evidence-final/acceptance-small.zip`（153,471 bytes、SHA-256 `497378d94feadb564c7a75718c7e8d2c76b1600752bd30a773db614368fa1db3`）です。37 entry中、manifestの36件を全size/hash照合しました。full新XMLのSimulator110件と既存Editor失敗、実command、成功/失敗raw、runner、source-lockを正とします。Mac source-lock SHAは`0cc1b92336fc8f110bf6f3dba814c5c2732f9ceb9c919d895549a4eeffd3e3fa`です。

先行related invocationは`test --tests '*feature.simulator.*'`がdevelopmentにも適用され、`:development:test`のNo tests foundで停止してroot `:test`未到達でした。そこへcopy2されたEditor XMLは08:22:41の旧出力で、full後14:01:06の新XMLとは別です。先行runは関連110件の根拠として無効、単独Editor再試行の事実としても扱いません。full新XMLだけを最終結果へ集計し、先行失敗rawも削除しません。

2つの受入testコードは両OSで同一hashです。build時docはWindows `44603dea93bd636e52f6f642fec79720a32ea9832caba68a359e8e50c8748efa`、Mac `00029a2a0f4918b09ef0aaf6648ccf2587433cbc1f7435e5000305f4a822a2ce`で異なり、3file全hash一致とは扱いません。Windows新source-lock SHAは`27461f6152d1323172394444dfe91980843b574ecfe0542146c576677f1260ec`です。Windows build時doc原本を`manager268-ui-build-document-snapshot/`へ保存し、この最終docの記録専用追補後SHAはcanonical evidenceの別fieldへ記録します。旧lock/snapshotを上書きせず、記録更新のための再buildは行いません。
