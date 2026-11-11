plugins { java }

group = "xyz.foldedlettuce"
version = "1.6.1"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // Match this to the Velocity API your proxy runs (check papermc.io / the Velocity docs)
    compileOnly("com.velocitypowered:velocity-api:3.4.0-SNAPSHOT")
    annotationProcessor("com.velocitypowered:velocity-api:3.4.0-SNAPSHOT")
    compileOnly("net.luckperms:api:5.4")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release.set(21)
}

tasks.jar { archiveFileName.set("LettuceChat-${project.version}.jar") }
