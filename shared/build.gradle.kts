import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.androidLibrary)
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "shared2"
            isStatic = false
            export("io.insert.koin:koin-core")
        }
    }

    jvm()

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core"))
            implementation(project(":database"))
            implementation(project(":feature-model"))
            implementation(project(":feature-workspace"))
            implementation(project(":feature-memory"))
            implementation(project(":feature-files"))
            implementation(project(":feature-prompt"))
            implementation(project(":feature-chat"))
            implementation(project(":feature-settings"))
            implementation(project(":feature-user"))

            // Koin 核心库
            implementation(libs.koin.core)
            // Koin for Compose Multiplatform (提供 koinInject() 等)
            implementation(libs.koin.compose)
        }

        jvmMain.dependencies {
            implementation(libs.landscapist.coil3)
        }
        androidMain.dependencies {
            // Koin Android specific helpers (e.g., for androidContext())
            implementation(libs.koin.android)
            implementation(libs.landscapist.coil3)
        }
        appleMain.dependencies {
            // put your Multiplatform dependencies here
            implementation(libs.landscapist.coil3)
        }
        nativeMain.dependencies {
            implementation(libs.landscapist.coil3)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

android {
    namespace = "ai.fatai.shared"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
}
