import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.nativeplatform.platform.internal.DefaultNativePlatform.getCurrentOperatingSystem

plugins {
    kotlin("jvm")
    id("com.gradle.plugin-publish")
    id("java-gradle-plugin")
    id("maven-publish")
    id("com.github.johnrengelman.shadow") version "7.0.0"
}

gradlePluginConfig {
    pluginId = "org.jetbrains.compose"
    implementationClass = "org.jetbrains.compose.ComposePlugin"
    pluginPortalTags = listOf("ui-framework")
}

mavenPublicationConfig {
    displayName = "JetBrains Compose Gradle Plugin"
    description = "JetBrains Compose Gradle plugin for easy configuration"
    artifactId = "compose-gradle-plugin"
}

val buildConfigDir
    get() = project.layout.buildDirectory.dir("generated/buildconfig")
val buildConfig = tasks.register("buildConfig", GenerateBuildConfig::class.java) {
    classFqName.set("org.jetbrains.compose.ComposeBuildConfig")
    generatedOutputDir.set(buildConfigDir)
    fieldsToGenerate.put("composeVersion", BuildProperties.composeVersion(project))
    fieldsToGenerate.put("isComposeWithWeb", BuildProperties.isComposeWithWeb(project))
    fieldsToGenerate.put("experimentalOELPublication", BuildProperties.experimentalOELPublication(project))
    fieldsToGenerate.put("oelAndroidXVersion", BuildProperties.oelAndroidXVersion(project).orEmpty())
}
tasks.named("compileKotlin") {
    dependsOn(buildConfig)
}
sourceSets.main.configure {
    java.srcDir(buildConfigDir)
}

val embeddedDependencies by configurations.creating

dependencies {
    // By default, Gradle resolves plugins only via Gradle Plugin Portal.
    // To avoid declaring an additional repo, all dependencies must:
    // 1. Either be provided by Gradle at runtime (e.g. gradleApi());
    // 2. Or be included and optionally relocated.
    // Use `embedded` helper to include a dependency.
    fun embedded(dep: Any) {
        compileOnly(dep)
        embeddedDependencies(dep)
    }

    compileOnly(gradleApi())
    compileOnly(localGroovy())
    compileOnly(kotlin("gradle-plugin-api"))
    compileOnly(kotlin("gradle-plugin"))

    testImplementation(gradleTestKit())
    testImplementation(platform("org.junit:junit-bom:5.7.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")

    // include relocated download task to avoid potential runtime conflicts
    embedded("de.undercouch:gradle-download-task:4.1.1")
    embedded(project(":preview-rpc"))
}

val shadow = tasks.named<ShadowJar>("shadowJar") {
    val fromPackage = "de.undercouch"
    val toPackage = "org.jetbrains.compose.$fromPackage"
    relocate(fromPackage, toPackage)
    archiveClassifier.set("shadow")
    configurations = listOf(embeddedDependencies)
    exclude("META-INF/gradle-plugins/de.undercouch.download.properties")
}

val jar = tasks.named<Jar>("jar") {
    dependsOn(shadow)
    from(zipTree(shadow.get().archiveFile))
    this.duplicatesStrategy = DuplicatesStrategy.INCLUDE
}

// __SUPPORTED_GRADLE_VERSIONS__
testGradleVersion("6.4")
testGradleVersion("6.8.3")
testGradleVersion("7.1")

val javaHomeForTests: String? = when {
    // __COMPOSE_NATIVE_DISTRIBUTIONS_MIN_JAVA_VERSION__
    JavaVersion.current() >= JavaVersion.VERSION_15 -> System.getProperty("java.home")
    else -> System.getenv("JDK_15")
         ?: System.getenv("JDK_FOR_GRADLE_TESTS")
}
val isWindows = getCurrentOperatingSystem().isWindows

val gradleTestsPattern = "org.jetbrains.compose.gradle.*"
tasks.test {
    filter {
        excludeTestsMatching(gradleTestsPattern)
    }
}
fun testGradleVersion(gradleVersion: String) {
    val taskProvider = tasks.register("testGradle-$gradleVersion", Test::class) {
        tasks.test.get().let { defaultTest ->
            classpath = defaultTest.classpath
        }
        systemProperty("gradle.version.for.tests", gradleVersion)
        filter {
            includeTestsMatching(gradleTestsPattern)
        }
    }
    tasks.named("check") {
        dependsOn(taskProvider)
    }
}

configureJUnit()

tasks.withType<Test>().configureEach {
    configureJavaForComposeTest()

    dependsOn(":publishToMavenLocal")
    systemProperty("compose.plugin.version", BuildProperties.deployVersion(project))
}

task("printAllAndroidxReplacements") {
    doLast { printAllAndroidxReplacements() }
}