# 内容確認用プレビュー

表示はJavaFXと同梱Noto Sans JPで行う。Minecraftの外観・pixel寸法を再現する表示ではない。通常版とdevelopment版は同じ描画実装・素材を使用する。

## Componentと書体

`ComponentSegments`はTextComponentのchildren、色、font、5装飾を行へ展開する。装飾の明示falseは親の継承を解除する。クリック・hover・insertionは表示runへ渡さない。未解決の翻訳Componentは展開せず拒否する。

未指定とminecraft:default/minecraft:uniformはManager標準書体による確認表示である。Regular/Boldの原本を使用し、斜体は描画時にShearを適用する。下線・取消線・影・静的難読化・空行を表示する。難読化はcodepointから決まる固定文字であり、アニメーションしない。

minecraft:alt、minecraft:illageralt、未知fontは未対応としてfont ID・元の内容を表示する。警告runと本文runを分け、本文の色・太字・斜体・下線・取消線を維持する。未対応fontの元内容の確認表示には難読化を適用しない。同梱cmapにない文字はU+表記で明示する。画像のaccessibleTextにも元の内容を保持する。保存Componentやfont IDを書き換えず、プレビュー都合の保存・アップロード拒否は行わない。特殊英字2書体、Minecraft精密寸法、Color pack・sbix・任意resource packは対象外である。

## 同梱原本と通知

Noto Sans JP Version 2.004のRegular/Boldは[公式固定commit](https://github.com/notofonts/noto-cjk/tree/523d033d6cb47f4a80c58a35753646f5c3608a78/Sans/SubsetOTF/JP)の原本を改変・改名せず同梱する。出典・著作権・OFL全文はresources/licenses/previewに置く。フォントのOFLとアプリの権利を区別する。

自作の共通アイテム記号と未収録記号はresources/images/previewのSVGを正本とする。Minecraft素材の抽出・変換・トレースを使用しない。`python3.12 -B scripts/prepare-preview-symbols.py`は標準ライブラリだけで32px PNGを再生成する。変更時はpreview-assets.propertiesのhashを更新し、両OSの実表示を確認する。

`PreviewResources`は原本・cmap・画像・通知のSHA-256と一覧を検査する。欠落・改変時はプレビューを停止し、利用者へエラーを表示する。実行時download、外部cache、OS font探索、git/Python bootstrapは使用しない。範囲外文字の検査を描画前に行い、OSの自動fallbackで対応済み扱いにしない。

## 描画と検証

`PreviewRenderer`はJavaFX Application Thread上で、同じfont/サイズから論理advance・baselineと視覚境界を計測し、斜体・装飾・影を含む画像を作る。`PreviewCanvas`の既存入口から呼び、ImageViewには論理サイズと出力倍率別snapshotを設定する。画像サイズは内容に応じて拡張する。ホバーの準備とcacheは#253の責務である。

Component、未対応指定、resource検査の単体テストはJavaFX Toolkitを起動しない。これらの成功は実描画成功を意味しない。実表示は許可された両OSで、FXMLロード、編集反映、明暗テーマ、HiDPI、長い行、結合文字・補助文字、装飾・影の欠けを確認する。配布物検査では通知を保持し、Minecraft抽出素材と開発fixtureの混入を検査する。
