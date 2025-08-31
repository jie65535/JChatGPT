package top.jie65535.mirai.tools

import com.aallam.openai.api.chat.Tool
import com.aallam.openai.api.core.Parameters
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import net.mamoe.mirai.contact.AudioSupported
import net.mamoe.mirai.event.events.MessageEvent
import net.mamoe.mirai.utils.ExternalResource.Companion.toExternalResource
import top.jie65535.mirai.JChatGPT
import top.jie65535.mirai.PluginConfig
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.time.measureTime

/**
 * 发送语音消息，调用阿里TTS，需要系统中存在ffmpeg，因为要转换到QQ支持的amr格式。
 */
class SendVoiceMessage : BaseAgent(
    tool = Tool.function(
        name = "sendVoiceMessage",
        description = "发送一条文本转语音消息。",
        parameters = Parameters.buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("content") {
                    put("type", "string")
                    put("description", "语音消息文本内容")
                }
            }
            putJsonArray("required") {
                add("content")
            }
        }
    )
) {
    companion object {
        const val API_URL = "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation"
    }

    override val loadingMessage: String
        get() = "录音中..."

    override val isEnabled: Boolean
        get() = PluginConfig.dashScopeApiKey.isNotEmpty()


    override suspend fun execute(args: JsonObject?, event: MessageEvent): String {
        requireNotNull(args)
        if (event.subject !is AudioSupported) return "当前聊天环境不支持发送语音！"

        val content = args.getValue("content").jsonPrimitive.content

        // https://help.aliyun.com/zh/model-studio/qwen-tts
        val response = httpClient.post(API_URL) {
            contentType(ContentType("application", "json"))
            header("Authorization", "Bearer " + PluginConfig.dashScopeApiKey)
            setBody(buildJsonObject {
                put("model", PluginConfig.ttsModel)
                putJsonObject("input") {
                    put("text", content)
                    put("voice", "Chelsie") // Chelsie（女） Cherry（女） Ethan（男） Serena（女）
                }
            }.toString())
        }

        val responseJson = response.bodyAsText()
        val responseObject = Json.parseToJsonElement(responseJson).jsonObject
        return try {
            val url = responseObject
                .getValue("output").jsonObject
                .getValue("audio").jsonObject
                .getValue("url").jsonPrimitive.content

            val voiceFolder = JChatGPT.resolveDataFile("voice")
            voiceFolder.mkdir()
            val amrFile = File(voiceFolder, "${System.currentTimeMillis()}.amr")
            // 下载WAV并转到AMR
            downloadWav2Amr(url, amrFile.absolutePath)
            // 如果转换出来了则发送消息
            if (amrFile.exists()) {
                val audioMessage = amrFile.toExternalResource("amr").use {
                    (event.subject as AudioSupported).uploadAudio(it)
                }
                event.subject.sendMessage(audioMessage)
                "OK"
            } else {
                "语音转换失败"
            }
        } catch (e: Throwable) {
            JChatGPT.logger.error("语音生成结果解析异常", e)
            responseJson
        }
    }

    /**
     * 下载WAV并转换到AMR语音文件
     * @param url 下载地址
     * @param outputAmrPath 目标文件路径
     */
    private suspend fun downloadWav2Amr(url: String, outputAmrPath: String) {
        val wavBytes: ByteArray
        val downloadDuration = measureTime {
            wavBytes = httpClient.get(url).bodyAsBytes()
        }
        JChatGPT.logger.info("下载语音文件耗时 $downloadDuration，文件大小 ${wavBytes.size} Bytes，开始转换为AMR...")

        val convertDuration = measureTime {
            val ffmpeg = ProcessBuilder(
                "ffmpeg",
                "-f", "wav",    // 指定输入格式
                "-i", "pipe:0", // 从标准输入读取
                "-ar", "8000",
                "-ac", "1",
                "-b:a", "12.2k",
                "-y", // 覆盖输出文件
                outputAmrPath // 输出到目标文件位置
            ).start()
            ffmpeg.outputStream.use {
                it.write(wavBytes)
            }
            // 等待FFmpeg处理完成
            val completed = ffmpeg.waitFor(PluginConfig.timeout, TimeUnit.MILLISECONDS)

            if (!completed) {
                ffmpeg.destroy()
                JChatGPT.logger.error("转换文件超时")
            }

            if (ffmpeg.exitValue() != 0) {
                JChatGPT.logger.error("FFmpeg执行失败，退出代码：${ffmpeg.exitValue()}")
            }
        }

        JChatGPT.logger.info("转换音频耗时 $convertDuration")
    }

}