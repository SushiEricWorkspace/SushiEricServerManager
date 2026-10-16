package io.github.sushiericworkspace.sushiericservermanager.communication

import io.github.sushiericworkspace.sushiericservermanager.config.RemoteOperatingSystem
import org.slf4j.Logger

enum class SshFailureCode {
    DNS_RESOLUTION_FAILED,
    HOST_UNREACHABLE,
    CONNECTION_REFUSED,
    CONNECTION_TIMEOUT,
    HOST_KEY_NOT_APPROVED,
    HOST_KEY_CHANGED,
    HOST_KEY_STORE_FAILED,
    PASSWORD_AUTHENTICATION_FAILED,
    PASSWORD_AUTHENTICATION_DISABLED,
    PUBLIC_KEY_AUTHENTICATION_FAILED,
    PRIVATE_KEY_NOT_FOUND,
    PRIVATE_KEY_PERMISSION_FAILED,
    PRIVATE_KEY_FORMAT_UNSUPPORTED,
    PRIVATE_KEY_PASSPHRASE_REQUIRED,
    REMOTE_DIRECTORY_NOT_FOUND,
    REMOTE_PATH_NOT_DIRECTORY,
    REMOTE_DIRECTORY_NOT_READABLE,
    REMOTE_DIRECTORY_NOT_WRITABLE,
    AUTHORIZED_KEYS_UPDATE_FAILED,
    KEY_GENERATION_FAILED,
    SFTP_UNAVAILABLE,
    PROFILE_SAVE_FAILED,
    UNEXPECTED
}

data class SshFailure(
    val code: SshFailureCode,
    val detail: String? = null,
    val publicKeyRegistered: Boolean = false,
    val generatedPrivateKeyPath: String? = null,
    val generatedKeyFormat: String? = null,
    val remoteOperatingSystem: RemoteOperatingSystem? = null
) {
    val title: String
        get() = when (code) {
            SshFailureCode.DNS_RESOLUTION_FAILED,
            SshFailureCode.HOST_UNREACHABLE,
            SshFailureCode.CONNECTION_REFUSED,
            SshFailureCode.CONNECTION_TIMEOUT -> "SSH接続先へ到達できません"

            SshFailureCode.HOST_KEY_CHANGED -> "ホスト鍵が変更されています"
            SshFailureCode.HOST_KEY_NOT_APPROVED -> "ホスト鍵が承認されませんでした"
            SshFailureCode.PASSWORD_AUTHENTICATION_FAILED,
            SshFailureCode.PASSWORD_AUTHENTICATION_DISABLED,
            SshFailureCode.PUBLIC_KEY_AUTHENTICATION_FAILED -> "SSH認証に失敗しました"

            SshFailureCode.PRIVATE_KEY_NOT_FOUND,
            SshFailureCode.PRIVATE_KEY_PERMISSION_FAILED,
            SshFailureCode.PRIVATE_KEY_FORMAT_UNSUPPORTED,
            SshFailureCode.PRIVATE_KEY_PASSPHRASE_REQUIRED -> "秘密鍵を使用できません"

            SshFailureCode.REMOTE_DIRECTORY_NOT_FOUND,
            SshFailureCode.REMOTE_PATH_NOT_DIRECTORY,
            SshFailureCode.REMOTE_DIRECTORY_NOT_READABLE,
            SshFailureCode.REMOTE_DIRECTORY_NOT_WRITABLE -> "接続先ディレクトリを使用できません"

            SshFailureCode.AUTHORIZED_KEYS_UPDATE_FAILED -> "公開鍵を登録できません"
            SshFailureCode.KEY_GENERATION_FAILED -> "SSH鍵を生成できません"
            SshFailureCode.SFTP_UNAVAILABLE -> "SFTPを開始できません"
            else -> "SSH処理に失敗しました"
        }

    fun userMessage(): String {
        return buildString {
            append(baseMessage())

            val hints = troubleshootingHints()
            if (hints.isNotEmpty()) {
                append("\n\n確認事項:")
                hints.forEach { hint ->
                    append("\n・")
                    append(hint)
                }
            }

            detail?.takeIf { it.isNotBlank() }?.let {
                append("\n\n詳細: ")
                append(it)
            }

            if (publicKeyRegistered) {
                when (code) {
                    SshFailureCode.PROFILE_SAVE_FAILED ->
                        append("\n\n公開鍵登録と秘密鍵認証には成功しています。authorized_keysから自動削除は行っていません。")

                    SshFailureCode.AUTHORIZED_KEYS_UPDATE_FAILED ->
                        append("\n\n公開鍵は追記済みですが、後続のACL設定などに失敗しました。登録済み公開鍵は自動削除していません。")

                    else ->
                        append("\n\n公開鍵の登録処理は完了していますが、生成した秘密鍵での接続確認に失敗しました。登録済み公開鍵は自動削除していません。")
                }
            }

            generatedPrivateKeyPath?.takeIf { it.isNotBlank() }?.let {
                append("\n\n生成済み秘密鍵: ")
                append(it)
                append("\n再試行前に削除せず、原因確認に利用できます。")
            }
        }
    }

    private fun baseMessage(): String = when (code) {
        SshFailureCode.DNS_RESOLUTION_FAILED ->
            "入力されたホスト名をIPアドレスへ変換できませんでした。SSH接続を開始する前の名前解決で失敗しています。"

        SshFailureCode.HOST_UNREACHABLE ->
            "接続先ホストまでネットワーク経路を確立できませんでした。"

        SshFailureCode.CONNECTION_REFUSED ->
            "接続先ホストには到達しましたが、指定ポートでSSH接続が拒否されました。"

        SshFailureCode.CONNECTION_TIMEOUT ->
            "指定したSSHポートへの接続がタイムアウトしました。"

        SshFailureCode.HOST_KEY_NOT_APPROVED ->
            "初回接続時のホスト鍵が承認されなかったため、安全のため接続を中止しました。"

        SshFailureCode.HOST_KEY_CHANGED ->
            "保存済みのホスト鍵と、現在サーバーが提示したホスト鍵が一致しません。中間者攻撃またはサーバー再構築の可能性があるため接続を拒否しました。"

        SshFailureCode.HOST_KEY_STORE_FAILED ->
            "承認したホスト鍵をアプリのknown_hostsへ安全に保存できませんでした。"

        SshFailureCode.PASSWORD_AUTHENTICATION_FAILED ->
            "初回公開鍵登録に使用するパスワード認証が通りませんでした。"

        SshFailureCode.PASSWORD_AUTHENTICATION_DISABLED ->
            "接続先SSHサーバーがパスワード認証を許可していません。自動公開鍵登録には初回だけパスワード認証が必要です。"

        SshFailureCode.PUBLIC_KEY_AUTHENTICATION_FAILED ->
            "秘密鍵は読み込めましたが、接続先が対応する公開鍵を受け入れませんでした。"

        SshFailureCode.PRIVATE_KEY_NOT_FOUND ->
            "指定された秘密鍵ファイルが見つかりません。"

        SshFailureCode.PRIVATE_KEY_PERMISSION_FAILED ->
            "生成した秘密鍵を現在のOSユーザーだけが利用できる権限へ制限できませんでした。安全のため鍵生成を中止しました。"

        SshFailureCode.PRIVATE_KEY_FORMAT_UNSUPPORTED ->
            "秘密鍵の形式をSSHライブラリが読み込めません。"

        SshFailureCode.PRIVATE_KEY_PASSPHRASE_REQUIRED ->
            "選択した秘密鍵はパスフレーズを必要とします。現在のGUIではパスフレーズ付き秘密鍵の継続利用に対応していません。"

        SshFailureCode.REMOTE_DIRECTORY_NOT_FOUND ->
            "指定された接続先ルートディレクトリが存在しません。"

        SshFailureCode.REMOTE_PATH_NOT_DIRECTORY ->
            "指定された接続先パスはディレクトリではありません。"

        SshFailureCode.REMOTE_DIRECTORY_NOT_READABLE ->
            "SSH認証には成功しましたが、指定された接続先ルートディレクトリを読み取れません。"

        SshFailureCode.REMOTE_DIRECTORY_NOT_WRITABLE ->
            "SSH認証には成功しましたが、指定された接続先ルートディレクトリへ書き込めません。"

        SshFailureCode.AUTHORIZED_KEYS_UPDATE_FAILED ->
            "パスワード認証には成功しましたが、接続先の公開鍵認証ファイルへ生成した公開鍵を登録できませんでした。"

        SshFailureCode.KEY_GENERATION_FAILED ->
            "接続元PCでSSH鍵ペアを生成または保存できませんでした。"

        SshFailureCode.SFTP_UNAVAILABLE ->
            "SSH認証には成功しましたが、ファイル操作に必要なSFTPセッションを開始できませんでした。"

        SshFailureCode.PROFILE_SAVE_FAILED ->
            "SSH接続の確認には成功しましたが、接続プロファイルを設定ファイルへ保存できませんでした。"

        SshFailureCode.UNEXPECTED ->
            "分類できないSSHエラーが発生しました。秘密情報を除いたエラー種別をログへ記録しています。"
    }

    private fun troubleshootingHints(): List<String> = when (code) {
        SshFailureCode.DNS_RESOLUTION_FAILED -> listOf(
            "ホスト名のスペルを確認してください。IPアドレスを直接指定できる場合は、IPアドレスでも切り分けできます。",
            "VPN、社内DNS、家庭内DNSなど、そのホスト名を解決するために必要なネットワークへ接続しているか確認してください。"
        )

        SshFailureCode.HOST_UNREACHABLE -> listOf(
            "接続先PCが起動しており、接続元PCから同じネットワークまたは必要なVPNへ到達できるか確認してください。",
            "ルーター、VPN、VLAN、セキュリティグループなどで接続先ネットワークへの経路が遮断されていないか確認してください。"
        )

        SshFailureCode.CONNECTION_REFUSED -> listOf(
            "接続先でSSH/OpenSSH Serverが起動しているか確認してください。",
            "設定したポート番号と、SSHサーバーが実際に待ち受けているポート番号が一致しているか確認してください。"
        )

        SshFailureCode.CONNECTION_TIMEOUT -> listOf(
            "接続先のファイアウォールやルーターでSSHポートが破棄されていないか確認してください。",
            "外部ネットワークから接続する場合は、ポート転送やVPNの設定も確認してください。"
        )

        SshFailureCode.HOST_KEY_NOT_APPROVED -> listOf(
            "表示されたフィンガープリントを接続先管理者が確認できる値と照合してから、再度承認してください。"
        )

        SshFailureCode.HOST_KEY_CHANGED -> listOf(
            "接続先を再構築、OS再インストール、OpenSSH再設定した場合はホスト鍵が変わっていないか確認してください。",
            "変更に心当たりがない場合はknown_hostsを削除して続行せず、接続先管理者へ確認してください。"
        )

        SshFailureCode.HOST_KEY_STORE_FAILED -> listOf(
            "接続元PCのアプリデータディレクトリへ書き込めるか確認してください。",
            "セキュリティソフトやフォルダー保護機能がknown_hostsの作成・ACL変更を遮断していないか確認してください。"
        )

        SshFailureCode.PASSWORD_AUTHENTICATION_FAILED -> passwordAuthenticationHints()
        SshFailureCode.PASSWORD_AUTHENTICATION_DISABLED -> passwordAuthenticationDisabledHints()
        SshFailureCode.PUBLIC_KEY_AUTHENTICATION_FAILED -> publicKeyAuthenticationHints()

        SshFailureCode.PRIVATE_KEY_NOT_FOUND -> listOf(
            "秘密鍵のパスが正しいか、ファイルを移動・削除していないか確認してください。",
            "公開鍵（.pub）ではなく秘密鍵ファイルを選択してください。"
        )

        SshFailureCode.PRIVATE_KEY_PERMISSION_FAILED -> listOf(
            "接続元PCのアプリデータディレクトリとSSH鍵保存先へ、現在のユーザーがACLまたはアクセス権を変更できるか確認してください。",
            "WindowsではNTFS以外のドライブ、企業ポリシー、ランサムウェア防止機能などによりACL変更が拒否される場合があります。",
            "macOSではアプリデータディレクトリの所有者と書き込み権限を確認してください。"
        )

        SshFailureCode.PRIVATE_KEY_FORMAT_UNSUPPORTED -> listOf(
            "PKCS#8、PEM、OpenSSH形式など、SSHJが読み込める秘密鍵を選択してください。",
            "鍵ファイルが破損していないか、公開鍵ファイルを誤って選択していないか確認してください。"
        )

        SshFailureCode.PRIVATE_KEY_PASSPHRASE_REQUIRED -> listOf(
            "パスフレーズなしの専用鍵を用意するか、アプリの「SSH鍵を生成して登録」で専用鍵を生成してください。"
        )

        SshFailureCode.REMOTE_DIRECTORY_NOT_FOUND -> listOf(
            "接続先OS上で入力したパスが実在するか確認してください。",
            "Windowsでも区切り文字は C:/Users/user/server のように入力できます。"
        )

        SshFailureCode.REMOTE_PATH_NOT_DIRECTORY -> listOf(
            "ファイルではなく、データを保存するディレクトリのパスを指定してください。"
        )

        SshFailureCode.REMOTE_DIRECTORY_NOT_READABLE -> remoteDirectoryPermissionHints(write = false)
        SshFailureCode.REMOTE_DIRECTORY_NOT_WRITABLE -> remoteDirectoryPermissionHints(write = true)
        SshFailureCode.AUTHORIZED_KEYS_UPDATE_FAILED -> authorizedKeysHints()

        SshFailureCode.KEY_GENERATION_FAILED -> listOf(
            "接続元PCのアプリデータディレクトリに空き容量があり、現在のユーザーがファイルを作成できるか確認してください。",
            "セキュリティソフトやフォルダー保護機能が鍵ファイルの作成を遮断していないか確認してください。",
            "同じ場所で鍵生成を繰り返し失敗する場合は、アプリデータディレクトリの所有者とアクセス権を確認してください。"
        )

        SshFailureCode.SFTP_UNAVAILABLE -> listOf(
            "接続先SSHサーバーでSFTP subsystemが無効化されていないか確認してください。",
            "SSHログインだけを許可する制限設定、ForceCommand、Subsystem設定などがSFTPを妨げていないか確認してください。"
        )

        SshFailureCode.PROFILE_SAVE_FAILED -> listOf(
            "接続元PCの設定ファイル保存先へ書き込めるか、空き容量があるか確認してください。",
            "セキュリティソフトやフォルダー保護機能が設定ファイル更新を遮断していないか確認してください。"
        )

        SshFailureCode.UNEXPECTED -> listOf(
            "接続先OS、ユーザー名、ホスト、ポート、認証方式を確認してから再試行してください。",
            "再現する場合はアプリのログに記録されたエラー種別を確認してください。パスワードや秘密鍵内容はログへ出力しません。"
        )
    }

    private fun passwordAuthenticationHints(): List<String> {
        val common = mutableListOf(
            "接続先OSのユーザー名と、そのユーザーの実際のログインパスワードを確認してください。",
            "対象ユーザーがSSHログインを許可されているか、アカウントがロック・無効化されていないか確認してください。"
        )
        if (remoteOperatingSystem == RemoteOperatingSystem.WINDOWS) {
            common.add("Windows HelloのPINではなく、Windowsユーザーのパスワードを入力してください。Microsoftアカウント利用時はOpenSSHで認識されるユーザー名も確認してください。")
        }
        return common
    }

    private fun passwordAuthenticationDisabledHints(): List<String> {
        val common = mutableListOf(
            "sshdの設定でPasswordAuthenticationまたはkeyboard-interactive認証が初回登録時だけ利用できるようになっているか確認してください。",
            "公開鍵登録が完了した後は、運用方針に合わせてパスワード認証を再び無効化できます。"
        )
        if (remoteOperatingSystem == RemoteOperatingSystem.WINDOWS) {
            common.add("Windows OpenSSH Serverではsshd_config変更後にsshdサービスの再起動が必要です。")
        }
        return common
    }

    private fun publicKeyAuthenticationHints(): List<String> {
        val common = mutableListOf(
            "接続設定のユーザー名と、公開鍵を登録したユーザーが同じか確認してください。",
            "現在選択している秘密鍵と、接続先へ登録した公開鍵が同じ鍵ペアか確認してください。"
        )
        when (remoteOperatingSystem) {
            RemoteOperatingSystem.WINDOWS -> {
                common.add("Windows OpenSSHでは、Administratorsグループのユーザーは通常 %ProgramData%\\ssh\\administrators_authorized_keys、一般ユーザーは %USERPROFILE%\\.ssh\\authorized_keys を使用します。")
                common.add("管理者ユーザーの場合はadministrators_authorized_keysのACLにAdministratorsとSYSTEMが適切に設定されているか、sshd_configのMatch Group administrators / AuthorizedKeysFileが標準設定から変更されていないか確認してください。")
            }

            RemoteOperatingSystem.MACOS,
            RemoteOperatingSystem.UBUNTU_DESKTOP,
            RemoteOperatingSystem.UBUNTU_SERVER -> {
                common.add("接続先ユーザーの ~/.ssh が0700、authorized_keysが0600相当で、所有者が接続ユーザーになっているか確認してください。")
            }

            null -> Unit
        }
        return common
    }

    private fun remoteDirectoryPermissionHints(write: Boolean): List<String> {
        val operation = if (write) "書き込み" else "読み取り"
        val common = mutableListOf(
            "SSHでログインしたユーザーに、指定ディレクトリの${operation}権限があるか確認してください。"
        )
        when (remoteOperatingSystem) {
            RemoteOperatingSystem.WINDOWS ->
                common.add("Windowsでは対象フォルダーのNTFS ACLを確認し、管理者グループ所属だけを理由にアクセスできると仮定しないでください。SSHセッションで実際に使用されるユーザー権限が必要です。")

            RemoteOperatingSystem.MACOS,
            RemoteOperatingSystem.UBUNTU_DESKTOP,
            RemoteOperatingSystem.UBUNTU_SERVER ->
                common.add("接続先で対象ディレクトリの所有者、グループ、chmod権限を確認してください。")

            null -> Unit
        }
        return common
    }

    private fun authorizedKeysHints(): List<String> = when (remoteOperatingSystem) {
        RemoteOperatingSystem.WINDOWS -> listOf(
            "接続先ユーザーがAdministratorsグループに所属する場合、通常のユーザープロファイルではなく %ProgramData%\\ssh\\administrators_authorized_keys が登録先になります。一般ユーザーは %USERPROFILE%\\.ssh\\authorized_keys が対象です。",
            "管理者用ファイルではAdministratorsとSYSTEMのACLが必要です。企業ポリシー、UAC、フォルダー保護、セキュリティソフトによりProgramData配下への作成やACL変更が拒否されていないか確認してください。",
            "自動登録ではPowerShellをSSH経由で実行します。PowerShellの起動やリモートコマンド実行が制限されていないか確認してください。",
            "sshd_configでAuthorizedKeysFileやMatch Group administratorsを変更している場合、アプリの標準登録先と実際の認証先が一致しているか確認してください。"
        )

        RemoteOperatingSystem.MACOS,
        RemoteOperatingSystem.UBUNTU_DESKTOP,
        RemoteOperatingSystem.UBUNTU_SERVER -> listOf(
            "接続先ユーザーのホームディレクトリへ書き込めるか、~/.sshを作成できるか確認してください。",
            "~/.ssh と authorized_keys の所有者が接続ユーザーで、ディレクトリ0700・ファイル0600相当の権限を設定できるか確認してください。",
            "ホームディレクトリが読み取り専用、容量不足、クォータ超過になっていないか確認してください。"
        )

        null -> listOf(
            "接続先ユーザーの公開鍵認証ファイルへ書き込めるか、所有者とアクセス権を確認してください。"
        )
    }
}

sealed interface SshResult<out T> {
    data class Success<T>(val value: T) : SshResult<T>
    data class Failure(val failure: SshFailure) : SshResult<Nothing>
}

object SecretRedactor {
    fun redact(text: String?, secrets: Iterable<CharSequence>): String {
        var result = text.orEmpty()
        secrets
            .map(CharSequence::toString)
            .filter { it.isNotEmpty() }
            .forEach { secret -> result = result.replace(secret, "***") }
        return result
    }

    fun safeThrowableName(throwable: Throwable): String {
        return throwable::class.qualifiedName ?: "Throwable"
    }
}

object SafeSshLogger {
    fun warn(
        logger: Logger,
        event: String,
        failureCode: SshFailureCode? = null,
        throwable: Throwable? = null,
        diagnostic: String? = null
    ) {
        logger.warn(
            "SSH event={}, code={}, type={}, diagnostic={}",
            event,
            failureCode?.name ?: "NONE",
            throwable?.let(SecretRedactor::safeThrowableName) ?: "NONE",
            diagnostic ?: "NONE"
        )
    }
}
