import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.android.kotlin.multiplatform.library")
}

kotlin {
    android {
        namespace = "com.metaldetectoraudioapp.shared"
        compileSdk = 36
        minSdk = 28

        compilations.all {
            compileTaskProvider.configure {
                compilerOptions {
                    jvmTarget.set(JvmTarget.JVM_17)
                }
            }
        }
    }

    jvm("desktop") {
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions {
                    jvmTarget.set(JvmTarget.JVM_17)
                }
            }
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.ui)
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
            }
        }

        // JVM source set shared by both Android and Desktop targets.
        val jvmMain by creating {
            dependsOn(commonMain)
            dependencies {
                implementation("org.json:json:20240303")
            }
        }

        val androidMain by getting {
            dependsOn(jvmMain)
            dependencies {
                implementation("org.tensorflow:tensorflow-lite:2.16.1")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
            }
        }

        val desktopMain by getting {
            dependsOn(jvmMain)
            dependencies {
                implementation(compose.desktop.currentOs)
                // ONNX Runtime for desktop CNN inference (mel spectrogram computed in Kotlin)
                implementation("com.microsoft.onnxruntime:onnxruntime:1.20.0")
                // Provides Dispatchers.Main on desktop JVM
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
            }
        }
    }
}

