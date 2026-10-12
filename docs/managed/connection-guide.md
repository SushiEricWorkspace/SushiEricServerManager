# ローカル監督への管理接続

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

## 接続入力

開発用モード選択の「管理テスト環境へ接続」から次を入力します。SSHの認証/host key設定は使用せず変更もしません。

| 入力 | 前提 |
| --- | --- |
| 管理root | 初期化済みsupervisorの絶対パス |
| Python | Python 3.12実行ファイルの絶対パス |
| CLI | 当該`.github/scripts/dev-server.py`の絶対パス |
| instanceId | 対象枠のID |
| leaseId/epoch | 運用者が取得・更新した貸出 |

Managerは自動acquire/renewしません。入力は接続中のメモリだけに保持し、profiles.jsonへ保存しません。
statusのRUNNING・epoch・世代と管理endpointを確認し、generation/runId/nonce/sessionIdを固定します。
登録では自身のnative PID/開始時刻/cwdも送り、監督が実識別を照合します。
接続成功後の世代切替、自動reconnect、SSH/SFTP・直接管理rootへのfallbackは行いません。

## 保存と隔離

管理ファイルのI/Oは`ManagedEditorDataStore`からstdin付きCLIを通じて監督へ渡します。
EditorDataDescriptorのSerializer/Validator・レシピの最新ストア検証を再利用し、CommonモデルやYAMLを再実装しません。
cacheは取得本文のSerializer用コピーであり、管理rootをLocalStoreとして開きません。
rename/moveはファイル内IDも再出力し、監督の1操作で移動します。途中失敗は再試行・rollback削除せず保留します。

アプリデータの`managed/<session hash>/`へautosave/offline/cacheを隔離します。
hashには管理root、instance、generation、run、nonce、新規sessionを含み、leaseの秘密値は含めません。
同じpublic IDでも別profile・世代・sessionのstore identity、履歴、pending構造操作、自動保存ペアを共有しません。
新規接続で旧sessionキャッシュを自動復元・uploadしません。退避データは残し、別sessionへ勝手に反映しません。
ローカル取り込みはこのoffline領域から未保存編集へ読み込む操作で、サーバーへ反映するのは通常の保存です。

コマンド実行・入出金/実績の保存APIも監督仲介です。監視socketはread-onlyで、古いcallbackは現在の接続を上書きしません。
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

ManagedSession/ManagedManagementClient/ManagedEditorDataStoreの試験はGUI・Minecraftを起動せず、合成監督と使い捨てWebSocketを使用します。
JavaFXの実表示・主要操作・実Mod保存往復・macOS native連携は別確認で、Windows単体の成功へ読み替えません。
GUI試験は許可後に専用appdataで起動します。
Windowsは子プロセスのLOCALAPPDATA/APPDATAを専用ディレクトリへ、macOSは当該JVMのuser.homeを専用ディレクトリへ指定します。
既存アプリ設定や人間用runを使用しません。


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
