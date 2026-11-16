import com.google.gms.googleservices.GoogleServicesPlugin.MissingGoogleServicesStrategy
import groovy.json.JsonSlurper
import java.security.MessageDigest

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.secrets)
  alias(libs.plugins.google.services)
}

android {
  namespace = "com.example"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.example"
    minSdk = 24
    targetSdk = 36
    versionCode = 1
    versionName = "1.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  // Do not remove or modify these signingConfigs. They are necessary for building, installing,
  // and updating Android apps in AI Studio.
  signingConfigs {
    create("release") {
      val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks"
      storeFile = file(keystorePath)
      storePassword = System.getenv("STORE_PASSWORD")
      keyAlias = "upload"
      keyPassword = System.getenv("KEY_PASSWORD")
    }
    create("debugConfig") {
      storeFile = file("${rootDir}/debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
  }

  buildTypes {
    release {
      isDebuggable = false
      isCrunchPngs = false
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.getByName("debugConfig")
    }
    debug {
      // Do not remove or modify this signingConfig assignment. It is necessary for Android apps in
      // AI Studio.
      signingConfig = signingConfigs.getByName("debugConfig")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
  dependenciesInfo {
    includeInApk = false
    includeInBundle = true
  }
}

// Configure the Secrets Gradle Plugin to use .env and .env.example files
// to match the convention used in Web projects.
secrets {
  propertiesFileName = ".env"
  defaultPropertiesFileName = ".env.example"
  ignoreList.add("FIREBASE_APPCHECK_DEBUG_TOKEN")
}

googleServices { missingGoogleServicesStrategy = MissingGoogleServicesStrategy.WARN }

// Some unused dependencies are commented out below instead of being removed.
// This makes it easy to add them back in the future if needed.
dependencies {
  implementation(platform(libs.androidx.compose.bom))
  // implementation(platform(libs.firebase.bom))
  // implementation(libs.accompanist.permissions)
  implementation(libs.androidx.activity.compose)
  // implementation(libs.androidx.camera.camera2)
  // implementation(libs.androidx.camera.core)
  // implementation(libs.androidx.camera.lifecycle)
  // implementation(libs.androidx.camera.view)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.media3.common)
  implementation(libs.androidx.media3.exoplayer)
  // implementation(libs.androidx.media3.session)
  // implementation(libs.androidx.navigation.compose)
  // implementation(libs.androidx.room.ktx)
  // implementation(libs.androidx.room.runtime)
  // implementation(libs.coil.compose)
  implementation(libs.converter.moshi)
  // implementation(libs.firebase.ai)
  // Uncomment to use Firestore:
  // implementation(libs.firebase.firestore)

  // Uncomment ALL FOUR of the following dependencies together to use Firebase Auth and Google
  // Sign-In via Credential Manager:
  // implementation(libs.firebase.auth)
  // implementation(libs.androidx.credentials)
  // implementation(libs.androidx.credentials.play.services)
  // implementation(libs.googleid)
  // implementation(libs.firebase.appcheck.recaptcha)
  // implementation(libs.firebase.appcheck.debug)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.logging.interceptor)
  implementation(libs.moshi.kotlin)
  implementation(libs.okhttp)
  // implementation(libs.play.services.location)
  implementation(libs.retrofit)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  // "ksp"(libs.androidx.room.compiler)
  "ksp"(libs.moshi.kotlin.codegen)
}

// Build-time SHA-256 integrity verification for bundled Quran data assets
tasks.register("verifyQuranAssetHashes") {
  group = "verification"
  description = "Verifies SHA-256 digests of all files in src/main/assets/quran against manifest.sha256.json"
  val quranAssetsDir = layout.projectDirectory.dir("src/main/assets/quran").asFile
  val manifestFile = layout.projectDirectory.file("src/main/assets/quran/manifest.sha256.json").asFile
  doLast {
    if (!manifestFile.exists()) {
      throw GradleException("Missing SHA-256 manifest: ${manifestFile.absolutePath}")
    }
    val json = JsonSlurper().parse(manifestFile) as Map<*, *>
    val entries = json["files"] as? List<*> ?: throw GradleException("Invalid manifest.sha256.json format")
    val expected = mutableMapOf<String, String>()
    for (item in entries) {
      val map = item as Map<*, *>
      expected[map["path"].toString()] = map["sha256"].toString().lowercase()
    }
    val digest = MessageDigest.getInstance("SHA-256")
    val actualFiles = quranAssetsDir.walkTopDown()
      .filter { it.isFile && it.name != "manifest.sha256.json" }
      .toList()
    val actualPaths = mutableSetOf<String>()
    for (f in actualFiles) {
      val relPath = quranAssetsDir.toPath().relativize(f.toPath()).toString().replace("\\", "/")
      actualPaths.add(relPath)
      val hash = digest.digest(f.readBytes()).joinToString("") { b -> "%02x".format(b) }
      digest.reset()
      val exp = expected[relPath]
        ?: throw GradleException("Unregistered Quran asset file: $relPath (actual sha256=$hash)")
      if (exp != hash) {
        throw GradleException("SHA-256 mismatch for $relPath: expected=$exp actual=$hash")
      }
    }
    for (expPath in expected.keys) {
      if (!actualPaths.contains(expPath)) {
        throw GradleException("Missing Quran asset file listed in manifest: $expPath")
      }
    }
    logger.lifecycle("Verified SHA-256 integrity for ${actualPaths.size} Quran asset files.")
  }
}

tasks.named("preBuild").configure {
  dependsOn("verifyQuranAssetHashes")
}
