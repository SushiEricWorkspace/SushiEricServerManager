# 状態付き複数命中シミュレーター

`SimulatorEquipmentResult.Ready.multipleHitInput(...)`で、同じ攻撃者・対象・tickの順序付き入力を固定します。#265と同じ技能・装備判定を使い、`SimulatorMultipleHitSimulator.calculate(request)`からCoreの状態付き計算または厳密期待値を同期実行します。UI、保存、比較、実Entity更新は行いません。

## 入力

`SimulatorHit(attack, matched, cooldownTicks, launchSnapshot, hitId, pierceOverHeal)`を処理順に渡します。既定hitIdのUUID生成は行作成時だけです。再計算、copy、並べ替え、比較ではIDを維持し、削除したIDを別の矢へ再利用しません。命中IDの重複・空白、混在方向、発射Snapshotの当事者不一致、異なるworldは`Unsupported`です。

`baseStats`とReadyの技能・装備は指定時点の固定能力です。矢種・rank・発射地点からの距離は各行の`SimulatorAttack.PlayerProjectile`に保持します。式を発射時能力で評価する場合は発射時のReadyとbaseStatsを渡してください。`launchSnapshot`は履歴等の発射時記録であり、式の能力値を暗黙に置き換えません。SnapshotのMapは組立時に複製します。

現在のHP、OverHeal、ST、MP、構え、ガード・パリィCD、特殊効果CD、間隔、履歴、BREAKは`SameTickDamageState`を初期値の正本として渡します。単発用Mob入力の`currentHp`は複数命中の適用量には使いません。`targetBreakActive`は実際の状態異常有効状態であり、runtimeが残っていてもfalseを指定できます。tick末cleanupは行いません。初期stateは組立時にコピーして内部へ隔離し、公開`initialState`と実行時のCore入力は毎回値コピーを返します。Core結果の前後stateや公開取得値を変更しても、保存中の要求や次の再計算へ反映しません。

ガードはReadyの有効盾（MAIN_HAND→OFF_HAND）と組立済STRから生成し、現在ST・構え・CDはCoreが各行の状態から参照します。装備強化は`activeUpgrades`経由で一度だけ渡します。`extraEffects`には装備由来を重ねず、独立した解決済み効果だけを指定します。部位・SOURCE/TARGET・CDは維持され、全効果の対応判定をCoreへ委譲します。効果定義はCoreと同じ純粋で不変の定義を使ってください。

近接のIDとコストはReadyの装備から決まり、素手・技能未達メインハンドは`normalDirectMelee(null)`の任意ST24を維持します。HP適用成功後だけ履歴・攻撃コスト・間隔・BREAKが進み、拒否時も準備中ガード消費とCDが後続へ渡ります。成功後の確定コスト不足例外もCoreの契約を維持します。

## 射撃単位の記録

`SimulatorShotPlan`は成立済み1射のmetadataです。指定時は、未命中を含む全行が投射物で、発射本数と行数が一致する必要があります。

| 明示profile | 発射本数 | 矢消費 | 記録条件 |
|---|---|---|---|
| `bow(BowContent)` | `2*max(multi,0)+1` | 1 | 標準弓の成立済み1射 |
| `standardCrossbow(CrossbowData)` | `max(arrowCount,0)` | `ceil(本数*(1-clamp(効率,0,1)))` | 標準profileのチャージ完了時に一度 |
| `manual(...)` | 手入力 | 手入力 | 空白以外の条件を明示 |

標準profileの射撃時ST/MPコストはありません。手動profileの`resourceCosts`も一射の記録として保持し、initialStateから再消費しません。命中行へ矢消費や射撃コストを分配せず、能力値も本数で割りません。固定Modと同じ直接投射物経路を使い、本数を理由にMultiShot効果を選びません。Item ID別の独自profileは自動識別せず、利用者が標準条件または手入力条件を明示します。未命中数は`hits.size - matchedCount`で確認できます。

## 実行と結果

```kotlin
val input = equipment.multipleHitInput(position, baseStats, mob, tick, initialState,
    hits, targetX, targetZ, shotPlan = shotPlan)
val request = SimulatorMultipleHitRequest(generation, input, SimulatorMultipleHitMode.Expected)
val result = SimulatorMultipleHitSimulator.calculate(request)
```

`Expected`はCoreの全状態分岐を列挙した主指標で、行ごとの周辺期待値・適用確率・拒否理由確率と終端枝を保持します。`Sample(seed)`はhitIdと版付き安定乱数による一回の実行です。結果の`randomVersion`とseed、および各行traceを記録できます。同じIDの抽選値は他行の分岐や並べ替えで消費ずれしません。

どちらも結果の`result`にCoreの行と合計を省略せず保持します。`calculatedDamage`/`meanCalculatedDamage`は算出値、`healthDamage`/`meanHealthDamage`は純HP減少、`overHealDamage`/`meanOverHealDamage`はOverHeal吸収、`appliedDamage`/`meanAppliedDamage`は両方込みです。過剰ダメージ、未命中、死亡後、Preparation拒否を適用量に混ぜません。行別の前後stateと計算内訳・PreparationはSampleのtransitionから、Expectedの最終状態はbranchesから参照します。期待値を行ごとに独立計算して合計する処理はありません。

取消、評価65,536回上限超過、対応外はそれぞれ専用結果型で返し、数値の途中結果を公開しません。`request.cancel()`はExpectedの各Core評価前へ接続します。SampleはCore一括実行の前後で確認します。スレッド生成とgeneration採番、古い世代を表示しない判断は利用側の責務です。

追撃、連鎖、時間進行、遅延volley、飛翔衝突、DPSは実行しません。後続状態へ影響する未対応強化・効果・毒矢は全体対応外です。有効なオフハンド短剣を持つPlayerMeleeも、Coreの同tick状態に追撃予約を表現できないため全体対応外とし、「オフハンド短剣の追撃予約は未対応」と返します。非近接、および技能未達で予約がない短剣は許容します。サーバー受入やJavaFX画面をこの純サービスの検証に含めません。

固定依存はDataModel `93220ce834ab7957f9d480eaec27cfb76ee1052d`、CombatCore `0c5b1a4cd431d3394ce01e9f118e1a7d7f4920ab`、Mod仕様参照 `b5515a22a6086728d7189fab07c4da06b6a4cf32`です。
