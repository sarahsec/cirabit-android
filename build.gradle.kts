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
    configurations.configureEach {
        resolutionStrategy.eachDependency {
            when {
                requested.group == "io.netty" -> useVersion("4.1.137.Final")
                requested.group == "com.google.guava" && requested.name == "guava" -> {
                    val current = requested.version.orEmpty()
                    useVersion(if (current.endsWith("-android")) "33.7.2-android" else "33.7.2-jre")
                }
                requested.group == "org.apache.commons" && requested.name == "commons-lang3" ->
                    useVersion("3.18.0")
                requested.group == "org.apache.httpcomponents" && requested.name == "httpclient" ->
                    useVersion("4.5.14")
                requested.group == "org.bouncycastle" &&
                    (requested.name.startsWith("bcprov") || requested.name.startsWith("bcpkix")) ->
                    useVersion("1.86")
                requested.group == "org.bitbucket.b_c" && requested.name == "jose4j" ->
                    useVersion("0.9.6")
                requested.group == "org.jdom" && requested.name == "jdom2" ->
                    useVersion("2.0.6.1")
            }
        }
    }

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
