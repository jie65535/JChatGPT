package top.jie65535.mirai

import net.mamoe.mirai.console.data.AutoSavePluginConfig
import net.mamoe.mirai.console.data.ValueDescription
import net.mamoe.mirai.console.data.value

object PluginConfig : AutoSavePluginConfig("Config") {
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

    @ValueDescription("百炼平台API KEY")
    val dashScopeApiKey: String by value("")

    @ValueDescription("百炼平台图片编辑模型")
    val imageEditModel: String by value("qwen-image-edit")

    @ValueDescription("百炼平台TTS模型")
    val ttsModel: String by value("qwen-tts")

    @ValueDescription("Jina API Key")
    val jinaApiKey by value("")

    @ValueDescription("SearXNG 搜索引擎地址，如 http://127.0.0.1:8080/search 必须启用允许json格式返回")
    val searXngUrl: String by value("")

    @ValueDescription("在线运行代码 glot.io 的 api token，在官网注册账号即可获取。")
    val glotToken: String by value("")

    @ValueDescription("群管理是否自动拥有对话权限，默认是")
    val groupOpHasChatPermission: Boolean by value(true)

    @ValueDescription("好友是否自动拥有对话权限，默认是")
    val friendHasChatPermission: Boolean by value(true)

    @ValueDescription("机器人是否可以禁言别人，默认禁止")
    val canMute: Boolean by value(false)

    @ValueDescription("群荣誉等级权限门槛，达到这个等级相当于自动拥有对话权限。")
    val temperaturePermission: Int by value(50)

    @ValueDescription("等待响应超时时间，单位毫秒，默认60秒")
    val timeout: Long by value(60000L)

    @Deprecated("使用外部文件而不是在配置文件内保存提示词")
    @ValueDescription("系统提示词，该字段已弃用，使用提示词文件而不是在这里修改")
    var prompt: String by value("你是一个乐于助人的助手")

    @ValueDescription("系统提示词文件路径，相对于插件配置目录")
    val promptFile: String by value("SystemPrompt.md")

    @ValueDescription("创建Prompt时取最近多少分钟内的消息")
    val historyWindowMin: Int by value(10)

    @ValueDescription("创建Prompt时取最多几条消息")
    val historyMessageLimit: Int by value(20)

    @ValueDescription("是否打印Prompt便于调试")
    val logPrompt by value(false)

    @ValueDescription("达到需要合并转发消息的阈值")
    val messageMergeThreshold by value(150)

    @ValueDescription("最大循环次数，至少2次")
    val retryMax: Int by value(5)

    @ValueDescription("关键字呼叫，支持正则表达式")
    val callKeyword by value("[小筱][林淋月玥]")

    @ValueDescription("是否显示工具调用消息，默认是")
    val showToolCallingMessage by value(true)

    @ValueDescription("是否启用记忆编辑功能，记忆存在data目录，提示词中需要加上{memory}来填充记忆，每个群都有独立记忆")
    val memoryEnabled by value(true)

    @ValueDescription("是否启用好感度系统")
    val enableFavorabilitySystem by value(true)

    @ValueDescription("好感度每日基础偏移速度（点/天）")
    val favorabilityBaseShiftSpeed by value(2.0)

    @ValueDescription("表情包路径，配置后会加载目录下的文件名，提示词中需要用{meme}来插入上下文")
    val memeDir: String by value("")
}