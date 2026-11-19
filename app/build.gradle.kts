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
        getByName("main").assets.srcDir(rootProject.file(".local/vehicle-pack-assets"))
        getByName("test").resources.srcDir(rootProject.file("tools/thinkdiag/schema"))
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

// Actions performs heavy source conversion. An APK contains only the small, source-pinned
// hash-tree index. Per-vehicle/system objects live in release storage, not assets or preferences.
val verifyToyotaVehicleIndex by tasks.registering(Exec::class) {
    val source = rootProject.file(".local/vehicle-pack-assets/toyota-vehicle-packs")
    inputs.file(source.resolve("index.json"))
    inputs.files(rootProject.fileTree("tools/thinkdiag") { include("vehicle_bundle.py", "verify_vehicle_bundle.py") })
    workingDir(rootProject.projectDir)
    commandLine("python3", "tools/thinkdiag/verify_vehicle_bundle.py", "--directory", source.absolutePath, "--index-only")
}
tasks.named("preBuild").configure { dependsOn(verifyToyotaVehicleIndex) }
// Validate the real Actions conversion with the same Kotlin codecs used on the phone.
tasks.withType<Test>().configureEach {
    systemProperty("toyota.pack.directory", rootProject.file(".local/toyota-vehicle-packs").absolutePath)
}

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
