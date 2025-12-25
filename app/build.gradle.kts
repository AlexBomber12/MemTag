plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ktlint)
}

android {
    namespace = "com.alexbomber12.memtag"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.alexbomber12.memtag"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
    flavorDimensions += "hw"
    productFlavors {
        create("mock") {
            dimension = "hw"
        }
        create("device") {
            dimension = "hw"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
    }
}

val deviceLibDirs =
    listOf("libs", "lib").map { dirName ->
        layout.projectDirectory.dir(dirName).asFile
    }
val rawDeviceLibs =
    deviceLibDirs.flatMap { dir ->
        dir.listFiles()?.toList().orEmpty()
    }
val deviceLibArtifacts =
    rawDeviceLibs.filter { file ->
        file.extension.equals("jar", ignoreCase = true) ||
            file.extension.equals("aar", ignoreCase = true)
    }
val hasDeviceApiAar =
    deviceLibArtifacts.any { file ->
        file.extension.equals("aar", ignoreCase = true) &&
            file.name.contains("DeviceAPI", ignoreCase = true)
    }
val resolvedDeviceLibs =
    if (hasDeviceApiAar) {
        deviceLibArtifacts.filterNot { file ->
            file.name.startsWith("cw-deviceapi", ignoreCase = true)
        }
    } else {
        deviceLibArtifacts
    }
val verifyDeviceLibs =
    tasks.register("verifyDeviceLibs") {
        doLast {
            val libs = resolvedDeviceLibs
            if (libs.isEmpty()) {
                throw GradleException(
                    "Device flavor requires vendor UHF SDK jars/aars in app/libs (preferred) " +
                        "or app/lib (legacy). Add the Chainway SDK files or build the mock flavor " +
                        "with :app:assembleMockDebug.",
                )
            }
        }
    }

tasks.matching { task ->
    task.name.startsWith("preDevice") && task.name.endsWith("Build")
}.configureEach {
    dependsOn(verifyDeviceLibs)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    add("deviceImplementation", files(resolvedDeviceLibs))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

ktlint {
    android.set(true)
    outputToConsole.set(true)
    ignoreFailures.set(false)
}
