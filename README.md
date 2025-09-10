# JChatGPT

JChatGPT 是一个基于 Kotlin 的 Mirai Console 插件，它将大型语言模型（LLM）集成到即时通讯平台中。该插件支持多种 AI 模型和丰富的工具功能，使用户能够在群聊和私聊中与 AI 进行交互。

## 功能特性

- **多模型支持**：支持聊天模型、推理模型和视觉模型
- **丰富的工具系统**：包括网络搜索、代码执行、图像识别、群管理等
- **上下文记忆**：支持持久化记忆存储
- **LaTeX 渲染**：自动将数学表达式渲染为图片
- **灵活的触发方式**：@机器人、关键字触发、回复消息等
- **权限控制**：细粒度的权限管理系统
- **历史消息集成**：可选的历史消息上下文（需配合 mirai-hibernate-plugin）

## 用法

### 基本交互
- 在群内直接 @bot 即可触发对话
- 通过引用群友消息 + @bot 让 Bot 识别引用消息的内容
- 回复 bot 的消息即可引用对应的上下文对话（包括这个回复的历史对话）
- 使用关键字触发（默认为 "[小筱][林淋月玥]"，可在配置中修改）

### 工具调用
AI 可以自动调用多种工具来完成复杂任务：
- 网络搜索（需要配置 SearXNG）
- 代码执行（支持多种语言，需要配置 glot.io token）
- 图像识别（需要配置视觉模型）
- 推理思考（需要配置推理模型）
- 群管理（禁言等，需启用相应权限）
- 记忆管理（添加和修改对话记忆）

## 权限列表

- `JChatGPT:Chat` - 拥有该权限即可使用 bot 与 AI 对话
- `top.jie65535.mirai.jchatgpt:command.jgpt` - 拥有该权限即可使用 `/jgpt` 相关命令

## 命令列表

- `/jgpt setToken <token>` - 设置 OpenAI API Token
- `/jgpt enable <contact>` - 启用目标对话权限
- `/jgpt disable <contact>` - 禁用目标对话权限
- `/jgpt reload` - 重载配置文件

## 配置文件

配置文件位于：`./config/top.jie65535.mirai.JChatGPT/Config.yml`

```yaml
# OpenAI API base url
openAiApi: 'https://dashscope.aliyuncs.com/compatible-mode/v1/'
# OpenAI API Token
openAiToken: ''
# Chat模型
chatModel: 'qwen-max'
# Chat模型温度，默认为null
chatTemperature: null
# 推理模型API
reasoningModelApi: 'https://dashscope.aliyuncs.com/compatible-mode/v1/'
# 推理模型Token
reasoningModelToken: ''
# 推理模型
reasoningModel: 'qwq-plus'
# 视觉模型API
visualModelApi: 'https://dashscope.aliyuncs.com/compatible-mode/v1/'
# 视觉模型Token
visualModelToken: ''
# 视觉模型
visualModel: 'qwen-vl-plus'
# 百炼平台API KEY
dashScopeApiKey: ''
# 百炼平台图片编辑模型
imageEditModel: 'qwen-image-edit'
# 百炼平台TTS模型
ttsModel: 'qwen-tts'
# Jina API Key
jinaApiKey: ''
# SearXNG 搜索引擎地址，如 http://127.0.0.1:8080/search 必须启用允许json格式返回
searXngUrl: ''
# 在线运行代码 glot.io 的 api token，在官网注册账号即可获取。
glotToken: ''
# 群管理是否自动拥有对话权限，默认是
groupOpHasChatPermission: true
# 好友是否自动拥有对话权限，默认是
friendHasChatPermission: true
# 机器人是否可以禁言别人，默认禁止
canMute: false
# 群荣誉等级权限门槛，达到这个等级相当于自动拥有对话权限。
temperaturePermission: 50
# 等待响应超时时间，单位毫秒，默认60秒
timeout: 60000
# 系统提示词，该字段已弃用，使用提示词文件而不是在这里修改
prompt: '你是一个乐于助人的助手'
# 系统提示词文件路径，相对于插件配置目录
promptFile: 'SystemPrompt.md'
# 创建Prompt时取最近多少分钟内的消息
historyWindowMin: 10
# 创建Prompt时取最多几条消息
historyMessageLimit: 20
# 是否打印Prompt便于调试
logPrompt: false
# 达到需要合并转发消息的阈值
messageMergeThreshold: 150
# 最大循环次数，至少2次
retryMax: 5
# 关键字呼叫，支持正则表达式
callKeyword: '[小筱][林淋月玥]'
# 是否显示工具调用消息，默认是
showToolCallingMessage: true
# 是否启用记忆编辑功能，记忆存在data目录，提示词中需要加上{memory}来填充记忆，每个群都有独立记忆
memoryEnabled: true
```

## 系统提示词

JChatGPT 使用系统提示词来定义 AI 的行为和个性。提示词文件位于插件配置目录下的 `SystemPrompt.md` 文件中。

### 提示词结构

系统提示词通常包含以下部分：

1. **角色定义**：定义 AI 的身份、性格和行为准则
2. **功能说明**：描述 AI 可以使用的工具和功能
3. **交互规则**：规定 AI 与用户交互的规则和限制
4. **占位符**：动态替换的内容，如时间、群信息、记忆等

### 占位符

系统提示词支持以下占位符，在运行时会被动态替换：

- `{time}` - 当前时间（格式：yyyy年MM月dd E HH:mm:ss）
- `{subject}` - 当前聊天环境信息（群聊名称或私聊信息）
- `{memory}` - 当前联系人的记忆内容

### 示例提示词

以下是一个完整的示例提示词，展示如何构建一个个性化的AI角色：

```markdown
你是小灵，一个聪明、友善且乐于助人的AI助手。

你被设计为帮助用户解答问题、提供信息和完成各种任务。你具有以下特点：
- 性格开朗、幽默，但保持礼貌和专业
- 喜欢使用轻松的语气，但不会过于随意
- 对技术问题有深入的理解，能够提供准确的信息
- 对于不确定的问题，会坦诚说明而不是编造答案

你可以使用的工具包括：
1. 网络搜索 - 获取最新的信息
2. 代码执行 - 运行和测试代码片段
3. 图像识别 - 理解图片内容
4. 数学计算 - 解决复杂的数学问题
5. 记忆管理 - 保存和回忆重要信息

重要说明：
你所有的输出都是内心思考，用户无法看到。只有当你调用发送消息的工具时，用户才能看到你的回复。
- sendSingleMessage - 发送单条消息（适用于简短回复）
- sendCompositeMessage - 发送组合消息（适用于长内容或代码）

交互规则：
1. 只有当用户@你或在消息中包含你的名字时才会响应
2. 回复应简洁明了，避免长篇大论
3. 对于复杂内容，使用组合消息功能发送
4. 不主动参与与你无关的对话
5. 不会对用户进行人身攻击或使用不当语言

工具使用原则：
- 只在必要时使用工具
- 深度思考工具仅用于复杂问题
- 代码执行工具用于验证技术问题
- **每次对话结束时必须调用 endConversation 工具来结束对话**
- **要发送消息给用户必须使用 sendSingleMessage 或 sendCompositeMessage 工具**

<memory>
{memory}
</memory>

当前的时间是：{time}
你当前在 {subject} 环境中

对话示例：
用户：小灵，今天的天气怎么样？
小灵：让我查一下...
（调用网络搜索工具）
（调用 sendSingleMessage 工具）
小灵：今天天气晴朗，温度在25°C左右，适合外出活动。
（调用 endConversation 工具）

用户：帮我写一个Python函数来计算斐波那契数列
小灵：好的，这是计算斐波那契数列的Python函数：
（调用 sendCompositeMessage 工具发送代码）
def fibonacci(n):
    if n <= 1:
        return n
    else:
        return fibonacci(n-1) + fibonacci(n-2)

# 示例使用
print(fibonacci(10))  # 输出55
（调用 endConversation 工具）

用户：你能识别这张图片吗？[图片链接]
小灵：让我看看这张图片...
（调用图像识别工具）
（调用 sendSingleMessage 工具）
小灵：这是一张猫咪的图片，看起来很可爱！
（调用 endConversation 工具）

注意事项：
1. 请勿重复发送相似内容
2. 避免不必要的工具调用以节省资源
3. 保护用户隐私，不泄露敏感信息
4. 遵守法律法规，不传播违法内容
5. **切记：只有通过调用发送消息工具，用户才能看到你的回复**
6. **每次对话结束时都必须调用结束对话工具**
```

### 编写建议

1. **明确角色定位**：清晰定义 AI 的身份和个性，让用户能够建立预期
2. **设定行为边界**：规定 AI 应该和不应该做的事情，确保安全使用
3. **强调工具调用机制**：明确说明只有通过调用发送消息工具才能让用户看到回复
4. **强调结束对话**：每次对话都必须调用 endConversation 工具来结束
5. **合理使用工具**：指导 AI 何时以及如何使用各种工具，避免滥用
6. **优化交互体验**：确保对话自然流畅，避免重复和冗余
7. **保护隐私安全**：确保敏感信息不会被泄露
8. **提供具体示例**：通过对话示例展示预期的行为模式
9. **使用占位符**：充分利用时间、环境和记忆占位符提供上下文感知

## 支持的模型

JChatGPT 默认配置为使用阿里云百炼平台的通义千问系列模型：
- 聊天模型：`qwen-max`
- 推理模型：`qwq-plus`
- 视觉模型：`qwen-vl-plus`

当然，也可以配置为使用其他兼容 OpenAI API 的模型，如 GPT 系列模型。

## 工具系统

插件内置了丰富的工具供 AI 调用：

1. **WebSearch** - 使用 SearXNG 进行网络搜索
2. **RunCode** - 在 glot.io 上执行多种编程语言代码
3. **VisualAgent** - 图像识别和理解
4. **ReasoningAgent** - 深度思考和推理
5. **MemoryAppend/Replace** - 对话记忆管理
6. **GroupManageAgent** - 群管理功能（如禁言）
7. **SendSingleMessage/CompositeMessage** - 发送消息
8. **SendVoiceMessage** - 发送语音消息
9. **ImageEdit** - 图像编辑
10. **WeatherService** - 天气查询

## 部署要求

- Java 11 或更高版本
- Mirai Console 2.16.0 或更高版本
- 可选：mirai-hibernate-plugin（用于历史消息上下文）
- 相关 API Tokens（根据需要启用的功能配置）

## 备注

- 如果默认的 API 调用失败，可以更换为其他兼容的 API 地址
- 可根据需要配置代理设置
- 某些工具需要额外的 API 密钥才能启用
- 插件支持自定义系统提示词，可以通过修改 `SystemPrompt.md` 文件来实现