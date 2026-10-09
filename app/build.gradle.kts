import java.util.Properties

plugins { id("com.android.application"); id("org.jetbrains.kotlin.plugin.compose") }
val signingFile = rootProject.file("signing.properties")
val signing = Properties().apply { if(signingFile.exists()) signingFile.inputStream().use { load(it) } }
val integrationFile = rootProject.file("integrations.properties")
val integrations = Properties().apply { if (integrationFile.exists()) integrationFile.inputStream().use { load(it) } }
val publicAdUnits = Properties().apply { rootProject.file("ad-units.properties").inputStream().use { load(it) } }
fun integration(key: String): String = integrations.getProperty(key, publicAdUnits.getProperty(key, "")).trim()
val nativePlacements = listOf("day", "products", "diary", "stats", "more", "achievements", "cloud")
fun quoted(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
android {
    namespace = "ru.dietdiary.offline"
    compileSdk = 37
    defaultConfig {
        applicationId = "ru.dietdiary.offline"
        minSdk = 26
        targetSdk = 37
        versionCode = 3
        versionName = "1.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        manifestPlaceholders["appAuthRedirectScheme"] = "ru.dietdiary.offline"
        buildConfigField("String", "GOOGLE_CLIENT_ID", quoted(integration("google.clientId")))
        nativePlacements.forEach { placement ->
            buildConfigField("String", "YANDEX_NATIVE_${placement.uppercase()}_ID", quoted(integration("yandex.native.$placement")))
        }
        buildConfigField("String", "YANDEX_APP_OPEN_ID", quoted(integration("yandex.appOpenId")))
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildFeatures { compose = true; buildConfig = true }
    signingConfigs {
        if(signingFile.exists()) create("localRelease") {
            storeFile = rootProject.file(signing.getProperty("storeFile"))
            storePassword = signing.getProperty("storePassword")
            keyAlias = signing.getProperty("keyAlias")
            keyPassword = signing.getProperty("keyPassword")
        }
    }
    buildTypes {
        debug {
            // Debug never sends requests to production units. Optional demos are local only.
            val demos = integrations.getProperty("ads.debugDemo", "false").toBoolean()
            nativePlacements.forEach { placement ->
                buildConfigField("String", "YANDEX_NATIVE_${placement.uppercase()}_ID", quoted(if(demos) "demo-native-app-yandex" else ""))
            }
            buildConfigField("String", "YANDEX_APP_OPEN_ID", quoted(if(demos) "demo-appopenad-yandex" else ""))
        }
        release { isMinifyEnabled = false; if(signingFile.exists()) signingConfig=signingConfigs.getByName("localRelease") }
    }
}
dependencies {
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.work:work-runtime-ktx:2.11.2")
    implementation("net.openid:appauth:0.11.1")
    implementation("com.yandex.android:mobileads:8.5.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.09.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
