package top.jie65535.mirai.profile

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import net.mamoe.mirai.message.data.At
import net.mamoe.mirai.message.data.ForwardMessage
import net.mamoe.mirai.message.data.Image
import net.mamoe.mirai.message.data.MessageChain
import net.mamoe.mirai.message.data.MessageSource
import net.mamoe.mirai.message.data.PlainText
import net.mamoe.mirai.message.data.QuoteReply
import net.mamoe.mirai.message.data.SingleMessage
import net.mamoe.mirai.message.data.content
import top.jie65535.mirai.data.ChatMessageRecord

object ProfileMessageRenderer {
    private val json = Json { ignoreUnknownKeys = true }

    fun render(record: ChatMessageRecord, aliases: Map<Long, String>, maxChars: Int): String {
        val content = runCatching {
            renderJsonCode(record.code, aliases)
        }.recoverCatching {
            renderChain(record.toMessageChain(), aliases)
        }.getOrElse {
            "[消息内容解析失败]"
        }.replace(Regex("[\\r\\n]+"), " ").trim().replaceUnpairedSurrogates()

        if (content.length <= maxChars) return content.ifEmpty { "[无文本消息]" }
        return content.takeUtf16Safely(maxChars).trimEnd() + "...[截断]"
    }

    fun referencedUserIds(record: ChatMessageRecord): Set<Long> = runCatching {
        referencedUserIdsFromJson(record.code)
    }.recoverCatching {
        buildSet {
            record.toMessageChain().forEach { message ->
                when (message) {
                    is At -> add(message.target)
                    is QuoteReply -> add(message.source.fromId)
                }
            }
        }
    }.getOrDefault(emptySet())

    fun authoredTextLength(record: ChatMessageRecord): Int = runCatching {
        val messages = json.parseToJsonElement(record.code) as? JsonArray
            ?: throw IllegalArgumentException("消息记录不是 JSON array")
        messages.sumOf { element ->
            val message = element as? JsonObject
            if (message?.string("type") == "PlainText") message.string("content").orEmpty().trim().length else 0
        }
    }.recoverCatching {
        record.toMessageChain().filterIsInstance<PlainText>().sumOf { it.content.trim().length }
    }.getOrDefault(0)

    private fun renderJsonCode(code: String, aliases: Map<Long, String>): String {
        val messages = json.parseToJsonElement(code) as? JsonArray
            ?: throw IllegalArgumentException("消息记录不是 JSON array")
        return renderJsonMessages(messages, aliases)
    }

    private fun renderJsonMessages(messages: JsonArray, aliases: Map<Long, String>): String =
        messages.joinToString("") { element ->
            val message = element as? JsonObject ?: return@joinToString ""
            when (val type = message.string("type")) {
                "PlainText" -> message.string("content").orEmpty()
                "At" -> message.long("target")?.let { target ->
                    "@${aliases[target] ?: "用户"}"
                }.orEmpty()
                "AtAll" -> "@全体成员"
                "Image", "FlashImage" -> if (message.boolean("isEmoji") == true) "[表情包]" else "[图片]"
                "QuoteReply" -> renderJsonQuote(message, aliases)
                "ForwardMessage" -> renderJsonForward(message, aliases)
                "MessageOrigin", "ShowImageFlag" -> ""
                "Face", "MarketFace", "VipFace" -> "[表情]"
                "Audio" -> "[语音]"
                "FileMessage" -> "[文件${message.string("name")?.let { ": $it" }.orEmpty()}]"
                "LightApp", "SimpleServiceMessage", "MusicShare" -> "[卡片消息]"
                "PokeMessage" -> "[戳一戳]"
                null -> ""
                else -> message.string("content") ?: "[$type]"
            }
        }

    private fun renderJsonQuote(message: JsonObject, aliases: Map<Long, String>): String {
        val source = message["source"] as? JsonObject ?: return "[引用消息]"
        val authorId = source.long("fromId")
        val author = authorId?.let { aliases[it] } ?: "其他用户"
        val original = (source["originalMessage"] as? JsonArray)
            ?.let { renderJsonMessages(it, aliases) }
            .orEmpty()
            .replace(Regex("[\\r\\n]+"), " ")
            .takeUtf16Safely(160)
        return "[引用 $author: $original]"
    }

    private fun renderJsonForward(message: JsonObject, aliases: Map<Long, String>): String = buildString {
        append("[转发消息]")
        val nodes = message["nodeList"] as? JsonArray ?: return@buildString
        nodes.take(20).forEach { element ->
            val node = element as? JsonObject ?: return@forEach
            val sender = node.string("senderName") ?: "未知用户"
            val chain = node["messageChain"] as? JsonArray
            append(' ').append(sender).append(": ")
            append(chain?.let { renderJsonMessages(it, aliases) }.orEmpty().takeUtf16Safely(200))
        }
        if (nodes.size > 20) append(" ...[转发内容截断]")
    }

    private fun referencedUserIdsFromJson(code: String): Set<Long> {
        val messages = json.parseToJsonElement(code) as? JsonArray
            ?: throw IllegalArgumentException("消息记录不是 JSON array")
        return buildSet { collectReferencedUserIds(messages, this) }
    }

    private fun collectReferencedUserIds(messages: JsonArray, destination: MutableSet<Long>) {
        messages.forEach { element ->
            val message = element as? JsonObject ?: return@forEach
            when (message.string("type")) {
                "At" -> message.long("target")?.let(destination::add)
                "QuoteReply" -> {
                    val source = message["source"] as? JsonObject ?: return@forEach
                    source.long("fromId")?.let(destination::add)
                    (source["originalMessage"] as? JsonArray)?.let {
                        collectReferencedUserIds(it, destination)
                    }
                }
                "ForwardMessage" -> (message["nodeList"] as? JsonArray)?.forEach nodeLoop@ { nodeElement ->
                    val node = nodeElement as? JsonObject ?: return@nodeLoop
                    (node["messageChain"] as? JsonArray)?.let {
                        collectReferencedUserIds(it, destination)
                    }
                }
            }
        }
    }

    private fun JsonObject.string(key: String): String? =
        (get(key) as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.long(key: String): Long? =
        (get(key) as? JsonPrimitive)?.longOrNull

    private fun JsonObject.boolean(key: String): Boolean? =
        (get(key) as? JsonPrimitive)?.booleanOrNull

    private fun renderChain(chain: MessageChain, aliases: Map<Long, String>): String =
        chain.joinToString("") { message -> renderSingle(message, aliases) }

    private fun renderSingle(message: SingleMessage, aliases: Map<Long, String>): String = when (message) {
        is MessageSource -> ""
        is PlainText -> message.content
        is At -> "@${aliases[message.target] ?: "用户"}"
        is Image -> if (message.isEmoji) "[表情包]" else "[图片]"
        is QuoteReply -> {
            val author = aliases[message.source.fromId] ?: "其他用户"
            val quoted = renderChain(message.source.originalMessage, aliases)
                .replace(Regex("[\\r\\n]+"), " ")
                .takeUtf16Safely(160)
            "[引用 $author: $quoted]"
        }
        is ForwardMessage -> buildString {
            append("[转发消息]")
            message.nodeList.take(20).forEach { node ->
                append(" ").append(node.senderName).append(": ")
                append(
                    renderChain(node.messageChain, aliases)
                        .replace(Regex("[\\r\\n]+"), " ")
                        .takeUtf16Safely(200)
                )
            }
            if (message.nodeList.size > 20) append(" ...[转发内容截断]")
        }
        else -> message.content
    }

    private fun String.takeUtf16Safely(maxLength: Int): String {
        require(maxLength >= 0) { "maxLength must not be negative" }
        if (length <= maxLength) return this
        val endIndex = if (maxLength > 0 &&
            Character.isHighSurrogate(this[maxLength - 1]) &&
            Character.isLowSurrogate(this[maxLength])
        ) {
            maxLength - 1
        } else {
            maxLength
        }
        return substring(0, endIndex)
    }

    private fun String.replaceUnpairedSurrogates(): String {
        var output: StringBuilder? = null
        var index = 0
        while (index < length) {
            val current = this[index]
            when {
                Character.isHighSurrogate(current) &&
                        index + 1 < length && Character.isLowSurrogate(this[index + 1]) -> {
                    output?.append(current)?.append(this[index + 1])
                    index += 2
                }
                Character.isSurrogate(current) -> {
                    if (output == null) output = StringBuilder(length).append(this, 0, index)
                    output.append('\uFFFD')
                    index++
                }
                else -> {
                    output?.append(current)
                    index++
                }
            }
        }
        return output?.toString() ?: this
    }
}
