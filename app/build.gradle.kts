import org.gradle.api.configuration.BuildFeatures
import javax.inject.Inject

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

abstract class BuildFeaturesAccessor @Inject constructor(
    val buildFeatures: BuildFeatures,
)

val releaseSigningVariableNames = listOf(
    "ANDROID_KEYSTORE_PATH",
    "ANDROID_KEYSTORE_PASSWORD",
    "ANDROID_KEY_ALIAS",
    "ANDROID_KEY_PASSWORD",
)

val releaseArtifactRequested = gradle.startParameter.taskNames.any { requestedTask ->
    val taskName = requestedTask.substringAfterLast(':')
    taskName.matches(Regex("(?i)(assemble|bundle|install|package|sign).*release.*"))
}

val configurationCacheRequested = objects
    .newInstance<BuildFeaturesAccessor>()
    .buildFeatures
    .configurationCache
    .requested
    .getOrElse(false)

if (releaseArtifactRequested && configurationCacheRequested) {
    throw GradleException(
        "Release signing must run with --no-configuration-cache so credentials are not " +
            "retained in Gradle's configuration cache.",
    )
}

// Do not read or configure signing secrets for debug/test/lint invocations. Besides reducing
// exposure, this keeps them out of the configuration cache used by normal development builds.
val releaseSigningEnvironment = if (releaseArtifactRequested) {
    releaseSigningVariableNames.associateWith { name ->
        providers.environmentVariable(name).orNull?.takeIf { it.isNotBlank() }
    }
} else {
    emptyMap()
}

val missingReleaseSigningVariables = if (releaseArtifactRequested) {
    releaseSigningVariableNames.filter { releaseSigningEnvironment[it] == null }
} else {
    emptyList()
}

val prepareStrongSwan by tasks.registering(Exec::class) {
    group = "build setup"
    description = "Prepares strongSwan and builds its Android OpenSSL library."
    commandLine("bash", rootProject.file("scripts/prepare-strongswan-android.sh").absolutePath)
}

if (releaseArtifactRequested && missingReleaseSigningVariables.isNotEmpty()) {
    throw GradleException(
        "A signed release was requested, but these environment variables are missing: " +
            missingReleaseSigningVariables.joinToString() +
            ". Configure all release-signing values and try again.",
    )
}

val releaseKeystorePath = releaseSigningEnvironment["ANDROID_KEYSTORE_PATH"]
if (
    releaseArtifactRequested &&
    releaseKeystorePath != null &&
    (!file(releaseKeystorePath).isFile || !file(releaseKeystorePath).canRead())
) {
    throw GradleException(
        "ANDROID_KEYSTORE_PATH must point to an existing keystore file readable by Gradle.",
    )
}

android {
    namespace = "org.strongswan.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.saeed.ikev2vpn"
        minSdk = 30
        targetSdk = 36
        versionCode = 5
        versionName = "1.3.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    ndkVersion = "27.3.13750724"

    externalNativeBuild {
        ndkBuild {
            path = file("$rootDir/third_party/strongswan/src/frontends/android/app/src/main/jni/Android.mk")
        }
    }

    sourceSets {
        getByName("main") {
            val strongSwanFrontend = rootProject.file("third_party/strongswan/src/frontends/android/app/src/main")
            java.srcDir(strongSwanFrontend.resolve("java"))
            res.srcDir(strongSwanFrontend.resolve("res"))
        }
    }

    signingConfigs {
        if (releaseArtifactRequested) {
            create("release") {
                storeFile = file(releaseSigningEnvironment.getValue("ANDROID_KEYSTORE_PATH")!!)
                storePassword = releaseSigningEnvironment.getValue("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = releaseSigningEnvironment.getValue("ANDROID_KEY_ALIAS")
                keyPassword = releaseSigningEnvironment.getValue("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("release") {
            if (releaseArtifactRequested) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
    }
}

tasks.named("preBuild").configure {
    dependsOn(prepareStrongSwan)
}

tasks.configureEach {
    if (name.startsWith("configureNdkBuild") || name.startsWith("externalNativeBuild")) {
        dependsOn(prepareStrongSwan)
    }
}

val verifyReleaseSigning by tasks.registering {
    group = "verification"
    description = "Prevents release packaging from bypassing the explicit signing checks."
    notCompatibleWithConfigurationCache(
        "Release signing credentials must not be retained in the configuration cache.",
    )

    doLast {
        if (!releaseArtifactRequested) {
            throw GradleException(
                "Release packaging must be requested explicitly with a full task name, for " +
                    "example: ./gradlew --no-daemon --no-configuration-cache assembleRelease",
            )
        }
    }
}

tasks.configureEach {
    val lowerName = name.lowercase()
    val isReleasePackagingTask = "release" in lowerName && listOf(
        "assemble",
        "bundle",
        "install",
        "package",
        "sign",
    ).any { prefix -> lowerName.startsWith(prefix) }

    if (isReleasePackagingTask) {
        dependsOn(verifyReleaseSigning)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.01")

    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.10.0")
    implementation("androidx.datastore:datastore-preferences:1.2.1")
    val cameraXVersion = "1.6.2"
    implementation("androidx.camera:camera-camera2:$cameraXVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraXVersion")
    implementation("androidx.camera:camera-view:$cameraXVersion")
    implementation("com.google.zxing:core:3.5.4")

    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.core:core:1.17.0")
    implementation("androidx.lifecycle:lifecycle-process:2.9.4")
    implementation("androidx.preference:preference:1.2.1")
    implementation("androidx.localbroadcastmanager:localbroadcastmanager:1.1.0")
    implementation("com.google.android.material:material:1.13.0")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito:mockito-core:5.23.0")
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
