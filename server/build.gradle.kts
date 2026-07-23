import java.util.Properties

plugins {
    base
}

val sdkDirectory: File = run {
    val localProperties = Properties().apply {
        rootProject.file("local.properties").takeIf(File::isFile)?.inputStream()?.use(::load)
    }
    val configured = providers.gradleProperty("sdk.dir").orNull
        ?: providers.environmentVariable("ANDROID_SDK_ROOT").orNull
        ?: providers.environmentVariable("ANDROID_HOME").orNull
        ?: localProperties.getProperty("sdk.dir")
        ?: if (System.getProperty("os.name").contains("win", ignoreCase = true)) {
            File(System.getProperty("user.home"), "AppData/Local/Android/Sdk").absolutePath
        } else {
            ""
        }
    file(configured).also {
        require(it.isDirectory) { "Android SDK not found. Configure sdk.dir, ANDROID_SDK_ROOT, or ANDROID_HOME." }
    }
}

val androidJar = sdkDirectory.resolve("platforms/android-36/android.jar").also {
    require(it.isFile) { "Android SDK platform 36 is required: ${it.absolutePath}" }
}

val buildToolsDirectory = sdkDirectory.resolve("build-tools")
    .listFiles(File::isDirectory)
    .orEmpty()
    .filter { File(it, if (System.getProperty("os.name").contains("win", true)) "d8.bat" else "d8").isFile }
    .maxByOrNull { directory ->
        directory.name.split('.', '-', '_').map { it.toIntOrNull() ?: 0 }
            .fold(0L) { value, part -> value * 1_000L + part }
    }
    ?: error("No Android build-tools installation containing d8 was found.")

val serverClasses = layout.buildDirectory.dir("classes/java/main")
val serverBytecodeJar = layout.buildDirectory.file("intermediates/inputbridge-server-classes.jar")
val serverArtifact = layout.buildDirectory.file("dist/inputbridge-server.jar")

val compileServer by tasks.registering(JavaCompile::class) {
    group = "build"
    description = "Compiles the minimal InputBridge Android-side server."
    source = fileTree("src/main/java") { include("**/*.java") }
    destinationDirectory.set(serverClasses)
    classpath = files()
    options.bootstrapClasspath = files(androidJar)
    options.encoding = "UTF-8"
    sourceCompatibility = JavaVersion.VERSION_1_8.toString()
    targetCompatibility = JavaVersion.VERSION_1_8.toString()
}

val packageServerClasses by tasks.registering(Jar::class) {
    group = "build"
    description = "Packages Java bytecode for D8 input."
    dependsOn(compileServer)
    archiveFileName.set("inputbridge-server-classes.jar")
    destinationDirectory.set(layout.buildDirectory.dir("intermediates"))
    from(serverClasses)
}

val buildServer by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds the DEX/JAR pushed to Android devices."
    dependsOn(packageServerClasses)
    inputs.file(serverBytecodeJar)
    outputs.file(serverArtifact)

    doFirst {
        serverArtifact.get().asFile.parentFile.mkdirs()
        serverArtifact.get().asFile.delete()
    }

    val d8 = buildToolsDirectory.resolve(if (System.getProperty("os.name").contains("win", true)) "d8.bat" else "d8")
    commandLine(
        d8.absolutePath,
        "--min-api", "26",
        "--lib", androidJar.absolutePath,
        "--output", serverArtifact.get().asFile.absolutePath,
        serverBytecodeJar.get().asFile.absolutePath,
    )
}

tasks.assemble {
    dependsOn(buildServer)
}

tasks.clean {
    delete(layout.buildDirectory)
}
