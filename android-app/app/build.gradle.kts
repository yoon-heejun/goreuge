plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "io.cellbalance.app"
    compileSdk = 35
    buildToolsVersion = "35.0.0"
    defaultConfig {
        applicationId = "io.cellbalance.app"
        minSdk = 28
        targetSdk = 35
        versionCode = 5
        versionName = "0.4.1"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildTypes { release { isMinifyEnabled = false } }
}
dependencies {
    compileOnly(files(android.sdkDirectory.resolve("platforms/android-35/optional/android.car.jar")))
    implementation("ai.pleos.playground:Vehicle:2.3.2")
    testImplementation("junit:junit:4.13.2")
}
