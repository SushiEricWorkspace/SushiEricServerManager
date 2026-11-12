# シミュレーターの装備・強化入力

`SimulatorEquipmentAssembler.resolve(SimulatorEquipmentInput(...), SkillLevelSnapshot(...))`は装備の固定入力から、`Ready`または`Unsupported`を返します。画面、保存先、オンライン接続、ItemStackを参照しません。

## 入力と有効化

`SimulatorEquippedItem`はItemBaseData、`Map<LevelledItemUpgradeDefinition, Int>`、カテゴリ別の非レベル強化を受け取り、Mapと倍率対象集合まで複製します。非レベル強化はカテゴリごとに1件で、カテゴリと定義を一致させます。

部位とItemTypeは`StatsPart.supportType`で照合します。OFF_HANDのSHORT_SWORDだけは追撃専用として受け入れ、通常の装備sourceとItemTypeへ登録しません。不一致の装備は`Unsupported`です。

装備の技能条件は固定した`SkillLevelSnapshot`と`ItemBaseData.skillLevelRequirements`の全条件を照合します。未指定技能はCoreと同じ初期levelです。条件未達の部位は`inactiveEquipment`へ理由を残し、能力source・特殊効果・メインハンドID/装備由来コスト・盾・オフハンド短剣予約から一括除外します。元装備は削除せず、技能条件を変える場合は同じ固定装備入力を再解決します。管理者による技能条件免除は通常シミュレーターの対象外です。

レベル制強化は固定Modの`ItemUpgradeDataManager.normalize`と同様に0以下を無効化し、上限超過を`maxLevel`へ補正します。対象ItemTypeと強化の対応はCoreの`canApplyTo`へ委譲し、不一致は無効化します。元の入力値を変更せず、無効化・補正の理由と有効levelを`adjustments`へ残します。非レベル強化はModと同じ`ItemUpgradeCategory.entries`順に解決し、Active入力ではCoreの`NON_LEVELLED_LEVEL`を使います。

## 対応表

| 強化 | 対応 | 経路 |
|---|---|---|
| Honing | 対象の剣系 | ItemStatsCalculationの固定値補正 |
| Melee/Bow/Crossbow/Armor Reforge | 各定義の対象ItemType | ItemStatsCalculationの固定値・倍率補正 |
| ArmorCustomEnchantment（PROTECTION/HP_BOOST/VITALITY/RUSH） | 各定義の対象ItemType | ItemStatsCalculationの固定値補正 |
| Melee BRAWN | 近接武器 | ItemStatsCalculationの筋力補正 |
| Melee SHARPNESS | 近接武器 | CoreのDamageMultiplierEffect、BEFORE_COMPLETE |
| LIFE_STEAL/FROZEN_EDGE/FIRE_ASPECT/KNOCKBACK等、上記以外 | 非対応 | 適用後効果・属性変更を省略した計算入力を返さない |

技能条件未達の装備、およびレベル0以下や対象ItemTypeと不一致の強化はActiveではないため、非対応効果の有無の判定対象にもなりません。有効な非対応強化を1件でも含む場合は`Unsupported`となり、部位・強化・理由と元の固定入力だけを公開します。部分的なSnapshotやReadyは返しません。

## 集計とPreparation

`ItemStatsCalculation`へ有効なレベル制/非レベル強化を渡し、`stats`のSelf適用済み固定値と、`statMultipliers`のAll/Parts未適用倍率を装備sourceへ変換します。Selfをもう一度sourceへ登録しません。基礎能力・技能source・装備sourceの最終集計、正規化と範囲制限は既存の`SimulatorPlayerInput`とCoreが行います。

`Ready.singleHitInput`は基礎能力・現在値と装備sourceから#264の入力を生成します。技能は装備の可否を解決した`Ready.skillLevels`を一度だけ適用し、別の技能値は受け付けません。方向からSOURCE/TARGETを選び、`ActiveItemUpgradeCollector`と`ActiveItemUpgrade.preparationEffects`で部位・カテゴリ順・登録順・宣言順・CD期限を持つ効果を渡します。独自の発動Kind条件や5%等の倍率式はManagerへ写しません。SHARPNESSのMAIN_HAND条件とSOURCE役割の判定もCoreに従います。Mobからの攻撃ではTARGETとして効果入力を保持し、SOURCE専用効果はCoreが適用対象から除きます。

特殊効果CDは`SimulatorEffectCooldownKey(role, providerKey, effectId)`で指定します。部位をキーへ加えず、同じ提供元・効果の期限を共有します。`Ready.preparationEffects`を複数命中サービスから利用する場合も同じ契約です。呼出し側が渡す既存の`DamagePreparationValues.specialEffects`は装備から解決する効果とは別の追加効果で、装備効果を重ねて渡さないでください。

直接近接のItemBaseData IDとコストは利用可能な固定メインハンドと`ItemResourceCostCatalog.normalDirectMelee`から生成します。利用可能なメインハンドがない場合はIDをnullとし、固定Modと同じく`normalDirectMelee(null)`から素手の任意コストを生成します。装備由来コストは使用しません。コストが不足する場合の任意強化や成功後消費の決定はCoreへ委譲します。オフハンド短剣はItemStatsCalculationの固定値を`ShortSwordFollowUpStats.fromStats`へ渡し、会心率以外の半減と必須コストをCoreに任せます。追撃は予約要求として保持するだけで、実行・HP変更・実消費は行いません。

## ガード

ガードの盾は利用可能なMAIN_HANDを優先し、次にOFF_HANDから選びます。盾なし・技能条件未達の盾ではガードを生成しません。構えていない有効盾も自動パリィの対象です。

`SimulatorGuardState`は`raising`、`blockUntilTick`、`parryUntilTick`だけを持ちます。省略時は構えなし・CDなしです。筋力は装備・技能を集計したplayer Snapshot、スタミナは同じ`dynamicStats`の現在値から取得します。装備由来の筋力や盾定義、現在スタミナをガード用に手計算し直す必要はありません。Player→Mobではプレイヤーガードを生成しません。

`singleHitInput`に渡す`DamagePreparationValues.playerGuard`はnullとします。非nullは二重の正本として拒否します。Coreの`PlayerGuardInput`を明示して能力値を直接指定する場合は、#264の`SimulatorInput`を使います。

発射時能力で計算する場合は発射時点の装備入力を固定し、`launchSnapshot`も明示します。現在の装備を暗黙に再取得する処理はありません。

## 検証

`SimulatorEquipmentAssemblerTest`は次の独立した期待値を固定しています。

- 基礎100、装備10/Self1.5、All1.1、MAIN_HAND向けParts1.2、COMBAT1でBASE112＋MAIN_HAND19.8＝131.8。
- 基準単発60に対しSHARPNESS level1/5で63/75。formulaは60を保ち、BEFORE_COMPLETE後だけ増加。
- BRAWN1は筋力20→25で62.5、SHARPNESS1は筋力20のまま63。
- Honingは物理+5/会心率+10。Reforge各群はCOMMON/ULTIMATEの端点値、防具強化は独立した定数値。
- SOURCE/TARGET・部位・登録順/CD、上限補正・対象外・非対応、通常剣の成功後コスト8、素手/技能未達装備の任意コスト24と充足/不足境界、短剣の半減と5tick後予約、入力不変性。
- BRAWN1＋COMBAT1の筋力27で、防御前120に対する部分パリィ後46.5。両手盾優先、構えなし、CD/スタミナ、盾なし、技能上昇/低下と複数要件に応じた全装備経路の切替。

対象Mod基準は`b5515a22a6086728d7189fab07c4da06b6a4cf32`、Coreは`981126c9b2157ef648196a30376fe60047bf558d`です。サーバー上の実適用、複数命中と画面受入はそれぞれ後続の範囲です。
