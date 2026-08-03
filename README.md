# JChatGPT

JChatGPT 是一个基于 Kotlin 的 Mirai Console 插件，它将大型语言模型（LLM）集成到即时通讯平台中。生产运行链路为 NapCat -> OneBot -> Overflow -> Mirai Console -> JChatGPT；插件使用 Mirai 兼容 API，但联系人刷新等运行时能力以 Overflow 为准。

## 功能特性

- **多模型支持**：支持聊天模型、推理模型和视觉模型
- **接入点容灾**：聊天模型可配置多个备用接入点，主接入点 key 到期 / 限流 / 服务不稳定时自动切换
- **丰富的工具系统**：包括网络搜索、代码执行、图像识别、群管理等
- **上下文记忆**：支持持久化记忆存储
- **技能系统**：Bot 可在群聊中自我沉淀可复用知识，全局跨群、按需加载、低上下文污染
- **用户画像系统**：好感度、印象、标签、Bot 自定义代号
- **渐进式历史画像**：群聊缓存闭合后，从原始上下文一次归纳所有有效参与者并持续修正长期画像
- **Token消耗统计**：按天 × 用户 × 群聚合记录，支持多维度统计查询
- **LaTeX 渲染**：自动将数学表达式渲染为图片
- **灵活的触发方式**：@机器人、关键字触发、回复消息等
- **权限控制**：细粒度的权限管理系统
- **内置历史与联系人快照**：使用插件自维护的 SQLite 保存、检索消息，并异步同步好友、群和群成员公开信息

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
- 技能管理（沉淀、加载、迭代、删除可复用知识技能）
- 聊天历史搜索（按关键词、发送者、时间范围检索群聊消息，需启用历史消息上下文）
- 用户画像查询（按当前发送者、QQ号或群名片读取已归纳的长期画像）

## 权限列表

- `JChatGPT:Chat` - 拥有该权限即可使用 bot 与 AI 对话
- `top.jie65535.mirai.jchatgpt:command.jgpt` - 拥有该权限即可使用 `/jgpt` 相关命令

## 命令列表

### 基础命令
- `/jgpt enable <contact>` - 启用目标对话权限
- `/jgpt disable <contact>` - 禁用目标对话权限
- `/jgpt reload` - 重载配置文件
- `/jgpt clearMemory` - 清空所有对话记忆
- `/jgpt clearContextCache` - 清空所有对话上下文缓存
- `/jgpt skills` - 列出当前所有技能（名称 + 简介）

### Token统计
- `/jgpt tokens [days]` - 查看最近指定天数的Token使用简报（默认7天）

### 渐进式历史画像（实验）
- 日常使用无需画像命令：群聊缓存会话闭合后，一次模型调用会静默归纳其中所有有实质发言的参与者
- `/jgpt profileAnalyze <userIds> [batches]` - 分析用户画像，多个 ID 用逗号分隔
- `/jgpt profileAnalyzeGroup [groupIds] [batches]` - 分析群画像，多个 ID 用逗号分隔；不传群号时并发推进历史库中的全部群
- `/jgpt profileShow <userId>` - 查看用户画像
- `/jgpt profileCompact [userIds]` - 压缩画像，不传 ID 时处理全部用户
- `/jgpt profileStop` - 当前批次完成后停止画像任务

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
# 聊天模型额外请求体JSON，会合并到请求体中。例如DeepSeek关闭思维: {"thinking": {"type": "disabled"}}
chatModelExtraBody: ''
# 聊天模型备用接入点列表（容灾）。主接入点连续失败时按顺序切换；每项留空的字段会继承主接入点
# 例如只换API KEY就只填token，只换模型就只填model，整体换服务商就都填
chatFallbacks: []
#   - api: 'https://api.deepseek.com/v1/'
#     token: 'sk-xxxx'
#     model: 'deepseek-chat'
#     extraBody: ''
# 是否启用实验性的历史用户画像分析
profileEnabled: true
# 画像模型接入点；以下字段留空时分别继承聊天模型配置
profileModelApi: ''
profileModelToken: ''
profileModel: ''
profileModelTemperature: null
profileModelExtraBody: ''
# 留空使用插件自己的聊天库；本地实验可填写外部SQLite历史库的绝对路径
profileHistoryDatabasePath: ''
# 异步刷新好友、群、群成员联系人快照，写入 chat-history.sqlite
contactSnapshotEnabled: true
contactSnapshotInitialDelaySeconds: 30
contactSnapshotRefreshIntervalMinutes: 1440
contactSnapshotGroupDelayMillis: 200
# 每批目标用户消息数、片段软间隔及上下文限制
profileBatchTargetMessages: 120
profileBatchMaxEpisodes: 16
profileEpisodeGapMinutes: 60
profileContextBeforeMessages: 30
profileContextAfterMessages: 30
profileContextCoreMessages: 300
profileMaxMessageChars: 1000
# 模型结构化响应失败后的重试次数（0~3）和短摘要上限
profileRetryMax: 2
profileSummaryMaxLength: 500
# 缓存会话闭合后自动维护画像；整段会话只调用一次模型
profileAutoUpdateEnabled: true
# 一次自动归纳最多读取最近150条会话消息
profileAutoConversationMessageLimit: 150
# 本人有效文本少于此字符数时不调用画像模型
profileAutoMinAuthoredTextChars: 20
# 普通群聊自动携带相关群友的好感度状态和短画像
profileAutoInjectEnabled: true
profileAutoInjectMaxUsers: 4
profileAutoInjectSummaryMaxChars: 300
# 备用接入点冷却时间（分钟）。某接入点失败后在此时间内会被排到重试队尾，避免每条消息都先卡在故障接入点上。0为禁用
fallbackCooldownMinutes: 5
# 对话、画像和视觉模型失败后的统一退避：首轮基础毫秒数、单次最大毫秒数；设为0可禁用
# 实际等待会按失败次数指数增长，并加入20%以内的随机抖动，两个值最大均为60000
retryBackoffBaseMillis: 1000
retryBackoffMaxMillis: 10000
# 推理模型额外请求体JSON，会合并到请求体中。例如DeepSeek启用思维: {"thinking": {"type": "enabled"}}
reasoningModelExtraBody: ''
# 视觉模型额外请求体JSON，会合并到请求体中。
visualModelExtraBody: ''
# 是否先由机器人下载视觉图片并以Base64上传；建议保持开启，避免百炼下载QQ临时链接失败
visualImageBase64Enabled: true
# 视觉模型最大尝试次数，取值1~3；重试时复用已下载的图片
visualRetryMax: 2
# 百炼平台API KEY
dashScopeApiKey: ''
# 百炼平台图像模型（文生图 + 图像编辑）
imageModel: 'qwen-image-2.0'
# 是否在生成图片右下角添加 Qwen-Image 水印
imageWatermark: false
# 百炼平台TTS模型，qwen3-tts-instruct-flash 支持 instructions 指令控制语气
ttsModel: 'qwen3-tts-instruct-flash'
# Jina API Key
jinaApiKey: ''
# SearXNG 搜索引擎地址，如 http://127.0.0.1:8080/search 必须启用允许json格式返回
searXngUrl: ''
# 在线运行代码 glot.io 的 api token，在官网注册账号即可获取。
glotToken: ''
# 和风天气专属 API Host，可在和风天气控制台的设置页面查看
qWeatherApiHost: ''
# 和风天气项目 ID
qWeatherProjectId: ''
# 和风天气 JWT 凭据 ID
qWeatherCredentialId: ''
# Ed25519 私钥路径，相对于插件配置目录，也可以填写绝对路径
qWeatherPrivateKeyPath: 'qweather-ed25519-private.pem'
# 群管理是否自动拥有对话权限，默认是
groupOpHasChatPermission: true
# 好友是否自动拥有对话权限，默认是
friendHasChatPermission: true
# 机器人是否可以禁言别人，默认禁止
canMute: false
# 群荣誉等级权限门槛，达到这个等级相当于自动拥有对话权限。
temperaturePermission: 50
# 等待响应超时时间（整个请求的总超时与socket读超时），单位毫秒，默认60秒
timeout: 60000
# 首块响应超时时间，单位毫秒，默认10秒。若连接建立后在此时间内没收到首块data:则中断走重试
firstChunkTimeout: 10000
# 画像模型首块响应超时时间
profileFirstChunkTimeout: 180000
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
# 是否启用技能系统，技能存在data/skills目录（全局跨群），提示词中需要加上{skills}来注入技能索引
skillsEnabled: true
# 是否启用好感度系统
enableFavorabilitySystem: true
# 聊天记录搜索最大天数
searchHistoryMaxDays: 30
# 聊天记录搜索最大查询条数，防止内存溢出
searchHistoryMaxRecords: 5000
```

聊天记录与联系人快照保存在插件数据目录的 `chat-history.sqlite` 中，并使用 SQLite WAL 模式支持记录与查询并行进行。
数据库由插件在首次启动时自动创建和维护，无需安装额外的聊天记录插件。

联系人刷新在后台通过 Overflow 的公开 `RemoteBot.executeAction` 调用 OneBot
`get_friend_list`、`get_group_list` 和 `get_group_member_list`，不使用反射，也不依赖 Overflow 的 internal
实现类。默认启动延迟 30 秒后刷新、此后每 24 小时刷新一次；单个群失败时保留上一版成员快照，完整刷新成功后
会淘汰已经删除的好友关系和已经离群的成员关系。周期任务不会逐人调用 `queryProfile()`，避免对数万群成员
产生高频资料卡请求和账号风控风险。

### 渐进式历史画像

画像维护不需要群友执行命令。Bot 成功完成一轮群聊后，系统沿用上下文缓存的超时时间进行防抖；期间再次
触发会合并为同一会话，缓存真正闭合时还会纳入 Bot 回复后的群聊消息。后台读取最近至多 150 条消息，只把
本人有效文本达到门槛的账号列为候选，然后用一次模型调用同时比较所有候选人的当前画像并返回按用户分组的
`ADD / UPDATE / CONFIRM / DELETE` 操作。没有可靠变化的参与者仍会被标记为已检查，但不会生成空洞画像。

模型只使用单次请求内有效的临时编号，不接触画像条目的内部 UUID；不合规建议会被跳过，不阻断其他有效更新。
临时用户别名不会写入最终画像。`profileCompact` 可独立清理重复或低价值条目，且不推进历史水位线。
旧历史可通过 `profileAnalyze` 或 `profileAnalyzeGroup` 手动分批推进。执行不带参数的 `profileAnalyzeGroup` 时，
插件会从画像历史库枚举所有含有效群消息的群，并为每个群同时启动一个分析任务，不设置额外的应用层并发上限；
显式传入群号时仍只分析指定群。全量模式仅发送启动和最终汇总，逐群进度与结果写入日志，避免回执刷屏。

下一次正常群聊会自动携带触发者和最近发言者的认识。现有好感度、Bot 代号、标签和主观印象会与证据驱动的
长期画像按同一个人合并渲染，并明确给出长期画像条目数；私聊也会携带对方的可靠画像摘要和条目数。
模型需要完整细节时可主动调用 `queryUserProfile`，按 QQ 号、群名片、昵称或好友备注读取画像。两套画像数据
仍独立保存，自动画像不会修改好感度。

`queryUserProfile` 解析出唯一联系人后才按需调用一次 `queryProfile()`，并在内存中缓存 10 分钟；周期联系人
同步不会触发该调用，因此群成员总量不会放大资料卡请求数。

好友昵称、群名片、群角色、签名、年龄等联系人公开资料只作为人物识别提示，不直接视为画像证据。画像模型
仍必须依据对应用户本人在聊天历史中的发言，才能新增或确认长期画像条目。

画像发生变更时，日志会输出 `PROFILE_OPERATIONS` 供抽查。

画像结果保存在插件数据目录的 `user-profile.sqlite`，不会覆盖现有好感度或旧印象数据。实验时可把历史
备份配置为只读来源，例如：

```yaml
profileHistoryDatabasePath: 'D:\backups\chat-history.sqlite'
profileModelApi: 'https://example.com/v1/'
profileModelToken: '在部署环境中填写，不要提交到Git'
profileModel: '兼容chat/completions的模型名'
```

执行 `/jgpt reload` 后即可自动运行。画像提示词由插件内置；相关命令仅用于人工验收。外部历史库始终只读。

### 和风天气

天气工具使用[和风天气开发服务](https://dev.qweather.com/docs/start/)和 JWT 凭据，简单配置流程如下：

1. 注册和风天气开发者帐号，在控制台创建项目，并在“设置”中查看专属 API Host。
2. 使用 OpenSSL 在本地生成 Ed25519 密钥：

   ```bash
   openssl genpkey -algorithm ED25519 -out qweather-ed25519-private.pem
   openssl pkey -pubout -in qweather-ed25519-private.pem -out qweather-ed25519-public.pem
   ```

3. 在项目中添加凭据，认证方式选择 `JSON Web Token`，上传公钥并勾选需要使用的天气、GeoAPI 和预警接口。
4. 将私钥放到插件配置目录，例如 `config/top.jie65535.mirai.JChatGPT/qweather-ed25519-private.pem`，然后在 `Config.yml` 填写 API Host、项目 ID、凭据 ID 和私钥路径。

私钥只保存在部署机器上，请勿上传或提交到 Git。配置不完整时天气工具不会启用，修改后执行 `/jgpt reload`。

天气工具支持实时天气、每日预报、逐小时预报、分钟级降水和官方天气预警。

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
- `{skills}` - 全局技能索引（仅名称 + 一句话简介，正文按需加载）

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

### 视觉图片传输

视觉工具支持单图和多图，默认由机器人下载后以 Base64 上传，长图会按顺序切片，避免百炼无法下载 QQ 临时图片链接。如兼容服务不支持 Base64，可将 `visualImageBase64Enabled` 设为 `false`。

## 接入点容灾

聊天模型支持配置多个**备用接入点**，当主接入点连续调用失败（key 到期、用量超限、服务不稳定、超时等）时自动切换，提升可用性。

### 配置方式

在 `chatFallbacks` 中按优先级顺序列出备用接入点。每项的任意字段**留空则继承主接入点**对应配置，因此三种容灾场景都覆盖：

```yaml
chatFallbacks:
  # 只换 API KEY（同服务商备用 key）
  - token: 'sk-备用key'
  # 只换模型（同接入点降级到更稳定/更便宜的模型）
  - model: 'qwen-plus'
  # 整体换一个服务商
  - api: 'https://api.deepseek.com/v1/'
    token: 'sk-deepseek-xxxx'
    model: 'deepseek-chat'
    extraBody: ''
fallbackCooldownMinutes: 5
```

### 工作机制

- 主接入点为列表首位，备用接入点按配置顺序排在其后。
- **单次对话内**：某接入点流式调用失败时，经短暂指数退避后切换到下一个接入点重试，而非反复重试同一个故障点。
- **跨对话冷却**：失败的接入点进入冷却期（`fallbackCooldownMinutes` 分钟），冷却期内会被排到重试队尾。这样主接入点 key 到期后，后续消息会直接走健康的备用接入点，不必每条都先卡一次超时。调用成功或冷却到期后自动恢复。
- 仅在**LLM 调用本身失败**时才切换接入点，后续工具执行异常不会误判正常接入点为故障。
- 备用接入点切换只作用于聊天模型；推理模型和视觉模型仍使用各自接入点，其中视觉模型自身的失败重试也使用统一退避。
- `/jgpt reload` 会重建接入点列表并清空冷却状态。

## 工具系统

插件内置了丰富的工具供 AI 调用：

1. **WebSearch** - 使用 SearXNG 进行网络搜索
2. **RunCode** - 在 glot.io 上执行多种编程语言代码
3. **VisualAgent** - 图像识别和理解
4. **ReasoningAgent** - 深度思考和推理
5. **MemoryAppend/Replace** - 对话记忆管理
6. **LoadSkill/SaveSkill/DeleteSkill** - 技能管理（加载、沉淀/迭代、删除全局技能）
7. **GroupManageAgent** - 群管理功能（如禁言）
8. **SendSingleMessage/CompositeMessage** - 发送消息
9. **SendVoiceMessage** - 发送语音消息
10. **ImageAgent** - 图像生成与编辑（文生图、单图编辑、多图融合）
11. **WeatherService** - 天气查询
12. **SearchChatHistory** - 按关键词、发送者、时间范围搜索插件内置 SQLite 聊天历史
13. **QueryUserProfile** - 按当前发送者、QQ 号、群名片、昵称或好友备注读取长期画像，并针对该联系人按需读取公开资料卡

## 用户画像系统

JChatGPT 维护对每位用户的画像，由好感度、Bot 自定义代号、最多 5 个标签和印象文本组成。模型可以通过 `adjustUserFavorability` 工具在交流后增量更新这些字段（同一次调用可以同时调好感度、加 tag、改印象，不必分开）。

### 字段
- **value（好感度）**：范围 -100（完全不理会）到 100（非常好的朋友）
- **name（代号）**：Bot 给此人起的内部代号，区别于 QQ 昵称，长度 ≤20
- **tags（标签）**：最多 5 个简短标签，记录身份/职业/偏好/技术栈等
- **impression（印象）**：自由文本，长度 ≤200
- **reasons（调整原因）**：只有 change ≠ 0 的调整才会追加，保留最近 10 条

### 好感度机制
- 负好感度用户有一定概率不会收到回复，概率 = |好感度| / 100
- 好感度不会随时间自动偏移，只会由模型工具显式调整
- 好感度为 0 时仍会保留该用户的代号、标签、印象和调整原因

### 注入到上下文
- 群聊：合并列出当前相关群友的关系状态，以及可靠长期画像的摘要和条目数
- 私聊：合并注入对方的关系状态，以及可靠长期画像的摘要和条目数
- 模型需要画像明细时主动调用 `queryUserProfile`，不必把全部条目常驻在上下文中
- 只有好感度数值、其它关系字段和可靠长期画像均为空的用户不会被列出，避免提示词噪声

### 配置选项
- `enableFavorabilitySystem` - 是否启用画像系统（默认：true）

## 技能系统

JChatGPT 允许 Bot 在群聊中**自我沉淀可复用的知识和经验**，存成"技能"（本质是带简介的提示词文档），并在需要时按需加载。例如群友反复问到某个软件/模组的用法、常见报错排查，Bot 在回答或被纠正的过程中学到的内容可以沉淀成技能，跨群复用。

### 设计要点
- **全局跨群**：技能不按群隔离，任何群学到的都能在其它群复用。
- **低上下文污染**：日常对话只在系统提示词里常驻"技能名 + 一句话简介"的索引，正文不进上下文。
- **按需加载**：当话题命中某个技能时，Bot 才用 `loadSkill` 把正文读入上下文。
- **自我迭代**：Bot 通过 `saveSkill` 沉淀/更新技能，过时的用 `deleteSkill` 删除，无需人工介入（也支持手动编辑文件）。

### 存储格式
每个技能 = `data/skills/` 下的一个 markdown 文件，带 frontmatter：

```markdown
---
name: kubejs-basics
description: KubeJS 基础语法、常见报错与排查方法
---

（正文：沉淀下来的知识、经验或提示词）
```

技能名为 kebab-case，只能包含字母、数字、下划线、连字符（用于校验防止路径穿越）。

### 相关工具
- **loadSkill(name)** - 加载某技能正文到上下文
- **saveSkill(name, description, content)** - 新增或整篇覆盖一个技能（迭代 = 先 loadSkill 读全文，改好后同名写回）
- **deleteSkill(name)** - 删除过时或失效的技能

### 配置与命令
- 配置项 `skillsEnabled`（默认 true）控制是否启用技能系统
- 系统提示词中需包含 `{skills}` 占位符以注入技能索引
- `/jgpt skills` - 列出当前所有技能；`/jgpt reload` 会重新扫描技能目录

## Token消耗统计

JChatGPT 按 (日期, userId, groupId) 三元组聚合每次对话的 Token 消耗，并通过一条命令提供汇总、趋势和排名信息。

> 历史版本曾按每次请求逐条记录，但增长不受控（数千条后会触发 mamoe-yamlkt 的编/解码 bug 导致整个 data.yml 无法加载）。现已改为按天聚合并搬到独立的 `token_usage.json`，data.yml 只保留小规模的 memory / favorability 数据。

### 功能特性
- **自动记录**：每次对话累加到当日聚合行
- **聚合维度**：日期 × 用户 × 群（同一人同一天在同一群只占一行）
- **一屏简报**：同时展示用量汇总、缓存命中率、每日趋势及用户/群组排名
- **隐私保护**：群组排名仅显示群名，不显示群号

### 记录内容
每条聚合记录包含：
- 日期（yyyy-MM-dd，本地时区）
- 用户QQ号和最近一次记录到的昵称
- 群组ID和群名（群聊），私聊时为null
- 当日累计输入Token数（promptTokens）
- 当日累计输出Token数（completionTokens）
- 当日累计总Token数（totalTokens）
- 当日累计缓存命中Token数（cachedTokens）
- 当日调用次数（callCount）

### 统计命令

```
/jgpt tokens [days]
```
- `days` 必须为正整数，默认为7，时间范围包含当天
- 汇总输入、输出、总Token数、调用次数、活跃用户数和当日Token数
- 输入统计包含缓存命中率及命中Token数
- 有多个日期的数据时显示每日趋势
- 显示时间范围内Token消耗最高的5名用户和5个群组；群组仅显示名称，不显示群号
- 大于等于1,000的Token数会使用 `K` 或 `M` 缩写
- 输出示例：
  ```
  📊 Token 简报 · 最近 7 天

  输入 923.4K（缓存命中 38.2%，省 352.8K）
  输出 528.9K
  总计 1.45M ｜ 调用 186 次 ｜ 活跃 15 人
  今日 215.4K

  📈 每日趋势
    03-16  391.2K
    03-17  526.7K
    03-18  534.4K

  👤 Top 用户
    1. 张三  523.5K
    2. 李四  318.2K

  👥 Top 群组
    1. 技术交流群  876.5K
    2. 闲聊群  295.3K
  ```

### 数据存储
- Token 聚合记录保存在插件数据目录的 `token_usage.json` 文件中
- 由 `TokenUsageStore` 直接管，绕开 mamoe 的 plugin data 系统（避免 yamlkt 在大数据量下的编/解码 bug）
- 每次记录后写盘（先写 `.tmp` 再覆盖，避免半文件）
- 加载失败会自动备份原文件为 `token_usage.json.broken-<timestamp>` 并从空开始
- 记录永久保存，不会自动删除
- 数据格式为JSON，可手动查看和备份

### 使用场景
- **成本监控**：了解API调用成本，控制预算
- **使用分析**：分析哪些用户或群组使用最频繁
- **性能优化**：识别高消耗对话，优化提示词
- **趋势分析**：观察使用趋势，规划资源

### 注意事项
- 仅统计聊天模型的Token消耗
- 推理模型和视觉模型的消耗不在统计范围内
- 同一用户同一天在同一群的多次调用合并为一行（callCount 自增）
- 统计数据基于实际API返回的Token数
- 缓存命中统计取自兼容接口返回的 `prompt_cache_hit_tokens`；接口未返回时按0计算

## 部署要求

- Java 11 或更高版本
- Mirai Console 2.16.0 或更高版本
- Overflow（生产运行时；由 OneBot 对接 NapCat）
- 相关 API Tokens（根据需要启用的功能配置）

## 备注

- 如果默认的 API 调用失败，可以更换为其他兼容的 API 地址
- 可根据需要配置代理设置
- 某些工具需要额外的 API 密钥才能启用
- 插件支持自定义系统提示词，可以通过修改 `SystemPrompt.md` 文件来实现
