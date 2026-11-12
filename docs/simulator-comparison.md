# 戦闘結果の比較

`SimulatorComparisonSnapshot`と`SimulatorComparisonSession`は、#266の評価済み結果を比較するUI非依存モデルです。Coreの戦闘計算、入力の再集計、ファイルI/O、永続保存は実行しません。

## 記録と基準操作

```kotlin
val request = SimulatorMultipleHitRequest(generation, input, SimulatorMultipleHitMode.Expected)
val result = SimulatorMultipleHitSimulator.calculate(request)
val record = SimulatorComparisonSnapshot.capture(request, result, version)
val comparison = SimulatorComparisonSession()
comparison.setBaseline(record)
val delta = comparison.compare(currentRecord, latestGeneration)
comparison.clearBaseline()
```

`version`は`SimulatorComparisonVersion(combatCore, dataModel, calculation)`です。Core/DataModelの固定座標やcommit/hash、Manager計算仕様の固定識別子を呼出し側が明示します。同一仕様には同じ識別子を使用し、固定依存記録の読込は呼出し側の責務に留めます。Sampleの乱数版は結果の実`randomVersion`を正本とし、別のcaller版を要求しません。seedと抽選記録も既存結果から保持します。

captureは要求と結果のgeneration、完了モード、Sampleのseedの不一致を拒否します。#266で固定済みの入力Readyはそのまま再利用し、取消tokenや乱数器は保持しません。装備ItemBaseData（Loreの行/sectionリストを含む）と装備sourceの公開取得値は既存入力型側でprivate固定記録から値コピーを返し、公開Map/Listの変更を固定Readyの入力説明値へ戻しません。Core結果の行、状態のMap/履歴、抽選記録、Preparation、期待値の確率Map/終端枝はcapture時に複製します。公開`result`も毎回値コピーを返すため、元の結果や取得した結果のcollection変更で基準は変わりません。コピーは戦闘計算を再実行しません。

基準の保持/更新は`setBaseline`、解除は`clearBaseline`だけです。比較や現在入力の変更で自動更新しません。一セッションの基準は一つの評価モードです。ExpectedとSampleを両方保持する呼出し側はモード別のセッションを使い、数値を混ぜて比較しません。操作は呼出し側で直列化します。

基準は過去のgenerationでも保持できます。`compare(current, latestGeneration)`は現在記録だけを最新要求世代と照合し、不一致なら`Stale`として数値差を返しません。古い完了通知を現在値として採用しないため、latestGenerationには呼出し側が採番中の現在世代を渡します。

## 比較可能な結果

主指標は`SimulatorComparisonResult.Expected`です。Core23の状態付き厳密期待値をそのまま使い、一行の単発平均を独立に足し合わせません。Sampleは別の`Sample`結果として扱います。

Core/DataModel/計算仕様の識別子、モード、双方の完了Sampleの乱数版が一致した場合だけ、双方Completedの差を返します。seedが異なるSampleはそれぞれのseedを保持し、同じ抽選条件だったとは表示しません。期待値にはseedを使用しません。

- `NoBaseline`: 基準なし。
- `Stale`: 現在記録の世代が古い、または最新世代と不一致。
- `Incompatible`: 版またはモードが不一致。
- `Incomplete`: 取消、上限超過、非対応を含むため比較差分なし。完了側の実評価記録と未完了側の理由は保持し、失敗側を0や途中値で補いません。

これらには比較のtotals/rows差分がありません。評価65,536回の上限や取消・非対応の理由/回数は#266の結果を保ちます。

## 値と行の差

`SimulatorDamageChanges`は算出値、純HP減少、OverHeal吸収、HPとOverHealを含む適用量を別々に保持します。差は`current - baseline`の符号付き値で、増減率はその差を基準で割った百分率です。基準0では0→0も含め割合をnullとし、0%とは扱いません。過剰ダメージや死亡後の未適用値を純HPへ足しません。

各行は既存hitIdで対応付けます。現在行の順で出力し、削除行はその後に基準の順で付けます。共通行は`Matched`で元/現在のindexと数値差、追加は`Added`、削除は`Removed`です。追加/削除へ架空の0行を補わず、IDを再採番しません。`Matched.moved`はindexの変化を示します。

期待値行は周辺平均、状態/拒否理由の確率Mapを保持し、終端枝は記録のCore結果から参照できます。混合分岐に一意のbefore/after状態を割り当てません。Sample行の具体的なbefore/after・phaseTrace・拒否/死亡/BREAK/CD差はCoreのtransitionから参照します。

## 抽選の差

Sampleの共通hitIdについて、`DamageRollKind`と同種内の出現番号で`SimulatorDrawComparison`を作ります。抽選値、確率、成立はCoreの`Reached`をそのまま保持し、`NotReached`には値を補完しません。両側未到達のoccurrenceはnullです。片側だけ追加の出現がある場合は、対応する記録がない側をnullとします。

同じseed・hitIdでは追加/削除や前行の分岐で後行のReached値はずれません。一方、判定確率や前行のHP/CD等が変われば、同じ値でも成立・未到達・ダメージは変わります。この条件差と乱数値の差を別に表示できる記録です。

## 検証

`SimulatorComparisonTest`は同一条件差0、装備/防御/距離変更、基準0、過剰ダメージ/OH分離、行追加削除/順序変更、Reached値固定と確率変更、パリィCDによる後行未到達/拒否差、死亡後行と終端枝、世代/モード/seed整合、未完了・版違い、元Map/結果collection/公開getter変更後の基準不変性をUIなしで確認します。

比較モデルはサーバー実適用や画面受入を代替しません。固定入力・計算規則・結果の整合は#266とCore、Mod結合と画面受入は後続の検証範囲です。
