plugins {
    id("com.android.application")
}

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
        versionName = "1.0.0"
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

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
