import java.nio.file.Path
import java.nio.file.Files
import java.net.URI
import java.util.Locale
import java.util.UUID

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.javafx)
    application
}

group = "io.github.sushiericworkspace.sushiericservermanager"
version = "1.0-SNAPSHOT"

val commonReleaseVersion = providers.gradleProperty("commonReleaseVersion")
    .orNull
    ?.trim()
    ?.takeIf { it.isNotEmpty() }

if (commonReleaseVersion?.startsWith("v") == true) {
    throw GradleException("Commonの正式版バージョンにはvを付けないでください: $commonReleaseVersion")
}

val releaseTaskNames = setOf("releaseWindowsInstaller", "releaseMacDmg")
val releaseTaskRequested = gradle.startParameter.taskNames.any {
    it.substringAfterLast(':') in releaseTaskNames
}
if (releaseTaskRequested && commonReleaseVersion == null) {
    throw GradleException(
        "正式リリース成果物の作成には-PcommonReleaseVersion=<version>が必要です。"
    )
}

val commonDependency = if (commonReleaseVersion == null) {
    "io.github.sushiericworkspace:sushieric-common-editor-dev:0.1.0-dev.+"
} else {
    "io.github.sushiericworkspace:sushieric-common:$commonReleaseVersion"
}

dependencies {
    implementation(commonDependency)

    // GUI 関連ライブラリ
    implementation("org.controlsfx:controlsfx:11.2.1")
    implementation("org.kordamp.ikonli:ikonli-javafx:12.3.1")
    implementation("org.kordamp.ikonli:ikonli-fontawesome5-pack:12.3.1")

    // データ・通信関連
    implementation("com.hierynomus:sshj:0.39.0")
    implementation("org.yaml:snakeyaml:2.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    // ロギング
    implementation("org.slf4j:slf4j-api:2.0.12")
    implementation("ch.qos.logback:logback-classic:1.5.3")

    implementation("org.bouncycastle:bcprov-jdk18on:1.78")

    testImplementation(kotlin("test"))
}

configurations.configureEach {
    resolutionStrategy.cacheDynamicVersionsFor(0, "seconds")
}

kotlin { jvmToolchain(21) }

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    compilerOptions {
        freeCompilerArgs.add("-Xjdk-release=21")
    }
}

javafx {
    version = libs.versions.javafx.get()
    modules("javafx.controls", "javafx.fxml")
}

/**
 * appVersionから、実行時に参照するAppVersion.CURRENTを生成する。
 *
 * 出力はbuild配下で、ソースには含めない。コンパイルの前に必ず実行される。
 */
val generateAppVersion = tasks.register("generateAppVersion") {
    group = "build"
    description = "gradle.propertiesのappVersionからAppVersion.CURRENTを生成します"

    val version = releaseVersion
    val outputDir = layout.buildDirectory.dir("generated/source/appVersion")
    inputs.property("appVersion", version)
    outputs.dir(outputDir)

    doLast {
        val file = outputDir.get().asFile
            .resolve("io/github/sushiericworkspace/sushiericservermanager/update/AppVersion.kt")
        file.parentFile.mkdirs()
        file.writeText(
            """
            package io.github.sushiericworkspace.sushiericservermanager.update

            /** gradle.propertiesのappVersionから生成した、実行中のアプリの版です。手で編集しないでください。 */
            object AppVersion {
                const val CURRENT = "$version"
            }
            """.trimIndent() + "\n"
        )
    }
}

kotlin.sourceSets.named("main") {
    kotlin.srcDir(generateAppVersion)
}

application {
    mainClass.set("io.github.sushiericworkspace.sushiericservermanager.app.Launcher")
}

tasks.test {
    useJUnitPlatform()
}

val appName = "SushiEricServerManager"

// アプリの版。gradle.propertiesのappVersionが唯一の定義で、
// AppVersion.CURRENT、成果物名、jpackageの版はこの値から作る。
val releaseVersion = providers.gradleProperty("appVersion").orNull?.trim().orEmpty()
if (!Regex("""\d+\.\d+\.\d+""").matches(releaseVersion)) {
    throw GradleException("gradle.propertiesのappVersionはX.Y.Z形式で指定してください: '$releaseVersion'")
}

// jpackageに渡すパッケージ用バージョン。releaseVersionの先頭の数字に1を足す。
// macOSのjpackageでは、最初の数字を0にできないため1以上にする必要がある。
// 常に1を足すことで、appVersionが1.0.0以降になっても、パッケージ版が減らず更新として扱われる。
val packageVersion = releaseVersion.split('.').let { (major, minor, patch) ->
    "${major.toInt() + 1}.$minor.$patch"
}

val mainJarName = "SushiEricServerManager-1.0-SNAPSHOT.jar"
val mainClassName = "io.github.sushiericworkspace.sushiericservermanager.app.Launcher"

// jpackageに渡す入力フォルダ。
// installDistで生成されたlibフォルダを指定する。
val packageInputDir = "build/install/SushiEricServerManager/lib"

// 配布物の出力先。
val appImageOutputDir = layout.buildDirectory.dir("release").get().asFile.absolutePath
val installerOutputDir = layout.buildDirectory.dir("installer").get().asFile.absolutePath
val releaseInstallerOutputDir = layout.buildDirectory.dir("release-installer").get().asFile.absolutePath

// Windows用アイコン。
// exe本体、ショートカット、スタートメニューのアイコンに使われる。
val windowsIconPath = "src/main/resources/icon/app.ico"

// macOS用アイコン。
// .app、.dmg作成時のアプリアイコンに使われる。
val macIconPath = "src/main/resources/icon/app.icns"

// jpackageが生成するWindowsインストーラー名。
// 基本的に「アプリ名-パッケージバージョン.exe」になる。
val windowsInstallerBaseName = "$appName-$packageVersion.exe"

// GitHub Releasesなどに置くためのリリース用インストーラー名。
val windowsInstallerReleaseName = "$appName-$releaseVersion-Windows-Installer.exe"

// jpackageが生成するmacOS dmg名。
// 基本的に「アプリ名-パッケージバージョン.dmg」になる。
val macDmgBaseName = "$appName-$packageVersion.dmg"

// GitHub Releasesなどに置くためのリリース用dmg名。
val macDmgReleaseName = "$appName-$releaseVersion-macOS-arm64-Installer.dmg"

/**
 * 0.2.2のManagerが読む移行用のupdate.jsonを生成する。
 *
 * 新しい更新の仕組みを含む最初のReleaseにだけ添付する。0.2.2は、update.jsonを取得できないと
 * 起動を中止するためである。手順はdocs/update/release-guide.mdを参照する。
 *
 * 出力: build/release-installer/update.json
 *
 * 変更内容は-PupdateNotes="1つ目|2つ目"のように、|区切りで指定できる。
 */
tasks.register("generateTransitionalUpdateJson") {
    group = "release"
    description = "0.2.2向けの移行用update.jsonを生成します"

    val version = releaseVersion
    val windowsName = windowsInstallerReleaseName
    val macName = macDmgReleaseName
    val notes = providers.gradleProperty("updateNotes")
    val output = layout.buildDirectory.file("release-installer/update.json")
    inputs.property("appVersion", version)
    inputs.property("updateNotes", notes.orElse(""))
    outputs.file(output)

    doLast {
        fun quote(text: String): String = buildString {
            append('"')
            text.forEach { character ->
                when (character) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(character)
                }
            }
            append('"')
        }

        val baseUrl = "https://github.com/SushiEricWorkspace/SushiEricServerManager/releases/download/v$version"
        val noteList = notes.orNull.orEmpty().split('|').map(String::trim).filter(String::isNotEmpty)
        val json = buildString {
            appendLine("{")
            appendLine("  \"version\": ${quote(version)},")
            appendLine("  \"windowsDownloadUrl\": ${quote("$baseUrl/$windowsName")},")
            appendLine("  \"macDownloadUrl\": ${quote("$baseUrl/$macName")},")
            appendLine("  \"notes\": [${noteList.joinToString(", ") { quote(it) }}]")
            appendLine("}")
        }
        output.get().asFile.apply {
            parentFile.mkdirs()
            writeText(json)
        }
    }
}

/**
 * 現在のアプリ名のapp-image出力だけを削除する。
 *
 * appNameを変更した場合、古い名前のフォルダは残るため、
 * その場合は手動で削除する。
 */
tasks.register<Delete>("cleanAppImageOutput") {
    group = "release"
    description = "現在のアプリ名のapp-image出力を削除します"

    delete("$appImageOutputDir/$appName")
}

/**
 * Windows用インストーラーの出力先を削除する。
 *
 * 古いインストーラーが残っていると紛らわしいため、
 * インストーラー作成前に削除する。
 */
tasks.register<Delete>("cleanWindowsInstallerOutput") {
    group = "release"
    description = "Windows用インストーラー出力を削除します"

    delete(installerOutputDir)
    delete(releaseInstallerOutputDir)
}

/**
 * macOS用インストーラーの出力先を削除する。
 *
 * 古いdmgが残っていると紛らわしいため、
 * dmg作成前に削除する。
 */
tasks.register<Delete>("cleanMacInstallerOutput") {
    group = "release"
    description = "macOS用インストーラー出力を削除します"

    delete(installerOutputDir)
    delete(releaseInstallerOutputDir)
}

/**
 * Windows用のインストール不要アプリフォルダを作成する。
 *
 * 出力例:
 * build/release/SushiEricServerManager/SushiEricServerManager.exe
 *
 * これはインストーラーではなく、フォルダごと配布する形式。
 * 動作確認やzip配布に使う。
 */
tasks.register<Exec>("packageWindowsAppImage") {
    group = "release"
    description = "Windows用のJavaランタイム同梱アプリフォルダを作成します"

    dependsOn("cleanAppImageOutput", "installDist")

    workingDir = projectDir

    commandLine(
        "jpackage",
        "--type", "app-image",
        "--name", appName,
        "--app-version", packageVersion,
        "--input", packageInputDir,
        "--main-jar", mainJarName,
        "--main-class", mainClassName,
        "--dest", appImageOutputDir,
        "--icon", windowsIconPath
    )
}

/**
 * Windows用exeインストーラーを作成する。
 *
 * 出力例:
 * build/installer/SushiEricServerManager-1.0.0.exe
 *
 * --win-menu:
 * スタートメニューに登録する。
 *
 * --win-shortcut:
 * デスクトップショートカットを作成する。
 *
 * --win-per-user-install:
 * ユーザー単位インストールにする。
 * 自動アップデートでProgram Filesの権限問題を避けやすくするため。
 */
val windowsInstallerResources = layout.buildDirectory.dir("windows-installer-resources")

// JDK 21のjpackageはネストしたper-user配置の親へRemoveFolderを生成せず、WiX ICE64で失敗する。
// JDK自身のテンプレートを使用し、空の親だけを削除する定義を補う。データや親の再帰削除は行わない。
tasks.register("prepareWindowsInstallerResources") {
    doLast {
        val args = tasks.named<Exec>("packageWindowsInstaller").get().commandLine
        val name = args[args.indexOf("--name") + 1]
        val installDirectory = args[args.indexOf("--install-dir") + 1]
        val parent = Path.of(installDirectory).parent
        val output = windowsInstallerResources.get().asFile.apply { mkdirs() }
        val template = Files.readString(Path.of(
            URI.create("jrt:/jdk.jpackage/jdk/jpackage/internal/resources/main.wxs")
        ))
        val adjusted = if (parent == null) template else {
            // DirectoryのIDはJDK 21のWixAppImageFragmentBuilderと同じ規則で導出する。
            val key = "Folder@" + "TARGETDIR\\LocalAppDataFolder\\$parent".lowercase(Locale.ROOT)
            val directoryId = "dir" + UUID.nameUUIDFromBytes(key.toByteArray(Charsets.UTF_8)).toString().replace("-", "")
            val component = """
                <DirectoryRef Id="$directoryId">
                  <Component Id="EmptyDataDirectory" Guid="*">
                    <RegistryValue Root="HKCU" Key="Software\$name\Installer" Name="DataDirectory" Type="integer" Value="1" KeyPath="yes" />
                    <RemoveFolder Id="RemoveEmptyDataDirectory" On="uninstall" />
                  </Component>
                </DirectoryRef>
            """.trimIndent()
            check(template.contains("<ComponentGroupRef Id=\"Files\"/>")) { "JDKのWiXテンプレートが未対応です。" }
            template.replace("<ComponentGroupRef Id=\"Files\"/>", "<ComponentGroupRef Id=\"Files\"/><ComponentRef Id=\"EmptyDataDirectory\"/>")
                .replace("</Product>", "$component\n</Product>")
        }
        output.resolve("main.wxs").writeText(adjusted, Charsets.UTF_8)
    }
}

tasks.register<Exec>("packageWindowsInstaller") {
    group = "release"
    description = "Windows用exeインストーラーを作成します"

    dependsOn("cleanWindowsInstallerOutput", "installDist", "prepareWindowsInstallerResources")

    workingDir = projectDir

    commandLine(
        File(System.getProperty("java.home"), "bin/jpackage.exe").absolutePath,
        "--type", "exe",
        "--name", appName,
        "--app-version", packageVersion,
        "--input", packageInputDir,
        "--main-jar", mainJarName,
        "--main-class", mainClassName,
        "--dest", installerOutputDir,
        "--icon", windowsIconPath,
        "--win-menu",
        "--win-shortcut",
        "--win-per-user-install",
        // データ領域の親は維持し、MSIの削除対象をアプリ専用ディレクトリに限定する。
        "--install-dir", "$appName\\app",
        "--resource-dir", windowsInstallerResources.get().asFile
    )
}

/**
 * Windows用インストーラーをリリース用ファイル名へコピー、リネームする。
 *
 * 入力:
 * build/installer/SushiEricServerManager-1.0.0.exe
 *
 * 出力:
 * build/release-installer/SushiEricServerManager-<appVersion>-Windows-Installer.exe
 */
tasks.register<Copy>("renameWindowsInstaller") {
    group = "release"
    description = "Windows用インストーラーをリリース用ファイル名へ変更します"

    dependsOn("packageWindowsInstaller")

    from(installerOutputDir) {
        include(windowsInstallerBaseName)
        rename {
            windowsInstallerReleaseName
        }
    }

    into(releaseInstallerOutputDir)
}

/**
 * Windows用リリースインストーラーを作成するためのまとめタスク。
 *
 * GitHub Releasesに置くWindows用インストーラーを作る場合は、
 * 基本的にこのタスクだけ実行する。
 */
tasks.register("releaseWindowsInstaller") {
    group = "release"
    description = "Windows用インストーラーを作成し、リリース用ファイル名で出力します"

    dependsOn("renameWindowsInstaller")
}

/**
 * macOS用の.appアプリを作成する。
 *
 * このタスクはMac上で実行する。
 * Windows上ではmacOS用のapp-imageは作成できない。
 *
 * 出力例:
 * build/release/SushiEricServerManager.app
 */
tasks.register<Exec>("packageMacAppImage") {
    group = "release"
    description = "macOS用の.appアプリを作成します"

    dependsOn("cleanAppImageOutput", "installDist")

    workingDir = projectDir

    commandLine(
        "jpackage",
        "--type", "app-image",
        "--name", appName,
        "--app-version", packageVersion,
        "--input", packageInputDir,
        "--main-jar", mainJarName,
        "--main-class", mainClassName,
        "--dest", appImageOutputDir,
        "--icon", macIconPath
    )
}

/**
 * macOS用dmgを作成する。
 *
 * このタスクはMac上で実行する。
 * GitHub Releasesに置くmacOS版は基本的にdmgを使う。
 *
 * 出力例:
 * build/installer/SushiEricServerManager-1.0.0.dmg
 */
tasks.register<Exec>("packageMacDmg") {
    group = "release"
    description = "macOS用dmgを作成します"

    dependsOn("cleanMacInstallerOutput", "installDist")

    workingDir = projectDir

    commandLine(
        "jpackage",
        "--type", "dmg",
        "--name", appName,
        "--app-version", packageVersion,
        "--input", packageInputDir,
        "--main-jar", mainJarName,
        "--main-class", mainClassName,
        "--dest", installerOutputDir,
        "--icon", macIconPath
    )
}

/**
 * macOS用dmgをリリース用ファイル名へコピー、リネームする。
 *
 * 入力:
 * build/installer/SushiEricServerManager-1.0.0.dmg
 *
 * 出力:
 * build/release-installer/SushiEricServerManager-<appVersion>-macOS-arm64-Installer.dmg
 */
tasks.register<Copy>("renameMacDmg") {
    group = "release"
    description = "macOS用dmgをリリース用ファイル名へ変更します"

    dependsOn("packageMacDmg")

    from(installerOutputDir) {
        include(macDmgBaseName)
        rename {
            macDmgReleaseName
        }
    }

    into(releaseInstallerOutputDir)
}

/**
 * macOS用リリースdmgを作成するためのまとめタスク。
 *
 * MacでPullしたあと、GitHub Releasesに置くmacOS用dmgを作る場合は、
 * 基本的にこのタスクだけ実行する。
 */
tasks.register("releaseMacDmg") {
    group = "release"
    description = "macOS用dmgを作成し、リリース用ファイル名で出力します"

    dependsOn("renameMacDmg")
}

/*
Windowsで動作確認用アプリフォルダを作る:
packageWindowsAppImage

WindowsでGitHub Release用インストーラーを作る:
releaseWindowsInstaller

Macで動作確認用.appを作る:
packageMacAppImage

MacでGitHub Release用dmgを作る:
releaseMacDmg
 */
