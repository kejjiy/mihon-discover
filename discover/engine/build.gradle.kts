plugins {
    id("org.jetbrains.kotlin.jvm")
    alias(mihonx.plugins.spotless)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    testImplementation(libs.bundles.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
