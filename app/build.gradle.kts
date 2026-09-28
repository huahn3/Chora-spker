import java.util.Properties

plugins {
    id("com.android.application")
    id("com.google.devtools.ksp")
    // Version comes from the catalog (libs.versions.toml -> kotlin). It was
    // hard-pinned to 2.4.0 here while the Compose/Kotlin compiler plugin stayed
    // at 2.3.21, so the serialization compiler plugin and the language version
    // could disagree on the metadata they read/write.
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.craftworks.music"
    compileSdk = 37

    androidResources {
        generateLocaleConfig = true
    }

    defaultConfig {
        applicationId = "com.craftworks.music"
        minSdk = 23
        targetSdk = 37
        versionCode = 311
        versionName = "1.31.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildFeatures {
        buildConfig = true
    }

    // Optional real release signing. Drop a `keystore.properties` at the repo
    // root (git-ignored) with storeFile / storePassword / keyAlias / keyPassword
    // and release builds get signed with it. Without the file, release falls back
    // to the debug key so local builds keep working.
    val keystorePropsFile = rootProject.file("keystore.properties")
    if (keystorePropsFile.exists()) {
        val props = Properties().apply {
            keystorePropsFile.inputStream().use { stream -> stream.use(::load) }
        }
        signingConfigs {
            create("release") {
                storeFile = file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Was hard-wired to the debug keystore, so "release" builds were
            // installable over debug builds and vice versa. Falls back to debug
            // only when no release keystore is configured (local/CI), which keeps
            // `assembleDebug` working while still allowing a real release signing
            // config via keystore.properties.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
        debug {
            isDebuggable = true
            isProfileable = true
        }
    }
    testOptions {
        unitTests {
            // The parser under test calls android.util.Log; without this every
            // test touching a log statement throws "not mocked".
            isReturnDefaultValues = true
        }
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true

        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            pickFirsts.add("META-INF/NOTICE.md")
            pickFirsts.add("META-INF/LICENSE.md")
        }
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)

    implementation(libs.androidx.tv.foundation)
    implementation(libs.androidx.tv.material)

    implementation(libs.androidx.navigation.compose)

    implementation(libs.reorderable)
    implementation(libs.androidx.media)

    implementation(libs.androidx.material.icons.core)

    implementation(libs.konsume.xml)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.org.snakeyaml)

    implementation(libs.coil.compose)
    implementation(libs.androidx.palette.ktx)
    // Not referenced directly, but it brings androidx.appcompat transitively —
    // AppearanceDialogs (both mobile and TV) uses AppCompatDelegate. Removing
    // this "unused" dependency breaks the build.
    implementation(libs.androidx.preference.ktx)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.material3.android)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.composefadingedges)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.logging)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.client.content.negotiation)

    coreLibraryDesugaring(libs.desugar.jdk.libs)
}