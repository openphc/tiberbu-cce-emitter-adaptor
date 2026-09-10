plugins {
    java
    id("org.springframework.boot") version "3.4.1"
    id("io.spring.dependency-management") version "1.1.7"
    id("jacoco")
    id("org.sonarqube") version "6.3.1.5724"
}

group = "org.openphc.tiberbu.cce"
version = "1.0.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

configurations {
    compileOnly {
        extendsFrom(configurations.annotationProcessor.get())
    }
}

repositories {
    mavenCentral()
}

val hapiFhirVersion = "7.4.0"
val wiremockVersion = "3.9.2"

dependencies {
    // Spring Boot starters
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    // Spring Retry — Collector forwarding backoff (E9)
    implementation("org.springframework.retry:spring-retry")
    implementation("org.springframework:spring-aspects")

    // HAPI FHIR — R4 parsing of bundle entries
    implementation("ca.uhn.hapi.fhir:hapi-fhir-base:$hapiFhirVersion")
    implementation("ca.uhn.hapi.fhir:hapi-fhir-structures-r4:$hapiFhirVersion")

    // Observability
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("net.logstash.logback:logstash-logback-encoder:7.4")

    // Lombok
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")

    // Testing
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.wiremock:wiremock-standalone:$wiremockVersion")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveBaseName = "tiberbu-cce-emitter-adaptor"
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required = true    // the format Sonar ingests
        html.required = true   // for local browsing
    }
    // The @SpringBootApplication entry point's main() is never invoked by a
    // @SpringBootTest run, and CollectorResponse.ErrorPayload is a plain,
    // logic-free record — neither's coverage says anything about correctness.
    classDirectories.setFrom(files(classDirectories.files.map {
        fileTree(it) {
            exclude(
                "**/CceEmitterAdaptorApplication.class",
                "**/model/CollectorResponse\$ErrorPayload.class"
            )
        }
    }))
}

sonar {
    properties {
        property("sonar.organization", "openphc")
        property("sonar.projectKey", "openphc_tiberbu-cce-emitter-adaptor")
        property("sonar.coverage.jacoco.xmlReportPaths",
            "${layout.buildDirectory.get()}/reports/jacoco/test/jacocoTestReport.xml")
    }
}

tasks.named("sonar") {
    dependsOn(tasks.jacocoTestReport)
}
