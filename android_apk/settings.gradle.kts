pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("https://maven.yandex.ru/releases")
            credentials {
                username = System.getenv("YANDEX_MAVEN_USER") ?: ""
                password = System.getenv("YANDEX_MAVEN_PASSWORD") ?: ""
            }
        }
    }
}
rootProject.name = "BusWidget"
include(":app")
