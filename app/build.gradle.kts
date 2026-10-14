import java.util.Properties

plugins { id("com.android.application") }

val bulkTextLocalProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun bulkTextConfig(name: String, fallback: String): String =
    providers.gradleProperty(name).orNull
        ?: providers.environmentVariable(name).orNull
        ?: bulkTextLocalProperties.getProperty(name)
        ?: fallback

android {
    namespace = "com.bulktext.gateway"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.bulktext.gateway.prototype"
        minSdk = 26
        targetSdk = 35
        versionCode = 22
        versionName = "0.18.8"

        val supabaseUrl = bulkTextConfig("BULKTEXT_SUPABASE_URL", "https://placeholder.supabase.co")
        val supabaseKey = bulkTextConfig("BULKTEXT_SUPABASE_ANON_KEY", "placeholder-key")
        buildConfigField("String", "SUPABASE_URL", "\"$supabaseUrl\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"$supabaseKey\"")
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
    buildFeatures {
        buildConfig = true
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildTypes { release { isMinifyEnabled = false } }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16")
    // Android's org.json classes are mocked in local JVM tests; use the real
    // reference implementation for RPC contract/parser unit tests.
    testImplementation("org.json:json:20240303")
}
