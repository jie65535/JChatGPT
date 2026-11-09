package top.jie65535.mirai.media

import top.jie65535.mirai.data.ImageAssetStore
import java.io.File

/** Markdown 图片仍走原有发送工具，只在此解析受管素材或旧 memeDir 中的本地文件。 */
internal object ImageMessageResolver {
    fun localFile(reference: String, store: ImageAssetStore?, legacyDirectory: String): File? {
        if (reference.startsWith("asset:")) {
            val source = ImageReferences.parse(reference) { null } as ImageSource.Asset
            return requireNotNull(store) { "素材库不可用" }.file(source.id)
        }
        require(!reference.startsWith("file:", ignoreCase = true)) { "本地图片请使用素材库引用" }
        val candidate = File(reference)
        if (!candidate.isAbsolute) return null
        require(legacyDirectory.isNotBlank()) { "本地图片不在配置的素材目录中" }
        val base = File(legacyDirectory).canonicalFile.toPath()
        val resolved = candidate.canonicalFile
        require(resolved.toPath().startsWith(base) && resolved.isFile) { "本地图片不存在或超出配置的素材目录" }
        require(resolved.length() <= ImageBinary.MAX_BYTES) { "本地图片超过 50 MiB" }
        require(resolved.extension.lowercase() in setOf("png", "jpg", "jpeg", "webp", "gif")) { "不支持的本地图片格式" }
        return resolved
    }
}
