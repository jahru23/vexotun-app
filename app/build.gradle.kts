plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
}

android {
  namespace = "com.vexotun.app"
  compileSdk = 36
  defaultConfig {
    applicationId = "com.vexotun.app"
    minSdk = 24
    targetSdk = 35
    versionCode = 9
    versionName = "1.5.1"
  }

  signingConfigs {
    create("release") {
      val ksPath = System.getenv("VEXOTUN_KEYSTORE_PATH")
      if (!ksPath.isNullOrBlank() && file(ksPath).exists()) {
        storeFile = file(ksPath)
        storePassword = System.getenv("KEYSTORE_PASSWORD")
        keyAlias = System.getenv("KEY_ALIAS")
        keyPassword = System.getenv("KEY_PASSWORD")
      }
    }
  }

  splits {
    abi {
      isEnable = true
      reset()
      include("arm64-v8a", "armeabi-v7a")
      isUniversalApk = false
    }
  }

  buildTypes {
    release {
      isMinifyEnabled = true
      isShrinkResources = true
      val releaseSigning = signingConfigs.getByName("release")
      if (releaseSigning.storeFile != null) {
        signingConfig = releaseSigning
      }
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  buildFeatures {
    compose = true
    aidl = false
    buildConfig = false
    shaders = false
  }

  packaging {
    resources {
      excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
  }
}

kotlin {
  jvmToolchain(17)
}

dependencies {
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  androidTestImplementation(composeBom)

  // Core Android dependencies
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)

  // Arch Components
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)

  // Compose
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  // Tooling
  debugImplementation(libs.androidx.compose.ui.tooling)
  // Instrumented tests
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.test.manifest)

  // Local tests: jUnit, coroutines, Android runner
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)

  // Instrumented tests: jUnit rules and runners
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.espresso.core)

  // Tunnel Core AAR (built via gomobile bind)
  implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))

  // Icons, QR Scanner & JSON Serialization
  implementation("androidx.compose.material:material-icons-extended")
  implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
  implementation("com.journeyapps:zxing-android-embedded:4.3.0")
}
