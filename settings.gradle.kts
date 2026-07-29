rootProject.name = "FatAI"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

include(":composeApp")
include(":server")
include(":shared") // Temporary compatibility bridge; feature code is being migrated out.
include(":core")
include(":database")
include(":feature-chat")
include(":feature-prompt")
include(":feature-memory")
include(":feature-knowledge")
include(":feature-model")
include(":feature-tools")
include(":feature-agent")
include(":feature-workspace")
include(":feature-files")
include(":feature-settings")
include(":feature-user")
