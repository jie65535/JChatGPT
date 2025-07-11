package top.jie65535.mirai.tools

import com.aallam.openai.api.chat.Tool
import com.aallam.openai.api.core.Parameters

class StopLoopAgent : BaseAgent(
    tool = Tool.function(
        name = "endConversation",
        description = "结束本轮对话",
        parameters = Parameters.Empty
    )
)