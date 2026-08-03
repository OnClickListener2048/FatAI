import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.androidLibrary)
}

kotlin {
    androidTarget { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
    iosArm64()
    iosSimulatorArm64()
    jvm()
    sourceSets {
        commonMain.dependencies {
            implementation(project(":core"))
            implementation(project(":database"))
            implementation(project(":feature-user"))
            implementation(project(":feature-settings"))
            implementation(project(":feature-tools"))
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.ktor.client.logging)
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.android)
            implementation(libs.ktor.client.okhttp)
        }
        jvmMain.dependencies { implementation(libs.ktor.client.cio) }
        appleMain.dependencies { implementation(libs.ktor.client.darwin) }
    }
}

android {
    namespace = "ai.fatai.feature.model"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig { minSdk = libs.versions.android.minSdk.get().toInt() }
}
