# 戦闘シミュレーターの単発入力

`feature.simulator`はJavaFX、EditorSession、保存先、通信へ依存しません。`SingleHitSimulator.calculate`を呼出し側のスレッドで実行し、Coreの単発サンプルまたは厳密期待値を返します。

## 入力

- `SimulatorPlayerInput`はスキル・装備を含まない全基礎能力、技能レベル、装備Part別source、ItemType、全動的能力を受け取ります。基礎値の既定値を暗黙に補いません。
- 装備sourceは`ItemStatsCalculation`のSelf適用済み固定値と、All/Parts未適用の倍率です。部位寄与への集計前に渡します。`StatsPartCalculator`の計算済み取り分や補正済み最終能力値を渡すと二重適用になります。BASE/SKILLを装備sourceとして渡すことはできません。技能sourceは`SkillStatsSource.sourceOf`で一度だけ生成します。
- 集計・正規化・範囲制限はCoreへ委譲します。入力条件の比較には複製済み`baseStats`、`skillLevels`、`equipmentSources`、`itemTypes`、`dynamicStats`、`attackSpec`を使用でき、集計結果は`player.snapshot`から読み取れます。
- `SimulatorMobInput`は全Mob能力、手段別攻撃力、現在HPを受け取り、複製します。現在HPは最大HPから補完しません。
- `SimulatorAttack`はプレイヤー/Mobそれぞれの近接・直接投射物・直接魔法の6種類です。近接/魔法のEffectは対応するDirect型だけを受け取ります。プレイヤー投射物は矢種、rank、発射地点からの距離を保持します。
- `DamagePreparationValues`には判定時点のtick、現在リソース、対象座標、BREAK、ガード/パリィ/CD、攻撃履歴、解決済み特殊効果を明示します。Snapshotの動的能力は撮影時点の値であり、Preparationの現在リソースとは区別します。
- `launchSnapshot`は発射時の情報です。式は`player`/`mob`に渡したSnapshotを使用します。発射時能力で式を評価する投射物では、発射時に生成した入力を渡してください。攻撃時tick・アイテム・基本ダメージを現在値で再撮影しません。

入力のMap、sourceの倍率List/対象Part集合、履歴、特殊効果List、ItemBaseData、発射SnapshotのMob攻撃Mapは元入力から分離します。特殊効果の定義自体にはCoreと同じ純粋で不変な定義を使用します。装備取得・強化効果の収集はこのサービスの責務には含みません。

## 要求と結果

`SingleHitRequest(generation, input, SingleHitMode.Sample(seed))`または`SingleHitMode.Expected`を指定し、`SingleHitSimulator.calculate(request)`へ渡します。同じ固定入力とseedは同じサンプルを返します。行IDに基づく抽選記録と複数命中は別のサービスで扱います。

結果は要求の`generation`をそのまま返します。呼出し側は最新世代との一致を確認して表示します。`request.cancel()`はスレッド間で利用でき、サービスは開始前とCore評価後に確認します。Core評価中の処理は中断せず、取消された結果の数値を返しません。スレッドの生成やUIへの通知は行いません。

`Sample`/`Expected`はCore結果を保持します。`formula`は会心・防御適用済みで、`preparation.phaseTrace`はその後の準備処理の記録です。Preparationの会心倍率1/防御0を式全体の内訳として表示しないでください。成功後リソースcost、拒否理由、拒否前の準備効果、追撃等の予約要求は保持しますが、HP変更・消費・CD更新・予約は行いません。単発期待値は拒否を0として加重平均し、予約した後続攻撃を含みません。

期待値の追加乱数要求は`Unsupported`、取消は`Cancelled`となり、数値を持ちません。通常サンプルはCoreが要求する乱数へseed付き乱数を供給します。外部乱数や可変状態を参照する特殊効果の再現性・期待値は契約の対象外です。

## 検証基準

`SingleHitSimulatorTest`はCore直接呼出しとの比較に加え、固定Mod `b5515a22a6086728d7189fab07c4da06b6a4cf32`の`SkillStatsSynchronizer`にあるCOMBATの筋力+2/level、攻撃3種のAll倍率+2%/levelを独立した数値で照合します。スキル0/1/最大、装備倍率との加算合成と二重適用防止を確認します。

単発基準は基礎物理95・筋力20・魔法60・知力100・防御100で、近接/投射物/魔法60、物理会心50%増・会心率25%で平均67.5、完全パリィ率40%で平均40.5です。通常近接のスタミナ充足は1.25倍で75になります。矢rank、距離、BREAK、Mob手段別攻撃力、ガード拒否前の消費/CD、seed再現、入力不変性、取消/非対応も検証します。これらの期待値は実装呼出しから算出せず固定しています。Modサーバーの実適用試験や画面表示の受入は別途必要です。
