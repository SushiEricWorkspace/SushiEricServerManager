package io.github.sushiericworkspace.sushiericservermanager.update

/** 自動でダウンロードできる成果物です。 */
data class UpdateAsset(
    val name: String,
    val url: String,
    val size: Long,
    /** 16進数64桁の小文字のSHA-256。 */
    val sha256: String
)

/** 更新の確認結果です。 */
sealed interface UpdateCheckResult {
    /** 実行中の版が最新、または最新のReleaseより新しい。 */
    data object UpToDate : UpdateCheckResult

    /** 実行中の版より新しいReleaseがある。 */
    sealed interface Update : UpdateCheckResult {
        val version: String
        val notes: String
        val releaseUrl: String
    }

    /** 自動でダウンロードして検証できる更新です。 */
    data class Automatic(
        override val version: String,
        override val notes: String,
        override val releaseUrl: String,
        val asset: UpdateAsset
    ) : Update

    /**
     * 自動更新できない更新です。対応する成果物がない、SHA-256を検証できない、
     * または自動更新に対応しないOSの場合で、ダウンロードページを案内します。
     *
     * @property reason 自動更新できない理由。
     */
    data class Manual(
        override val version: String,
        override val notes: String,
        override val releaseUrl: String,
        val reason: String
    ) : Update
}

/**
 * 最新のReleaseと実行中の版を比べて、更新の有無と更新の方法を決めます。
 *
 * OSごとの成果物は、名前の規則で選びます。
 * - Windows: `SushiEricServerManager-<版>-Windows-Installer.exe`
 * - macOS: `SushiEricServerManager-<版>-macOS-arm64-Installer.dmg`
 *
 * macOSの成果物はApple Silicon（arm64）専用です。arm64でないMacでは、自動更新せずに手動での更新を案内します。
 *
 * @param source 最新のReleaseの取得元。
 * @param currentVersion 実行中の版。
 * @param osName OSの名前。`os.name`と同じ形式。
 * @param osArch CPUのアーキテクチャ。`os.arch`と同じ形式。
 */
class UpdateChecker(
    private val source: ReleaseSource = GitHubReleaseSource(),
    private val currentVersion: String = AppVersion.CURRENT,
    private val osName: String = System.getProperty("os.name"),
    private val osArch: String = System.getProperty("os.arch")
) {
    /**
     * 更新を確認します。通信を伴うため、JavaFX Application Threadでは呼び出しません。
     *
     * @throws Exception 最新のReleaseを取得できない、またはタグが`vX.Y.Z`形式でない場合。
     */
    fun check(): UpdateCheckResult {
        val release = source.fetchLatest()
        val version = versionOf(release.tagName)

        if (!isNewerVersion(version, currentVersion)) return UpdateCheckResult.UpToDate

        val notes = release.body.orEmpty().trim()
        val assetName = assetNameFor(version)
            ?: return manual(version, notes, release, unsupportedReason())
        val asset = release.assets.firstOrNull { it.name == assetName }
            ?: return manual(version, notes, release, "対応する成果物（$assetName）がReleaseにありません。")
        val sha256 = sha256Of(asset.digest)
            ?: return manual(version, notes, release, "成果物のSHA-256を確認できないため、自動更新しません。")

        return UpdateCheckResult.Automatic(
            version = version,
            notes = notes,
            releaseUrl = release.htmlUrl,
            asset = UpdateAsset(asset.name, asset.downloadUrl, asset.size, sha256)
        )
    }

    private fun manual(version: String, notes: String, release: GitHubRelease, reason: String) =
        UpdateCheckResult.Manual(version, notes, release.htmlUrl, reason)

    private fun assetNameFor(version: String): String? {
        val os = osName.lowercase()
        return when {
            os.contains("win") -> "$PRODUCT_NAME-$version-Windows-Installer.exe"
            os.contains("mac") && isArm64() -> "$PRODUCT_NAME-$version-macOS-arm64-Installer.dmg"
            else -> null
        }
    }

    private fun isArm64(): Boolean = osArch.lowercase().let { it == "aarch64" || it == "arm64" }

    private fun unsupportedReason(): String =
        if (osName.lowercase().contains("mac")) {
            "Apple Silicon（arm64）のMacだけが自動更新に対応しています。"
        } else {
            "このOSは自動更新に対応していません。"
        }

    private companion object {
        const val PRODUCT_NAME = "SushiEricServerManager"
    }
}

/** タグ`vX.Y.Z`から、先頭の`v`を除いた版を返します。形式が違う場合は例外を送出します。 */
internal fun versionOf(tagName: String): String {
    val version = tagName.trim().removePrefix("v")
    require(Regex("""\d+\.\d+\.\d+""").matches(version)) { "タグがvX.Y.Z形式ではありません: $tagName" }
    return version
}

/** `sha256:<16進数64桁>`から小文字の16進数を取り出します。形式が違う、またはnullの場合はnullです。 */
internal fun sha256Of(digest: String?): String? {
    val text = digest?.trim()?.lowercase() ?: return null
    if (!text.startsWith("sha256:")) return null
    return text.removePrefix("sha256:").takeIf { Regex("[0-9a-f]{64}").matches(it) }
}

/** ドット区切りの数値を先頭から順に比べ、[remote]が[current]より新しい場合にtrueを返します。 */
internal fun isNewerVersion(remote: String, current: String): Boolean {
    val remoteParts = remote.split(".").map { it.toIntOrNull() ?: 0 }
    val currentParts = current.split(".").map { it.toIntOrNull() ?: 0 }

    for (index in 0 until maxOf(remoteParts.size, currentParts.size)) {
        val remotePart = remoteParts.getOrElse(index) { 0 }
        val currentPart = currentParts.getOrElse(index) { 0 }
        if (remotePart != currentPart) return remotePart > currentPart
    }
    return false
}
