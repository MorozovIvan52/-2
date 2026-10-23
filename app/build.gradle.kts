plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "ru.ivan.jarvis"
    compileSdk = 35

    defaultConfig {
        applicationId = "ru.ivan.jarvis"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "6.0"
        // Ключи Яндекса по умолчанию: подставляются только при сборке приватного репозитория из секретов CI.
        // В коде их нет; без секретов поля пустые и ключ вводится в «Настройках».
        buildConfigField("String", "YANDEX_API_KEY", "\"${System.getenv("YANDEX_API_KEY").orEmpty()}\"")
        buildConfigField("String", "YANDEX_FOLDER_ID", "\"${System.getenv("YANDEX_FOLDER_ID").orEmpty()}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    testImplementation("junit:junit:4.13.2")
}
