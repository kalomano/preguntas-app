plugins {
    id("com.android.application")
}

android {
    namespace = "com.pablito.quizunlock"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.pablito.quizunlock"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "1.1"
    }

    signingConfigs {
        create("release") {
            val storeFilePath = System.getenv("QUIZ_KEYSTORE_PATH")
            if (!storeFilePath.isNullOrBlank()) {
                storeFile = file(storeFilePath)
                storePassword = System.getenv("QUIZ_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("QUIZ_KEY_ALIAS")
                keyPassword = System.getenv("QUIZ_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
