package top.jie65535.mirai

import net.mamoe.mirai.console.data.AutoSavePluginData
import net.mamoe.mirai.console.data.value

object PluginData : AutoSavePluginData("data") {
    /**
     * 联系人记忆
     */
    val contactMemory by value(mutableMapOf<Long, String>())
}