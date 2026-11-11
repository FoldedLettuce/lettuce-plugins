plugins { java }

group = "xyz.foldedlettuce"
version = "1.1.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // Match this to your Paper version (the server log prints "Implementing API version ...")
    compileOnly("io.papermc.paper:paper-api:26.3.build.211-stable")
    compileOnly("net.luckperms:api:5.4")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release.set(25)
}

tasks.jar { archiveFileName.set("LettuceCore-${project.version}.jar") }
