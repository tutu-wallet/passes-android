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

// Shrunk-build check: R8 runs over this module, its dependencies and a probe standing in for
// a consuming app, with only the kernel's own embedded rules. ShrunkBuildSignatureTest calls it.
val shrunkProbe: SourceSet by sourceSets.creating
detekt { source.setFrom("src/main/kotlin", "src/test/kotlin", "src/shrunkProbe/kotlin") }
val r8: Configuration by configurations.creating

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

val shrinkProbe by tasks.registering(JavaExec::class) {
    val program = files(tasks.jar, shrunkProbeJar, configurations.runtimeClasspath)
    val rules = layout.projectDirectory.file("src/shrunkProbe/probe.pro")
    // A directory, not a jar: R8 copies BouncyCastle's jar signature files, which a class
    // loader would reject against the rewritten classes.
    val shrunkDir = layout.buildDirectory.dir("shrunkProbe/classes")
    inputs.files(program).withNormalizer(ClasspathNormalizer::class)
    inputs.file(rules)
    outputs.dir(shrunkDir)
    classpath = r8
    mainClass = "com.android.tools.r8.R8"
    argumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "--classfile",
                "--release",
                "--lib",
                System.getProperty("java.home"),
                "--pg-conf",
                rules.asFile.path,
                "--output",
                shrunkDir.get().asFile.path,
            ) + program.files.map { it.path }
        },
    )
    // R8 warns once per duplicate META-INF resource; keep that out of the build log unless it fails.
    val log = layout.buildDirectory.file("shrunkProbe/r8.log")
    isIgnoreExitValue = true
    doFirst {
        shrunkDir.get().asFile.apply {
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
    filter { includeTestsMatching("*.ShrunkBuild*") }
    inputs.files(shrinkProbe).withNormalizer(ClasspathNormalizer::class)
    systemProperty("passes.shrunkClasses", shrinkProbe.get().outputs.files.singleFile.path)
}

tasks.test {
    filter { excludeTestsMatching("*.ShrunkBuild*") }
}

tasks.check {
    dependsOn(shrunkTest)
}
