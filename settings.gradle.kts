rootProject.name = "SushiEricServerManager"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

include(":development")

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)

    repositories {
        maven {
            name = "dataModelDevelopment"
            url = uri(providers.gradleProperty("dataModelDevelopmentRepository").orElse("../.datamodel-dev-repository").get())
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
        mavenCentral()
    }
}
