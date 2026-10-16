import java.util.Properties

rootProject.name = "SushiEricServerManager"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

include(":development")

val developmentInputKeys = listOf("dataModelDevelopmentCoordinate", "combatCoreDevelopmentCoordinate",
    "combatCoreDataModelCoordinate", "dataModelDevelopmentRepository", "combatCoreDevelopmentRepository",
    "dataModelSourceCommit", "combatCoreSourceCommit")
val explicitDependencyInput = (developmentInputKeys + listOf("dataModelReleaseVersion", "combatCoreReleaseVersion")).any {
    providers.gradleProperty(it).isPresent
} || gradle.startParameter.taskNames.any { it.substringAfterLast(':') in setOf("releaseWindowsInstaller", "releaseMacDmg") }
val localDevelopment = Properties().apply {
    if (!explicitDependencyInput) {
        val input = file(".manager-development.properties")
        if (input.isFile) input.reader(Charsets.UTF_8).use { load(it) }
    }
}
gradle.extensions.extraProperties.set("managerDevelopmentInputs", localDevelopment)
fun developmentInput(key: String) = providers.gradleProperty(key).orElse(providers.provider {
    localDevelopment.getProperty(key)
})

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)

    repositories {
        maven {
            name = "dataModelDevelopment"
            url = uri(developmentInput("dataModelDevelopmentRepository").orElse("../.datamodel-dev-repository").get())
            content {
                includeModule(
                    "io.github.sushiericworkspace",
                    "sushieric-datamodel-editor-dev"
                )
            }
        }
        maven {
            name = "dataModelRelease"
            url = uri(providers.gradleProperty("dataModelReleaseRepository").orElse("../.datamodel-release-repository").get())
            content {
                includeModule(
                    "io.github.sushiericworkspace",
                    "sushieric-datamodel"
                )
            }
        }
        maven {
            name = "combatCoreDevelopment"
            url = uri(developmentInput("combatCoreDevelopmentRepository")
                .orElse(developmentInput("dataModelDevelopmentRepository").orElse("../.datamodel-dev-repository")).get())
            content {
                includeModule("io.github.sushiericworkspace", "sushieric-combat-core-dev")
                includeModule("io.github.sushiericworkspace", "sushieric-datamodel-mod-dev")
            }
        }
        maven {
            name = "combatCoreRelease"
            url = uri("../.combat-core-release-repository")
            content {
                includeModule("io.github.sushiericworkspace", "sushieric-combat-core")
            }
        }
        mavenCentral()
    }
}
