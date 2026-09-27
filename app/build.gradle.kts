plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.omp.terminal"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.omp.terminal"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    // The native helper in `jniLibs/` is only reachable if the APK's `lib/<abi>/` entries are
    // **compressed and extracted to disk at install time**, which is what puts a file the kernel
    // will `execve` into `applicationInfo.nativeLibraryDir`. Google's own definition of this
    // option, quoted from the API reference: "Whether to use the legacy convention of compressing
    // all .so files in the APK. If null, .so files will be uncompressed and page-aligned when
    // minSdk >= 23." The uncompressed, page-aligned form is the *other* path: nothing is written
    // to disk and the linker maps the entry straight out of the APK — which leaves the helper as a
    // compressed member of a ZIP, which is not something to exec.
    //
    // Google's documentation says this DSL option replaces the `android:extractNativeLibs`
    // manifest attribute, so both are set: the manifest line is the older mechanism the platform
    // documents, and this one is what AGP acts on. Verified with `aapt2 dump xmltree` against a
    // built APK — `android:extractNativeLibs(0x010104ea)=true` survives the merge — and that
    // APK's `lib/` entries come out DEFLATEd, which is this option taking effect.
    //
    // <https://developer.android.com/reference/tools/gradle-api/7.1/com/android/build/api/dsl/JniLibsPackagingOptions#uselegacypackaging>
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))
}
