# ローカル・SSH監督への管理接続

分離テスト枠をManagerで編集し、貸出・世代・起動識別を固定する接続方式です。
非管理のSSH/オフライン接続は維持します。SushiEricDataModelやModの保存形式・#504互換判定を複製しません。
監督側のCLIと終了証明は、兄弟`.github`の`scripts/dev_server/writer-guide.md`を参照してください。

## モジュールと起動

通常Managerはルートプロジェクトの`Launcher`で起動し、`development`へ依存しません。
管理接続の選択画面、ManagedSession、SupervisorClient、ManagedEditorDataStore、管理送信経路は
`development`モジュールだけに配置します。通常側との境界はEditorConnection、ManagementTransport、
EditorDataStoreのオフライン領域です。SushiEricDataModelモデルや通常SSH/SFTPのライブラリは通常側に残します。

開発用は`DevelopmentLauncher`から通常の編集機能と開発モジュールを組み合わせます。
正式版への起動時更新・必須更新操作・前回更新結果処理は利用しません。
開発配布は`development/build/install/SushiEricServerManager-Development/`へ生成され、
正式更新の配布・jpackage・リリースタスクへ渡しません。

```text
gradlew :run -PdataModelDevelopmentRepository=<Maven絶対パス>
gradlew :development:run -PdataModelDevelopmentRepository=<Maven絶対パス>
gradlew :development:installDist -PdataModelDevelopmentRepository=<Maven絶対パス>
```

## 初回設定

開発用の「管理テスト環境へ接続」で「初回設定・環境情報」を開きます。
「環境を登録・変更」からGit外の`profiles/<profileId>.json`を選択してください。
登録・編集は共通契約の`.github/scripts/connection-profile.py`を使用します。
形式1の非秘密設定・private権限・対象ホスト・Python 3.12・CLIを正本のConnectionStoreで検査します。
登録した環境は日常画面の一覧から選択でき、前回の環境と枠を再利用します。

Managerから起動する環境は、初回だけ次を設定して保存します。

- 借用時のowner。
- そのホストに配置済みの固定bundleのartifactId。
- 空枠を初めて準備するseed。
- 既存枠の初期化・リセットに使う承認済みtemplateId。空欄では既存枠の新規借用起動・リセットを行えません。
- 起動条件の承認とMinecraft EULAへの同意。

環境の登録だけで、新ホスト・root・bundle・templateの運用許可を発行しません。
起動条件はプロファイル全体のhashに束縛して保存し、root等を変更したプロファイルへ流用しません。
接続だけの場合は起動条件の設定を必要としません。
`development-connection.properties`には非秘密のプロファイルパス・表示名・初回設定・選択枠だけを保存します。
秘密token・引継ぎID・sessionを固定設定へ保存しません。

localプロファイルのhostは`socket.gethostname()`と一致させます。
SSHプロファイルは`transport: ssh`、対象ホストの`host`・`targetOs`・`sshUser`と対象ホスト上の絶対パスを登録します。
「SSHの初回設定」に、このPCのPython 3.12、確定した管理CLI、別途確認済みknown_hostsを指定します。
対象ホストのroot/Python/CLIをこのPCのファイルとして開かず、local・SFTPへfallbackしません。
環境の選択・画面の表示・更新で承認済みホストへ接続して枠状態を照合します。

SSH仲介は正本SshConnectionのOpenSSH、既存の非対話公開鍵認証、厳密なknown_hosts検証を使用します。
未確認・変更済みの鍵を拒否し、SSH設定・鍵の自動登録、agent/port forwarding、APIの外部公開を行いません。
Windowsでは監督と仲介のnative開始識別・cwd・OS ownerを照合できる、承認済みの同じ昇格状態で起動してください。
照合できない場合はwriter登録を拒否し、PID照合やOS owner検査を省略しません。

## 日常のボタン操作

環境と枠を選び、実行可能なボタンを押します。owner・引継ぎID・受取client・世代IDの入力は不要です。
画面を開くたびに枠一覧を照合し、「更新」で再照合できます。
空き・停止中・起動中・期限切れ・復旧待ちを区別し、貸出tokenを一覧表示しません。
一覧の照合で借用・引継ぎ消費・貸出更新・サーバー起動停止を行いません。

### Managerから起動する場合

操作中は工程完了率の％・ゲージと処理内容を表示します。起動は環境照合・貸出確認・世代準備・起動の4工程、
resetは貸出照合・世代準備・新世代照合の3工程です。コピー量や残り時間の推定値ではありません。
世代の初期化・リセットには数分かかる場合があります。失敗時は最後に完了した工程率を保持し、100％へ変更しません。
変更操作が完了するまで「閉じる」と接続画面の閉じるボタンは使用できません。
アプリ全体の異常終了は結果不明として保全し、成功へ変更しません。

1. AVAILABLEの枠で「借りて初期化・起動」を押し、表示された枠と条件を確認してボタンで確定します。
   明示acquireの返値からlease/epochを固定し、空枠は保存済みseedからprepare、既存枠は保存済みtemplateからresetします。
   新しい世代IDは自動生成し、旧世代は保持します。既存データを直接再利用せず、初期化することを確認画面へ表示します。
2. 起動成功後に「接続」を押して編集します。接続ボタンだけで起動は行いません。
3. 編集・保存後、Managerを正常切断します。切断だけではサーバー・貸出は残り、Managerがrenewを継続します。
4. 「停止」で正常保存・writer終了・JVM/child終了を照合します。同じ保持貸出の「起動」はその世代を再起動し、再借用・再初期化しません。
5. リセットする場合は停止後に「リセット」を押し、元templateを確認してボタンで確定します。
   新しい世代IDを生成してresetし、旧世代を保持します。起動は別の「起動」ボタンから行います。
6. 作業を終了する場合は停止後に「返却」を押します。監督が停止証明とwriter終了を照合した後だけreleaseします。

貸出は3600秒、監視は10秒ごとです。世代準備・起動停止とrenewを直列化し、正本が返した世代・期限だけを使用します。
他owner・復旧待ち・期限切れ・結果不明を奪取せず、prepare/reset/startの失敗から新しいacquireを行いません。
writer接続中の起動・停止・リセット・返却は受け付けません。
recover、未知writerの解除、snapshot/GCは日常画面から実行しません。
アプリ異常終了後の保持貸出は診断対象であり、初回設定から自動復元・再取得しません。

### 人間用起動スクリプトから引き継ぐ場合

起動側は同じprivate保存先へ、`manager`宛ての引継ぎを明示発行します。
Managerは同一プロファイル・枠・owner・epoch・世代・run/nonce、期限内OFFEREDの記録だけを接続候補にします。
CLAIMED・UNKNOWN・別client・変更済みプロファイル・古い起動を候補にしません。秘密tokenを画面へ渡しません。

枠を選んで「接続」を押すと、候補のowner・IDで既存claimを実行します。IDのコピーや受取clientの入力は不要です。
候補が複数ある場合は発行記録と期限から選択します。最新記録を勝手に消費しません。
正本ConnectionStoreがCLAIMINGを耐久化した後に同じホストCLIのlease-checkと照合します。
claim期限と接続中の貸出期限を分け、発行元が更新した正規CLIの現在期限を使用します。
返されたsessionIdを再生成せず、世代・run・nonceを固定してwriter-openします。

取得・renew・サーバー停止・返却の責任は発行元に残ります。引継ぎではacquireやrenewを行いません。
画面は取得元を表示し、Manager取得分だけを起動・停止・リセット・返却の対象にします。
正常切断ではManager自身のwriterだけを終了し、引継ぎsessionを再使用しません。
同じアプリで正常切断後に「接続」を押すと、同じ貸出・世代・起動識別と全writerのCLOSEDを照合し、
新しい引継ぎをprivateへ発行して一度だけ受領します。旧引継ぎ・sessionを再使用せず、発行元の責任は変わりません。
アプリ再起動後は保持記録から再接続を推測せず、発行元の新しい引継ぎが必要です。
発行元のrenew失敗・期限切れ・接続喪失では編集を保全して受付を止めます。

### private記録と異常終了

変更要求は送信前に、プロファイルと同じprivateディレクトリの`manager-operations/`へ
operationId・本文をUNKNOWNとして耐久化し、成功応答を照合した後だけCOMPLETEDへ変更します。
tokenはstdinとこのprivate記録だけに置き、argv・通常ログ・Gitへ転載しません。
結果不明・保持中の貸出記録があれば、再起動後も新しいacquireを禁止します。
監督とprivate記録を運用者が診断してください。記録を消して再取得する手順にはしません。

アプリ終了ではwriter-closeやreleaseを推測して送らず、renew監視を止めて記録を残します。
切断・timeout・renew失敗・監視socket喪失はwriterをUNKNOWNにし、旧sessionでの保存を拒否します。
閉鎖成功のときだけ通常のprofile切替を許可します。異常終了のprivate記録と退避は保全してください。

## 保存と隔離

管理ファイルのI/Oは`ManagedEditorDataStore`からSupervisorClientの同じ契約で監督へ渡します。
ローカルはstdin付きCLI、SSHは接続先ホスト上の仲介を使用します。
SSHのwriter登録は仲介自身のnative PID・開始識別・cwdです。外部ManagerのPIDを登録しません。
貸出操作用仲介とwriter用仲介を分け、writerが正常切断した後もManager取得分のrenewを維持します。
EditorDataDescriptorのSerializer/Validator・レシピの最新ストア検証を再利用し、SushiEricDataModelモデルやYAMLを再実装しません。
cacheは取得本文のSerializer用コピーであり、管理rootをLocalStoreとして開きません。
rename/moveはファイル内IDも再出力し、監督の1操作で移動します。途中失敗は再試行・rollback削除せず保留します。
鉱石の魔力量は`magic-power-amount`キーで保存します。既存の開発用鉱石データは自動移行されないため、魔力量を再設定するかデータを再生成してください。

アプリデータの`managed/<session hash>/`へautosave/offline/cacheを隔離します。
hashにはtransport・接続先（SSHは固定非秘密プロファイル全体）、instance、generation、run、nonce、sessionを含み、
leaseの秘密値は含めません。二ホスト・二枠の同一public IDも別領域です。
同じpublic IDでも別profile・世代・sessionのstore identity、履歴、pending構造操作、自動保存ペアを共有しません。
新規接続で旧sessionキャッシュを自動復元・uploadしません。退避データは残し、別sessionへ勝手に反映しません。
ローカル取り込みはこのoffline領域から未保存編集へ読み込む操作で、サーバーへ反映するのは通常の保存です。

コマンド実行・入出金/実績の保存APIも監督仲介です。
SSHはping・コマンド補完もwriter-I/Oへ委譲し、監視は仲介のmonitor-open/read/closeで購読します。
監視は1秒の待機で読み取り、現在のOPEN sessionにだけ通知します。
SSH仲介の許可対象にないconsoleログ購読は受理しません。監視socketはread-onlyで、古いcallbackは現在の接続を上書きしません。
`completed:true`は通信完了であり、APIの`success:false`はそのまま保存失敗として届けます。
既存画面の保存結果/編集pendingを成功へ変換する情報ではありません。
管理接続ではSSHサーバープロセス操作画面を表示しません。起動停止はsupervisor側で行います。

IPC上限はJSON envelope/応答全体で64KiBです。本文のescapeと識別項目も含むため、64KiBのYAMLは転送保証外です。
上限超過は拒否し、分割upload・別方式へfallbackしません。本文・leaseをコマンドラインやエラーログへ出しません。

## 失敗と切断

`error.rejectedBeforeEffect:true`の既知拒否だけはOPENを維持します。
ALREADY_EXISTS/permission/validationといったcodeだけから、作用前であると推測しません。
保存受付後の例外、応答/Close喪失、CLI timeout、登録応答不明ではsessionをUNKNOWNとして保持します。
同じ変更を別operationIdで再送・自動解除・別profileへ切替しません。監督statusと保全データを確認してください。

切断は新規受付を閉じ、受理済みAPI queueの各結果を確定させます。
executorをI/O lock外で待ち、実行中I/O・受理済み件数0・peer Closeを確認してからdrained/disconnectedを申告します。
queueを捨てたことをdrainと扱いません。timeoutでは登録を残します。
SSHの正常切断は監視unsubscribe・peer Close、writer-close、SSH子プロセスexit=0まで必要です。
仲介の接続・要求は各々15秒・40秒、Managerのframe待機は45秒です。
長いSSH世代準備はこの期限に収まらなければ結果不明として保全し、正本の証跡で照合してください。
このPCのprivate storeのssh-clientsと接続先rootのmanager/ssh-connectionsに、本文・tokenを含まない証跡が残ります。
認証失敗・EOF・要求ID不一致・保存結果不明で自動再接続や再送を行いません。
画面の編集退避は既存WindowManager、終了待機はバックグラウンドで行います。
終了中はimplicitExitを抑制し、成功後に画面遷移します。終了確認が失敗した場合はprofile切替を許可しません。
アプリ異常終了時にはwriter-closeを推測して送らず、監督側の登録を保留します。

Registry DBはversion=4です。旧開発DBは保全・拒否するため、新しい使い捨てrootを作成してください。
writer終了とJVM保存/終了は別証拠です。cleanEligibleは候補判定だけで、snapshotCleanは常にfalseです。
snapshot/restore/recoverや非協調的な登録外writerの証明はこの接続の範囲外です。

## 自動検証と実機の分離

JDK 25、完全なEditor SushiEricDataModel座標とrepositoryで実行します。

```text
gradlew build verifyReleaseDistribution -PdataModelDevelopmentCoordinate=io.github.sushiericworkspace:sushieric-datamodel-editor-dev:<固定dev版> -PdataModelDevelopmentRepository=<Maven絶対パス>
```

ManagedSession/ManagedLease/ManagedManagementClient/ManagedEditorDataStoreの試験はGUI・Minecraftを起動せず、合成監督と使い捨てWebSocketを使用します。
JavaFXの実表示・主要操作・実Mod保存往復・macOS native連携は別確認で、Windows単体の成功へ読み替えません。
借用経路のKotlinテストでは環境変数`MANAGED_PYTHON`（Python 3.12絶対パス）と
`MANAGED_CONTRACT_DIRECTORY`（確定した`.github`の絶対パス）も指定します。
未指定時は当該統合テストをskipし、成功と扱いません。

GUI試験は許可後に専用appdataで起動します。
Windowsは子プロセスのLOCALAPPDATA/APPDATAを専用ディレクトリへ、macOSは当該JVMのuser.homeを専用ディレクトリへ指定します。
既存アプリ設定や人間用runを使用しません。


Python bridgeはPython 3.12と確定済み共通契約のディレクトリで実行します。

```text
MANAGED_CONTRACT_DIRECTORY=<.githubの絶対パス> python3.12 -B -m unittest discover -s development/src/test/python -p 'test_*.py'
```

合成lease-check、SSH connectionと使い捨てprivate記録の試験です。実ホスト・Minecraftへ接続しません。
SSHの実保存・読込・正常切断は、許可したWindows→macOSとmacOS→Windowsの分離環境で個別に確認します。
OS固有native仲介・認証失敗・切断・キャンセルも確認し、実行できない方向は未確認として記録します。

## 配布検査

`build`のcheckで通常JARとruntime classpathのクラス・型参照・resourceを検査します。
`verifyReleaseDistribution`は通常installDistのlibも検査します。管理固有の外部ライブラリは現在なく、
SSH/SFTP・JSON・ロギングなど既存機能にも必要な依存を通常側に維持しています。
正式SushiEricDataModelを指定した場合はEditor専用SushiEricDataModelの混入も拒否します。

Windows installer・macOS dmg・各app imageの作成は、検査済み通常installDistだけを入力にします。
app imageは生成後のJARも検査します。OS包装は当該OS上で、依頼された場合だけ実行します。
installer/dmgは専用の検証環境で展開し、アプリ領域を指定して実成果物を検査します。

```text
gradlew verifyPackagedApplication -PpackagedApplicationDirectory=<展開済みアプリの絶対パス>
```

通常・開発構成のGUI表示と主要操作、Windows installer展開検査、macOS dmg展開検査、
実supervisorへの接続・保存・writer終了はそれぞれ独立して記録します。
JAR検査や合成監督テストの成功をOS包装・GUI・実機の成功として扱いません。


WindowsのJDK 25製exeは、インストールせずに内包MSIを取り出して検査できます。
Pythonと既存の7-Zip・WiX 3を使い、まだ存在しない出力先を指定します。
出力先へMSI、WiX定義、展開ログ、ハッシュ、`application/`を保全します。

```text
python scripts/extract-windows-installer.py --installer build/installer/SushiEricServerManager-<packageVersion>.exe --output build/windows-installer-inspection --seven-zip "<7z.exeの絶対パス>" --dark "<WiX 3のdark.exeの絶対パス>"
gradlew verifyPackagedApplication -PpackagedApplicationDirectory=build/windows-installer-inspection/application
```

Editor専用SushiEricDataModelで生成した包装の検査は開発検証です。正式SushiEricDataModelの配布確認と区別してください。
正式版の検査では、実装と互換性のある正式SushiEricDataModel版とリポジトリを指定します。

実Mod二枠の保存・正常切断・snapshot/別枠復元とprivate設定は、
[統合検証ガイド](../../../SushiEricServerMod/docs/core/manager-integration-guide.md)と
[OS・方向別の検証記録](../../../SushiEricServerMod/docs/core/manager-integration-results-guide.md)を参照してください。

## #201のWindows実機確認（2026-10-13）

ServerMod #548で承認された使い捨てroot・専用appdata、固定bundle
`4051b6a4c2a12cb4f36166f69246cfeb05a65f818f49ae5f006a0fcdaa49b305`で確認しました。

- 配色、再表示時の枠照合、手入力なし接続、編集・保存、正常切断後のボタン再接続を人間が確認しました。
  新しい引継ぎ・session、全writerのCLOSED、再接続journalの完了を正本CLIの記録と照合しています。
- 承認済みtemplateを使う初期化・起動とresetで、工程完了率の％・ゲージ・処理内容を確認しました。
  Manager取得分の接続・編集保存・正常切断、停止→同世代起動→停止、reset→起動準備済み→新世代起動、正常停止・返却まで、文字入力なしで操作できました。
- 同世代の2 runは別nonceで正常保存・JVM/child終了を確認し、reset後のfixture・ファイルhashは元templateと一致しました。
  全貸出返却・writer終了・private操作完了、旧世代保持、監督正常終了と6portの空きを照合しています。

macOSのGUI・今回の修正後の実SSH・installer/dmgは再実行していません。
自動検証の合成監督・SSH仲介試験や既存の統合検証結果を、これらの実機成功へ読み替えません。
秘密を含み得る原本は試験rootのprivate記録とinstance診断へ保全し、Gitへ追加していません。
