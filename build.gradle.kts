buildscript {
    configurations.classpath {
        resolutionStrategy.eachDependency {
            when {
                requested.group == "org.jdom" && requested.name == "jdom2" -> {
                    useVersion("2.0.6.1")
                    because("Mitigate CVE-2021-33813 from AGP transitive dependency")
                }

                requested.group == "com.google.protobuf" &&
                    requested.name in setOf(
                        "protobuf-java",
                        "protobuf-javalite",
                        "protobuf-kotlin",
                        "protobuf-kotlin-lite",
                    ) -> {
                    useVersion("3.25.5")
                    because("Mitigate CVE-2024-7254 in AGP/UTP transitive dependencies")
                }

                requested.group == "io.netty" &&
                    requested.name in setOf(
                        "netty-buffer",
                        "netty-codec",
                        "netty-codec-http",
                        "netty-codec-http2",
                        "netty-codec-socks",
                        "netty-common",
                        "netty-handler",
                        "netty-handler-proxy",
                        "netty-resolver",
                        "netty-transport",
                        "netty-transport-native-unix-common",
                    ) -> {
                    useVersion("4.1.118.Final")
                    because("Mitigate CVE-2025-24970 in AGP/UTP transitive dependencies")
                }

                requested.group == "org.bitbucket.b_c" && requested.name == "jose4j" -> {
                    useVersion("0.9.6")
                    because("Mitigate CVE-2024-29371 in AGP transitive dependency")
                }

                requested.group == "org.apache.commons" && requested.name == "commons-compress" -> {
                    useVersion("1.26.0")
                    because("Mitigate CVE-2024-26308 in AGP transitive dependency")
                }
            }
        }
    }
}

import org.gradle.api.artifacts.dsl.LockMode

// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

val resolveIdeRuntimeClasspathCopyLocks = tasks.register("resolveIdeRuntimeClasspathCopyLocks") {
    group = "build setup"
    description = "Resolves Android Studio runtime classpath copies when refreshing lock state."
}

subprojects {
    dependencyLocking {
        lockAllConfigurations()
        lockMode.set(LockMode.STRICT)
    }

    pluginManager.withPlugin("com.android.application") {
        val resolveIdeRuntimeClasspathCopyLock = tasks.register("resolveIdeRuntimeClasspathCopyLock") {
            group = "build setup"
            description = "Resolves this module's Android Studio runtime classpath copies."
            notCompatibleWithConfigurationCache("Resolves copied configurations at execution time")
            doFirst {
                check(gradle.startParameter.isWriteDependencyLocks) {
                    "$path must be run with --write-locks"
                }
            }
            doLast {
                listOf("debugRuntimeClasspath", "releaseRuntimeClasspath").forEach { configurationName ->
                    configurations.getByName(configurationName).copy().resolve()
                }
            }
        }
        resolveIdeRuntimeClasspathCopyLocks.configure {
            dependsOn(resolveIdeRuntimeClasspathCopyLock)
        }
    }
}

tasks.whenTaskAdded {
    if (name.contains("ArtProfile")) {
        enabled = false
    }
}

tasks.register("clientRewriteContractTest") {
    group = "verification"
    description = "Runs the complete compatibility gate for a from-scratch client rewrite."
    dependsOn(":app:testDebugUnitTest")
}
