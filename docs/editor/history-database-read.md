# 履歴のDB読取

入出金・ショップ売買の履歴は、world結合済みの接続ではDataModelのDB履歴を使用します。Management接続中に履歴保存を要求してYAMLを取得する操作は行いません。実績の達成履歴は既存の取得経路を使用します。

## 接続

- 通常Management: `database_context_request`でworld/runを取得し、`database_history_start/page/end`を同じ接続へ送ります。
- 通常ローカル: `LocalHistorySource(world, shop, worldUuid)`を`LocalEditorDataStore`の`historySource`へ渡します。worldとshop_dataは同一ホストの実体の絶対パスです。DBの作成・移行・run-state初期化は行いません。
- 通常SSHでManagement未接続: サーバープロファイルの`databaseHistory`へ、world UUIDと配備・照合済みD3 runtimeの起動コマンドを設定します。既存のSSH認証・ホスト鍵検証を使用し、要求はUTF-8 stdinへ書いてEOFを送ります。稼働DBをSFTPで取得しません。
- 管理接続: developmentの`ManagedManagementTransport`が監督writerの`database`要求へ送ります。nonceを監督操作IDとして使用し、所有bindingの補完と検査は既存proxyへ委譲します。通常Managerはdevelopmentへ依存しません。

SSH設定例（実際の配備先・world UUIDに置き換える）:

```json
"databaseHistory": {
  "worldUuid": "11111111-1111-1111-1111-111111111111",
  "commandLine": "/opt/java21/bin/java -cp '/opt/db-adapter/lib/*' io.github.sushiericworkspace.sushiericdatamodel.database.host.HostAdapterCli --world-root '/srv/minecraft/world' --shop-root '/srv/minecraft/sushieric/shop_data'"
}
```

Java 21とDataModelの固定host-adapter runtimeを接続先へあらかじめ配備し、座標・SHA-256を照合します。runtimeの自動配備や既定配置の推測は行いません。`commandLine`へ秘密情報やJSON本文を含めません。Windows SSHでは接続先shellに適した引用符と実行ファイルの絶対パスを指定します。設定がなければ同じ画面に接続設定エラーを表示します。

worldを持たないローカル編集領域は、コピー済みYAML履歴の文脈を維持します。world結合済みDBの取得失敗をYAML履歴へ切り替えません。移行未完了・seal不備・管理binding不在は状態表示へ返し、直接DBや任意SQLで迂回しません。生YAMLからのDB移行はMod #577の範囲です。

## 検索と表示

初回の高水位と未確定ID集合を後続ページへ引き継ぎます。Managementでは接続専用handle、D3では`HostHistorySnapshot`を使います。時刻の秒・nano、UUID、transaction_idによるDataModelの順序を維持し、ページを返した実件数だけoffsetを進めます。Managementのbyte上限で短いページが返っても、`hasMore`がある間は取得を続けます。

各画面は同じ初回集合をI/O workerで分割取得し、既存の日時・種別・商品・プレイヤー条件をworkerで絞り込みます。名前・商品IDの部分一致はKotlinの`contains(ignoreCase = true)`を使い、SQL LIKEへ置き換えません。画面の日時境界とシステムのタイムゾーンを維持します。件数は表示件数と読み込んだ集合の件数です。

DBプレイヤー一覧は履歴に記録されたUUIDと名前、既存プレイヤーディレクトリを統合し、サーバーの`usercache.json`を名前補完と履歴のないプレイヤーの列挙に使用します。名前のない履歴はUUIDで表示します。移行元の片側送金を別の取引と結合せず、記録された相手・実行者・理由・免除を保ちます。免除は既存の金額セル内に表示します。

画面終了・再読込・取得中の条件変更は旧処理を中断し、検索handleを終了します。Management接続内の検索は直列化し、タブ間でhandleを競合させません。開始要求中のキャンセルは応答を回収してhandleを閉じ、開始timeoutの遅延応答も表示せず終了要求だけを送ります。接続世代が変わった応答は使いません。接続変化では表示を消し、再読込で新しい集合を開きます。

## 確認の範囲

実SQLiteの分割・新規追加・遅延確定と再読込、元worldのファイル不変、codec、Unicodeとnano、名前不明・片側移行行、Managementの認証エラー・切断・timeout・キャンセル、SSHのstdin/EOFとtimeoutを自動試験します。JavaFXは実画面へ表示しないMonocleでFXMLと主要操作を確認します。

管理接続のRunning実結合、停止中の管理sessionは.github #64の固定成果との受入が必要です。通常SSHの実ホスト・macOS GUI・実画面の物理操作・20/30/40人の集中取引と継続負荷は自動fixtureの成功と区別します。

DBのプロフィールだけに存在し、履歴・usercache・旧ディレクトリに現れないプレイヤーの全件列挙は、現在のHost/Management履歴契約では提供されていません。共通API側の追加契約が必要です。
