import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    // Kotlin itself is built into AGP 9; only the Compose compiler plugin is applied.
    alias(libs.plugins.kotlin.compose)
}

// The version lives in package.json (bumped by Changesets); -Ptono.version=1.2.3 overrides it.
val packageVersion = Regex("\"version\"\\s*:\\s*\"([^\"]+)\"")
    .find(rootProject.file("package.json").readText())?.groupValues?.get(1)
    ?: error("No \"version\" in package.json")
val appVersion = ((findProperty("tono.version") as String?) ?: packageVersion).removePrefix("v")
// 1.2.3 -> 10203, so every newer version installs over the last one.
val appVersionCode = appVersion.split(".").map { part -> part.takeWhile(Char::isDigit).ifEmpty { "0" }.toInt() }
    .let { p -> (p + listOf(0, 0, 0)).take(3) }
    .let { (major, minor, patch) -> major * 10000 + minor * 100 + patch }
    .coerceAtLeast(1) // 0.0.0 (before the first release) still needs a valid versionCode
// In GitHub Actions this is the repo being built; locally it comes from gradle.properties.
val updateRepo = System.getenv("GITHUB_REPOSITORY") ?: (findProperty("tono.updateRepo") as String?) ?: ""

android {
    namespace = "app.tono"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.tono"
        // Android 10+: scoped storage + MediaStore.RELATIVE_PATH, no storage permission needed.
        minSdk = 29
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersion
        // GitHub repo ("owner/name") whose Releases the in-app update check reads.
        buildConfigField("String", "UPDATE_REPO", "\"$updateRepo\"")
    }

    // Release key lives outside version control in keystore.properties (see README).
    val keystoreProps = Properties().apply {
        val f = rootProject.file("keystore.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    signingConfigs {
        if (keystoreProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfigs.findByName("release")?.let { signingConfig = it }
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        // NewPipeExtractor uses newer java.* APIs; desugaring keeps them working on older Androids.
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES")
        }
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.media3.common)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.transformer)

    implementation(libs.okhttp)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.newpipe.extractor)

    testImplementation(libs.junit)
}
