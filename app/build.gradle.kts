plugins {
    id("com.android.application")
}

import java.util.Properties

// Public version; the release workflow reads both values to decide whether to publish.
val releaseVersionName = "1.0.0"

val embeddedRuntimeDir = layout.projectDirectory.dir("src/main/assets/runtime")
val requiredRuntimeAssets = listOf(
    "ubuntu-22.04.5-base-arm64.targz",
    "proot",
    "proot-loader",
    "libtalloc.so.2",
    "libandroid-shmem.so",
    "busybox",
    "cacert.pem"
)

val verifyRuntimeAssets by tasks.registering {
    inputs.files(requiredRuntimeAssets.map { embeddedRuntimeDir.file(it) })
    doLast {
        val missing = requiredRuntimeAssets.filter {
            !embeddedRuntimeDir.file(it).asFile.isFile
        }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "Missing embedded runtime assets: $missing\n" +
                    "Run tools/fetch-runtime.sh to download and verify them first."
            )
        }
    }
}

val syncLicenses by tasks.registering(Copy::class) {
    from(rootProject.layout.projectDirectory.dir("licenses"))
    into(layout.buildDirectory.dir("generated/licenses-assets/licenses"))
}

tasks.named("preBuild") {
    dependsOn(verifyRuntimeAssets, syncLicenses)
}

android {
    namespace = "cn.lineai.terminal.extension"
    compileSdk = 36

    defaultConfig {
        applicationId = "cn.lineai.terminal.extension"
        minSdk = 26
        // PRoot executes guest binaries from the app data directory. Android 10+ blocks
        // that for apps targeting API 29+, so this extension intentionally stays at 28.
        targetSdk = 28
        versionCode = 1
        versionName = releaseVersionName
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        aidl = true
    }

    androidResources {
        noCompress.addAll(listOf("targz", "pem", "so", "bin"))
    }

    sourceSets["main"].assets.srcDir(
        layout.buildDirectory.dir("generated/licenses-assets").get().asFile
    )

    // Release signing mirrors LineCode: the CI workflow decodes RELEASE_KEYSTORE_BASE64
    // into signing/ and writes signing.properties; local release builds use the same file.
    signingConfigs {
        create("release") {
            val propertiesFile = rootProject.file("signing.properties")
            if (propertiesFile.isFile) {
                val properties = Properties()
                properties.load(propertiesFile.inputStream())
                storeFile = file(properties.getProperty("storeFile"))
                storePassword = properties.getProperty("storePassword")
                keyAlias = properties.getProperty("keyAlias")
                keyPassword = properties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        // targetSdk 28 is a hard requirement for executing PRoot from app data on Android 10+.
        // Lint's ExpiredTargetSdkVersion check targets Play Store requirements and does not apply.
        disable.add("ExpiredTargetSdkVersion")
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

tasks.register("validateReleaseSigning") {
    group = "verification"
    description = "Fails the release build early when the signing configuration is incomplete."
    doLast {
        val config = android.signingConfigs.getByName("release")
        if (config.storeFile == null || !config.storeFile!!.isFile) {
            throw GradleException(
                "Release keystore is missing. Decode RELEASE_KEYSTORE_BASE64 into signing/ and" +
                    " write signing.properties (storeFile/storePassword/keyAlias/keyPassword)."
            )
        }
        if (config.storePassword.isNullOrEmpty() || config.keyAlias.isNullOrEmpty() ||
            config.keyPassword.isNullOrEmpty()
        ) {
            throw GradleException("Release signing properties are incomplete.")
        }
    }
}
