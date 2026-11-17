package com.invrestore

import java.io.File

/** 测试资源路径：兼容从仓库根目录或 app 模块目录启动 Gradle 的情况。 */
internal object TestPaths {

    fun sample(name: String): File = firstExisting(
        File("..", "samples/$name"),
        File("samples/$name"),
        File("/workspace/LocalInvertRestorer/samples/$name")
    ) ?: error("sample not found: $name")

    fun screens(): File {
        val dir = firstExisting(
            File("..", "docs/screens"),
            File("docs/screens"),
            File("/workspace/LocalInvertRestorer/docs/screens")
        ) ?: File("../docs/screens")
        dir.mkdirs()
        return dir
    }

    private fun firstExisting(vararg files: File): File? = files.firstOrNull { it.exists() }
}
