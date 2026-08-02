package top.jie65535.mirai.data

import kotlinx.serialization.SerializationException
import net.mamoe.mirai.Mirai
import net.mamoe.mirai.message.code.MiraiCode
import net.mamoe.mirai.message.data.MessageChain
import net.mamoe.mirai.message.data.MessageSource
import net.mamoe.mirai.message.data.MessageSourceKind
import net.mamoe.mirai.message.data.buildMessageSource

/**
 * 插件自维护的聊天消息记录。
 *
 * [recalled]：0=正常、1=发送失败、2=自行撤回、3=管理员撤回。
 */
data class ChatMessageRecord(
    val id: Long = 0,
    val botId: Long,
    val fromId: Long,
    val targetId: Long,
    val ids: String?,
    val internalIds: String?,
    val time: Int,
    val kind: MessageSourceKind,
    val code: String,
    val recalled: Int = 0,
) {
    fun toMessageSource(): MessageSource {
        return Mirai.buildMessageSource(botId, kind) {
            fromId = this@ChatMessageRecord.fromId
            targetId = this@ChatMessageRecord.targetId
            ids = this@ChatMessageRecord.ids.toIntArray()
            internalIds = this@ChatMessageRecord.internalIds.toIntArray()
            time = this@ChatMessageRecord.time
            messages(messages = toMessageChain())
        }
    }

    fun toMessageChain(): MessageChain {
        return try {
            MessageChain.deserializeFromJsonString(code)
        } catch (cause: SerializationException) {
            try {
                MiraiCode.deserializeMiraiCode(code)
            } catch (_: Throwable) {
                throw cause
            }
        }
    }

    companion object {
        fun fromSuccess(source: MessageSource, message: MessageChain): ChatMessageRecord = ChatMessageRecord(
            botId = source.botId,
            fromId = source.fromId,
            targetId = source.targetId,
            ids = source.ids.joinToString(","),
            internalIds = source.internalIds.joinToString(","),
            time = source.time,
            kind = source.kind,
            code = with(MessageChain) { message.serializeToJsonString() },
        )

        private fun String?.toIntArray(): IntArray {
            return if (isNullOrEmpty()) {
                IntArray(0)
            } else {
                split(',').map { it.trim().toInt() }.toIntArray()
            }
        }
    }
}
