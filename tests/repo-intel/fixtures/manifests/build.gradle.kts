plugins {
    `java-library`
}

group = "com.acme"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    api("io.ktor:ktor-client-core:2.3.12")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    // version-looking name trap reused as coordinate-style string
    implementation("com.acme:widget-2.0.0-rc1:0.9.1")
    testImplementation("io.kotest:kotest-runner-junit5:5.9.1")
    testImplementation("io.mockk:mockk:1.13.11")
}
