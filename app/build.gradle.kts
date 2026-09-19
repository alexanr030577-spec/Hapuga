plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
 namespace="ru.hapuga.app"; compileSdk=35
 defaultConfig { applicationId="ru.hapuga.app"; minSdk=26; targetSdk=35; versionCode=14; versionName="0.14-viewfinder-lock" }
 compileOptions { sourceCompatibility=JavaVersion.VERSION_17; targetCompatibility=JavaVersion.VERSION_17 }
 kotlinOptions { jvmTarget="17" }
}
dependencies { implementation("androidx.core:core-ktx:1.15.0"); implementation("androidx.appcompat:appcompat:1.7.0") }