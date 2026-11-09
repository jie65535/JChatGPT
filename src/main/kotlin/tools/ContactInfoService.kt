package top.jie65535.mirai.tools

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import net.mamoe.mirai.contact.Member
import net.mamoe.mirai.contact.MemberPermission
import net.mamoe.mirai.contact.UserOrBot
import top.jie65535.mirai.data.ImageAsset
import top.jie65535.mirai.data.ImageAssetStore
import top.jie65535.mirai.media.ImageBinary

/** 公开联系人资料与头像素材；非必要的兼容调用失败时保留其余可用字段。 */
internal class ContactInfoService(
    private val store: ImageAssetStore?,
    private val download: suspend (String) -> ByteArray,
    private val report: (String) -> Unit = {},
) {
    suspend fun load(contact: UserOrBot, groupId: Long?, includeAvatar: Boolean): JsonObject {
        val userId = contact.id
        val warnings = mutableListOf<String>()
        fun failed(field: String, cause: Throwable) {
            warnings += "${field}暂不可用"
            report("读取联系人$field 失败: user=$userId, group=$groupId, cause=${cause.javaClass.simpleName}")
        }
        fun <T> read(field: String, block: () -> T): T? = try {
            block()
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: Exception) {
            failed(field, cause)
            null
        } catch (cause: NotImplementedError) {
            failed(field, cause)
            null
        }

        val nickname = read("昵称") { contact.nick.take(100) }
        val member = (contact as? Member)?.takeIf { groupId != null }
        val nameCard = member?.let { read("群名片") { it.nameCard.take(100) } }
        val role = member?.let {
            read("群内身份") {
                when (it.permission) {
                    MemberPermission.OWNER -> "群主"
                    MemberPermission.ADMINISTRATOR -> "管理员"
                    MemberPermission.MEMBER -> "普通成员"
                }
            }
        }
        var avatar: ImageAsset? = null
        var avatarError: String? = null
        if (includeAvatar) {
            if (store == null) {
                avatarError = "素材库暂不可用，无法保存头像参考图。"
            } else {
                try {
                    val bytes = download(contact.avatarUrl)
                    avatar = withContext(Dispatchers.IO) {
                        val image = ImageBinary.inspect(bytes)
                        val name = nickname.orEmpty().replace(Regex("[\\r\\n\\t]"), " ").take(80).ifBlank { userId.toString() }
                        store.save(image, "$name 的头像", "avatar", description = "QQ $userId 的头像", tags = listOf("头像"))
                    }
                } catch (cause: CancellationException) {
                    throw cause
                } catch (cause: Exception) {
                    failed("头像", cause)
                    avatarError = "头像获取失败，可让对方直接发送图片作为参考。"
                } catch (cause: NotImplementedError) {
                    failed("头像", cause)
                    avatarError = "当前运行环境暂不支持读取此联系人的头像，可让对方直接发送图片。"
                }
            }
        }
        return buildJsonObject {
            put("userId", userId)
            nickname?.let { put("nickname", it) }
            groupId?.let { put("groupId", it) }
            nameCard?.let { put("nameCard", it) }
            role?.let { put("role", it) }
            avatar?.let { image ->
                putJsonObject("avatar") {
                    put("image", image.reference)
                    put("markdown", "![头像](${image.reference})")
                    image.width?.let { put("width", it) }
                    image.height?.let { put("height", it) }
                    put("collected", image.collected)
                }
            }
            avatarError?.let { put("avatar_error", it) }
            if (warnings.isNotEmpty()) putJsonArray("warnings") { warnings.forEach { add(it) } }
        }
    }
}
