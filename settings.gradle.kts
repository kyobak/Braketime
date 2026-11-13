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
            url = uri("https://nexus-playground.pleos.ai/repository/maven-releases/")
            // 다른 라이브러리 조회가 이 저장소로 새지 않게 그룹을 묶는다
            content { includeGroupByRegex("""ai\.pleos(\..+)?""") }
        }
    }
}
rootProject.name = "Braketime"
include(":core", ":vehicle", ":companion", ":server")
