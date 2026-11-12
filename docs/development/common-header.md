# 共通上部バー

主要画面は`Utility.createScene`から`AppFrame`を通り、JavaFX 27の`StageStyle.EXTENDED`と`HeaderBar`を使用します。
OS標準の最小化・最大化・閉じるボタンはJavaFXのネイティブ装飾を維持します。左右の予約paddingを無効化せず、メニューと設定ボタンはドラッグ対象から除外し、アプリ名とHeaderBarの背景を移動領域にします。

Windowsでは「ファイル・編集・表示・サーバー・ウィンドウ・ヘルプ」、アプリ名、設定、右端のOS標準ボタンの順です。
macOSではMenuBarをシステムメニューへ移し、左のOS標準ボタンを維持します。JavaFX 27のstandalone installDist launcherではOS登録名が`java`となる制約を実機で確認しています。
設定メニューは入口だけを置き、外観とキーボードショートカットの項目は未有効です。
Windowsの一般的なSnap領域に収まるよう、Stageの最小幅を500px、共通rootを480pxとし、上部メニューの余白を小さくしています。
狭い幅ではアプリ名をellipsis表示し、Tooltipで全文を確認できます。接続状態は折り返し、接続の詳細ボタンは推奨幅を維持します。
Editor本体のサイドバーや編集内容の幅は変更しません。
[MicrosoftのSnap対応資料](https://learn.microsoft.com/en-us/windows/apps/desktop/modernize/ui/apply-snap-layout-menu)にある
HTMAXBUTTONと最小幅500epx以下の要件を基準とします。実Snap操作の確認とは区別します。

接続行はサーバー名、SSH、Management API、Minecraft、TPS、書き込み可否を常時表示します。
オフラインのSSH/APIは対象外、MC/TPSは不明です。管理接続はSSHを使わず、APIと書き込み可否を独立表示します。
API未接続またはSSH切断時には受信済みTPSを表示せず、不明へ戻します。通信によって確認できない停止状態を推測しません。
Protocol/Schemaの版は現在の通信契約で受信しないため、接続の詳細に未取得と表示します。互換性評価とAPI接続失敗の理由も詳細へ表示します。

編集・監視画面の「ホームへ戻る」は既存のCloseRequestを通し、保存退避と編集終了の処理を維持してHomeを前面へ戻します。
「切断」は既存の終了フローで接続を終了してサーバー一覧へ戻ります。オフラインと開発管理接続では動作モード選択へ戻ります。
「モード変更」は接続終了後に動作モード選択へ戻る別操作です。起動準備中はモード変更と切断をbutton/menuの両方で無効にします。
表示済みStageへのScene差替でも状態更新を開始し、離脱時にはStageのリスナーを解除して更新タイマーを止めます。サーバー一覧には戻り先を明記します。
ファイル/編集メニューは既存の保存・新規作成・Undo/Redoへ委譲し、操作中・読取専用の制限を維持します。
Home下部の既存切断操作も、通常接続は「切断してサーバー選択へ」、管理接続は「切断して動作モード選択へ」と表示します。

サーバー一覧の「前回状態」と確認時刻は、今回のアプリ起動中に共通バーで観測した情報です。
一度も観測していないサーバーは未確認と表示し、一覧を表示しただけではSSHやAPIへ接続しません。
履歴は名前・host・port・user・path・接続先OS・Management portのidentityで区別し、鍵や認証秘密は保持しません。
設定の接続先変更では別identityを未確認とし、プロファイル削除時にはその名前の履歴を破棄します。履歴は接続設定の保存形式を変更せず、終了時に破棄します。

## 検証

接続状態の整形テストは、API切断後の古いTPS、SSH切断、管理接続の読取専用、オフラインと未選択、未確認と履歴時刻の区別を確認します。
GUI検証では専用設定・ローカルデータを用い、Platform.runLaterによる実Stage/FXML、メニューと予約領域、遷移、最大化・全画面と終了を確認します。
外部サーバーへの接続、jpackage、GitHub Actionsの実行は含めません。
OS操作を必要とするSnap表示・システムメニュー・異なるDPI間の移動は、JavaFXプロパティの検証と区別して記録します。

API契約の参照先:
- [StageStyle.EXTENDED](https://openjfx.io/javadoc/27/javafx.graphics/javafx/stage/StageStyle.html#EXTENDED)
- [HeaderBar](https://openjfx.io/javadoc/27/javafx.graphics/javafx/scene/layout/HeaderBar.html)
- [OpenJFX Glassのアプリ名取得](https://github.com/openjdk/jfx/blob/jfx27/modules/javafx.graphics/src/main/java/com/sun/glass/ui/Application.java)

## Windows検証結果（2026-10-18）

Windows 11 x64、Temurin 25.0.4+7で本体とdevelopmentのcompile/test/build、両installDist、通常配布内容検査が成功しました。
本体630件は619成功・11skip、development45件は44成功・実Mod接続1skipです。
本体のskipはmacOS限定8件、OS別名パス1件、nativeRobot未指定1件、実SFTP未指定1件です。
Windows生成の通常・開発起動スクリプトには`-Xdock:name`が含まれないことを確認しました。

通常・開発の実アプリを別々の専用設定・ローカルデータで起動し、Platform.runLaterのAttach harnessで次を確認しました。
- 動作モード選択、Home、アイテム・鉱石・ショップ・レシピ、サーバー一覧の実Stage/FXMLと6メニュー順
- EXTENDED_WINDOW対応、右の標準ボタン予約領域138×29、操作領域の非drag指定
- 既存の保存・同期・新規作成・修復メニューの移設、ホームへ戻る、モード変更とサーバー一覧からの復帰
- 共通メニューによる鉱石作成、硬度25.45への編集、差分保存、Editor再生成後の再読込
- 合成状態の管理API接続・読取専用と切断後の旧TPS不明化（外部接続なし）
- JavaFXプロパティ経由の最大化・全画面・復元と通常終了（両プロセスexit 0）

対象PID/HWNDだけのWin32読み取りでは、標準system menuの移動・サイズ変更・最小化・最大化・閉じる、
WS_THICKFRAME/WS_MAXIMIZEBOX/WS_SYSMENU、DWM caption領域、最大化ボタンのWM_NCHITTEST=9（HTMAXBUTTON）を確認しました。
100%/150%ではDWM caption中央、200%では対象HWND右端から100～180pxの受動走査でHTMAXBUTTONを確認しました。
強制UI scale 200%ではDWM boundsの中央がHTCLOSEとなるため、DWM boundsだけからボタン位置を推測していません。
これはOS標準機能の前提の確認です。実際のSnap popup、system menuの開閉、ポインタによるドラッグは未実施です。
専用プロセスの150%・200%出力scaleでメニュー/アプリ名/設定/標準ボタン予約領域のlayout非重複を確認しました。
実OS DPIは96であり、異なるDPIのモニター間移動の検証には相当しません。

最初のharnessは条件機能照会をFX thread外で行って失敗しました。領域検査でもfocus outlineを操作領域として扱う誤判定がありました。
いずれもbuild配下のharnessだけを修正し、製品コードの修正なしで成功しました。前面の別アプリを写したRobot画像は証拠に使用せず、
再生成可能な画像を除去してScene.snapshotへ切り替えました。

macOSの検証結果と残るOS固有の制約は次節に記載します。OS実操作の不足は公開前の判断事項です。

固定DataModel commit: `93220ce834ab7957f9d480eaec27cfb76ee1052d`。Windowsのtask専用Mavenへの発行結果:

```text
io.github.sushiericworkspace:sushieric-datamodel-editor-dev:0.1.0-dev.20261018033030659.f19d34d7-63fc-40dc-8f0f-cf90a111cd0f
JAR SHA-256: 8dc2bcaa4fc3cc5a7c465944c67c7f8b500f12fa071ef786c2cf7b6d4de9195a
POM SHA-256: 14fdb93ccd6fe0eb94ee26fe6468cb54eddfe776c15ac7e2f8d95081b3ba4fb6
```

反証で見つかった準備中の切替競合、表示済みStageへの状態更新開始漏れ、Scene離脱後のStageリスナー残存、同名別接続先への履歴流用を修正しました。
修正後の両build/test/installDistと通常・開発の実GUI回帰が成功しました。準備中のbutton/menu無効化、表示済みStageの状態更新、Scene差替時の旧listener解除・Timeline停止を実Stageで確認しました。

共通枠の最小幅を調査した専用harnessでは、外枠500px/Scene484pxに対し、余白調整前のHeader最小幅538pxがはみ出すことを確認しました。
最小幅・アプリ名ellipsis・メニュー余白の候補ではHeader484px、menu 0～247px、アプリ名247～290px、設定290～346px、
標準ボタン予約346～484pxとなりました。この測定は製品への反映前のharness結果です。反映後も100%・150%・200%の専用実Stageで外枠500px、状態の全文折返し、詳細入口幅とHeader領域非重複を確認しました。

Snap最小幅の修正は共通枠・メニュー余白・詳細入口とselector制限に限定しました。500px外枠のnative予約非重複・状態折返しのFX回帰テストを追加し、修正後のFX回帰テストは成功しました。

## macOS検証結果（2026-10-18）

macOS arm64、OpenJDK 25.0.1で本体・developmentのbuild/test/installDistと通常配布内容検査が成功しました。
本体630件は608成功・22skip、development45件は44成功・実Mod1skipです。Windows限定、実SFTP未指定、既存のmacOS除外FX試験をskipし、新規AppFrameの実Stage回帰は成功しました。

Mac生成の通常・開発installDist launcherを専用home/ローカルデータで起動し、両方の実FXML、4Editor、共通メニュー経由の新規編集・保存・再読込、合成API切断・読取専用、画面遷移、準備中入口とStage監視寿命、最大化・全画面・復元を確認しました。両最終プロセスはexit 0で終了しました。
JavaFXのnative MenuBarにAppleと6メニューが並び、左の標準ボタン予約68×28、output scale 2での領域分離を確認しました。

`-Xdock:name`を指定しても対象PIDのLaunchServices表示名と同一プロセス内AppKitのprocessName/localizedNameは`java`でした。製品名表示を成立させない追加引数は除去しました。GlassのJava側nameはApplicationクラス名であり、OS名の成功判定には使用しません。standalone起動のOSアプリ名は要求との差分として残しています。リリース用app bundleの生成は実行していません。

検証helperでJava側nameの誤判定、Objective-C呼出しのPython側segfault、JNI helperパス誤指定がありました。製品sourceは変更せず、生成launcherで正常終了を取り直しました。失敗証跡は保持しています。
実ポインタによるnative操作、OSメニューpopup開閉、異DPIモニター間移動は未実施です。

固定DataModelのMac task専用Maven発行結果:

```text
io.github.sushiericworkspace:sushieric-datamodel-editor-dev:0.1.0-dev.20261018042418385.8a4f18d5-ba2e-4f4b-a13c-190199d3a6fc
JAR SHA-256: d8b4083b8c6516f8905792f63e8d33e0b1a09a5e27ade0330719f70461d52de8
POM SHA-256: d5f34c16fbec62153c23b45d8151fbcccf04a056078dd9d508084814dea942f1
```

無効な追加引数の除去後、両OSで通常・開発installDistと配布内容検査だけを再実行し成功しました。JavaFX実装とテストは変更していません。既存jpackageの`appName`/`--name`は`SushiEricServerManager`に設定済みですが、app bundle生成・名称表示の実包装検証は未実施です。

Windowsの追加native限定試行では、専用StageをtoFront/requestFocusした後もforeground HWND/PIDが自アプリと一致しなかったため、最初のmouse/key/capture前で停止しました。イベント送信と画像取得は0回、アプリexit 0です。caption drag・Alt+Space・Snap hoverの実操作成功とは扱いません。
Screen一覧は2560×1440の3画面、JavaFX報告dpi 108・output scale 1.0で全画面同一でした。対象HWNDのWin32 DPI 96と区別し、異scaleモニター間の実DPI移動は実施不可です。OS設定は変更していません。
この追加記録だけをMac最終実行後に追記し、機能source・Gradle・testはMac実行時と同一です。Macへは再接続していません。
