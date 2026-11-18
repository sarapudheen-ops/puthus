plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.bod3.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.bod3.app"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

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
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    sourceSets {
        getByName("main").assets.srcDir(rootProject.file(".local/toyota-app-assets"))
        getByName("test").resources.srcDir(rootProject.file("tools/thinkdiag/schema"))
    }
    androidResources {
        // The database is already compressed. Do not inflate APK build/install work.
        noCompress += "zip"
    }
    lint {
        // Produce HTML/XML/SARIF reports under app/build/reports for CI artifacts
        htmlReport = true
        xmlReport = true
        sarifReport = true
        // Also print issues to the console so CI can annotate them.
        textReport = true
        textOutput = file("stdout")
    }
}

// Generated data is deliberately not checked into Git. Every APK (debug or release)
// must contain the complete pinned library; there is no missing-data fallback build.
val verifyToyotaSourceLibrary by tasks.registering(Exec::class) {
    val source = rootProject.file(".local/toyota-app-assets/toyota-source")
    val stamp = layout.buildDirectory.file("intermediates/toyota-source/verified.txt")
    inputs.dir(source)
    inputs.files(rootProject.fileTree("tools/thinkdiag") { include("*.py", "schema/*.sql") })
    outputs.file(stamp)
    workingDir(rootProject.projectDir)
    commandLine("python3", "tools/thinkdiag/verify_app_database.py", "--directory", source.absolutePath,
        "--stamp", stamp.get().asFile.absolutePath)
}
tasks.named("preBuild").configure { dependsOn(verifyToyotaSourceLibrary) }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.core)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.core)
    // Same SQLite queries as Android, exercised on the JVM against the shared schema.
    testImplementation("org.xerial:sqlite-jdbc:3.46.1.0")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
