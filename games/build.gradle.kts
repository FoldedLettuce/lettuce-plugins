plugins {
    java
}

group = "xyz.foldedlettuce"
version = "2.20.1" // keep in sync with src/main/resources/plugin.yml

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // Paper 26.x uses the new version scheme (<mcver>.build.<n>), this grabs the newest 26.3 API build
    compileOnly("io.papermc.paper:paper-api:26.3.build.+")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release.set(25) // Paper 26.x needs Java 25
}

tasks.jar {
    // jar comes out as build/libs/LettuceGames-<version>.jar
    archiveFileName.set("LettuceGames-${project.version}.jar")
}
