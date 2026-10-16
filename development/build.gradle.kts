plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.javafx)
    application
}

kotlin { jvmToolchain(21) }
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

tasks.test { useJUnitPlatform() }

// 製品APIの公開範囲を広げず、管理ストア統合テストだけで通常mainのinternal APIを参照します。
tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileTestKotlin") {
    friendPaths.from(rootProject.tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileKotlin")
        .flatMap { it.destinationDirectory })
}
