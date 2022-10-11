plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(gradleApi())
    implementation(gradleKotlinDsl())

    implementation(kotlin("gradle-plugin", "1.7.20"))
    implementation("org.jetbrains.dokka:dokka-gradle-plugin:1.7.20")
    implementation("ru.vyarus:gradle-github-info-plugin:1.3.0")
}

gradlePlugin {
    plugins {
        register("kotlin-maven-central-publish") {
            id = "kotlin-maven-central-publish"
            implementationClass = "org.hildan.krossbow.gradle.plugins.KotlinMavenCentralPublishPlugin"
        }
    }
}
