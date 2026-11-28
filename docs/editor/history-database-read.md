# 履歴のDB読取

入出金・ショップ売買履歴の通常Local/SSH接続は、DataModelのPostgreSQL v2契約を使用します。ManagerはPGへ接続せず、運用者が配備・照合した固定Host helperだけが保護設定を読みます。実績の達成履歴は既存経路を使用します。

## 非秘密の接続参照

`PostgresHostReference`は次を保持します。

- `storageContractVersion=2`
- `runState`: 承認済みworldの`sushieric/run-state-v2/current.json`のUTF-8本文
- `shop`: helperの保護設定と一緒に承認した`PostgresRunShopBinding`（shop rootの絶対pathと実体識別）。world外の配置も同じ参照を使う
- `javaExecutable`: 固定Host用Javaの絶対path
- `runtimeDirectory`: SHA-256照合済みHost ZIPの展開先（直下に`lib/`）
- `settingsFile`: 運用者が固定した、world/shop外のHost設定fileの絶対path

run記録のformat/backend/schema/phase、接続参照ID、deployment/DB/world/generationと実体識別はDataModelの`PostgresRunCodec`で検査します。接続時には非秘密のcurrentだけを再観測し、固定bindingを比較してから、その元bytesのhashをOpenへ渡します。要求へURL、conninfo、任意Properties、DB/role/passfile、秘密本文を渡す入口はありません。保護設定の内容・secret fileはManagerでは読みません。設定file自体の配備・保護・不変性は運用者の責務です。

## 通常Local/SSH

Localは`LocalEditorDataStore(worldRoot, hostReference = reference)`で明示接続します。world rootと参照のcanonical worldを照合し、接続所有のtransportから固定`PostgresWriterCli --settings <設定file>`を起動します。商品一覧・ディレクトリ読取は`reference.shop.root`、商品本体は同じshopを承認したhelperから取得します。profile/world下の別shopへ切り替えません。DB/run記録の作成・移行は行いません。world marker/run記録があるのに参照がない場合は拒否します。worldを持たない編集workspaceはコピー済みYAMLの経路を維持します。

SSHではprofileの`databaseHistory`にworld UUIDと`postgres: PostgresHostReference`を設定します。`commandLine`は空にします。旧commandLineを含むprofileは読取保持しますが、PGとして実行・自動変換しません。SSHの認証とホスト鍵検証は既存経路を使います。接続先OSに合わせて固定引数を引用し、Windows shellが展開する文字と相対配置を起動前に拒否します。

両経路とも同じ編集接続の商品・履歴が一つのHを共有し、各要求と終了処理を直列化します。検索中の商品要求も同じHへ送り、検索のcloseはsnapshotの利用を終了するだけでHを閉じません。Hは接続終了時にClose応答・EOF・process/channelの終端を確認して終了します。単独利用の`LocalHistorySource`/`SshHistorySource`だけは検索がHを所有します。stdin/stdoutはDataModelの4byte big-endian長＋UTF-8 frameです。途中でstdinを閉じて別processへページを送りません。stderrは並行排出し、内部pathやdriver例外を利用者向け応答へ転記しません。timeout・不正応答・切断・接続実体の変更は取得失敗とし、旧応答を使用しません。

`shop`は非秘密の承認情報であり、helperの設定本文をManagerで読んで推測しません。運用者はhelperのshop設定と同じ組を配備してください。Host編集記録が持つshopとも照合します。shopを含まない先行task profileは再設定が必要で、自動補完・別配置へのfallbackは行いません。SSHの商品loadはprofile下のexistsを前提にせず、helperの承認shopからの読取結果で成否を返します。

SFTPは既存YAML操作と非秘密current一件の観測だけに使用します。DBコピー、保護設定の読取、SQLへの迂回、誤binding時のYAML fallbackは行いません。RESTORE_EMPTY/adopt前、NEW_GAMEのみ、ACL不備、容量不足、未確定編集は空履歴として返しません。

## 検索と表示

初回の高水位と未確定ID集合を全ページへ引き継ぎます。時刻の秒・nano、UUID、transaction ID、Unicodeの順序はDataModelの結果を維持し、取得実件数だけoffsetを進めます。部分一致・日時境界・表示件数は既存画面の意味を維持します。

接続解決・file/通信I/OはI/O workerで行います。画面終了・再読込・条件変更では旧処理を中断し、検索を閉じます。通信中断でHが失敗した場合は自動再生成しません。画面の世代検査と認証済み接続の実体検査を維持します。

プレイヤー一覧は履歴・既存ディレクトリ・usercacheを統合します。DBのplayers/profileだけに存在するUUIDの全件列挙は#242の残件です。

## Management接続

通常ManagementはMod #631の型付きPG APIを使用します。DB要求・応答とbindingはstorageContractVersion=2を必須とし、未知・重複field、不正Unicode、旧版・版省略を拒否します。profileの承認済みPostgresRunBinding全体とworldを照合し、game_dirtyの観測がある場合はrunも照合します。初回contextのrunを検索元へ固定し、再接続前の応答とnonceの再利用を拒否します。v2参照のない旧profileを自動昇格しません。profileのdatabaseHistory配下は未知fieldを拒否し、設定全体の前方互換方針は維持します。読込を拒否した場合は既存loaderの規約どおり空の設定を返し、元fileを書き換えません。失敗からSSHへ自動迂回しません。managedのlayout/supervisor受入は.github #148/#64待ちです。通常Managerからdevelopmentへの依存はありません。

DB送信先は受付時の接続に固定し、送信先の呼出しでは相関lockを保持しません。送信先がfalseを返した場合は未受付としてreply登録を外し、例外の場合は受付不明としてnonceとreplyを保持します。登録したnonceは再利用しません。例外後に届くStartも既存の遅延回収でEndを送り、商品要求の元の保存待ちは保全します。

## 検証と正式化

`scripts/verify-postgres-port.py`はWindowsのtask専用検証です。絶対repository・task作業領域・JDK25・固定Core座標を明示し、DataModel #171の不変task GAVの8 artifactをSHA-256照合します。Gradleの`postgresPortValidation`はEditor/Hostの利用側検証だけを許可し、正式包装/releaseを拒否します。Coreの宣言済みDataModelとtask Editorの版差は検証報告に残し、CoreのPG受入成功とは扱いません。

通常unitと非表示JavaFX/FXML、Local/SSHのframe/EOF・timeout・旧応答、固定Host ZIP/JDK21＋専用PG18.7で履歴と編集・注入拒否・容量失敗を検証します。PG試験は`postgresPortTest`を指定し、`--host-java-home`も必須です。RAM24GiB・C:100GiBの資源ゲートとtask専用port・正常停止確認を使用します。通常buildのRAMは16GiB以上です。

非表示検証には[JavaFX同梱のHeadless実装](https://openjfx.io/highlights/26/)を使用します。runnerは`glass.platform=headless`とsoftware描画を固定し、実際のGlass実装も試験で照合します。追加のMonocle配布物への依存はなく、Monocleそのものの実行結果とは区別します。

Monocle 21.0.2はJavaFX 27で`_supportsExtendedWindows()`が不足し、Stage生成に失敗します。先行portは同梱Headlessの限定受入とし、window/native受入は保留します。既存WindowSizingTestの過大SceneでのHeadless描画例外とWindowSizePersistenceTestの画面種別遷移例外も、既存不具合/Headless制約として保留します。同梱Headlessの仮想画面は1000×1000のため、レイアウト試験の通常幅は利用可能画面に合わせ、最小850×600の条件を維持します。

正式版はDataModel #144の正式GAV/hashと対応Coreの確定後に切り替えます。task版によるbuild成功を正式配布成功と混同しません。ManagementはMod停止vector全件とWindows wire3 BOUNDの非秘密参照をManager試験で消費します。wire3の認証・起動profile・descriptor・supervisorはmanaged側の契約であり、通常WebSocketへ独自に持ち込みません。実ModとのWebSocket結合、実SSHホスト、macOS、managed結合、実画面の物理操作は後続受入です。
