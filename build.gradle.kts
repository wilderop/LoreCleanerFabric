plugins {
    id("fabric-loom") version "1.17.12"
}

base {
    archivesName.set("LoreCleaner")
}

version = "1.1.1"
group = "com.wilder0p"

loom {
    enableModProvidedJavadoc.set(false)
    decompilers { clear() }
}

tasks.named<net.fabricmc.loom.task.RemapJarTask>("remapJar") {
    targetNamespace.set("named")
}

repositories {
    mavenCentral()
}

dependencies {
    minecraft("com.mojang:minecraft:26.2")
    mappings("net.fabricmc:yarn:1.21.11+build.6:v2")
    modImplementation("net.fabricmc:fabric-loader:0.19.5")
    modImplementation("net.fabricmc.fabric-api:fabric-api:0.159.0+26.2")
    implementation("redis.clients:jedis:4.4.6")
    include("redis.clients:jedis:4.4.6")
    include("org.apache.commons:commons-pool2:2.11.1")
    implementation("com.google.code.gson:gson:2.11.0")
    include("com.google.code.gson:gson:2.11.0")
    implementation("org.mariadb.jdbc:mariadb-java-client:3.5.1")
    include("org.mariadb.jdbc:mariadb-java-client:3.5.1")
}

tasks.processResources {
    filesMatching("fabric.mod.json") {
        expand("version" to project.version)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}
