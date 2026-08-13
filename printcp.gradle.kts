// Temporary helper: applied by `-I` init script to dump app compile classpath
allprojects {
    if (name == "app") {
        afterEvaluate {
            configurations.findByName("debugCompileClasspath")?.let { cfg ->
                cfg.resolve().forEach { f -> println("CP-JAR: " + f.absolutePath) }
            }
        }
    }
}
