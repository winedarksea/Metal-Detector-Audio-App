import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

val desktopModelResources = layout.buildDirectory.dir("desktopModelResources")

kotlin {
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
        val desktopMain by getting {
            resources.srcDir(desktopModelResources)
            dependencies {
                implementation(project(":shared"))
                implementation(compose.desktop.currentOs)
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.ui)
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
                implementation("org.json:json:20240303")
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.metaldetectoraudioapp.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "MetalDetectorAudio"
            packageVersion = "1.0.0"

            macOS {
                bundleID = "com.metaldetectoraudioapp.desktop"
                entitlementsFile.set(project.file("entitlements.plist"))
            }
        }

        // Copy model assets into the classpath resources.
        jvmArgs("--add-opens", "java.base/java.lang=ALL-UNNAMED")
    }
}

// Copy model files into desktop resources so they're available on the classpath.
tasks.register<Copy>("copyModelAssets") {
    from("${rootDir}/models") {
        include("starter_model_cnn.onnx", "starter_model_metadata.json")
    }
    into(desktopModelResources)
}

tasks.named("desktopProcessResources") {
    dependsOn("copyModelAssets")
}
