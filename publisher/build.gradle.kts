plugins { kotlin("jvm") version "2.1.20";application }
repositories { mavenCentral() }
kotlin { jvmToolchain(17) }
application { mainClass.set("com.personal.maodaily.publisher.PublisherKt") }
dependencies {
 implementation("org.apache.pdfbox:pdfbox:2.0.27")
 implementation("xpp3:xpp3:1.1.4c")
 implementation("org.json:json:20240303")
 testImplementation("junit:junit:4.13.2")
}
tasks.jar {
 manifest { attributes["Main-Class"]="com.personal.maodaily.publisher.PublisherKt" }
 duplicatesStrategy=DuplicatesStrategy.EXCLUDE
 from(configurations.runtimeClasspath.get().map { if(it.isDirectory) it else zipTree(it) })
 exclude("META-INF/*.SF","META-INF/*.RSA","META-INF/*.DSA")
 archiveFileName.set("reading-publisher.jar")
}
