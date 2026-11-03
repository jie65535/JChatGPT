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

### DeepSeek 网页搜索与抓取

在 `Config.yml` 中引用 `Models.yml` 的 DeepSeek provider（`type: openai`），即可同时启用 `deepseekSearch` 和 `deepseekFetch`：

```yaml
deepSeekToolsProvider: deepseek
```

可复用聊天模型的 provider，留空则禁用。修改后执行 `/jgpt reload`。仅使用 DeepSeek 时，可清空 `searXngUrl` 和 `jinaReaderUrl` 关闭旧工具。接口和计费见 [官方指南](https://api-docs.deepseek.com/zh-cn/guides/search.md)。

### 群聊触发限制

默认开启 `requireOwnerInGroup`：只有配置的主人也在群内，JChatGPT 才允许触发群聊对话。请在 `Config.yml` 中填写主人的 QQ：

```yaml
ownerId: 123456789 # 替换为你的 QQ 号
requireOwnerInGroup: true
```

此限制优先于聊天权限，覆盖 `@Bot`、引用回复、关键词以及连续会话触发。主人未配置、QQ 号无效、不在群内或成员查询失败时，均静默忽略，不发送拒绝提示。检查使用 Overflow 提供的当前群成员信息，不使用历史联系人快照；成员变动的生效时间取决于 Overflow 的同步。

私聊仍按原有逻辑处理，聊天记录继续保存，画像相关配置、管理命令和其他插件不受此开关影响。开关限制新的消息触发，不会强制取消已经开始的模型请求或工具调用。设为 `false` 可恢复原有群聊触发行为，修改后执行 `/jgpt reload`。

升级后此开关也默认开启；未配置 `ownerId` 时，所有群聊对话触发都会被忽略。

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
/jgpt profileAnalyze <groupId> <userIds> [batches]
/jgpt profileAnalyzeGroup [groupIds] [batches]
/jgpt profileShow <groupId> <userId>
/jgpt profileCompact <groupId> [userIds]
/jgpt profileStop
```

群成员画像按 **群号 + 用户 QQ** 独立保存，包括摘要、条目、证据支持、修订和分析进度。同一人在不同群的画像互不合并；群聊自动注入和 `queryUserProfile` 只读取当前群，私聊不读取群画像。群聊中执行查看、单用户分析或压缩命令，也只能指定当前群；有命令权限的控制台或私聊管理员可明确指定群号维护。`profileCompact` 省略用户列表时，仅压缩指定群中达到门槛的画像。

首次升级到按群画像时，插件会自动将旧 `user-profile.sqlite` 做成包含已提交 WAL 数据的完整 SQLite 备份，保存在 `data/top.jie65535.mirai.JChatGPT/profile-backups/user-profile-before-group-<时间>-<唯一标识>.sqlite`，启动日志会给出实际路径。备份通过完整性与记录数量验证后，才事务性清空旧画像、证据、修订和群分析游标，建立新的按群存储。备份或迁移失败时画像功能暂时禁用，原数据保留；后续重启不会再次清空已经建立的按群画像。

备份只供管理员离线保管，运行时不会读取或重新导入。聊天历史保留，可以用 `profileAnalyzeGroup` 或 `profileAnalyze` 按群重新归纳旧记录，因此旧信息可能从其原属群的聊天记录中再次生成。此次变更仅涉及群成员画像；好感度的代号、标签、主观印象和普通会话记忆维持原有行为。

## 数据位置

- `config/top.jie65535.mirai.JChatGPT/`：模型、插件配置和系统提示词
- `data/top.jie65535.mirai.JChatGPT/chat-history.sqlite`：聊天历史、联系人快照和模型用量
- `data/top.jie65535.mirai.JChatGPT/user-profile.sqlite`：按群隔离的成员画像
- `data/top.jie65535.mirai.JChatGPT/profile-backups/`：升级前的旧画像备份
- `data/top.jie65535.mirai.JChatGPT/skills/`：Bot 沉淀的全局技能

数据库和运行时配置包含不可替代的私有数据，不要提交到 Git，也不要在没有备份时删除或重建。
