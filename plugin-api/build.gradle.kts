plugins {
	`java-library`
	`maven-publish`
}


publishing {
	publications {
		create<MavenPublication>("plugin-api") {
			from(components["java"])
		}
	}

	repositories {
		maven {
			name = "github"
			url = uri("https://maven.pkg.github.com/asek-ll/ccemux-fork")
			credentials {
				username = "asek-ll"
				password = System.getenv("GITHUB_TOKEN")
			}
		}
	}
}




val ccVersion: String by extra

dependencies {
	api("cc.tweaked:cc-tweaked-1.20.1-core:$ccVersion")
	api("org.slf4j:slf4j-api:2.0.6")
	api("com.google.guava:guava:31.1-jre")
}
