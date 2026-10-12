rootProject.name = "SushiEricServerManager"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

include(":development")

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)

    repositories {
        maven {
            name = "commonDevelopment"
            url = uri(providers.gradleProperty("commonDevelopmentRepository").orElse("../.common-dev-repository").get())
            content {
                includeModule(
                    "io.github.sushiericworkspace",
                    "sushieric-common-editor-dev"
                )
            }
        }
        maven {
            name = "commonRelease"
            url = uri(providers.gradleProperty("commonReleaseRepository").orElse("../.common-release-repository").get())
            content {
                includeModule(
                    "io.github.sushiericworkspace",
                    "sushieric-common"
                )
            }
        }
        mavenCentral()
    }
}
