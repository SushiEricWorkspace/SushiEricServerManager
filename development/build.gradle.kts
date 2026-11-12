import org.gradle.api.tasks.testing.Test
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.javafx)
    application
}

kotlin { jvmToolchain(25) }

java { toolchain.languageVersion.set(JavaLanguageVersion.of(25)) }
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions.freeCompilerArgs.add("-Xjdk-release=25")
}

javafx {
    version = libs.versions.javafx.get()
    modules("javafx.controls", "javafx.fxml")
}

dependencies {
    implementation(project(":"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    testImplementation(kotlin("test"))
}

application {
    applicationName = "SushiEricServerManager-Development"
    mainClass.set("io.github.sushiericworkspace.sushiericservermanager.app.DevelopmentLauncher")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    val config = layout.buildDirectory.dir("test-config/$name").get().asFile
    systemProperty("sushieric.test.configRoot", config.absolutePath)
    environment("LOCALAPPDATA", config.resolve("localappdata").absolutePath)
    environment("APPDATA", config.resolve("appdata").absolutePath)
    environment("XDG_CONFIG_HOME", config.resolve("xdg").absolutePath)
    systemProperty("user.home", config.resolve("home").absolutePath)
    doFirst {
        listOf("localappdata", "appdata", "home", "xdg").forEach { config.resolve(it).mkdirs() }
    }
}

// 製品APIの公開範囲を広げず、管理ストア統合テストだけで通常mainのinternal APIを参照します。
tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileTestKotlin") {
    source(rootProject.file("src/test/kotlin/io/github/sushiericworkspace/sushiericservermanager/editor/render/PreviewCanvasFxTest.kt"))
    friendPaths.from(rootProject.tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileKotlin")
        .flatMap { it.destinationDirectory })
}
