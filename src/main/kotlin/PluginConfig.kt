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
    var chatModel: String by value("gpt-3.5-turbo-1106")

    @ValueDescription("Chat默认提示")
    var prompt: String by value("")

    @ValueDescription("群管理是否自动拥有对话权限，默认是")
    val groupOpHasChatPermission: Boolean by value(true)

    @ValueDescription("好友是否自动拥有对话权限，默认是")
    val friendHasChatPermission: Boolean by value(true)

    @ValueDescription("等待响应超时时间，单位毫秒，默认60秒")
    val timeout: Long by value(60000L)
}