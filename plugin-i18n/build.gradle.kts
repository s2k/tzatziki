plugins {
    id("org.jetbrains.kotlin.jvm") version "1.7.20"
    id("org.jetbrains.intellij") version "1.13.1"
}

group = "io.nimbly.translation"
version = "1.2.0"

val notes by extra {"""
       <b>Please kindly report any problem... and Rate &amp; Review this plugin !</b><br/>
       <br/>
       Change notes :
       <ul> 
         <li><b>1.2.0</b> Add support of camel case</li>
         <li><b>1.0.0</b> Display translation inlined in text</li>
         <li><b>1.0.0</b> Initial version</li>
       </ul>
      """

    /**
     * Supports of markdown
     * - https://github.com/commonmark/commonmark-java
     * - https://github.com/vsch/flexmark-java
     */
}

val versions by extra {
    mapOf(
        "intellij-version" to "IU-2022.3.1",
    )
}

intellij {
    version.set(versions["intellij-version"])
}

dependencies {
    implementation(project(":i18n"))
}

tasks {

    withType<JavaCompile> {
        sourceCompatibility = "11"
        targetCompatibility = "11"
    }
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions.jvmTarget = "11"
    }

    patchPluginXml {

        // Check build number here : https://www.jetbrains.com/idea/download/other.html
        sinceBuild.set("212")    // 2021.2.4
        untilBuild.set("241.*")

        changeNotes.set(notes)
    }

    buildSearchableOptions {
        enabled = false
    }

    jar {
        archiveBaseName.set("translation")
    }
    instrumentedJar {
        // exclude("META-INF/*") // Workaround for runPluginVerifier duplicate plugins...
    }

    runPluginVerifier {
        ideVersions.set(
            listOf("IU-2022.3.1"))
    }

    publishPlugin {
        val t = "perm:aG1heGltZQ==.OTItOTI5Nw==.zJ37fiKDe5cwNCAN4tib1IvEtIuOis"
        token.set(t)
    }
}

