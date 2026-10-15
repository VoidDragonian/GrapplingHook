plugins {
    java
}

group = "dev.grapplinghook"
version = "1.1.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/") {
        name = "papermc"
    }
}

dependencies {
    // Paper 26.x API (the only dependency). Provided by the server at runtime.
    compileOnly("io.papermc.paper:paper-api:26.3.build.+")
}

java {
    // Paper 26.1+ requires Java 25
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks {
    compileJava {
        options.encoding = "UTF-8"
        options.release.set(25)
    }
    processResources {
        filteringCharset = "UTF-8"
        filesMatching("plugin.yml") {
            expand("version" to project.version)
        }
    }
    jar {
        archiveBaseName.set("GrapplingHook")
    }
}
