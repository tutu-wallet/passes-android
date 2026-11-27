plugins {
    alias(libs.plugins.walt.passes.kotlin.library)
    alias(libs.plugins.walt.passes.quality)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.bouncycastle.bcprov)
    implementation(libs.bouncycastle.bcpkix)
    implementation(libs.zxing.core)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
}

// Shrunk-build check: R8 (classfile mode, the JDK as library) runs over this module, its
// dependencies and a probe, adding no keep rule but the probe's own. ShrunkBuildSignatureTest
// runs on the output, so it covers keep rules at run time and nothing specific to Android.
val shrunkProbe: SourceSet by sourceSets.creating
val r8: Configuration by configurations.creating { isCanBeConsumed = false }
val shrunkClasses = layout.buildDirectory.dir("shrunkProbe/classes")
val shrunkTestPattern = "*.ShrunkBuild*"

detekt { source.from("src/shrunkProbe/kotlin") }

kotlin.target.compilations.named("shrunkProbe") {
    associateWith(kotlin.target.compilations.getByName("main"))
}

dependencies {
    // R8 sits whole inside the builder jar, so none of AGP's other dependencies are needed.
    r8(libs.android.builder) { isTransitive = false }
}

val shrunkProbeJar by tasks.registering(Jar::class) {
    archiveClassifier = "shrunk-probe"
    from(shrunkProbe.output)
}

val r8ShrunkProbe by tasks.registering(JavaExec::class) {
    description = "Shrinks and renames this module, its dependencies and the probe with R8."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    val program = files(tasks.jar, shrunkProbeJar, configurations.runtimeClasspath)
    val rules = layout.projectDirectory.file("src/shrunkProbe/probe.pro")
    inputs.files(program).withNormalizer(ClasspathNormalizer::class)
    inputs.file(rules)
    // A directory, not a jar: R8 copies BouncyCastle's jar signature files, which a class
    // loader would reject against the rewritten classes.
    outputs.dir(shrunkClasses)
    classpath = r8
    mainClass = "com.android.tools.r8.R8"
    argumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "--classfile",
                "--release",
                "--lib",
                javaLauncher.get().metadata.installationPath.asFile.path,
                "--pg-conf",
                rules.asFile.path,
                "--output",
                shrunkClasses.get().asFile.path,
            ) + program.files.map { it.path }
        },
    )
    // R8 warns once per duplicate META-INF resource; keep that out of the build log unless it fails.
    val log = layout.buildDirectory.file("shrunkProbe/r8.log")
    isIgnoreExitValue = true
    doFirst {
        shrunkClasses.get().asFile.apply {
            deleteRecursively()
            mkdirs()
        }
        standardOutput = log.get().asFile.outputStream()
        errorOutput = standardOutput
    }
    doLast {
        standardOutput.close()
        if (executionResult.get().exitValue != 0) throw GradleException("R8 failed:\n" + log.get().asFile.readText())
    }
}

// Its own task: the R8 run takes about half a minute, too long to sit in front of `test`.
val shrunkTest by tasks.registering(Test::class) {
    description = "Runs signature verification inside the R8-shrunk copy of this module."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter { includeTestsMatching(shrunkTestPattern) }
    inputs.files(r8ShrunkProbe).withNormalizer(ClasspathNormalizer::class)
    // An argument provider, not systemProperty: the absolute path stays out of the cache key.
    jvmArgumentProviders.add(
        CommandLineArgumentProvider { listOf("-Dpasses.shrunkClasses=" + shrunkClasses.get().asFile.path) },
    )
}

tasks.test {
    filter { excludeTestsMatching(shrunkTestPattern) }
}

tasks.check {
    dependsOn(shrunkTest)
}
