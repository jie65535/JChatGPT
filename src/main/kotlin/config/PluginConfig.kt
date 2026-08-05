package top.jie65535.mirai.config

import kotlinx.serialization.Serializable
import net.mamoe.mirai.console.data.AutoSavePluginConfig
import net.mamoe.mirai.console.data.ValueDescription
import net.mamoe.mirai.console.data.value

/**
 * 聊天模型备用接入点。用于主接入点（openAiApi/openAiToken/chatModel）连续失败时容灾切换。
 * 任一字段留空则继承主接入点对应配置，因此可只换 API KEY、只换模型、或整体换一个服务商。
 */
@Serializable
data class ChatFallbackEndpoint(
    val api: String = "",
    val token: String = "",
    val model: String = "",
    val extraBody: String = "",
)

object PluginConfig : AutoSavePluginConfig("Config") {
    @ValueDescription("主人QQ，AI可以通过工具向主人发起请求，会等待一段时间")
    val ownerId: Long by value()

    @ValueDescription("OpenAI API base url")
    val openAiApi: String by value("https://dashscope.aliyuncs.com/compatible-mode/v1/")

    @ValueDescription("OpenAI API Token")
    var openAiToken: String by value("")

    @ValueDescription("Chat模型")
    var chatModel: String by value("qwen-max")

    @ValueDescription("Chat模型温度，默认为null")
    var chatTemperature: Double? by value(null)

    @ValueDescription("推理模型API")
    var reasoningModelApi: String by value("https://dashscope.aliyuncs.com/compatible-mode/v1/")

    @ValueDescription("推理模型Token")
    var reasoningModelToken: String by value("")

    @ValueDescription("推理模型")
    var reasoningModel: String by value("qwq-plus")

    @ValueDescription("视觉模型API")
    var visualModelApi: String by value("https://dashscope.aliyuncs.com/compatible-mode/v1/")

    @ValueDescription("视觉模型Token")
    var visualModelToken: String by value("")

    @ValueDescription("视觉模型")
    var visualModel: String by value("qwen-vl-plus")

    @ValueDescription("聊天模型额外请求体JSON，会合并到请求体中。例如DeepSeek关闭思维: {\"thinking\": {\"type\": \"disabled\"}}")
    val chatModelExtraBody: String by value("")

    @ValueDescription("聊天模型备用接入点列表（容灾）。主接入点连续失败时按顺序切换；每项留空的字段会继承主接入点，例如只换API KEY就只填token，只换模型就只填model")
    val chatFallbacks: List<ChatFallbackEndpoint> by value()

    @ValueDescription("是否启用实验性的历史用户画像分析")
    val profileEnabled: Boolean by value(true)

    @ValueDescription("画像分析模型API。留空时继承聊天模型API")
    val profileModelApi: String by value("")

    @ValueDescription("画像分析模型Token。留空时继承聊天模型Token")
    val profileModelToken: String by value("")

    @ValueDescription("画像分析模型。留空时继承聊天模型")
    val profileModel: String by value("")

    @ValueDescription("画像分析模型温度。留空时继承聊天模型温度")
    val profileModelTemperature: Double? by value(null)

    @ValueDescription("画像分析模型额外请求体JSON。留空时继承聊天模型额外请求体")
    val profileModelExtraBody: String by value("")

    @ValueDescription("画像模型同时执行的请求上限，取值1~512，默认128；应用层排队且不计入首块超时，仅影响画像模型")
    val profileMaxConcurrentRequests: Int by value(128)

    @ValueDescription("画像分析使用的聊天记录SQLite路径。留空时使用插件自己的chat-history.sqlite；本地实验可填写历史库绝对路径")
    val profileHistoryDatabasePath: String by value("")

    @ValueDescription("是否异步刷新好友、群、群成员联系人快照；结果写入 chat-history.sqlite，供历史画像和上下文显示使用")
    val contactSnapshotEnabled: Boolean by value(true)

    @ValueDescription("插件启动或首次收到消息后延迟多少秒开始刷新联系人快照，避免阻塞启动")
    val contactSnapshotInitialDelaySeconds: Int by value(30)

    @ValueDescription("联系人快照定时刷新间隔（分钟）。设为0仅启动后刷新一次")
    val contactSnapshotRefreshIntervalMinutes: Long by value(24 * 60L)

    @ValueDescription("刷新每个群成员列表后的额外延迟（毫秒），用于降低 OneBot 端压力")
    val contactSnapshotGroupDelayMillis: Long by value(200L)

    @ValueDescription("每个画像分析批次最多读取目标用户多少条消息")
    val profileBatchTargetMessages: Int by value(120)

    @ValueDescription("全量推进群画像时，群内至少有多少条尚未处理的消息才启动；显式指定群号不受限制")
    val profileBulkGroupMinPendingMessages: Int by value(20)

    @ValueDescription("是否每天定时推进已接近历史水位线的群画像")
    val profileDailyGroupUpdateEnabled: Boolean by value(false)

    @ValueDescription("每日群画像推进时间，使用服务器本地时区，格式 HH:mm")
    val profileDailyGroupUpdateTime: String by value("04:30")

    @ValueDescription("每日群画像只自动推进最早待处理消息距今不超过多少天的群，必须为正数")
    val profileDailyGroupUpdateMaxPendingAgeDays: Int by value(7)

    @ValueDescription("每个画像分析批次最多包含多少个离散对话片段；同一秒的消息仍会一起处理")
    val profileBatchMaxEpisodes: Int by value(16)

    @ValueDescription("目标用户相邻发言超过多少分钟时划分为新的对话片段")
    val profileEpisodeGapMinutes: Int by value(60)

    @ValueDescription("每个对话片段最多附带多少条前置上下文")
    val profileContextBeforeMessages: Int by value(30)

    @ValueDescription("每个对话片段最多附带多少条后续上下文")
    val profileContextAfterMessages: Int by value(30)

    @ValueDescription("画像分析单批次最多保留多少条非目标用户上下文消息")
    val profileContextCoreMessages: Int by value(300)

    @ValueDescription("画像分析中单条消息最多保留的字符数")
    val profileMaxMessageChars: Int by value(1000)

    @ValueDescription("画像模型响应无效时的最大重试次数，取值0~3")
    val profileRetryMax: Int by value(2)

    @ValueDescription("单条画像结论的最大字符数")
    val profileSummaryMaxLength: Int by value(500)

    @ValueDescription("是否在群聊会话结束后静默自动维护相关用户画像")
    val profileAutoUpdateEnabled: Boolean by value(true)

    @ValueDescription("群画像历史推进的目标消息数；实时自动维护改按自然空窗读取完整连续会话")
    val profileAutoConversationMessageLimit: Int by value(150)

    @ValueDescription("用户在会话中至少包含多少个本人文本字符才调用画像模型")
    val profileAutoMinAuthoredTextChars: Int by value(20)

    @ValueDescription("是否在普通群聊上下文中自动注入相关用户的画像摘要")
    val profileAutoInjectEnabled: Boolean by value(true)

    @ValueDescription("一次普通对话最多自动注入多少名相关用户的画像摘要")
    val profileAutoInjectMaxUsers: Int by value(4)

    @ValueDescription("普通对话中每名用户的画像摘要最多注入多少字符")
    val profileAutoInjectSummaryMaxChars: Int by value(300)

    @ValueDescription("备用接入点冷却时间（分钟）。某接入点失败后在此时间内会被排到重试队尾，避免每条消息都先卡在故障接入点上。设为0禁用，默认5分钟")
    val fallbackCooldownMinutes: Long by value(5L)

    @ValueDescription("失败后首次重试的基础退避时间（毫秒），后续按指数增长并加入抖动。设为0禁用退避，最大60000")
    val retryBackoffBaseMillis: Long by value(1000L)

    @ValueDescription("失败重试的最大退避时间（毫秒）。设为0禁用退避，最大60000")
    val retryBackoffMaxMillis: Long by value(10000L)

    @ValueDescription("推理模型额外请求体JSON，会合并到请求体中。例如DeepSeek启用思维: {\"thinking\": {\"type\": \"enabled\"}}")
    val reasoningModelExtraBody: String by value("")

    @ValueDescription("视觉模型额外请求体JSON，会合并到请求体中。")
    val visualModelExtraBody: String by value("")

    @ValueDescription("视觉模型是否先由机器人下载图片并以Base64上传。建议开启，可避免百炼下载QQ临时图片链接失败")
    val visualImageBase64Enabled: Boolean by value(true)

    @ValueDescription("视觉模型单次工具调用的最大尝试次数，取值1~3，默认2次。图片只下载和编码一次，重试仅重新请求模型")
    val visualRetryMax: Int by value(2)

    @ValueDescription("百炼平台API KEY")
    val dashScopeApiKey: String by value("")

    @ValueDescription("百炼平台图像模型，支持文生图与图像编辑。可选：qwen-image-2.0 / qwen-image-2.0-pro / qwen-image-edit-max / qwen-image-edit-plus 等")
    val imageModel: String by value("qwen-image-2.0")

    @ValueDescription("是否在生成的图片右下角添加 Qwen-Image 水印")
    val imageWatermark: Boolean by value(false)

    @ValueDescription("百炼平台TTS模型。qwen3-tts-instruct-flash 支持 instructions 指令控制；纯发音可用 qwen3-tts-flash 或 qwen-tts")
    val ttsModel: String by value("qwen3-tts-instruct-flash")

    @ValueDescription("Jina API Key")
    val jinaApiKey by value("")

    @ValueDescription("Jina Reader API 地址，默认使用在线服务；自托管示例：http://127.0.0.1:4223/")
    val jinaReaderUrl: String by value("https://r.jina.ai/")

    @ValueDescription("SearXNG 搜索引擎地址，如 http://127.0.0.1:8080/search 必须启用允许json格式返回")
    val searXngUrl: String by value("")

    @ValueDescription("在线运行代码 glot.io 的 api token，在官网注册账号即可获取。")
    val glotToken: String by value("")

    @ValueDescription("和风天气专属 API Host，例如 abc1234xyz.def.qweatherapi.com")
    val qWeatherApiHost: String by value("")

    @ValueDescription("和风天气项目 ID，用于 JWT 的 sub")
    val qWeatherProjectId: String by value("")

    @ValueDescription("和风天气凭据 ID，用于 JWT 的 kid")
    val qWeatherCredentialId: String by value("")

    @ValueDescription("和风天气 Ed25519 私钥文件路径，相对于插件配置目录，也可以填写绝对路径")
    val qWeatherPrivateKeyPath: String by value("qweather-ed25519-private.pem")

    @ValueDescription("群管理是否自动拥有对话权限，默认是")
    val groupOpHasChatPermission: Boolean by value(true)

    @ValueDescription("好友是否自动拥有对话权限，默认是")
    val friendHasChatPermission: Boolean by value(true)

    @ValueDescription("机器人是否可以禁言别人，默认禁止")
    val canMute: Boolean by value(false)

    @ValueDescription("群荣誉等级权限门槛，达到这个等级相当于自动拥有对话权限。")
    val temperaturePermission: Int by value(50)

    @ValueDescription("等待响应超时时间（整个请求的总超时与socket读超时），单位毫秒，默认60秒")
    val timeout: Long by value(60000L)

    @ValueDescription("首块响应超时时间，单位毫秒，默认10秒。若连接建立后在此时间内没收到首块data:则中断走重试")
    val firstChunkTimeout: Long by value(10000L)

    @ValueDescription("视觉模型首块响应超时时间，单位毫秒，默认120秒。视觉模型需先下载图片再出首块，比对话天然慢，故单独放宽")
    val visualFirstChunkTimeout: Long by value(120000L)

    @ValueDescription("推理模型首块响应超时时间，单位毫秒，默认90秒。推理模型出首块前常有思考预热，比对话慢，故单独放宽")
    val reasoningFirstChunkTimeout: Long by value(90000L)

    @ValueDescription("画像分析模型首块响应超时时间，单位毫秒，默认180秒")
    val profileFirstChunkTimeout: Long by value(180000L)

    @Deprecated("使用外部文件而不是在配置文件内保存提示词")
    @ValueDescription("系统提示词，该字段已弃用，使用提示词文件而不是在这里修改")
    var prompt: String by value("你是一个乐于助人的助手")

    @ValueDescription("系统提示词文件路径，相对于插件配置目录")
    val promptFile: String by value("SystemPrompt.md")

    @ValueDescription("创建Prompt时取最近多少分钟内的消息")
    val historyWindowMin: Int by value(10)

    @ValueDescription("初次创建Prompt时最多读取几条近期消息；模型运行期间的增量消息不受此限制")
    val historyMessageLimit: Int by value(20)

    @ValueDescription("启用对话上下文内存缓存，允许在短时间内保持上下文连续")
    val enableContextCache by value(true)

    @ValueDescription("上下文缓存有效期（分钟），超过此时间未活动则重新创建上下文")
    val contextCacheTimeoutMinutes by value(10)

    @ValueDescription("是否打印Prompt便于调试")
    val logPrompt by value(false)

    @ValueDescription("达到需要合并转发消息的阈值")
    val messageMergeThreshold by value(150)

    @ValueDescription("单次对话正常调用模型的最大循环轮数，至少2轮；失败重试不占用此轮数")
    val retryMax: Int by value(10)

    @ValueDescription("关键字呼叫，支持正则表达式")
    val callKeyword by value("[小筱][林淋月玥]")

    @ValueDescription("是否显示工具调用消息，默认是")
    val showToolCallingMessage by value(true)

    @ValueDescription("是否启用记忆编辑功能，记忆存在data目录，提示词中需要加上{memory}来填充记忆，每个群都有独立记忆")
    val memoryEnabled by value(true)

    @ValueDescription("是否启用技能系统，技能存在data/skills目录（全局跨群），提示词中需要加上{skills}来注入技能索引")
    val skillsEnabled by value(true)

    @ValueDescription("是否启用好感度系统")
    val enableFavorabilitySystem by value(true)

    @ValueDescription("表情包路径，配置后会加载目录下的文件名，提示词中需要用{meme}来插入上下文")
    val memeDir: String by value("")

    @ValueDescription("请求主人回复等待时间，单位毫秒，默认300秒")
    val requestOwnerWaitTimeout: Long by value(300000L)

    @ValueDescription("单个工具调用返回内容的最大字符数，超过将被截断并标注")
    val maxToolOutputLength: Int by value(15000)

    @ValueDescription("聊天记录搜索最大天数")
    val searchHistoryMaxDays: Int by value(30)

    @ValueDescription("聊天记录搜索最大查询条数，防止内存溢出")
    val searchHistoryMaxRecords: Int by value(5000)
}
