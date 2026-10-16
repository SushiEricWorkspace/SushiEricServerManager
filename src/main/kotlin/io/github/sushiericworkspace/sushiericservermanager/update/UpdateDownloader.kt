package io.github.sushiericworkspace.sushiericservermanager.update

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.CancellationException

/** ダウンロードした成果物のSHA-256が、Releaseの`digest`と一致しないことを表します。 */
class ChecksumMismatchException(val expected: String, val actual: String) :
    IOException("SHA-256が一致しません。期待: $expected、実際: $actual")

/**
 * 更新の成果物をダウンロードして、SHA-256を検証します。
 *
 * 成果物は[directory]の下の`<版>`ディレクトリへ保存します。ダウンロード中は`.part`の
 * 一時ファイルへ書き、検証に成功した場合だけ成果物の名前へ変更します。
 * 検証に失敗した場合と中止した場合は、一時ファイルを削除します。
 * 通信を伴うため、JavaFX Application Threadでは呼び出しません。
 *
 * @param directory 成果物の保存先の親ディレクトリ。
 * @param open 成果物のURLを開いて入力ストリームを返す関数。呼び出し側がcloseします。
 */
class UpdateDownloader(
    private val directory: File,
    private val open: (String) -> InputStream = ::openHttp
) {
    /**
     * [update]の成果物をダウンロードして検証します。
     *
     * 保存先に検証済みの成果物が既にある場合は、再ダウンロードせずにそのファイルを返します。
     *
     * @param onProgress 受信したバイト数と全体のバイト数。全体が不明の場合は0以下です。
     * @param isCancelled trueを返すと中止します。
     * @return 検証済みの成果物。
     * @throws ChecksumMismatchException SHA-256が一致しない場合。
     * @throws CancellationException 中止された場合。
     * @throws IOException 通信または保存に失敗した場合。
     */
    fun download(
        update: UpdateCheckResult.Automatic,
        onProgress: (received: Long, total: Long) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false }
    ): File {
        val asset = update.asset
        val target = directory.resolve(update.version).resolve(asset.name)
        target.parentFile.mkdirs()

        if (target.isFile && sha256OfFile(target) == asset.sha256) {
            onProgress(target.length(), target.length())
            return target
        }

        val temporary = File(target.parentFile, "${asset.name}.part")
        try {
            val actual = open(asset.url).use { input -> copyWithDigest(input, temporary, asset.size, onProgress, isCancelled) }
            if (actual != asset.sha256) throw ChecksumMismatchException(asset.sha256, actual)
            target.delete()
            check(temporary.renameTo(target)) { "成果物を保存できません: $target" }
            return target
        } finally {
            temporary.delete()
        }
    }

    private fun copyWithDigest(
        input: InputStream,
        output: File,
        total: Long,
        onProgress: (Long, Long) -> Unit,
        isCancelled: () -> Boolean
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_SIZE)
        var received = 0L
        output.outputStream().use { out ->
            while (true) {
                if (isCancelled()) throw CancellationException("ダウンロードを中止しました。")
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
                out.write(buffer, 0, read)
                received += read
                onProgress(received, total)
            }
        }
        return digest.digest().toHex()
    }

    private companion object {
        const val BUFFER_SIZE = 64 * 1024
    }
}

private fun sha256OfFile(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().toHex()
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

private val downloadClient: HttpClient = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(10))
    .followRedirects(HttpClient.Redirect.NORMAL)
    .build()

private fun openHttp(url: String): InputStream {
    val request = HttpRequest.newBuilder(URI.create(url))
        .header("User-Agent", "SushiEricServerManager/${AppVersion.CURRENT}")
        .GET()
        .build()
    val response = downloadClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
    if (response.statusCode() != 200) {
        response.body().close()
        throw IOException("成果物をダウンロードできません: HTTP ${response.statusCode()}")
    }
    return response.body()
}
