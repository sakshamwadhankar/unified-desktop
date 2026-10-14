plugins { id("com.android.library") }

android {
    namespace = "top.niunaijun.blackbox"
    compileSdk = 36
    defaultConfig { minSdk = 29; targetSdk = 36 }
    buildFeatures { aidl = true; buildConfig = true }
    ndkVersion = "29.0.13846066"
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    defaultConfig { ndk { abiFilters += listOf("arm64-v8a", "x86_64") } }
    sourceSets["main"].java.srcDirs("vendor/source/Bcore/src/main/java", "src/main/java")
    sourceSets["main"].aidl.srcDirs("vendor/source/Bcore/src/main/aidl")
    sourceSets["main"].res.srcDirs("vendor/source/Bcore/src/main/res", "src/main/res")
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt") } }
    packaging { jniLibs { useLegacyPackaging = true } }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.2.0")
    implementation("com.google.android.material:material:1.3.0")
    implementation("com.moandjiezana.toml:toml4j:0.7.2")
    implementation("com.github.tiann:FreeReflection:3.2.2")
    implementation(project(":engine-black-reflection"))
    annotationProcessor(project(":engine-compiler"))
}
