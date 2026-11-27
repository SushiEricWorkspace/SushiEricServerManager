# 履歴のDB読取

入出金・ショップ売買履歴の通常Local/SSH接続は、DataModelのPostgreSQL v2契約を使用します。ManagerはPGへ接続せず、運用者が配備・照合した固定Host helperだけが保護設定を読みます。実績の達成履歴は既存経路を使用します。

## 非秘密の接続参照

`PostgresHostReference`は次を保持します。

- `storageContractVersion=2`
- `runState`: 承認済みworldの`sushieric/run-state-v2/current.json`のUTF-8本文
- `javaExecutable`: 固定Host用Javaの絶対path
- `runtimeDirectory`: SHA-256照合済みHost ZIPの展開先（直下に`lib/`）
- `settingsFile`: 運用者が固定した、world/shop外のHost設定fileの絶対path

run記録のformat/backend/schema/phase、接続参照ID、deployment/DB/world/generationと実体識別はDataModelの`PostgresRunCodec`で検査します。接続時には非秘密のcurrentだけを再観測し、固定bindingを比較してから、その元bytesのhashをOpenへ渡します。要求へURL、conninfo、任意Properties、DB/role/passfile、秘密本文を渡す入口はありません。保護設定の内容・secret fileはManagerでは読みません。設定file自体の配備・保護・不変性は運用者の責務です。

## 通常Local/SSH

Localは`LocalEditorDataStore(worldRoot, hostReference = reference)`で明示接続します。world rootと参照のcanonical worldを照合し、`LocalHistorySource`から固定`PostgresWriterCli --settings <設定file>`を起動します。DB/run記録の作成・移行は行いません。world marker/run記録があるのに参照がない場合は拒否します。worldを持たない編集workspaceはコピー済みYAMLの経路を維持します。

SSHではprofileの`databaseHistory`にworld UUIDと`postgres: PostgresHostReference`を設定します。`commandLine`は空にします。旧commandLineを含むprofileは読取保持しますが、PGとして実行・自動変換しません。SSHの認証とホスト鍵検証は既存経路を使います。接続先OSに合わせて固定引数を引用し、Windows shellが展開する文字と相対配置を起動前に拒否します。

両経路とも一つの検索につき一つのHをOpenからCloseまで保持します。stdin/stdoutはDataModelの4byte big-endian長＋UTF-8 frameです。途中でstdinを閉じて別processへページを送りません。Close応答・EOF・process/channelの終端を確認します。stderrは並行排出し、内部pathやdriver例外を利用者向け応答へ転記しません。timeout・不正応答・切断・接続実体の変更は取得失敗とし、旧応答を使用しません。

SFTPは既存YAML操作と非秘密current一件の観測だけに使用します。DBコピー、保護設定の読取、SQLへの迂回、誤binding時のYAML fallbackは行いません。RESTORE_EMPTY/adopt前、NEW_GAMEのみ、ACL不備、容量不足、未確定編集は空履歴として返しません。

## 検索と表示

初回の高水位と未確定ID集合を全ページへ引き継ぎます。時刻の秒・nano、UUID、transaction ID、Unicodeの順序はDataModelの結果を維持し、取得実件数だけoffsetを進めます。部分一致・日時境界・表示件数は既存画面の意味を維持します。

接続解決・file/通信I/OはI/O workerで行います。画面終了・再読込・条件変更では旧処理を中断し、検索とHを閉じます。画面の世代検査と認証済み接続の実体検査を維持します。

プレイヤー一覧は履歴・既存ディレクトリ・usercacheを統合します。DBのplayers/profileだけに存在するUUIDの全件列挙は#242の残件です。

## 後続の接続

通常Managementの型付きPG APIはMod #631待ちです。v2参照を設定したprofileでは、接続中Managementの旧DB契約をPG接続として使用せず、明示的な未接続エラーを返します。失敗からSSHへ自動迂回しません。managedのlayout/supervisor受入は.github #148/#64待ちです。通常Managerからdevelopmentへの依存はありません。

## 検証と正式化

`scripts/verify-postgres-port.py`はWindowsのtask専用検証です。絶対repository・task作業領域・JDK25・固定Core座標を明示し、DataModel #143の不変task GAVの8 artifactをSHA-256照合します。Gradleの`postgresPortValidation`はEditor/Hostの利用側検証だけを許可し、正式包装/releaseを拒否します。Coreの宣言済みDataModelとtask Editorの版差は検証報告に残し、CoreのPG受入成功とは扱いません。

通常unitと非表示JavaFX/FXML、Local/SSHのframe/EOF・timeout・旧応答、固定Host ZIP/JDK21＋専用PG18.7で履歴と編集・注入拒否・容量失敗を検証します。PG試験は`postgresPortTest`を指定し、`--host-java-home`も必須です。RAM24GiB・C:100GiBの資源ゲートとtask専用port・正常停止確認を使用します。通常buildのRAMは16GiB以上です。

非表示検証には[JavaFX同梱のHeadless実装](https://openjfx.io/highlights/26/)を使用します。runnerは`glass.platform=headless`とsoftware描画を固定し、実際のGlass実装も試験で照合します。追加のMonocle配布物への依存はなく、Monocleそのものの実行結果とは区別します。

候補のMonocle 21.0.2はJavaFX 27で`_supportsExtendedWindows()`が不足し、Stage生成に失敗することを確認しました。Monocleでの画面受入は未完了です。同梱Headlessの仮想画面は1000×1000のため、レイアウト試験の通常幅は利用可能画面に合わせ、最小850×600の条件を維持します。

正式版はDataModel #144の正式GAV/hashと対応Coreの確定後に切り替えます。task版によるbuild成功を正式配布成功と混同しません。実SSHホスト、macOS、Management/managed結合、実画面の物理操作は後続受入です。
