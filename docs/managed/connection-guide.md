# ローカル・SSH監督への管理接続

分離テスト枠をManagerで編集し、貸出・世代・起動識別を固定する接続方式です。
非管理のSSH/オフライン接続は維持します。CommonやModの保存形式・#504互換判定を複製しません。
監督側のCLIと終了証明は、兄弟`.github`の`scripts/dev_server/writer-guide.md`を参照してください。

## モジュールと起動

通常Managerはルートプロジェクトの`Launcher`で起動し、`development`へ依存しません。
管理接続の入力画面、ManagedSession、SupervisorClient、ManagedEditorDataStore、管理送信経路は
`development`モジュールだけに配置します。通常側との境界はEditorConnection、ManagementTransport、
EditorDataStoreのオフライン領域です。Commonモデルや通常SSH/SFTPのライブラリは通常側に残します。

開発用は`DevelopmentLauncher`から通常の編集機能と開発モジュールを組み合わせます。
正式版への起動時更新・必須更新操作・前回更新結果処理は利用しません。
開発配布は`development/build/install/SushiEricServerManager-Development/`へ生成され、
正式更新の配布・jpackage・リリースタスクへ渡しません。

```text
gradlew :run -PcommonDevelopmentRepository=<Maven絶対パス>
gradlew :development:run -PcommonDevelopmentRepository=<Maven絶対パス>
gradlew :development:installDist -PcommonDevelopmentRepository=<Maven絶対パス>
```

## プロファイルと枠の選択

開発用の「管理テスト環境へ接続」でGit外の`profiles/<profileId>.json`を選択します。
登録・編集は共通契約の`.github/scripts/connection-profile.py`を使用してください。
形式1の非秘密設定・private権限・対象ホスト・Python 3.12・CLIを正本のConnectionStoreで検査します。
localプロファイルのhostには`socket.gethostname()`と一致する対象ホスト名を指定します。
SSHプロファイルには`transport: ssh`、対象ホストの`host`・`targetOs`・`sshUser`と
対象ホスト上の絶対パスを登録します。画面の「SSH用：このPCの設定」で、このPCの
Python 3.12、確定した管理CLI、別途確認済みknown_hostsの絶対パスを指定してください。
プロファイル読み込みでは正本を検査し、「枠状態を更新」などの明示操作からSSH接続します。
対象ホストのroot/Python/CLIをこのPCのファイルとして開きません。local・SFTPへfallbackしません。

選択したプロファイルのパス、貸出owner、受取client、このPCのツール・known_hostsのパスをアプリデータの
`development-connection.properties`へ保存します。root/Python/CLIは登録済みプロファイルを再利用します。
leaseId/epoch/generation/run/nonce/session・引継ぎIDは固定設定へ保存しません。
画面にはホスト、root、運用承認の参照と、候補枠の状態・owner・期限切れを表示します。
承認参照は運用者の既承認の証跡です。JSONを登録するだけでは新しい運用承認を発行しません。

「枠状態を更新」は候補枠だけを一覧表示し、貸出tokenを列挙しません。
AVAILABLE、停止中、RUNNING、他ownerの貸出、期限切れ、RECOVERY_REQUIREDを区別します。
期限切れや復旧待ちを奪取・自動復旧しません。SSH仲介は正本SshConnectionのOpenSSHを使用します。
既存の非対話公開鍵認証と厳密なknown_hosts検証を使い、未確認・変更済みの鍵を拒否します。
SSH設定や鍵の自動登録・更新、agent/port forwarding、APIの外部公開は行いません。
通常ManagerのSSHJ/SFTP設定・認証処理は変更しません。

Windowsの監督は、SSH仲介のnative開始識別・cwd・OS ownerを照合できる権限状態で起動してください。
同一ユーザーでも監督と仲介の昇格状態が異なるとアクセス拒否になるため、承認済みの同じSSH経路から監督を起動するなど、照合できる状態を揃えます。
照合できない場合はwriter登録を拒否し、PID照合やOS owner検査を省略しません。

### Manager自身が借りる場合

1. AVAILABLEの枠と自身のownerを選び、「借りて接続」を押します。
2. acquireは一度だけ実行し、返されたlease/epoch/枠と現在世代をprivate記録へ固定します。
   新規貸出は停止・未束縛のため、この操作だけでは起動・writer登録を行いません。
3. 「別操作：世代を準備」で空枠にはprepare、既存枠にはreset/restoreを明示します。
   配置済みartifactId、新しいgenerationId、seed/templateId/snapshotIdとEULA同意が必要です。
   正本CLIが全条件を照合し、成功応答の世代だけを貸出へ固定します。旧世代は削除しません。
4. 「別操作：サーバーを起動」を明示し、成功後に「保持中の貸出で接続」を押します。
   貸出検査のRUNNING・owner・epoch・世代とstatusのrun/nonceを照合してwriterを登録します。
5. Managerだけ切断してもサーバー・貸出は残り、このアプリがrenewを継続します。
   終了時はwriter切断後に管理画面へ戻り、「別操作：サーバーを正常停止」と
   「停止確認済みの貸出を返却」を明示します。返却は監督の停止・writer終了証明を必要とします。

貸出は3600秒、監視は10秒ごとです。長い世代準備・起動停止はCLIの独立した応答期限で待ち、
同じ貸出の世代更新とrenewを直列化します。途中応答不明では再送・kill・releaseしません。
prepare/reset/restore/start/stopは接続ボタンの暗黙副作用ではありません。
recover、未知writerの解除、snapshot/GCはこの画面から実行しません。

### 人間用起動スクリプトから引き継ぐ場合

発行元は共通契約の`ConnectionStore.offer`へ現在の正規`lease-check`結果を渡し、
同じownerと受取client（画面の既定は`manager`）に対する一回限りの引継ぎIDを発行します。
発行元はrenew・サーバーの継続運転・停止・返却の責任を保持します。

Managerで発行元と同じowner、受取client、明示された引継ぎIDを入力し、
「引継ぎを受けて接続」を押します。acquireやrenewは行いません。
正本ConnectionStoreがCLAIMINGを先に耐久化し、同じホストCLIの`lease-check`と再照合します。
claim期限と接続中の貸出期限を分け、取得後は発行元が更新した正規CLIの現在期限を使用します。
返されたsessionIdを再生成せず、世代・run・nonceを固定してwriter-openします。
他owner/client・期限切れ・旧epoch/世代/run/nonce・二重claimを拒否します。
Manager自身のwriter終了だけを確認し、サーバー停止・貸出返却を発行元から奪いません。
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
EditorDataDescriptorのSerializer/Validator・レシピの最新ストア検証を再利用し、CommonモデルやYAMLを再実装しません。
cacheは取得本文のSerializer用コピーであり、管理rootをLocalStoreとして開きません。
rename/moveはファイル内IDも再出力し、監督の1操作で移動します。途中失敗は再試行・rollback削除せず保留します。

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

Registry DBはversion=3です。旧開発DBは保全・拒否するため、新しい使い捨てrootを作成してください。
writer終了とJVM保存/終了は別証拠です。cleanEligibleは候補判定だけで、snapshotCleanは常にfalseです。
snapshot/restore/recoverや非協調的な登録外writerの証明はこの接続の範囲外です。

## 自動検証と実機の分離

JDK 21、完全なEditor Common座標とrepositoryで実行します。

```text
gradlew build verifyReleaseDistribution -PcommonDevelopmentCoordinate=io.github.sushiericworkspace:sushieric-common-editor-dev:<固定dev版> -PcommonDevelopmentRepository=<Maven絶対パス>
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
正式Commonを指定した場合はEditor専用Commonの混入も拒否します。

Windows installer・macOS dmg・各app imageの作成は、検査済み通常installDistだけを入力にします。
app imageは生成後のJARも検査します。OS包装は当該OS上で、依頼された場合だけ実行します。
installer/dmgは専用の検証環境で展開し、アプリ領域を指定して実成果物を検査します。

```text
gradlew verifyPackagedApplication -PpackagedApplicationDirectory=<展開済みアプリの絶対パス>
```

通常・開発構成のGUI表示と主要操作、Windows installer展開検査、macOS dmg展開検査、
実supervisorへの接続・保存・writer終了はそれぞれ独立して記録します。
JAR検査や合成監督テストの成功をOS包装・GUI・実機の成功として扱いません。


WindowsのJDK 21製exeは、インストールせずに内包MSIを取り出して検査できます。
Pythonと既存の7-Zip・WiX 3を使い、まだ存在しない出力先を指定します。
出力先へMSI、WiX定義、展開ログ、ハッシュ、`application/`を保全します。

```text
python scripts/extract-windows-installer.py --installer build/installer/SushiEricServerManager-<packageVersion>.exe --output build/windows-installer-inspection --seven-zip "<7z.exeの絶対パス>" --dark "<WiX 3のdark.exeの絶対パス>"
gradlew verifyPackagedApplication -PpackagedApplicationDirectory=build/windows-installer-inspection/application
```

Editor専用Commonで生成した包装の検査は開発検証です。正式Commonの配布確認と区別してください。
正式版の検査では、実装と互換性のある正式Common版とリポジトリを指定します。
