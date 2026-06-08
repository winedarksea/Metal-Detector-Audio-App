import org.jetbrains.kotlin.gradle.targets.js.dsl.ExperimentalWasmDsl
import java.time.Instant

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

val generatedWebBuildInfoDir = layout.buildDirectory.dir("generated/source/buildInfo/wasmJsMain/kotlin")
val appVersionName = providers.gradleProperty("app.version.name").orElse("0.2.0")
val buildDateUtc = providers.gradleProperty("app.build.date.utc").orElse(Instant.now().toString())

val generateWebBuildInfo by tasks.registering {
    val outputFile = generatedWebBuildInfoDir.map {
        it.file("com/metaldetectoraudioapp/web/AppBuildInfo.kt").asFile
    }
    inputs.property("appVersionName", appVersionName)
    inputs.property("buildDateUtc", buildDateUtc)
    outputs.file(outputFile)

    doLast {
        val file = outputFile.get()
        file.parentFile.mkdirs()
        file.writeText(
            """
            package com.metaldetectoraudioapp.web

            object AppBuildInfo {
                const val APP_VERSION_NAME = "${appVersionName.get()}"
                const val BUILD_DATE_UTC = "${buildDateUtc.get()}"
            }
            """.trimIndent()
        )
    }
}

kotlin {
    compilerOptions {
        optIn.add("kotlin.js.ExperimentalWasmJsInterop")
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        compilations.all {
            compileTaskProvider.configure {
                dependsOn(generateWebBuildInfo)
            }
        }
        browser {
            commonWebpackConfig {
                outputFileName = "webApp.js"
            }
        }
        binaries.executable()
    }

    sourceSets {
        val wasmJsMain by getting {
            kotlin.srcDir(generatedWebBuildInfoDir)
            dependencies {
                implementation(project(":shared"))
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.materialIconsExtended)
                implementation(compose.ui)
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
            }
        }
    }
}
