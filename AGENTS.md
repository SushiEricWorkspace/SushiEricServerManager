# AGENTS.md

このファイルはSushiEricServerManagerリポジトリ固有のAI開発規約です。
`COMMON-RULES`ブロックは`SushiEricWorkspace/.github/AI_GUIDELINES.md`から同期されるため、直接編集しないでください。

<!-- COMMON-RULES:START -->
## Organization共通ルール

このセクションは`SushiEricWorkspace/.github/AI_GUIDELINES.md`を正本とする。
各リポジトリの`AGENTS.md`にある`COMMON-RULES`ブロックは自動同期対象であり、直接編集しない。

### 基本方針

- 回答、コードコメント、KDoc、ドキュメント、コミットメッセージは日本語で記述する。
- 依頼内容と直接関係しないファイルは変更しない。
- 作業開始前に対象ファイルと直接依存する実装を確認する。
- リポジトリ全体を無条件に探索せず、必要な範囲を段階的に確認する。
- 過去の会話、古い資料、推測より、現在の作業対象ブランチにあるコードを優先する。
- 既存の命名、責務分割、パッケージ構成、改行、インデントに合わせる。
- 無関係なリファクタリングや一括フォーマット変更を同じ作業へ含めない。
- 実行できなかった確認や検証を、完了または成功したものとして報告しない。

### KDoc・ドキュメント

- 公開クラス、interface、object、enum、公開関数・プロパティには、用途や契約が名前だけでは十分に分からない場合KDocを付ける。
- KDocは単なる名前の言い換えにせず、責務、引数、戻り値、前提条件、制約、副作用、例外など利用側が必要とする情報を記載する。
- 使い方が自明でない公開APIには、必要に応じて短い使用例をKDocへ含める。
- 実装詳細を説明するためだけの冗長なKDocは避ける。
- ドキュメントやKDocのコード例は、現在存在する型、メソッド、引数、パッケージと一致させる。
- 実装変更によって既存ドキュメントが不正確になる場合は同じ作業内で更新する。

### データ形式の変更（破壊的変更）

- まだリリースまたは実環境へのデプロイが一度も行われていない機能・データについては、ユーザーから明示的に求められない限り、保存形式変更に対する旧形式からの移行処理や後方互換処理を追加しない。
- この方針により既存の開発用保存データとの互換性を破壊した場合は、作業完了時にその旨と、既存データの再生成・削除等が必要であることを明示する。

### 「反証して」と指示があった場合

- ユーザーから明示的に指示されない限り、Issue作成やコード変更は行わず、確認した問題だけを出力する。
- 次の観点を対象とする。
  - 共通化可能な冗長箇所。
  - importを使用して短縮できる呼び出し（例: `io.github.sushiericworkspace.sushiericservermod.SushiEricUISoundType.UI.play()`から`SushiEricUISoundType.UI.play()`への短縮）。
  - 不適切なメソッド名、クラス名、インターフェース名、パッケージ名、パッケージ構成、およびその他の名前。
  - 役割がほぼ重複している、または片方が不要なメソッド、クラス、インターフェースなど。
  - 例外を起こす可能性のある箇所。
  - 意図しない挙動を起こす可能性のある箇所。
  - その他、問題になり得る箇所。
- 確認範囲が明示されていない場合はリポジトリ全体を確認せず、一定範囲だけを確認し、出力時にその確認範囲も明示する。

### ビルド環境

- JDK 21を使用する。他のバージョンではビルドが失敗する。
- Gradle daemonのJVMは各リポジトリの`gradle/gradle-daemon-jvm.properties`で21に固定している。`JAVA_HOME`が別バージョンを指していても、daemonはJDK 21で起動する。このファイルを削除・変更しない。
- 上記はJDK 21がインストール済みであることを前提とする。未インストールの環境では`No matching toolchains`で失敗するため、JDK 21を導入する。
- 新しいJDKでビルドすると次の失敗が起きるため、原因を切り分ける手がかりとする。
  - `Inconsistent JVM-target compatibility detected for tasks 'compileJava' and 'compileKotlin'` … Javaのtoolchain指定が効いておらず、KotlinがGradleのJVMを使っている。
  - `IllegalArgumentException: <バージョン>` が`org.jetbrains.kotlin.com.intellij.util.lang.JavaVersion.parse`で発生 … KotlinコンパイラがそのJDKに未対応。daemonのJVMを21へ固定して回避する。
- Javaのtoolchain指定を実行中のJVMバージョンで条件分岐させない。条件付きにすると、新しいJDKでGradleを動かしたときにtoolchainが未設定となり、JavaとKotlinのJVM targetが食い違う。

### Git操作

- commitまたはpushは、ユーザーが明示的に依頼した場合だけ実行する。
- 作業前に対象ブランチの最新状態を確認する。
- 複数のIssueを同時に依頼された場合でも、ユーザーからまとめるよう明示されない限り、Issueごとに作業ブランチとPull Requestを分ける。
- 後続Issueが別Issueへ依存する場合も同じブランチへ変更を混在させない。前提Issueの変更をコミットした後、そのブランチまたはマージ済みの開発基準ブランチを起点として後続Issueのブランチを作成する。
- 前提Issueをコミットする許可がなく、後続Issueのブランチを正しく作成できない場合は、前提Issueだけを作業し、コミットの許可を得るまで後続Issueのコード変更を開始しない。
- force pushは明示的に依頼されない限り使用しない。
- ユーザーの未コミット変更や無関係な変更を破棄、上書き、同梱しない。
- コミット前に差分を確認し、依頼範囲外のファイルが含まれていないことを確認する。
- 同一目的の実装、呼び出し元変更、必要なドキュメント更新は、原則として1つの論理的なコミットにまとめる。
- 無関係な変更は同じコミットへ含めない。

### worktree運用

作業の分離単位はAI名ではなくタスクとする。同じタスクの担当を交代するときは同じworktreeを引き継ぎ、同時に書き込むownerは一人だけとする。

| ディレクトリ | 用途 |
|---|---|
| `<workspace>/<repo>/` | 人間・IDE（primary worktree） |
| `<workspace>/worktrees/<task-id>/<repo>/` | タスク専用worktree |
| `<workspace>/minecraft-dev/` | ソースとは独立した分離テスト環境（導入中） |

- primaryや他ownerのworktreeで作業しない。primaryの移動・checkout変更をAIが行わない。
- 作成・一覧・担当引継ぎ・削除は`.github/scripts/setup-worktrees.py`を使用する。task-id、Issue番号、対象repo、同時書込ownerを明示し、記録された基準commitから`feature/issue-<番号>`を作成する。使い方は`.github/scripts/dev_server/worktrees-guide.md`を参照する。
- 基準はrepoごとの最新origin開発ブランチを用いる（Common・ServerManager・CombatCoreはdevelop、ServerMod・.githubはmain）。前提Issueへ依存する場合は、repoごとに依存commitを明示する。現在のprimaryやorigin/HEADのリリースブランチから推測しない。
- 同一タスクの担当交代はownerを引き継ぐ。別worktreeを追加して同じタスクを二重実行しない。Gitのworktree lockは編集排他ではない。owner記録は同一ユーザー間の協調契約であり、他ownerは直接書き込まない。
- 既存branch/worktreeを上書きしない。未コミット変更がある作業の引継ぎ・削除は拒否する。途中失敗や登録不一致は既存の実体を残し、勝手に修復・強制削除しない。
- タスクworktreeへ`run/`や`.idea/`を複製しない。`build/`とプロジェクト`.gradle/`はworktreeごとに独立する。サーバーの永続instance、snapshot、artifactはworktreeの削除対象にしない。
- 削除はtask-idと現在ownerを明示して行う。worktreeをディレクトリ削除や広範囲のpruneで片付けない。branchは自動削除されないため、マージと削除の許可を確認して別途削除する。
- 移行中の既存`<repo>-claude/`・`<repo>-codex/`は自動移動・削除しない。進行中の作業は担当の固定worktreeで完了できる。作業branch削除後は最新の`origin/main`または`origin/develop`でdetached HEADへ戻す。新規タスクはタスクworktreeで開始する。
- 共通ルールの同期先は`scripts/sync-agent-rules.py --repo-root REPO=PATH`で明示する。タスク作業から人間・他AIのAGENTS.mdへ暗黙に同期しない。
- worktreeスキルの原本は`.github/skills/worktree/SKILL.md`とする。ローカル配置する場合は原本を使用し、`.claude/`等の配置先をコミットしない。
- 分離テスト環境の管理CLIは総合導入が完了するまで必須にしない。導入中は既存の検証手順を維持し、未実装の管理CLIを前提にサーバー起動や保存の成功を報告しない。

### コミットメッセージ

コミットメッセージは次の形式で日本語を使用する。

```text
type: 変更内容の概要

- 具体的な変更内容
- 具体的な変更内容
- 具体的な変更内容
```

概要は変更の目的が分かる簡潔な文にする。
主な`type`は`feat`、`fix`、`refactor`、`docs`、`test`、`build`、`chore`とする。

### ラベル

- ラベル名は`<分類>: <値>`形式を使用し、分類名と値は小文字の英字で統一する。
- 優先度ラベルは次の4種類だけを使用する。
  - `priority: critical`
  - `priority: high`
  - `priority: medium`
  - `priority: low`
- 種別ラベルは次の7種類だけを使用する。
  - `type: bug`
  - `type: feature`
  - `type: refactor`
  - `type: docs`
  - `type: test`
  - `type: build`
  - `type: chore`
- `priority`はIssueとPRへ必ず付与する。ユーザーが優先度を明示した場合はその指定に従い、明示がない場合は依頼内容、Issue、既存情報をもとに次の基準で判断して付与する。
  - `priority: critical`: データの損失、情報の漏洩、サーバーやアプリの停止など、影響が回復困難、または即時の対応が必要なもの。
  - `priority: high`: 主要な機能が利用できない、または回避策が設定ファイルの直接編集や手作業に限られるもの。
  - `priority: medium`: 利用に支障はあるが、実用的な回避策があるもの。
  - `priority: low`: 利便性の向上にとどまり、現状の操作でも同じ結果を得られるもの。
- AIが判断して付与する場合は、Issueやラベルの作成・更新をユーザーへ提示する際に、選んだ優先度とその根拠を併せて示す。
- PRの`type`ラベルは主な変更種別に合わせる。`fix`は`type: bug`、`feat`は`type: feature`とし、`refactor`、`docs`、`test`、`build`、`chore`は同名の`type`ラベルへ対応させる。
- 上記と同じ意味の別表記、大文字小文字違い、接頭辞なしのラベルを作成・使用しない。
- 一覧にないOrganization共通ラベルをAIが独断で新規作成しない。

### Pull Request

- PRを作成する場合は、SushiEricWorkspaceのOrganization共通PRテンプレートに従う。
- PRタイトルはコミットメッセージと同様に`type: 概要`形式の日本語とする。
- PR本文の見出しは`概要`、`変更内容`、`検証`、`関連Issue`の順で使用し、独自に追加・削除・名称変更しない。
- `概要`にはPRの目的を簡潔に記載し、`変更内容`には実際に行った変更を箇条書きで記載する。
- `検証`では実際に実行した項目だけを完了済みとして扱い、未実行のテスト、ビルド、動作確認を成功済みとして記載しない。
- Issue対応を完了するPRでは`Closes #<issue番号>`、関連付けのみの場合は`Refs #<issue番号>`を`関連Issue`へ記載する。関連Issueがない場合は`なし`と記載する。
- Issue本文に記載済みの仕様を長文で転載せず、実際の変更内容と検証結果を簡潔に記載する。

### 完了報告

commitとpushを依頼された場合は、次を簡潔に報告する。

- 主な変更内容
- ビルド、テスト、動作確認の結果
- コミットハッシュ
- pushしたブランチ

明示的に求められない限り、変更コード全文や長い実装解説は掲載しない。
<!-- COMMON-RULES:END -->

下位ディレクトリに別の`AGENTS.md`がある場合は、より作業対象に近いファイルの指示を優先してください。

## ブランチ運用

- `main`はリリース用ブランチとして扱う。
- `develop`は通常の開発用ブランチとして扱う。
- 新機能、修正、リファクタリング、ドキュメント更新は、原則として最新の`develop`を基準に行う。
- 作業はIssueごとに`feature/issue-<Issue番号>`を作成して行う。複数のAIを同時に運用するため、共有のAI専用ブランチは使用しない。
- 作業ブランチは常に最新の`develop`を起点として作成し、`develop`宛てのPRを作成する。
- マージ済みの作業ブランチは、ユーザーの許可を得たうえでリモートとローカルの両方から削除する。
- ユーザー管理のローカル環境で作業する場合は、AI専用ブランチへ切り替えず、人間が現在使用している開発ブランチを維持する。commitやpushの許可がある場合は、そのブランチへ反映する。
- ユーザーが明示的に`main`を指定しない限り、`main`へ直接commitまたはpushしない。
- リリース作業や`develop`から`main`への反映は、ユーザーから明示的に依頼された場合だけ行う。
- ユーザー管理の環境で作業対象ブランチが指定されていない場合は、`develop`を使用する。
- 存在しない`develop`などの基準ブランチを、AIが独断で新規作成しない。

## プロジェクト構成

- このリポジトリは`SushiEricServerManager`の単一Gradleプロジェクトで構成する。
- `Common`は独立したリポジトリ・Gradleプロジェクトとして管理し、ローカルでは`../Common`の`publishDevelopment`で発行されたEditor専用artifactを兄弟共通の`.common-dev-repository`から参照する。
- データモデル、データ種別、Manager、Validator、Serializer、共通RegistryはCommon側を正とし、ServerManager側へ重複定義しない。
- JavaFXなどEditor固有の依存をCommonへ持ち込まない。
- JavaFX画面、FXML Controller、編集ロジック、View、Service、SSH/SFTP通信、設定、アップデート処理を既存の`src`配下の責務に合わせて配置する。

## データ設計

- 既存の`ManagedData`、`SushiEricDataType`、Manager、Validatorの構造を優先して使用する。
- 新しい管理データを追加する場合は、ID、完成状態、データ種別、検証、保存、読み込み、複製の責務を確認する。
- 可変データを持つ型の`deepCopy()`では、元データとコピー先で`MutableList`、`MutableMap`、ネストされた可変オブジェクトの参照を共有しない。
- `completed`はValidatorの検証結果と一致させ、検証を通さず固定値として扱わない。
- データ種別ごとの保存先や生成処理は、既存の`SushiEricDataType`へ集約できる場合は個別分岐を増やさない。
- データ種別固有の読み書きは対応するManagerへ委譲する。
- ID、ファイル名、ディレクトリ名、YAMLキーなど既存保存データに関係する識別子を理由なく変更しない。
- Serializerや保存形式を変更する場合は、既存YAMLの読み込みと書き戻しを確認する。
- nullable、ダミー値、booleanフラグより、sealed型、enum、専用結果型で不正な状態を防げる場合は既存の型設計を優先する。

## JavaFXと画面実装

- FXML Controllerは、FXML要素とイベントの接続、画面状態の更新、処理の呼び出しを担当する。
- データI/O、SSH通信、複雑な編集処理をControllerへ直接集約せず、既存のService、Logic、Viewへ分離する。
- FXMLから参照するフィールドは既存コードに合わせて`@FXML private lateinit var`を使用する。
- FXMLから呼ばれるメソッドは必要に応じて`@FXML`と`@Suppress("unused")`を使用する。
- 画面遷移は既存の`Utility`、`AppScreen`、WindowManagerなどの経路を優先する。
- ダイアログ表示は既存の`CustomDialog`や専用Dialogクラスを使用する。
- ユーザー向けエラーはダイアログで表示し、開発者向け詳細はSLF4J Loggerへ出力する。
- JavaFXのNodeやStageを操作する処理はJavaFX Application Threadで実行する。
- SSH、SFTP、ネットワーク、重いファイルI/OをJavaFX Application Thread上で長時間実行しない。
- バックグラウンド処理から画面を更新する場合は`Platform.runLater`など既存の方法を使用する。
- FXML、Controller、CSSの変更は相互に確認し、`fx:id`、イベント名、スタイルクラスの不一致を残さない。
- 共通化できる見た目は`resources/css/common`を優先し、画面固有CSSへ同じ定義を重複させない。

## 編集処理とセッション

- 編集対象のデータと元データを分離し、保存前の編集で元データを意図せず変更しない。
- 既存のEditor Logic、Editor View、Editor Data Serviceの責務分割を維持する。
- データ種別ごとの操作は`EditorDataService.DataAccess`など既存の型付きアクセサを優先する。
- 読み込み、保存、削除、リネームの結果は既存の`LoadResult`、`SaveResult`、`DeleteResult`、`RenameResult`などの結果型を使用する。
- `EditorSession`へ共有状態を追加する場合は、接続や画面間共有に本当に必要か確認し、不要なグローバル状態を増やさない。
- エディターウィンドウやSSH接続は、画面終了時や切断時に既存の管理クラスを通して解放する。
- 自動保存バックアップの`editing`と`original`の意味を維持し、片方だけを更新して復元不能な状態を作らない。

## SSHと設定

- SSH/SFTP処理は既存の`SshManager`、接続Service、Security関連クラスへ集約する。
- ControllerやEditor LogicからSSHJ APIを直接操作せず、通信層へ委譲する。
- ホスト鍵確認、known hosts、公開鍵認証、秘密鍵、パスワードの扱いを弱める変更を行わない。
- 秘密鍵、パスワード、トークン、実サーバー情報をリポジトリへ追加しない。
- 認証方式やOS差異は既存の`AuthenticationType`、`RemoteOperatingSystem`、`OS`などの型を利用する。
- WindowsとmacOSでパスや保存先が異なる処理は既存の設定・パス解決クラスへ集約する。
- 設定ファイル形式を変更する場合は、既存設定の読み込み互換性を確認する。

## コード変更時の確認

- 変更対象の公開APIと呼び出し元への影響を確認する。
- 新しいデータ型を追加した場合は、Manager、Validator、Serializer、SushiEricDataType、Editor、差分表示への影響を確認する。
- sealed型や網羅的`when`へ種類を追加した場合は、影響するすべての分岐を確認する。
- 新しい画面やEditorを追加した場合は、画面遷移、WindowManager、FXML、CSS、Controller、Logic、Serviceへの接続を確認する。
- 通信処理を変更した場合は、接続、切断、失敗時、タイムアウト、認証エラー、リソース解放を確認する。
- unchecked castや強制キャストを避け、generic型や既存の型付きAPIを使用する。
- 既存の結果型や例外処理を無視し、単純なbooleanやnullだけへ置き換えない。

## GitHub Actions

- GitHub Actionsのworkflow（`.github/workflows/`）は、人間の明示的な許可がない限り、AIが実行しない。`gh workflow run`、`gh run rerun`、GitHubの画面や`workflow_dispatch`のAPIからの実行を含む。
- 実行を許可された場合は、実行の前に、対象のOS、入力値、見込みの消費（分数）を人間へ提示する。許可された1回だけを実行し、同じ許可で再実行しない。
- push、pull_request、scheduleなど、自動で実行されるトリガーを、人間の許可なくworkflowへ追加しない。現在のworkflowは、`workflow_dispatch`（手動実行）だけで動く。
- 無料枠を圧迫しないよう、対象のOSは必要なものだけを選ぶ。macOSのランナーは、非公開リポジトリではLinuxの10倍、Windowsは2倍の分数を消費する。
- workflowを追加・変更するときは、`timeout-minutes`と`concurrency`を設定する。

## ServerManager固有ドキュメント

- ドキュメントを変更する場合は、現在の`develop`上のコードと照合する。
- 「従来は」「以前は」「移行前は」など過去の実装経緯は原則として記載せず、現在の使用方法、型構造、内部処理、登録方法、制約、注意事項を記載する。

## 検証

- コード変更後は可能な環境で`./gradlew build`を実行する。
- Common変更後は兄弟ディレクトリのCommonで`./gradlew publishDevelopment`を実行してから確認する。
- 関連するテストが存在する場合は実行する。
- JavaFX画面を変更した場合は、FXMLロード、画面表示、主要操作を確認する。
- データ形式を変更した場合は、既存YAMLの読み込み、保存、再読み込みを確認する。
- SSH処理を変更した場合は、接続成功だけでなく、認証失敗、切断、キャンセルも確認する。
- WindowsやmacOS向けの`jpackage`タスクは、リリースまたはパッケージ作成を依頼された場合だけ実行する。
- OS固有のパッケージタスクを、対応していないOS上で成功したものとして扱わない。
