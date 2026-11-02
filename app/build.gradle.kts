import com.android.build.api.variant.FilterConfiguration.FilterType.ABI
import com.android.build.api.variant.Variant
import com.android.build.api.variant.VariantOutput
import com.android.build.api.variant.impl.VariantOutputImpl
import com.android.build.gradle.tasks.MergeSourceSetFolders
import com.github.megatronking.stringfog.plugin.StringFogExtension
import com.github.megatronking.stringfog.plugin.StringFogMode
import com.github.megatronking.stringfog.plugin.kg.RandomKeyGenerator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
apply(plugin = "stringfog")

val appPackageName = "net.kdt.pojavlaunch.firefly"
val currentVersion = "snowdrop"

var localProperty: Properties? = null
if (file("${rootDir}/local.properties").exists()) {
    localProperty = Properties()
    file("${rootDir}/local.properties").inputStream().use { localProperty?.load(it) }
}

val pwd = System.getenv("VERA_KEYSTORE_PASSWORD") ?: localProperty?.getProperty("pwd")
val baseVersionCode = 100000000 + (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: localProperty?.getProperty("BASE_VERSION_CODE")?.toIntOrNull() ?: 0)
val curseForgeApiKey = System.getenv("CURSEFORGE_API_KEY") ?: localProperty?.getProperty("CURSEFORGE_API_KEY")

configure<StringFogExtension> {
    implementation = "com.github.megatronking.stringfog.xor.StringFogImpl"
    fogPackages = arrayOf(appPackageName)
    kg = RandomKeyGenerator()
    mode = StringFogMode.bytes
}

fun currentDate(): String {
    val dateFormat = SimpleDateFormat("yyyyMMdd")
    return dateFormat.format(Date())
}

fun gitOutput(vararg args: String): String {
    return try {
        providers.exec {
            commandLine(*args)
            isIgnoreExitValue = true
        }.standardOutput.asText.get().trim()
    } catch (_: Exception) {
        ""
    }
}

fun versionName(): String {
    var tag = gitOutput("git", "describe", "--tags")
    if (tag.isEmpty()) {
        tag = gitOutput("git", "describe", "--always", "--tags")
    }

    val tagString: String = if (tag.isNotEmpty()) {
        "${currentVersion}-${tag}"
    } else {
        "${currentVersion}-${currentDate()}"
    }

    val branch = gitOutput("git", "branch", "--show-current")
    val result = tagString.trim().replace("-g", "-")
    return if (branch.isNotEmpty()) "$result-$branch" else result
}

fun processOutput(variant: Variant, output: VariantOutput) {
    if (output !is VariantOutputImpl) return

    setOutputFileName(variant, output)
    configureAssetsTask(variant)
}

fun setOutputFileName(variant: Variant, output: VariantOutputImpl) {
    val abi = output.getFilter(ABI)?.identifier ?: "all"
    val baseVersionName = currentDate()
    val buildTypeSuffix = if (variant.buildType == "release") baseVersionName else "Debug-${baseVersionName}"
    val baseName = "Pojav-Glow-Worm-${buildTypeSuffix}"

    output.outputFileName = "${baseName}-${abi}.apk"
}

fun configureAssetsTask(variant: Variant) {
    val variantName = variant.name.replaceFirstChar { it.uppercaseChar() }

    afterEvaluate {
        val task = tasks.named("merge${variantName}Assets").get() as MergeSourceSetFolders
        task.doLast {
            cleanRuntimeAssets(task.outputDir.get().asFile)
        }
    }
}

fun cleanRuntimeAssets(assetsDir: File) {
    val arch = System.getProperty("arch", "all")
    if (arch == "all") return

    val jreList = listOf("jre-8", "jre-17", "jre-21", "jre-25")

    jreList.forEach { jre ->
        val runtimeDir = File("${assetsDir}/components/$jre")
        cleanRuntimeDirectory(runtimeDir, arch)
    }
}

fun cleanRuntimeDirectory(runtimeDir: File, arch: String) {
    if (!runtimeDir.exists() || !runtimeDir.isDirectory) {
        println("Directory does not exist or is not a directory: $runtimeDir")
        return
    }

    val files = runtimeDir.listFiles() ?: emptyArray()
    if (files.isEmpty()) {
        println("No files found in directory: $runtimeDir")
        return
    }

    files.forEach { file ->
        if (shouldDeleteJreFile(file, arch)) {
            println("delete:${file} : ${file.delete()}")
        }
    }
}

fun shouldDeleteJreFile(file: File, arch: String): Boolean {
    return file.name != "version" &&
            !file.name.contains("universal") &&
            file.name != "bin-${arch}.tar.xz"
}

android {
    namespace = appPackageName

    compileSdk = 37

    lint {
        abortOnError = false
    }

    signingConfigs {
        create("releaseBuild") {
            storeFile = file("key-store.jks")
            storePassword = pwd
            keyAlias = "key0"
            keyPassword = pwd
        }
        create("customDebug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    defaultConfig {
        applicationId = appPackageName
        minSdk = 28
        targetSdk = 37
        versionCode = 9999999
        versionName = versionName()
        val arch = System.getProperty("arch", "all")
        if (arch == "all") {
            ndk {
                abiFilters.addAll(listOf("armeabi-v7a", "arm64-v8a"))
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("customDebug")
            resValue("string", "application_package", "${appPackageName}.debug")
            resValue("string", "storageProviderAuthorities", "${appPackageName}.scoped.gamefolder.debug")
            resValue("string", "fileProviderAuthorities", "${appPackageName}.scoped.FileFileProvider.debug")
        }
        release {
            isDebuggable = false
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("releaseBuild")
            resValue("string", "application_package", appPackageName)
            resValue("string", "storageProviderAuthorities", "${appPackageName}.scoped.gamefolder")
            resValue("string", "fileProviderAuthorities", "${appPackageName}.scoped.FileFileProvider")
        }
        configureEach {
            resValue("string", "curseforge_api_key", curseForgeApiKey.toString())
            resValue("string", "base_version_code", baseVersionCode.toString())
        }
    }

    androidComponents {
        onVariants { variant ->
            variant.outputs.forEach { output ->
                processOutput(variant, output)
            }
        }
    }

    splits {
        val arch = System.getProperty("arch", "all")
        if (arch != "all") {
            abi {
                isEnable = true
                reset()
                when (arch) {
                    "arm" -> include("armeabi-v7a")
                    "arm64" -> include("arm64-v8a")
                    "x86" -> include("x86")
                    "x86_64" -> include("x86_64")
                }
            }
        }
    }

    ndkVersion = "25.2.9519653"

    packaging {
        jniLibs {
            useLegacyPackaging = true
            pickFirsts += listOf("**/libbytehook.so")
        }
    }

    externalNativeBuild {
        ndkBuild {
            path = file("src/main/jni/Android.mk")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        prefab = true
        buildConfig = true
        aidl = true
        compose = true
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.11.0")
    implementation("javax.annotation:javax.annotation-api:1.3.2")
    implementation("commons-codec:commons-codec:1.23.0")
    implementation("androidx.preference:preference:1.2.1")
    implementation("androidx.drawerlayout:drawerlayout:1.2.0")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("androidx.annotation:annotation:1.11.0")
    implementation("androidx.documentfile:documentfile:1.1.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.2")

    implementation("com.bytedance:bytehook:1.1.2")
    implementation("com.jakewharton:process-phoenix:3.0.0")
    implementation("com.github.duanhong169:checkerboarddrawable:1.0.2")
    implementation("com.github.PojavLauncherTeam:portrait-sdp:ed33e89cbc")
    implementation("com.github.PojavLauncherTeam:portrait-ssp:6c02fd739b")
    implementation("com.github.Mathias-Boulay:ExtendedView:1.0.0")
    implementation("com.github.Mathias-Boulay:android_gamepad_remapper:2.0.3")
    implementation("com.github.Mathias-Boulay:virtual-joystick-android:1.14")
    implementation("com.github.megatronking.stringfog:xor:5.0.0")
    implementation("com.squareup.okhttp3:okhttp:5.5.1")

    implementation("net.sourceforge.htmlcleaner:htmlcleaner:2.29")

    implementation("org.commonmark:commonmark:0.32.0")
    implementation("org.commonmark:commonmark-ext-gfm-tables:0.32.0")
    implementation("org.greenrobot:eventbus:3.3.1")
    implementation("org.tukaani:xz:1.12")

    implementation("top.fifthlight.touchcontroller:proxy-client-android:0.0.8")

    implementation("com.moandjiezana.toml:toml4j:0.7.2") {
        exclude(group = "com.google.code.gson", module = "gson")
    }

    implementation("io.noties.markwon:core:4.6.2") {
        exclude(group = "com.atlassian.commonmark", module = "commonmark")
    }
    implementation("io.noties.markwon:image:4.6.2") {
        exclude(group = "com.atlassian.commonmark", module = "commonmark")
    }
    implementation("io.noties.markwon:image-glide:4.6.2") {
        exclude(group = "com.atlassian.commonmark", module = "commonmark")
    }

    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:5.5.1")
}
