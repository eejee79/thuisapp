import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// URL en token komen uit local.properties (staat alleen op jouw computer)
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun localProp(key: String) = (localProps.getProperty(key) ?: "").trim()

android {
    namespace = "nl.ejvegt.hawerkplek"
    compileSdk = 35

    defaultConfig {
        applicationId = "nl.ejvegt.hawerkplek"
        minSdk = 26
        targetSdk = 35
        versionCode = 74
        versionName = "1.73"

        buildConfigField("String", "HA_URL", "\"${localProp("HA_URL")}\"")
        buildConfigField("String", "HA_URL_LOCAL", "\"${localProp("HA_URL_LOCAL")}\"")
        buildConfigField("String", "HA_TOKEN", "\"${localProp("HA_TOKEN")}\"")
        buildConfigField("String", "PVE_URL", "\"${localProp("PVE_URL")}\"")
        buildConfigField("String", "PVE_TOKEN_ID", "\"${localProp("PVE_TOKEN_ID")}\"")
        buildConfigField("String", "PVE_SECRET", "\"${localProp("PVE_SECRET")}\"")
        buildConfigField("String", "FRITZ_URL", "\"${localProp("FRITZ_URL")}\"")
        buildConfigField("String", "FRITZ_USER", "\"${localProp("FRITZ_USER")}\"")
        buildConfigField("String", "FRITZ_PASS", "\"${localProp("FRITZ_PASS")}\"")
        buildConfigField("String", "GH_UPDATE_TOKEN", "\"${localProp("GH_UPDATE_TOKEN")}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    // Vaste ondertekeningssleutel (GitHub-build): elke nieuwe APK installeert over de vorige heen.
    // De sleutel komt uit keystore.properties / omgevingsvariabelen en staat nooit in de code.
    val ksFile = rootProject.file("thuisapp.jks")
    val ksPass = System.getenv("KEYSTORE_PASSWORD") ?: localProp("KEYSTORE_PASSWORD")
    signingConfigs {
        if (ksFile.exists() && ksPass.isNotEmpty()) {
            create("thuisapp") {
                storeFile = ksFile
                storePassword = ksPass
                keyAlias = "thuisapp"
                keyPassword = ksPass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("thuisapp") ?: signingConfigs.getByName("debug")
        }
        debug {
            signingConfigs.findByName("thuisapp")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            // Dubbele bestanden uit de bibliotheken (BouncyCastle e.d.) niet meenemen
            excludes += setOf(
                "META-INF/versions/**", "META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA",
                "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/INDEX.LIST",
            )
        }
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

// Eén extra bibliotheek: smbj, om muziek van de netwerkmap (SMB) te lezen.
dependencies {
    implementation("com.hierynomus:smbj:0.13.0")
}
