import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.time.Instant

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

val desktopModelResources = layout.buildDirectory.dir("desktopModelResources")
val generatedDesktopBuildInfoDir = layout.buildDirectory.dir("generated/source/buildInfo/desktopMain/kotlin")
val appVersionName = providers.gradleProperty("app.version.name").orElse("0.2.0")
val buildDateUtc = providers.gradleProperty("app.build.date.utc").orElse(Instant.now().toString())
val desktopPackageVersion = providers.gradleProperty("desktop.package.version").orElse("1.0.0")

val generateDesktopBuildInfo by tasks.registering {
    val outputFile = generatedDesktopBuildInfoDir.map {
        it.file("com/metaldetectoraudioapp/desktop/AppBuildInfo.kt").asFile
    }
    inputs.property("appVersionName", appVersionName)
    inputs.property("buildDateUtc", buildDateUtc)
    outputs.file(outputFile)

    doLast {
        val file = outputFile.get()
        file.parentFile.mkdirs()
        file.writeText(
            """
            package com.metaldetectoraudioapp.desktop

            object AppBuildInfo {
                const val APP_VERSION_NAME = "${appVersionName.get()}"
                const val BUILD_DATE_UTC = "${buildDateUtc.get()}"
            }
            """.trimIndent()
        )
    }
}

kotlin {
    jvm("desktop") {
        compilations.all {
            compileTaskProvider.configure {
                dependsOn(generateDesktopBuildInfo)
                compilerOptions {
                    jvmTarget.set(JvmTarget.JVM_17)
                }
            }
        }
    }

    sourceSets {
        val desktopMain by getting {
            resources.srcDir(desktopModelResources)
            kotlin.srcDir(generatedDesktopBuildInfoDir)
            dependencies {
                implementation(project(":shared"))
                implementation(compose.desktop.currentOs)
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.materialIconsExtended)
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
            packageVersion = desktopPackageVersion.get()

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
