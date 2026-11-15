import org.gradle.api.publish.maven.MavenPom

/** what both artifacts say about themselves on Maven Central */
fun MavenPom.castwirePom(title: String, about: String) {
    name.set(title)
    description.set(about)
    inceptionYear.set("2026")
    url.set("https://github.com/biolend83/castwire")
    licenses {
        license {
            name.set("GNU Affero General Public License v3.0")
            url.set("https://www.gnu.org/licenses/agpl-3.0.txt")
            distribution.set("repo")
        }
        license {
            name.set("Castwire Commercial License")
            url.set("https://github.com/biolend83/castwire/blob/main/COMMERCIAL.md")
            distribution.set("repo")
        }
    }
    developers {
        developer {
            id.set("biolend83")
            name.set("biolend83")
            url.set("https://github.com/biolend83")
        }
    }
    scm {
        url.set("https://github.com/biolend83/castwire")
        connection.set("scm:git:git://github.com/biolend83/castwire.git")
        developerConnection.set("scm:git:ssh://git@github.com/biolend83/castwire.git")
    }
}
