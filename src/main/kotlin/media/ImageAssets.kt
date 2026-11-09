package top.jie65535.mirai.media

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.jie65535.mirai.data.ImageAssetStore
import top.jie65535.mirai.tools.VisualImageResolver
import java.io.File
import java.util.Base64

/** 插件持有的素材库与原图下载器。 */
internal object ImageAssets {
    var store: ImageAssetStore? = null
        private set
    private var fetcher: VisualImageResolver? = null
    val downloader: VisualImageResolver
        @Synchronized get() = fetcher ?: VisualImageResolver().also { fetcher = it }

    fun init(dataFolder: File, report: (String, Throwable) -> Unit) {
        store = ImageAssetStore(File(dataFolder, "images"), report)
    }

    fun requireStore(): ImageAssetStore = store ?: error("图片素材库尚未初始化或初始化失败")

    fun prompt(): String = if (store == null) "图片素材库暂不可用。" else
        "所有群和私聊共用一个图片素材库。需要表情包或本地参考图时，先调用 imageLibrary(action=list) 按需分页列举，" +
            "不需要猜测关键词。返回的 asset:编号可用于识图、绘图和 Markdown 图片语法发送。" +
            "可根据对话自行收藏聊天图片或生成结果、整理名称描述标签。修改画面使用 imageAgent。"

    suspend fun read(source: ImageSource): ImageBinary = when (source) {
        is ImageSource.Chat -> if (source.isInline) withContext(Dispatchers.IO) {
            // 先检查编码长度，避免对过大的内嵌数据分配解码缓冲区。
            val prefixLength = "base64://".length
            val encodedLength = source.url.length - prefixLength
            require(encodedLength in 1..((ImageBinary.MAX_BYTES + 2) / 3 * 4)) {
                "聊天图片 ${source.index} 的内嵌图片为空或超过 50 MiB"
            }
            val bytes = try {
                Base64.getDecoder().decode(source.url.substring(prefixLength))
            } catch (_: IllegalArgumentException) {
                throw IllegalArgumentException("聊天图片 ${source.index} 的内嵌图片数据无效")
            }
            ImageBinary.inspect(bytes)
        } else ImageBinary.inspect(downloader.downloadOriginal(source.url, ImageBinary.MAX_BYTES))
        is ImageSource.Asset -> withContext(Dispatchers.IO) {
            val file = requireStore().file(source.id)
            require(file.length() <= ImageBinary.MAX_BYTES) { "素材超过 50 MiB" }
            ImageBinary.inspect(file.readBytes())
        }
    }

    @Synchronized
    fun close() {
        fetcher?.close()
        fetcher = null
        store = null
    }
}
