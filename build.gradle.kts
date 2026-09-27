plugins {
    java
}

group = "net.takensmp"
version = "0.4.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // Built against the stable 1.21 API so the same jar keeps working on newer Paper (26.x).
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    // Both are already on the server at runtime; only needed to compile.
    compileOnly("net.luckperms:api:5.4")
    compileOnly("org.apache.logging.log4j:log4j-core:2.24.1")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release.set(21)
}

tasks.processResources {
    val props = mapOf("version" to version)
    inputs.properties(props)
    filesMatching("plugin.yml") { expand(props) }
}

tasks.jar {
    archiveFileName.set("TakenBridge.jar")
}
