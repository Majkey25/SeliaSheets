package com.majkeylab.seliadocs.data

import java.io.File
import java.io.FileNotFoundException

internal class AssetStore(private val root: File) {
    fun prepare(): File {
        require((root.isDirectory || root.mkdirs()) && root.canWrite()) { "Asset storage unavailable" }
        return root
    }

    fun file(id: String): File {
        require(id.matches(Regex("[A-Za-z0-9._-]+")) && ".." !in id) { "Invalid asset ID" }
        val file = File(root, id)
        require(file.canonicalPath.startsWith(root.canonicalPath + File.separator))
        return file
    }

    fun requireFile(id: String): File =
        file(id).takeIf(File::isFile) ?: throw FileNotFoundException(id)

    fun files(): List<File> = root.listFiles().orEmpty().filter(File::isFile).sortedBy(File::getName)
}
