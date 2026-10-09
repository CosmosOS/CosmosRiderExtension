plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.2.0"
    id("org.jetbrains.intellij.platform") version "2.13.1"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        rider(providers.gradleProperty("platformVersion"))
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
    }
    testImplementation("junit:junit:4.13.2")
}

intellijPlatform {
    pluginConfiguration {
        id = providers.gradleProperty("pluginId")
        name = providers.gradleProperty("pluginName")
        version = providers.gradleProperty("pluginVersion")
        description = """
            Development toolkit for building bare-metal OS kernels with Cosmos OS gen3.
            <br/>
            <ul>
                <li>Create new kernel projects from templates</li>
                <li>Build kernels to bootable ISO images</li>
                <li>Run kernels in QEMU emulator</li>
                <li>Debug with GDB</li>
                <li>Manage project properties and QEMU configuration</li>
                <li>Check and install required development tools</li>
            </ul>
        """.trimIndent()

        ideaVersion {
            sinceBuild = "253"
            untilBuild = provider { null }
        }

        vendor {
            name = "Cosmos OS"
            url = "https://github.com/AzulMusic/CosmosOS"
        }
    }
}

kotlin {
    jvmToolchain(21)
}

tasks {
    test {
        // DebuggerSmokeTest boots a real kernel under QEMU and gdb; it only
        // runs when COSMOS_SMOKE_ROOT points at a nativeaot-patcher checkout.
        System.getenv("COSMOS_SMOKE_ROOT")?.let {
            environment("COSMOS_SMOKE_ROOT", it)
            testLogging.showStandardStreams = true
        }
    }
    wrapper {
        gradleVersion = "9.0"
    }
}
