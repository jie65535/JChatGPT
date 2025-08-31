package top.jie65535.mirai

import net.mamoe.mirai.console.data.AutoSavePluginData
import net.mamoe.mirai.console.data.value

object PluginData : AutoSavePluginData("data") {
    /**
     * 联系人记忆
     */
    val contactMemory by value(mutableMapOf<Long, String>())

    /**
     * 添加对话记忆
     */
    fun appendContactMemory(contactId: Long, newMemory: String) {
        val memory = contactMemory[contactId]
        if (memory.isNullOrEmpty()) {
            contactMemory[contactId] = newMemory
        } else {
            contactMemory[contactId] = "$memory\n$newMemory"
        }
    }

    /**
     * 替换对话记忆
     */
    fun replaceContactMemory(contactId: Long, oldMemory: String, newMemory: String) {
        val memory = contactMemory[contactId]
        if (memory.isNullOrEmpty()) {
            contactMemory[contactId] = newMemory
        } else {
            contactMemory[contactId] = memory.replace(oldMemory, newMemory)
                .replace("\n\n", "\n")
        }
    }
}