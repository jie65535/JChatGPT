package top.jie65535.mirai

/**
 * 会话内图片短索引：向 LLM 暴露递增整数，内部保留从原消息图片取得的精确 URL。
 * 同一 imageId 重复出现在上下文中时复用原编号，并用最新取得的 URL 刷新映射。
 */
internal class ImageIndex {
    private val imageUrlByIndex = LinkedHashMap<Int, String>()
    private val indexByImageId = HashMap<String, Int>()
    private var counter = 0

    @Synchronized
    fun add(imageId: String, imageUrl: String): Int {
        require(imageId.isNotBlank()) { "图片ID不能为空" }
        require(imageUrl.isNotBlank()) { "图片URL不能为空" }
        indexByImageId[imageId]?.let { index ->
            imageUrlByIndex[index] = imageUrl
            return index
        }

        val index = ++counter
        imageUrlByIndex[index] = imageUrl
        indexByImageId[imageId] = index
        return index
    }

    @Synchronized
    fun getUrl(index: Int): String? = imageUrlByIndex[index]
}
