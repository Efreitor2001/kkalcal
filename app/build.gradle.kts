import java.util.Properties
import java.net.URI
import java.security.KeyFactory
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

plugins { id("com.android.application"); id("org.jetbrains.kotlin.plugin.compose") }
val signingFile = rootProject.file("signing.properties")
val signing = Properties().apply { if(signingFile.exists()) signingFile.inputStream().use { load(it) } }
val integrationFile = rootProject.file("integrations.properties")
val integrations = Properties().apply { if (integrationFile.exists()) integrationFile.inputStream().use { load(it) } }
val publicAdUnits = Properties().apply { rootProject.file("ad-units.properties").inputStream().use { load(it) } }
val publicGoogleClient = Properties().apply { rootProject.file("google-client.properties").inputStream().use { load(it) } }
// Only public configuration belongs here. Robokassa passwords and the receipt signing
// private key belong exclusively to the payment backend, never to an Android build.
val publicPayments = Properties().apply {
    rootProject.file("payment-config.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun paymentSetting(key: String, environment: String, fallback: String = ""): String =
    (providers.gradleProperty(key).orNull ?: providers.environmentVariable(environment).orNull
        ?: publicPayments.getProperty(key, fallback)).trim()
val paymentEnabled = paymentSetting("payment.enabled", "DIET_PAYMENT_ENABLED", "false")
val paymentApiOrigin = paymentSetting("payment.apiBaseUrl", "DIET_PAYMENT_API_BASE_URL").trimEnd('/')
val paymentPublicKey = paymentSetting("payment.publicKeyBase64", "DIET_PAYMENT_PUBLIC_KEY_BASE64")
require(paymentEnabled in setOf("true", "false")) { "payment.enabled must be true or false" }
if (paymentApiOrigin.isNotBlank()) {
    val uri = URI(paymentApiOrigin)
    require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.port == -1 &&
        uri.path.isNullOrEmpty() && uri.query == null && uri.fragment == null) {
        "payment.apiBaseUrl must be an HTTPS origin without a port, path, credentials, query or fragment"
    }
}
if (paymentPublicKey.isNotBlank()) {
    val key = KeyFactory.getInstance("RSA").generatePublic(
        X509EncodedKeySpec(Base64.getDecoder().decode(paymentPublicKey)))
    require((key as RSAPublicKey).modulus.bitLength() in 2048..8192) {
        "payment.publicKeyBase64 must be a 2048-bit or stronger X509 SPKI RSA public key"
    }
}
require(paymentEnabled != "true" || (paymentApiOrigin.isNotBlank() && paymentPublicKey.isNotBlank())) {
    "Enabled payments require payment.apiBaseUrl and payment.publicKeyBase64"
}
fun integration(key: String): String = integrations.getProperty(key, publicGoogleClient.getProperty(key, "")).trim()
val nativePlacements = listOf("day", "products", "diary", "stats", "more", "achievements", "cloud")
val storeNames = linkedMapOf("googlePlay" to "Google Play", "rustore" to "RuStore", "huawei" to "Huawei AppGallery")
val storeAdPrefixes = mapOf("googlePlay" to "20211635", "rustore" to "20211679", "huawei" to "20207601")
val adSettings = nativePlacements.map { "native.$it" } + "appOpenId"
val allowedAdKeys = storeNames.keys.flatMap { store -> adSettings.map { "yandex.$store.$it" } }.toSet()
val unsupportedAdKeys = (publicAdUnits.stringPropertyNames() + integrations.stringPropertyNames())
    .filter { it.startsWith("yandex.") && it !in allowedAdKeys }.sorted()
require(unsupportedAdKeys.isEmpty()) {
    "Unsupported or legacy ad settings: ${unsupportedAdKeys.joinToString()}. Use yandex.<googlePlay|rustore|huawei>.native.<placement> or yandex.<store>.appOpenId."
}
fun storeAdUnit(store: String, setting: String): String {
    val key = "yandex.$store.$setting"
    val value = (integrations.getProperty(key) ?: publicAdUnits.getProperty(key)).orEmpty().trim()
    require(Regex("R-M-[1-9][0-9]*-[1-9][0-9]*").matches(value)) { "Missing, blank or malformed ad unit for $key" }
    require(value.startsWith("R-M-${storeAdPrefixes.getValue(store)}-")) { "Ad unit for $key belongs to another store" }
    val slot = value.substringAfterLast('-').toIntOrNull()
    require(slot != null && slot > 0) { "Invalid ad unit slot for $key" }
    require(!setting.startsWith("native.") || slot !in 8..10) { "Native slots 8..10 are reserved and cannot be used by $key" }
    return value
}
val storeAdUnits = storeNames.keys.associateWith { store ->
    adSettings.associateWith { storeAdUnit(store, it) }.also { units ->
        require(units.values.toSet().size == units.size) { "Duplicate ad units configured for $store" }
    }
}
val demoSetting = integrations.getProperty("ads.debugDemo", "false").trim().lowercase()
require(demoSetting in setOf("true", "false")) { "ads.debugDemo must be true or false" }
val debugDemos = demoSetting == "true"
fun quoted(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
android {
    namespace = "ru.dietdiary.offline"
    compileSdk = 37
    defaultConfig {
        applicationId = "ru.dietdiary.offline"
        minSdk = 26
        targetSdk = 37
        versionCode = 11
        versionName = "1.3.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        manifestPlaceholders["appAuthRedirectScheme"] = "ru.dietdiary.offline"
        buildConfigField("String", "GOOGLE_CLIENT_ID", quoted(integration("google.clientId")))
        buildConfigField("boolean", "PAYMENT_ENABLED", paymentEnabled)
        buildConfigField("String", "PAYMENT_API_BASE_URL", quoted(paymentApiOrigin))
        buildConfigField("String", "PAYMENT_PUBLIC_KEY_BASE64", quoted(paymentPublicKey))
    }
    // Keep every language installed so changing the phone language also works offline.
    bundle { language { enableSplit = false } }
    flavorDimensions += "store"
    productFlavors {
        storeNames.forEach { (store, displayName) ->
            create(store) {
                dimension = "store"
                buildConfigField("String", "STORE_ID", quoted(store))
                buildConfigField("String", "STORE_NAME", quoted(displayName))
                val units = storeAdUnits.getValue(store)
                nativePlacements.forEach { placement ->
                    buildConfigField("String", "YANDEX_NATIVE_${placement.uppercase()}_ID", quoted(units.getValue("native.$placement")))
                }
                buildConfigField("String", "YANDEX_APP_OPEN_ID", quoted(units.getValue("appOpenId")))
            }
        }
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
            // Build-type fields override every store flavor's production fields.
            nativePlacements.forEach { placement ->
                buildConfigField("String", "YANDEX_NATIVE_${placement.uppercase()}_ID", quoted(if(debugDemos) "demo-native-app-yandex" else ""))
            }
            buildConfigField("String", "YANDEX_APP_OPEN_ID", quoted(if(debugDemos) "demo-appopenad-yandex" else ""))
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if(signingFile.exists()) signingConfig=signingConfigs.getByName("localRelease")
        }
    }
}
dependencies {
    implementation(project(":robokassa-sdk"))
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
