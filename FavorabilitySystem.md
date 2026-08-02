# 好感度与主观印象系统

## 功能定位

好感度系统保存 Bot 对群友的主观认识，与证据驱动的长期用户画像互补：

- `FavorabilityInfo` 保存好感度、代号、标签、印象和最近调整原因；
- `user-profile.sqlite` 保存从历史聊天归纳出的长期画像；
- 两套数据独立持久化，在普通对话上下文中按用户合并渲染；
- 自动历史画像不会修改主观好感度。

## 数据结构

```kotlin
@Serializable
data class FavorabilityInfo(
    val userId: Long,
    val value: Int = 0,
    val reasons: List<String> = emptyList(),
    val impression: String = "",
    val name: String = "",
    val tags: List<String> = emptyList(),
)
```

字段约束：

- `value`：限制在 -100 到 100；
- `reasons`：只在好感度发生变化且提供原因时追加，保留最近 10 条；
- `impression`：最多 200 字符；
- `name`：最多 20 字符；
- `tags`：最多 5 项，每项最多 20 字符。

数据继续保存在 Mirai `data.yml` 中，已有字段保持兼容。

## AI 更新工具

`adjustUserFavorability` 是唯一的好感度和主观印象维护入口。模型可以在一次调用中：

- 通过 `change` 增减好感度；
- 覆盖 `impression` 或 `name`；
- 通过 `tags_add`、`tags_remove` 调整标签。

只更新印象或标签时，`change` 默认为 0。系统不会因为好感度为 0 而删除用户记录。

## 回复门控

启用 `enableFavorabilitySystem` 后，负好感度会降低对应用户触发 Bot 回复的概率：

```text
忽略概率 = abs(value) / 100
```

好感度不再随时间自动向 0 偏移，也没有管理员手动修改或清空好感度的命令。它只会在模型明确调用工具时变化。

## 上下文注入

普通聊天会把当前相关用户的主观认识和长期画像合并为紧凑文本：

- 群聊优先选择触发者和最近发言者；
- 私聊只注入当前联系人；
- 仅有数值、没有代号/标签/印象的空记录不会制造提示词噪声；
- 好感度为 0 但仍有代号、标签或印象的记录继续正常注入。

长期画像的生成、证据校验和自动维护流程参见 `ProfileSystemDesign-v3.md`。
