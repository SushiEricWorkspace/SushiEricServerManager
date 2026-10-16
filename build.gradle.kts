import java.util.Properties
import java.io.File
import java.nio.file.Path
import java.nio.file.Files
import java.net.URI
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.javafx)
    application
    `java-library`
}

group = "io.github.sushiericworkspace.sushiericservermanager"
version = "1.0-SNAPSHOT"

val developmentInputKeys = listOf("dataModelDevelopmentCoordinate", "combatCoreDevelopmentCoordinate",
    "combatCoreDataModelCoordinate", "dataModelDevelopmentRepository", "combatCoreDevelopmentRepository",
    "dataModelSourceCommit", "combatCoreSourceCommit")
val localDevelopment = gradle.extensions.extraProperties.get("managerDevelopmentInputs") as Properties
fun developmentInput(key: String) = providers.gradleProperty(key).orElse(providers.provider {
    localDevelopment.getProperty(key)
})

if (localDevelopment.isNotEmpty()) {
    check(localDevelopment.getProperty("format") == "1" && developmentInputKeys.all {
        !localDevelopment.getProperty(it).isNullOrBlank()
    }) { "固定開発依存の設定が不完全です。scripts/prepare-development.pyで再準備してください。" }
    for ((key, repositoryKey) in listOf("dataModelDevelopmentCoordinate" to "dataModelDevelopmentRepository",
        "combatCoreDataModelCoordinate" to "dataModelDevelopmentRepository",
        "combatCoreDevelopmentCoordinate" to "combatCoreDevelopmentRepository")) {
        val coordinate = localDevelopment.getProperty(key).split(':')
        check(coordinate.size == 3) { "固定開発座標が不正です: $key" }
        val (group, module, version) = coordinate
        val repository = File(localDevelopment.getProperty(repositoryKey))
        check(repository.isAbsolute) { "開発repositoryは絶対パスが必要です。" }
        for (extension in listOf("jar", "pom")) {
            val artifact = repository.resolve("${group.replace('.', '/')}/$module/$version/$module-$version.$extension")
            val expected = localDevelopment.getProperty("sha256.$module.$extension")
            check(artifact.isFile && expected != null && MessageDigest.getInstance("SHA-256")
                .digest(artifact.readBytes()).joinToString("") { "%02x".format(it) } == expected) {
                "固定開発成果物が不足または変更されています: $artifact。scripts/prepare-development.pyで再準備してください。"
            }
        }
    }
}

val dataModelReleaseVersion = providers.gradleProperty("dataModelReleaseVersion")
    .orNull
    ?.trim()
    ?.takeIf { it.isNotEmpty() }

if (dataModelReleaseVersion?.startsWith("v") == true) {
    throw GradleException("SushiEricDataModelの正式版バージョンにはvを付けないでください: $dataModelReleaseVersion")
}

val releaseTaskNames = setOf("releaseWindowsInstaller", "releaseMacDmg")
val releaseTaskRequested = gradle.startParameter.taskNames.any {
    it.substringAfterLast(':') in releaseTaskNames
}
if (releaseTaskRequested && dataModelReleaseVersion == null) {
    throw GradleException(
        "正式リリース成果物の作成には-PdataModelReleaseVersion=<version>が必要です。"
    )
}

val dataModelDevelopmentCoordinate = developmentInput("dataModelDevelopmentCoordinate").orNull
if (dataModelDevelopmentCoordinate != null && (dataModelReleaseVersion != null || !Regex(
        """io\.github\.sushiericworkspace:sushieric-datamodel-editor-dev:0\.1\.0-dev\.[a-zA-Z0-9.-]+"""
    ).matches(dataModelDevelopmentCoordinate))) {
    throw GradleException("開発SushiEricDataModelは完全なEditor用固定座標で指定し、正式版指定と併用しないでください。")
}

val dataModelDependency = dataModelDevelopmentCoordinate
    ?: dataModelReleaseVersion?.let { "io.github.sushiericworkspace:sushieric-datamodel:$it" }
    ?: throw GradleException("開発ビルドにはEditor用SushiEricDataModelの完全座標が必要です。scripts/prepare-development.pyで固定開発依存を準備してください。")

val combatCoreReleaseVersion = providers.gradleProperty("combatCoreReleaseVersion")
    .orNull
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
val combatCoreDevelopmentCoordinate = developmentInput("combatCoreDevelopmentCoordinate").orNull
val combatCoreDataModelCoordinate = developmentInput("combatCoreDataModelCoordinate").orNull
if ((combatCoreReleaseVersion == null) != (dataModelReleaseVersion == null)) {
    throw GradleException("CombatCoreとSushiEricDataModelは開発版または正式版を同じモードで指定してください。")
}
if (combatCoreReleaseVersion?.startsWith("v") == true) {
    throw GradleException("CombatCoreの正式版バージョンにはvを付けないでください: $combatCoreReleaseVersion")
}
if (combatCoreDevelopmentCoordinate != null && (combatCoreReleaseVersion != null || !Regex(
        """io\.github\.sushiericworkspace:sushieric-combat-core-dev:0\.1\.0-dev\.[a-zA-Z0-9.-]+"""
    ).matches(combatCoreDevelopmentCoordinate))) {
    throw GradleException("開発CombatCoreは完全な固定座標で指定し、正式版指定と併用しないでください。")
}
if (combatCoreDataModelCoordinate != null) {
    val expectedPrefix = if (dataModelReleaseVersion == null) {
        "io.github.sushiericworkspace:sushieric-datamodel-mod-dev:"
    } else {
        "io.github.sushiericworkspace:sushieric-datamodel:"
    }
    val expectedVersion = dataModelDevelopmentCoordinate?.substringAfterLast(':') ?: dataModelReleaseVersion
    if (!combatCoreDataModelCoordinate.startsWith(expectedPrefix) ||
        (expectedVersion != null && combatCoreDataModelCoordinate.substringAfterLast(':') != expectedVersion)) {
        throw GradleException("CombatCoreが使用するDataModelとManagerのDataModelは同じ発行版を指定してください。")
    }
}
val combatCoreDependency = combatCoreDevelopmentCoordinate
    ?: combatCoreReleaseVersion?.let { "io.github.sushiericworkspace:sushieric-combat-core:$it" }
    ?: throw GradleException("開発ビルドにはCombatCoreの完全座標が必要です。scripts/prepare-development.pyで固定開発依存を準備してください。")

if (dataModelReleaseVersion == null &&
    (dataModelDevelopmentCoordinate == null || combatCoreDevelopmentCoordinate == null || combatCoreDataModelCoordinate == null)) {
    throw GradleException("開発ビルドにはEditor用DataModel、CombatCore、Coreが使用するMod用DataModelの完全座標が必要です。")
}
if (dataModelReleaseVersion != null && combatCoreDataModelCoordinate == null) {
    throw GradleException("正式ビルドにはCombatCoreが使用するDataModelの完全座標が必要です。")
}

dependencies {
    api(dataModelDependency)
    implementation(combatCoreDependency) {
        exclude(group = "io.github.sushiericworkspace", module = "sushieric-datamodel-mod-dev")
        exclude(group = "io.github.sushiericworkspace", module = "sushieric-datamodel")
    }

    // GUI 関連ライブラリ
    implementation("org.controlsfx:controlsfx:11.2.1")
    implementation("org.kordamp.ikonli:ikonli-javafx:12.3.1")
    implementation("org.kordamp.ikonli:ikonli-fontawesome5-pack:12.3.1")

    // データ・通信関連
    api("com.hierynomus:sshj:0.39.0")
    implementation("org.yaml:snakeyaml:2.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    // ロギング
    api("org.slf4j:slf4j-api:2.0.12")
    implementation("ch.qos.logback:logback-classic:1.5.3")

    implementation("org.bouncycastle:bcprov-jdk18on:1.78")

    testImplementation(kotlin("test"))
    testImplementation("org.mockito:mockito-core:5.14.2")
}

val combatCoreMetadata = configurations.create("combatCoreMetadata") {
    isCanBeConsumed = false
    isCanBeResolved = true
}
dependencies.add(combatCoreMetadata.name, combatCoreDependency)

fun resolvedCombatCoreDataModelCoordinate(): String {
    val coreComponent = combatCoreMetadata.incoming.resolutionResult.allComponents.single { component ->
        val id = component.id as? org.gradle.api.artifacts.component.ModuleComponentIdentifier
        id != null && id.group == "io.github.sushiericworkspace" &&
            id.module == if (combatCoreReleaseVersion == null) "sushieric-combat-core-dev" else "sushieric-combat-core"
    }
    val dependency = coreComponent.dependencies
        .filterIsInstance<org.gradle.api.artifacts.result.ResolvedDependencyResult>()
        .mapNotNull { it.selected.id as? org.gradle.api.artifacts.component.ModuleComponentIdentifier }
        .single { it.group == "io.github.sushiericworkspace" && it.module.startsWith("sushieric-datamodel") }
    return "${dependency.group}:${dependency.module}:${dependency.version}"
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
 * 現在のアプリ名のapp-image出力だけを削除する。
 *
 * appNameを変更した場合、古い名前のフォルダは残るため、
 * その場合は手動で削除する。
 */
tasks.register<Delete>("cleanAppImageOutput") {
    group = "release"
    description = "現在のアプリ名のapp-image出力を削除します"

    delete("$appImageOutputDir/$appName", "$appImageOutputDir/$appName.app")
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


val developmentMarkers = listOf(
    "io/github/sushiericworkspace/sushiericservermanager/communication/managed/",
    "io/github/sushiericworkspace/sushiericservermanager/editor/store/ManagedEditorDataStore",
    "io/github/sushiericworkspace/sushiericservermanager/app/ManagedConnectionDialog",
    "io/github/sushiericworkspace/sushiericservermanager/app/ManagedApplicationFlow",
    "io/github/sushiericworkspace/sushiericservermanager/app/Development",
    "fxml/development/",
    "managed/connection-bridge.py",
    "managed/ssh-bridge.py",
    "DevelopmentLauncher",
    "ManagedConnectionDialog",
    "ManagedEditorDataStore"
)

fun verifyDistributionFiles(files: Collection<File>) {
    val jars = files.filter { it.extension == "jar" }
    check(jars.isNotEmpty()) { "検査対象にJARがありません。" }
    files.forEach { file ->
        check(!file.name.contains("Development", ignoreCase = true) && file.name != "development.jar") {
            "配布対象へ開発成果物が混入しています: $file"
        }
    }
    files.filter { it.extension == "cfg" }.forEach { config ->
        check(!config.readText().contains("DevelopmentLauncher")) { "開発入口を含む設定です: $config" }
    }
    jars.forEach { file ->
        ZipFile(file).use { zip ->
            zip.entries().asSequence().forEach { entry ->
                check(developmentMarkers.none { entry.name.contains(it) }) {
                    "配布JARへ開発機能が混入しています: $file!${entry.name}"
                }
                if (entry.name.endsWith(".class")) {
                    val body = zip.getInputStream(entry).use { it.readBytes().toString(Charsets.ISO_8859_1) }
                    check(developmentMarkers.none { body.contains(it) }) {
                        "配布クラスに開発型への参照があります: $file!${entry.name}"
                    }
                }
            }
        }
    }
}

val verifyReleaseContents = tasks.register("verifyReleaseContents") {
    group = "verification"
    description = "通常JARとruntime依存に管理接続の実装・resource・型参照が含まれないことを検査します"
    dependsOn(tasks.jar)
    val runtime = configurations.runtimeClasspath
    inputs.files(tasks.jar.flatMap { it.archiveFile }, runtime, combatCoreMetadata)
    doLast {
        check(runtime.get().allDependencies.none { it is ProjectDependency && it.dependencyProject.path == ":development" }) {
            "通常runtimeがdevelopmentモジュールへ依存しています。"
        }
        verifyDistributionFiles(runtime.get().files + tasks.jar.get().archiveFile.get().asFile)
        if (dataModelReleaseVersion != null) {
            check(runtime.get().files.none { it.name.startsWith("sushieric-datamodel-editor-dev-") }) {
                "正式配布へ開発SushiEricDataModelが混入しています。"
            }
            check(runtime.get().files.none { it.name.startsWith("sushieric-combat-core-dev-") }) {
                "正式配布へ開発CombatCoreが混入しています。"
            }
            check(runtime.get().files.any { it.name == "sushieric-combat-core-$combatCoreReleaseVersion.jar" }) {
                "正式版CombatCoreがruntimeにありません: $combatCoreReleaseVersion"
            }
        }
        val dataModelFiles = runtime.get().files.filter { it.name.startsWith("sushieric-datamodel") && it.extension == "jar" }
        check(dataModelFiles.size == 1) {
            "runtimeClasspathのSushiEricDataModel JARはEditor用の1つだけである必要があります: ${dataModelFiles.map { it.name }}"
        }
        check(runtime.get().files.none { it.name.startsWith("sushieric-datamodel-mod-dev-") }) {
            "CombatCore経由のMod用SushiEricDataModelがruntimeへ混入しています。"
        }
        check(runtime.get().files.none { it.name.startsWith("sushieric-datamodel-") && it.name != dataModelFiles.single().name }) {
            "runtimeへ複数のSushiEricDataModel実装が混入しています。"
        }
        val modelClassJars = mutableMapOf<String, MutableSet<String>>()
        runtime.get().files.filter { it.extension == "jar" }.forEach { file ->
            ZipFile(file).use { zip ->
                zip.entries().asSequence()
                    .filter { it.name.startsWith("io/github/sushiericworkspace/sushiericdatamodel/") && it.name.endsWith(".class") }
                    .forEach { entry -> modelClassJars.getOrPut(entry.name) { mutableSetOf() } += file.name }
            }
        }
        check(modelClassJars.none { (_, jars) -> jars.size > 1 }) {
            "runtime JAR間でSushiEricDataModelクラスが重複しています: ${modelClassJars.filterValues { it.size > 1 }}"
        }
        val combatCoreClassJars = mutableMapOf<String, MutableSet<String>>()
        runtime.get().files.filter { it.extension == "jar" }.forEach { file ->
            ZipFile(file).use { zip ->
                zip.entries().asSequence()
                    .filter { it.name.startsWith("io/github/sushiericworkspace/combatcore/") && it.name.endsWith(".class") }
                    .forEach { entry -> combatCoreClassJars.getOrPut(entry.name) { mutableSetOf() } += file.name }
            }
        }
        check(combatCoreClassJars.none { (_, jars) -> jars.size > 1 }) {
            "runtime JAR間でCombatCoreクラスが重複しています: ${combatCoreClassJars.filterValues { it.size > 1 }}"
        }
        val actualCoreDataModelCoordinate = resolvedCombatCoreDataModelCoordinate()
        check(actualCoreDataModelCoordinate == combatCoreDataModelCoordinate) {
            "CombatCore artifactの実DataModel依存が指定と一致しません: $actualCoreDataModelCoordinate / $combatCoreDataModelCoordinate"
        }
        check(dataModelFiles.single().name.contains(combatCoreDataModelCoordinate!!.substringAfterLast(':'))) {
            "CombatCoreとruntimeのSushiEricDataModel発行版が一致しません: $combatCoreDataModelCoordinate / ${dataModelFiles.single().name}"
        }
        combatCoreReleaseVersion?.let { expected ->
            check(runtime.get().files.any { it.name.startsWith("sushieric-combat-core-$expected") }) {
                "指定したCombatCore正式版がruntimeにありません: $expected"
            }
        }
        combatCoreDevelopmentCoordinate?.let { expected ->
            val module = expected.split(':')[1]
            check(runtime.get().files.any { it.name == "$module-${expected.substringAfterLast(':')}.jar" }) {
                "指定したCombatCore開発版がruntimeにありません: $expected"
            }
        }
    }
}

val recordCombatCoreDependency = tasks.register("recordCombatCoreDependency") {
    group = "verification"
    description = "固定されたCombatCoreとDataModelの座標・hash・ソースcommitを記録します"
    dependsOn(verifyReleaseContents)
    val output = layout.buildDirectory.file("reports/combat-core-dependencies.properties")
    outputs.file(output)
    inputs.property("dataModelCoordinate", dataModelDependency)
    inputs.property("combatCoreCoordinate", combatCoreDependency)
    inputs.property("combatCoreDataModelCoordinate", combatCoreDataModelCoordinate!!)
    inputs.property("dataModelSourceCommit", developmentInput("dataModelSourceCommit"))
    inputs.property("combatCoreSourceCommit", developmentInput("combatCoreSourceCommit"))
    inputs.files(configurations.runtimeClasspath, combatCoreMetadata)
    doLast {
        val runtimeFiles = configurations.runtimeClasspath.get().files
        val metadataFiles = combatCoreMetadata.files
        val artifacts = (runtimeFiles + metadataFiles).filter {
            it.name.startsWith("sushieric-combat-core-") || it.name.startsWith("sushieric-datamodel")
        }.sortedBy { it.name }.distinctBy { it.name }
        val lines = artifacts.map { file ->
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(file.readBytes()).joinToString("") { "%02x".format(it) }
            "artifact.${file.name}=sha256:$digest"
        }
        val dataModelCommit = developmentInput("dataModelSourceCommit").orNull ?: "未指定"
        val combatCoreCommit = developmentInput("combatCoreSourceCommit").orNull ?: "未指定"
        check(Regex("[0-9a-f]{40}").matches(dataModelCommit) && Regex("[0-9a-f]{40}").matches(combatCoreCommit)) {
            "固定artifactの検証記録にはDataModelとCombatCoreの完全なsource commit hashが必要です。"
        }
        val actualCoreDataModelCoordinate = resolvedCombatCoreDataModelCoordinate()
        val coordinates = listOf(
            "dataModel=$dataModelDependency",
            "combatCore=$combatCoreDependency",
            "combatCoreDataModel=$actualCoreDataModelCoordinate",
            "dataModelSourceCommit=$dataModelCommit",
            "combatCoreSourceCommit=$combatCoreCommit"
        )
        output.get().asFile.apply {
            parentFile.mkdirs()
            writeText((coordinates + lines).joinToString("\n") + "\n")
        }
    }
}

tasks.check { dependsOn(verifyReleaseContents, recordCombatCoreDependency) }

val verifyReleaseDistribution = tasks.register("verifyReleaseDistribution") {
    group = "verification"
    description = "installDistの配布用libを検査します"
    dependsOn("installDist", verifyReleaseContents, recordCombatCoreDependency)
    val libs = layout.buildDirectory.dir("install/$appName/lib")
    inputs.dir(libs)
    doLast { verifyDistributionFiles(libs.get().asFile.walkTopDown().filter { it.isFile }.toList()) }
}

// すべてのOS包装は、検査した通常配布だけを入力にします。
listOf("packageWindowsAppImage", "packageWindowsInstaller", "packageMacAppImage", "packageMacDmg").forEach { name ->
    tasks.named(name) { dependsOn(verifyReleaseDistribution) }
}
listOf("packageWindowsAppImage", "packageMacAppImage").forEach { name ->
    tasks.named(name) {
        doLast {
            verifyDistributionFiles(file(appImageOutputDir).walkTopDown().filter { it.isFile }.toList())
        }
    }
}

tasks.register("verifyPackagedApplication") {
    group = "verification"
    description = "展開済みinstaller/dmgまたはapp imageのJAR・設定を検査します"
    doLast {
        val location = providers.gradleProperty("packagedApplicationDirectory").orNull
            ?: throw GradleException("-PpackagedApplicationDirectory=<展開済みアプリの絶対パス>を指定してください。")
        val directory = file(location)
        check(directory.isDirectory) { "展開済みアプリがありません: $directory" }
        val files = directory.walkTopDown().filter { it.isFile }.toList()
        verifyDistributionFiles(files)
    }
}
