package top.jie65535.mirai

import net.mamoe.mirai.console.data.AutoSavePluginConfig
import net.mamoe.mirai.console.data.ValueDescription
import net.mamoe.mirai.console.data.value

object PluginConfig : AutoSavePluginConfig("Config") {
    @ValueDescription("OpenAI API base url")
    val openAiApi: String by value("https://api.openai.com/v1/")

    @ValueDescription("OpenAI API Token")
    var openAiToken: String by value("")

    @ValueDescription("Chat模型")
    var chatModel: String by value("qwen-max")

    @ValueDescription("推理模型")
    var reasoningModel: String by value("qwq-plus")

    @ValueDescription("Chat默认提示")
    var prompt: String by value("")

    @ValueDescription("群管理是否自动拥有对话权限，默认是")
    val groupOpHasChatPermission: Boolean by value(true)

    @ValueDescription("好友是否自动拥有对话权限，默认是")
    val friendHasChatPermission: Boolean by value(true)

    @ValueDescription("群荣誉等级权限门槛，达到这个等级相当于自动拥有权限。")
    val temperaturePermission: Int by value(60)

    @ValueDescription("等待响应超时时间，单位毫秒，默认60秒")
    val timeout: Long by value(60000L)

    @ValueDescription("SearXNG 搜索引擎地址，如 http://127.0.0.1:8080/search 必须启用允许json格式返回")
    val searXngUrl: String by value("")

    @ValueDescription("在线运行代码 glot.io 的 api token，在官网注册账号即可获取。")
    val glotToken: String by value("")

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

    @ValueDescription("Jina API Key")
    val jinaApiKey by value("")
}