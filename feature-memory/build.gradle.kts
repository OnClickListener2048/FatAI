import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins { alias(libs.plugins.kotlinMultiplatform); alias(libs.plugins.androidLibrary) }

kotlin {
    androidTarget { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
    iosArm64(); iosSimulatorArm64(); jvm()
    sourceSets {
        commonMain.dependencies {
            implementation(project(":core"))
            implementation(project(":database"))
            implementation(project(":feature-model"))
            implementation(project(":feature-user"))
            implementation(libs.ktor.client.core)
        }
    }
}

android { namespace = "ai.fatai.feature.memory"; compileSdk = libs.versions.android.compileSdk.get().toInt(); defaultConfig { minSdk = libs.versions.android.minSdk.get().toInt() } }
