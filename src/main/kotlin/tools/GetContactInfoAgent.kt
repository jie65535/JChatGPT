package top.jie65535.mirai.tools

import com.aallam.openai.api.chat.Tool
import com.aallam.openai.api.core.Parameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import net.mamoe.mirai.contact.Group
import net.mamoe.mirai.contact.User
import net.mamoe.mirai.contact.UserOrBot
import net.mamoe.mirai.event.events.GroupMessageEvent
import net.mamoe.mirai.event.events.MessageEvent
import top.jie65535.mirai.JChatGPT
import top.jie65535.mirai.media.ImageAssets

class GetContactInfoAgent : BaseAgent(
    Tool.function(
        name = "getContactInfo",
        description = "查询联系人的公开基础信息：QQ号、昵称，以及当前群的名片和身份。" +
            "userId 省略时查询本轮发起人；群聊仅查当前群成员，私聊可查发起人、机器人及已有好友。" +
            "默认同时获取头像，返回 avatar.image 素材引用，可直接交给 imageAgent 编辑或 imageRecognition 查看，" +
            "也可使用 avatar.markdown 发送、imageLibrary 收藏。获取头像不会自动识图或新增收藏。",
        parameters = Parameters.buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("userId") {
                    put("type", "integer")
                    put("minimum", 1)
                    put("description", "目标QQ号，可从 @ 对象或消息发送者信息取得；省略时查询本轮发起人")
                }
                putJsonObject("includeAvatar") {
                    put("type", "boolean")
                    put("default", true)
                    put("description", "是否下载头像并返回可复用的图片引用，默认 true；仅查基础资料时可设 false")
                }
            }
        },
    )
) {
    override suspend fun execute(args: JsonObject?, event: MessageEvent): String = withContext(Dispatchers.IO) {
        val (userId, includeAvatar) = parseArguments(args, event.sender.id)
        val group = (event as? GroupMessageEvent)?.group
        val contact = resolveContact(userId, event.sender, group, event.bot) { event.bot.friends[it] }
            ?: return@withContext buildJsonObject {
                put("userId", userId)
                put("error", if (group != null) "当前群中未找到此成员，请核对QQ号。" else
                    "未找到此联系人；私聊只能查询当前发起人、机器人及机器人已有好友。")
            }.toString()
        ContactInfoService(ImageAssets.store, download = { ImageAssets.downloader.downloadOriginal(it) },
            report = { JChatGPT.logger.warning(it) })
            .load(contact, group?.id, includeAvatar).toString()
    }

    internal companion object {
        fun parseArguments(args: JsonObject?, senderId: Long): Pair<Long, Boolean> {
            val userId = args?.get("userId")?.let { value ->
                require(value is JsonPrimitive && !value.isString) { "userId 须为正整数QQ号" }
                requireNotNull(value.longOrNull?.takeIf { it > 0 }) { "userId 须为正整数QQ号" }
            } ?: senderId
            val includeAvatar = args?.get("includeAvatar")?.let { value ->
                require(value is JsonPrimitive && !value.isString) { "includeAvatar 须为布尔值" }
                requireNotNull(value.booleanOrNull) { "includeAvatar 须为布尔值" }
            } ?: true
            return userId to includeAvatar
        }

        fun resolveContact(
            userId: Long,
            sender: User,
            group: Group?,
            bot: UserOrBot,
            findFriend: (Long) -> User?,
        ): UserOrBot? {
            if (userId == sender.id) return sender
            if (group != null) return group[userId] ?: bot.takeIf { userId == it.id }
            if (userId == bot.id) return bot
            return findFriend(userId)
        }
    }
}
