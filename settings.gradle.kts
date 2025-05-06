rootProject.name = "CloudstreamPlugins"

fun File.eachDir(block: (File) -> Unit) {
    listFiles()?.filter { it.isDirectory }?.forEach { block(it) }
}

File(rootDir, ".").eachDir { dir ->
    if (File(dir, "build.gradle.kts").exists()) {
        include(":${dir.name}")
    }
}
