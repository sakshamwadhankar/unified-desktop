plugins { id("com.android.library") }

android {
    namespace = "top.niunaijun.blackbox"
    compileSdk = 36
    defaultConfig { minSdk = 29 }
    buildFeatures { aidl = true; buildConfig = true }
    ndkVersion = "26.1.10909125"
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    defaultConfig { ndk { abiFilters += listOf("arm64-v8a", "x86_64") } }
    sourceSets {
        getByName("main") {
            manifest.srcFile("vendor/source/Bcore/src/main/AndroidManifest.xml")
            java.srcDirs("vendor/source/Bcore/src/main/java", "src/main/java")
            aidl.srcDirs("vendor/source/Bcore/src/main/aidl")
            res.srcDirs("vendor/source/Bcore/src/main/res", "src/main/res")
        }
    }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt") } }
    packaging { jniLibs { useLegacyPackaging = true } }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.2.0")
    implementation("com.google.android.material:material:1.3.0")
    implementation("com.moandjiezana.toml:toml4j:0.7.2")
    implementation("com.github.tiann:FreeReflection:3.2.2")
    implementation(project(":black-reflection"))
    annotationProcessor(project(":compiler"))
}
