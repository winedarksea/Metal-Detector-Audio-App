import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.time.Instant

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val appVersionName = providers.gradleProperty("app.version.name").orElse("0.2.0")
val appVersionCode = providers.gradleProperty("app.version.code").map(String::toInt).orElse(1)
val buildDateUtc = providers.gradleProperty("app.build.date.utc").orElse(Instant.now().toString())

android {
    namespace = "com.metaldetectoraudioapp.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.metaldetectoraudioapp.app"
        minSdk = 31
        targetSdk = 36
        versionCode = appVersionCode.get()
        versionName = appVersionName.get()
        buildConfigField("String", "BUILD_DATE_UTC", "\"${buildDateUtc.get()}\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    sourceSets {
        getByName("main").assets.srcDirs("src/main/assets", "${rootDir}/models")
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

val validateStarterTrainingInputs by tasks.registering(Exec::class) {
    group = "verification"
    description = "Validates labels and WAV consistency before building."
    // CWD must be rootDir so the script's default relative paths resolve correctly.
    workingDir = rootDir
    commandLine(
        "python3",
        "${rootDir}/scripts/train_starter_model.py",
        "--assets-dir", "${rootDir}/assets",
        "--labels-csv", "${rootDir}/assets/cleaned_labels.csv",
        "--dry-run",
    )
}

val validatePublishedModelArtifacts by tasks.registering {
    group = "verification"
    description = "Requires complete standard and no-mixed production model artifact sets."
    val requiredArtifactNames = listOf(
        "starter_model.tflite",
        "starter_model_cnn.tflite",
        "starter_model_cnn_int8.tflite",
        "starter_model_cnn.onnx",
        "starter_model_metadata.json",
        "starter_model_metrics.json",
        "starter_model_no_mixed.tflite",
        "starter_model_no_mixed_cnn.tflite",
        "starter_model_no_mixed_cnn_int8.tflite",
        "starter_model_no_mixed_cnn.onnx",
        "starter_model_no_mixed_metadata.json",
        "starter_model_no_mixed_metrics.json",
    )
    val artifactFiles = requiredArtifactNames.map { rootProject.file("models/$it") }
    inputs.files(artifactFiles)
    doLast {
        val missing = artifactFiles.filterNot(File::isFile)
        if (missing.isNotEmpty()) {
            throw GradleException(
                "Missing production model artifacts:\n" +
                    missing.joinToString("\n") { "  - ${it.relativeTo(rootDir)}" } +
                    "\nRun the default scripts/export_onnx_cnn_only.py command."
            )
        }
    }
}

// ── LabelCatalog single-source-of-truth enforcement ─────────────────────────
// :app does not depend on :shared (their ui.model packages would collide), so it
// keeps a byte-identical duplicate of LabelCatalog.kt. These tasks keep the two
// copies aligned: the build fails fast if they drift, and syncLabelCatalog
// regenerates the app copy from the shared source of truth with one command.
val sharedLabelCatalogFile =
    file("${rootDir}/shared/src/commonMain/kotlin/com/metaldetectoraudioapp/app/ui/model/LabelCatalog.kt")
val appLabelCatalogFile =
    file("src/main/java/com/metaldetectoraudioapp/app/ui/model/LabelCatalog.kt")

val syncLabelCatalog by tasks.registering(Copy::class) {
    group = "build setup"
    description = "Copies the shared LabelCatalog.kt over the app duplicate."
    from(sharedLabelCatalogFile)
    into(appLabelCatalogFile.parentFile)
}

val checkLabelCatalogInSync by tasks.registering {
    group = "verification"
    description = "Fails the build if app/ LabelCatalog.kt drifted from the shared source of truth."
    val sharedFile = sharedLabelCatalogFile
    val appFile = appLabelCatalogFile
    inputs.file(sharedFile)
    inputs.file(appFile)
    doLast {
        fun normalize(file: File) = file.readText().replace("\r\n", "\n").trimEnd()
        if (normalize(sharedFile) != normalize(appFile)) {
            throw GradleException(
                "app/ LabelCatalog.kt is out of sync with the shared source of truth " +
                    "(shared/src/commonMain/.../ui/model/LabelCatalog.kt).\n" +
                    "Edit the shared file, then run:  ./gradlew :app:syncLabelCatalog"
            )
        }
    }
}

tasks.named("preBuild").configure {
    dependsOn(validateStarterTrainingInputs)
    dependsOn(validatePublishedModelArtifacts)
    dependsOn(checkLabelCatalogInSync)
}

dependencies {
    // MDC Android – provides XML resource themes like Theme.Material3.DayNight.NoActionBar
    implementation("com.google.android.material:material:1.12.0")

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation(platform("androidx.compose:compose-bom:2025.09.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material3:material3-adaptive-navigation-suite")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.google.ai.edge.litert:litert:2.1.5")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("androidx.test:core:1.7.0")
}
