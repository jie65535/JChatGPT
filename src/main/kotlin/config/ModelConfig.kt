package top.jie65535.mirai.config

import kotlinx.serialization.Serializable
import net.mamoe.mirai.console.data.AutoSavePluginConfig
import net.mamoe.mirai.console.data.ValueDescription
import net.mamoe.mirai.console.data.value

/** A credential/API definition shared by one or more model aliases. */
@Serializable
data class ModelProviderDefinition(
    val name: String = "",
    val type: String = "openai",
    val api: String = "",
    val token: String = "",
)

/** A model alias used by role bindings in [PluginConfig]. */
@Serializable
data class ModelDefinition(
    val name: String = "",
    val provider: String = "",
    val model: String = "",
    val extraBody: String = "",
)

/**
 * Shared model catalog. Credentials live here so roles can reference one alias
 * without duplicating API URLs and keys in the main plugin configuration.
 */
object ModelConfig : AutoSavePluginConfig("Models") {
    @ValueDescription(
        """模型提供商与凭据；type 可用 openai 或 dashscope，token 对应 API Token/Key。
新安装可参考：
- name: deepseek
  type: openai
  api: 'https://api.deepseek.com/v1/'
  token: 'sk-xxxx'
- name: dashscope-native
  type: dashscope
  api: ''
  token: 'sk-xxxx'"""
    )
    var providers: List<ModelProviderDefinition> by value()

    @ValueDescription(
        """模型别名；每项通过 provider 绑定一个提供商，并填写实际模型名。
配置后还需在 Config.yml 将 chatModelAlias 等用途字段设为对应 name。
示例：
- name: chat-main
  provider: deepseek
  model: deepseek-chat
  extraBody: ''
- name: image-main
  provider: dashscope-native
  model: qwen-image-2.0
  extraBody: ''"""
    )
    var models: List<ModelDefinition> by value()
}
