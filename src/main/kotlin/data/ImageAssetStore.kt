package top.jie65535.mirai.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import top.jie65535.mirai.media.ImageBinary
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

@Serializable
internal data class ImageAsset(
    val id: String,
    val format: String,
    val name: String,
    val description: String = "",
    val tags: List<String> = emptyList(),
    val collected: Boolean = false,
    val source: String,
    val createdAt: Long = System.currentTimeMillis(),
    val width: Int? = null,
    val height: Int? = null,
    val model: String = "",
    val prompt: String = "",
    val references: List<String> = emptyList(),
) {
    val reference: String get() = "asset:$id"
    val markdown: String get() = "![${name.replace(Regex("[\\[\\]\\r\\n]"), " ")}]($reference)"
}

/** 独立于聊天历史的全局文件素材库；元数据原子写入，取消收藏不删除原文件。 */
internal class ImageAssetStore(
    val root: File,
    private val report: (String, Throwable) -> Unit = { _, _ -> },
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }
    private val assets = linkedMapOf<String, ImageAsset>()
    private val metadata = File(root, "metadata")
    private val files = File(root, "files")

    init {
        check(metadata.mkdirs() || metadata.isDirectory) { "无法创建素材元数据目录" }
        check(files.mkdirs() || files.isDirectory) { "无法创建素材文件目录" }
        metadata.listFiles { file -> file.extension == "json" }?.sortedBy { it.name }?.forEach { file ->
            try {
                val asset = json.decodeFromString<ImageAsset>(file.readText(Charsets.UTF_8))
                require(ID_REGEX.matches(asset.id) && file.nameWithoutExtension == asset.id)
                require(asset.format in FORMATS)
                assets[asset.id] = asset
            } catch (cause: Exception) {
                // 保留损坏记录，不以空索引覆盖用户数据。
                report("无法读取图片素材记录 ${file.name}，原文件已保留", cause)
            }
        }
    }

    @Synchronized
    fun get(id: String): ImageAsset = assets[id] ?: throw IllegalArgumentException("素材编号不存在：$id")

    @Synchronized
    fun file(id: String): File {
        val asset = get(id)
        return File(files, "${asset.id}.${asset.format}").also {
            require(it.isFile) { "素材文件缺失：$id" }
            require(it.canonicalFile.toPath().startsWith(files.canonicalFile.toPath())) { "素材文件超出库目录" }
        }
    }

    @Synchronized
    fun list(query: String = "", includeTemporary: Boolean = false): List<ImageAsset> = assets.values
        .filter { includeTemporary || it.collected }
        .filter { query.isBlank() || (it.name + " " + it.description + " " + it.tags.joinToString(" "))
            .contains(query.trim(), ignoreCase = true) }
        .sortedWith(compareByDescending<ImageAsset> { it.createdAt }.thenBy { it.id })

    @Synchronized
    fun save(
        image: ImageBinary,
        name: String,
        source: String,
        collected: Boolean = false,
        description: String = "",
        tags: List<String> = emptyList(),
        model: String = "",
        prompt: String = "",
        references: List<String> = emptyList(),
    ): ImageAsset {
        val id = "img_" + sha256(image.bytes)
        val previous = assets[id]
        if (previous != null) {
            if (!fileExists(previous)) atomicWrite(File(files, "$id.${previous.format}"), image.bytes)
            return if (collected) update(id, name, description, tags, true) else previous
        }
        // 已有同名记录无法读取时禁止覆盖它。
        require(!File(metadata, "$id.json").exists()) { "此图片已有无法读取的记录，请先由管理员检查" }
        val asset = ImageAsset(id, image.format, name.trim(), description.trim(), normalizeTags(tags), collected,
            source, width = image.width, height = image.height, model = model, prompt = prompt, references = references)
        validate(asset)
        atomicWrite(File(files, "$id.${image.format}"), image.bytes)
        persist(asset)
        return asset
    }

    @Synchronized
    fun update(
        id: String,
        name: String? = null,
        description: String? = null,
        tags: List<String>? = null,
        collected: Boolean? = null,
    ): ImageAsset {
        val previous = get(id)
        val next = previous.copy(name = name?.trim() ?: previous.name,
            description = description?.trim() ?: previous.description,
            tags = tags?.let(::normalizeTags) ?: previous.tags, collected = collected ?: previous.collected)
        validate(next)
        persist(next)
        return next
    }

    @Synchronized
    fun importDirectory(directory: File): Int {
        require(directory.isDirectory) { "配置的 memeDir 不是有效目录" }
        val base = directory.canonicalFile.toPath()
        var imported = 0
        directory.walkTopDown().maxDepth(8).onEnter { it.canonicalFile.toPath().startsWith(base) }.forEach { file ->
            if (!file.isFile || file.extension.lowercase() !in setOf("png", "jpg", "jpeg", "gif", "webp")) return@forEach
            try {
                require(file.canonicalFile.toPath().startsWith(base)) { "跳过目录外的链接文件" }
                require(file.length() in 1..ImageBinary.MAX_BYTES.toLong()) { "图片文件为空或过大" }
                val image = ImageBinary.inspect(file.readBytes())
                if (assets.containsKey("img_" + sha256(image.bytes))) return@forEach
                save(image, file.nameWithoutExtension.take(100).ifBlank { "本地图片" }, "local", collected = true)
                imported++
            } catch (cause: Exception) {
                report("导入本地图片失败：${file.name}", cause)
            }
        }
        return imported
    }

    private fun fileExists(asset: ImageAsset) = File(files, "${asset.id}.${asset.format}").isFile

    private fun persist(asset: ImageAsset) {
        atomicWrite(File(metadata, "${asset.id}.json"), json.encodeToString(asset).toByteArray(Charsets.UTF_8))
        assets[asset.id] = asset
    }

    private fun validate(asset: ImageAsset) {
        require(asset.name.isNotBlank() && asset.name.length <= 100) { "素材名称须为 1~100 字符" }
        require(asset.description.length <= 1000) { "素材描述最多 1000 字符" }
        require(asset.tags.size <= 20 && asset.tags.all { it.length <= 40 }) { "最多 20 个标签，每个最多 40 字符" }
    }

    companion object {
        private val ID_REGEX = Regex("img_[a-f0-9]{64}")
        private val FORMATS = setOf("png", "jpg", "gif", "webp")
        private fun normalizeTags(tags: List<String>) = tags.map(String::trim).filter(String::isNotBlank).distinct()
        internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it) }

        internal fun atomicWrite(file: File, bytes: ByteArray) {
            val parent = file.parentFile
            check(parent.mkdirs() || parent.isDirectory)
            val temporary = Files.createTempFile(parent.toPath(), ".image-", ".tmp")
            try {
                Files.write(temporary, bytes)
                try {
                    Files.move(temporary, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(temporary, file.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                Files.deleteIfExists(temporary)
            }
        }
    }
}
