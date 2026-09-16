plugins {
	kotlin("jvm") version "2.3.21"
}

group = "io.github.sehako"
version = "0.0.1-SNAPSHOT"
description = "ledger"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(25)
	}
}

repositories {
	mavenCentral()
}

kotlin {
	compilerOptions {
		freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
	}
}
