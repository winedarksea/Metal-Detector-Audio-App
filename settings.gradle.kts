pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
        // Kotlin/WASM plugin downloads three tools via detached Ivy configs; declare all here
        // so PREFER_SETTINGS mode can resolve them without project-level repo additions.
        ivy {
            name = "Node.js Distributions"
            url = uri("https://nodejs.org/dist")
            patternLayout {
                artifact("v[revision]/[artifact](-v[revision]-[classifier]).[ext]")
                ivy("v[revision]/ivy.xml")
                setM2compatible(true)
            }
            metadataSources { artifact() }
            content { includeModule("org.nodejs", "node") }
        }
        ivy {
            name = "Yarn Distributions"
            url = uri("https://github.com/yarnpkg/yarn/releases/download")
            patternLayout {
                artifact("v[revision]/[artifact]-v[revision].[ext]")
                ivy("v[revision]/ivy.xml")
            }
            metadataSources { artifact() }
            content { includeModule("com.yarnpkg", "yarn") }
        }
        ivy {
            name = "Binaryen Distributions"
            url = uri("https://github.com/WebAssembly/binaryen/releases/download")
            patternLayout {
                artifact("version_[revision]/[artifact]-version_[revision]-[classifier].[ext]")
            }
            metadataSources { artifact() }
            content { includeModule("com.github.webassembly", "binaryen") }
        }
    }
}

rootProject.name = "OnDeviceAudioApp"
include(":app")
include(":shared")
include(":desktopApp")
include(":webApp")
