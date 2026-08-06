package top.jie65535.mirai.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import net.mamoe.mirai.message.data.content

object ChatHistorySearchText {
    private const val MAX_INDEXED_CHARS = 16_384
    private const val MAX_QUOTED_CHARS = 320
    private const val MAX_FORWARD_NODES = 30
    private const val MAX_FORWARD_NODE_CHARS = 500

    private val json = Json { ignoreUnknownKeys = true }
    private val whitespace = Regex("\\s+")

    fun extract(code: String, atNames: Map<Long, String> = emptyMap()): String {
        val rendered = runCatching { renderJsonCode(code, atNames) }
            .recoverCatching { decodeMessageCode(code).content }
            .getOrDefault("")
        return rendered.normalize().takeUtf16Safely(MAX_INDEXED_CHARS)
    }

    fun extractAtTargets(code: String): Set<Long> = runCatching {
        val messages = json.parseToJsonElement(code) as? JsonArray ?: return@runCatching emptySet()
        buildSet { collectAtTargets(messages, this) }
    }.getOrDefault(emptySet())

    fun bigrams(text: String): String {
        val codePoints = text.lowercase().codePoints().toArray()
        if (codePoints.size < 2) return ""
        return buildString(codePoints.size * 3) {
            var tokenCount = 0
            for (index in 0 until codePoints.lastIndex) {
                if (Character.isWhitespace(codePoints[index]) || Character.isWhitespace(codePoints[index + 1])) {
                    continue
                }
                if (tokenCount++ > 0) append(' ')
                appendCodePoint(codePoints[index])
                appendCodePoint(codePoints[index + 1])
            }
        }
    }

    private fun renderJsonCode(code: String, atNames: Map<Long, String>): String {
        val messages = json.parseToJsonElement(code) as? JsonArray
            ?: throw IllegalArgumentException("消息记录不是 JSON array")
        return renderMessages(messages, atNames)
    }

    private fun renderMessages(messages: JsonArray, atNames: Map<Long, String>): String = messages.joinToString("") { element ->
        val message = element as? JsonObject ?: return@joinToString ""
        when (val type = message.string("type")) {
            "PlainText" -> message.string("content").orEmpty()
            "At" -> message.long("target")?.let { target -> "@${atNames[target] ?: target}" }.orEmpty()
            "AtAll" -> "@全体成员"
            "Image", "FlashImage" -> if (message.boolean("isEmoji") == true) "[表情包]" else "[图片]"
            "QuoteReply" -> renderQuote(message, atNames)
            "ForwardMessage" -> renderForward(message, atNames)
            "MessageOrigin", "MessageSource", "ShowImageFlag" -> ""
            "Face", "MarketFace", "VipFace" -> "[表情]"
            "Audio" -> "[语音]"
            "FileMessage" -> "[文件${message.string("name")?.let { ": $it" }.orEmpty()}]"
            "LightApp", "SimpleServiceMessage", "MusicShare" -> "[卡片消息]"
            "PokeMessage" -> "[戳一戳]"
            null -> ""
            else -> message.string("content") ?: "[$type]"
        }
    }

    private fun renderQuote(message: JsonObject, atNames: Map<Long, String>): String {
        val source = message["source"] as? JsonObject ?: return "[引用消息]"
        val author = source.long("fromId")?.toString() ?: "其他用户"
        val original = (source["originalMessage"] as? JsonArray)
            ?.let { renderMessages(it, atNames) }
            .orEmpty()
            .normalize()
            .takeUtf16Safely(MAX_QUOTED_CHARS)
        return "[引用 $author: $original]"
    }

    private fun renderForward(message: JsonObject, atNames: Map<Long, String>): String = buildString {
        append("[转发消息]")
        val nodes = message["nodeList"] as? JsonArray ?: return@buildString
        nodes.take(MAX_FORWARD_NODES).forEach { element ->
            val node = element as? JsonObject ?: return@forEach
            val sender = node.string("senderName") ?: "未知用户"
            val chain = node["messageChain"] as? JsonArray
            append(' ').append(sender).append(": ")
            append(
                chain?.let { renderMessages(it, atNames) }
                    .orEmpty()
                    .normalize()
                    .takeUtf16Safely(MAX_FORWARD_NODE_CHARS)
            )
        }
        if (nodes.size > MAX_FORWARD_NODES) append(" ...[转发内容截断]")
    }

    private fun collectAtTargets(messages: JsonArray, targets: MutableSet<Long>) {
        messages.forEach { element ->
            val message = element as? JsonObject ?: return@forEach
            when (message.string("type")) {
                "At" -> message.long("target")?.let(targets::add)
                "QuoteReply" -> {
                    val original = (message["source"] as? JsonObject)
                        ?.get("originalMessage") as? JsonArray
                    original?.let { collectAtTargets(it, targets) }
                }
                "ForwardMessage" -> (message["nodeList"] as? JsonArray).orEmpty().forEach { node ->
                    ((node as? JsonObject)?.get("messageChain") as? JsonArray)
                        ?.let { collectAtTargets(it, targets) }
                }
            }
        }
    }

    private fun String.normalize(): String = whitespace.replace(this, " ")
        .trim()
        .replaceUnpairedSurrogates()

    private fun String.takeUtf16Safely(maxLength: Int): String {
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

    private fun JsonObject.string(key: String): String? =
        (get(key) as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.long(key: String): Long? =
        (get(key) as? JsonPrimitive)?.longOrNull

    private fun JsonObject.boolean(key: String): Boolean? =
        (get(key) as? JsonPrimitive)?.booleanOrNull
}
