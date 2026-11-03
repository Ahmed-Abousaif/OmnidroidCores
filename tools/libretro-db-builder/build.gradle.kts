plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation("org.xerial:sqlite-jdbc:3.49.1.0")
    implementation("com.google.code.gson:gson:2.11.0")
    testImplementation("junit:junit:4.13.2")
}

application {
    mainClass.set("com.omnidroid.tools.libretrodb.BuildLibretroDbKt")
}

tasks.named<JavaExec>("run") {
    args(
        "--cores-root",
        rootProject.file("omnidroid-cores").absolutePath,
        "--app-root",
        rootProject.projectDir.absolutePath,
        "--cache",
        layout.projectDirectory.dir("cache").asFile.absolutePath,
    )
}
