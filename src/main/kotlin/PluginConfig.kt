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

    @ValueDescription("群荣誉等级权限门槛，达到这个等级相当于自动拥有对话权限。")
    val temperaturePermission: Int by value(50)

    @ValueDescription("等待响应超时时间，单位毫秒，默认60秒")
    val timeout: Long by value(60000L)

    @ValueDescription("系统提示词")
    var prompt: String by value("你是一个乐于助人的助手")

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
}