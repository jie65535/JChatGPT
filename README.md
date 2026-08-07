# JChatGPT

JChatGPT 是一个基于 Kotlin 的 Mirai Console 插件，为 QQ 提供 LLM 对话、工具调用、持久记忆、技能、用户画像、图片处理、用量统计和聊天历史检索。

生产运行链路：

```text
NapCat -> OneBot -> Overflow -> Mirai Console -> JChatGPT
```

插件使用 Mirai 兼容 API，但实际能力和兼容性以 Overflow 为准。

## 主要能力

- 统一配置聊天、画像、推理、视觉、网页摘要、图像和 TTS 模型
- 聊天模型多接入点容灾、失败退避和冷却
- 网络搜索、网页读取、GitHub 查询、代码执行、视觉、天气、群管理等工具
- 持久记忆、全局技能和渐进式用户画像
- QQ 图片理解、图片生成、语音发送和 LaTeX 渲染
- SQLite 聊天历史、联系人快照、全文检索和模型用量统计
- `@Bot`、引用回复、关键字和连续会话触发

## 构建与部署

构建需要让 `JAVA_HOME` 指向 JDK 17，产物保持 Java 11 兼容：

```powershell
.\gradlew.bat build buildPlugin --console=plain
```

插件产物位于：

```text
build/mirai/JChatGPT-<version>.mirai2.jar
```

运行环境需要 Mirai Console 2.16.0、Overflow，以及通过 OneBot 连接的 NapCat。将插件放入 Mirai Console 的 `plugins/` 后启动一次，插件会自动生成配置文件。

## 最小配置

模型与凭据写入：

```text
config/top.jie65535.mirai.JChatGPT/Models.yml
```

最小示例：

```yaml
providers:
  - name: deepseek
    type: openai
    api: 'https://api.deepseek.com/v1/'
    token: 'sk-xxxx'

models:
  - name: chat-main
    provider: deepseek
    model: deepseek-chat
```

然后在同目录的 `Config.yml` 中绑定用途：

```yaml
chatModelAlias: chat-main
chatFallbackModelAliases: []
```

修改配置后执行 `/jgpt reload`。其他模型、工具和实验功能按需配置，缺少依赖或凭据的可选工具不会启用。

## 使用

- 群聊中 `@Bot`，或回复 Bot 的消息
- 使用 `callKeyword` 配置的关键字触发
- 引用群友消息并 `@Bot`，让模型读取引用内容

主要权限：

- `JChatGPT:Chat`
- `top.jie65535.mirai.jchatgpt:command.jgpt`

常用命令：

```text
/jgpt enable <contact>
/jgpt disable <contact>
/jgpt reload
/jgpt clearMemory
/jgpt clearContextCache
/jgpt skills
/jgpt tokens [days]
```

画像维护命令：

```text
/jgpt profileAnalyze <userIds> [batches]
/jgpt profileAnalyzeGroup [groupIds] [batches]
/jgpt profileShow <userId>
/jgpt profileCompact [userIds]
/jgpt profileStop
```

## 数据位置

- `config/top.jie65535.mirai.JChatGPT/`：模型、插件配置和系统提示词
- `data/top.jie65535.mirai.JChatGPT/chat-history.sqlite`：聊天历史、联系人快照和模型用量
- `data/top.jie65535.mirai.JChatGPT/skills/`：Bot 沉淀的全局技能

数据库和运行时配置包含不可替代的私有数据，不要提交到 Git，也不要在没有备份时删除或重建。

## 开发验证

```powershell
.\gradlew.bat test --console=plain
.\gradlew.bat build buildPlugin --console=plain
```

涉及事件、联系人、撤回或群元数据的行为，最终仍需在 NapCat / OneBot / Overflow 实际链路中验证。
