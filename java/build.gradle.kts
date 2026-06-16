import com.google.protobuf.gradle.id

plugins {
    java
    id("org.springframework.boot") version "3.4.1"
    id("io.spring.dependency-management") version "1.1.7"
    id("com.google.protobuf") version "0.9.4"
    id("com.diffplug.spotless") version "6.25.0"
}

group = "dev.drim.relay"
version = "0.0.1"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

val grpcVersion = "1.68.1"
val protobufVersion = "3.25.5"
val testcontainersVersion = "1.20.4"

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-amqp")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.postgresql:postgresql")
    implementation("org.apache.kafka:kafka-clients:3.9.0")
    implementation("software.amazon.awssdk:s3:2.29.29")

    // gRPC presence service (internal companion-owned).
    implementation("io.grpc:grpc-netty-shaded:$grpcVersion")
    implementation("io.grpc:grpc-protobuf:$grpcVersion")
    implementation("io.grpc:grpc-stub:$grpcVersion")
    implementation("com.google.protobuf:protobuf-java:$protobufVersion")
    compileOnly("org.apache.tomcat:annotations-api:6.0.53")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.testcontainers:junit-jupiter:$testcontainersVersion")
    testImplementation("org.testcontainers:postgresql:$testcontainersVersion")
    testImplementation("org.testcontainers:rabbitmq:$testcontainersVersion")
    testImplementation("org.testcontainers:minio:$testcontainersVersion")
    testImplementation("org.awaitility:awaitility:4.2.2")
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:$protobufVersion"
    }
    plugins {
        id("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:$grpcVersion"
        }
    }
    generateProtoTasks {
        all().forEach {
            it.plugins {
                id("grpc")
            }
        }
    }
}

// Idiom-pass formatter (Task 1.2 stub, folded in at Task 3.2 per spotless.gradle.kts.stub).
// JUnit conventions: test classes *Test / *LyingTest; @DisplayName carries the scenario id.
spotless {
    java {
        target("src/**/*.java")
        targetExclude("build/**")
        googleJavaFormat()
        importOrder()
        removeUnusedImports()
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
    // Testcontainers spins real Docker deps; serialize so one Docker host hosts one suite.
    maxParallelForks = 1
    // Docker Desktop on macOS routes the default ~/.docker/run/docker.sock through a CLI gateway
    // that answers docker-java's /info probe with an empty Info stub + HTTP 400 (it only carries a
    // `com.docker.desktop.address` redirect label that docker-java does not follow). Point the test
    // JVM straight at the raw engine socket so docker-java reaches the real daemon. DOCKER_HOST in
    // the environment wins if already set.
    val dockerHost = System.getenv("DOCKER_HOST")
        ?: "unix://${System.getProperty("user.home")}/Library/Containers/com.docker.docker/Data/docker.raw.sock"
    environment("DOCKER_HOST", dockerHost)
    systemProperty("testcontainers.docker.host", dockerHost)

    // docker-java 3.4.0 (bundled with Testcontainers 1.20.4) negotiates API version 1.32 by default,
    // which Docker Engine 29 rejects ("client version 1.32 is too old. Minimum supported API version
    // is 1.40"). Pin a version inside Engine 29's [1.40, 1.54] window so the /info handshake succeeds.
    val dockerApiVersion = System.getenv("DOCKER_API_VERSION") ?: "1.43"
    environment("DOCKER_API_VERSION", dockerApiVersion)
    systemProperty("api.version", dockerApiVersion)

    // The raw engine socket lives on the macOS host and cannot be bind-mounted into a Linux
    // container, so Ryuk (the reaper) and any socket-mounting container must mount the in-VM path
    // /var/run/docker.sock instead. This override decouples the client socket (host raw.sock above)
    // from the socket Testcontainers bind-mounts into containers.
    val dockerSocketOverride = System.getenv("TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE") ?: "/var/run/docker.sock"
    environment("TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE", dockerSocketOverride)
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = false
    }
}
