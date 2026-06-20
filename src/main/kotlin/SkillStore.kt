package top.jie65535.mirai

import java.io.File

/**
 * 技能元信息：用于索引展示，不含正文。
 * @param name 技能名（kebab-case，同时是文件名），唯一
 * @param description 一句话简介，决定 bot 何时按需加载
 */
data class SkillMeta(
    val name: String,
    val description: String,
)

/**
 * 技能存储。每个技能 = 一个带 frontmatter 的 markdown 文件，放在 data/skills/ 下，全局跨群共享。
 *
 * 文件格式：
 * ```
 * ---
 * name: kubejs-basics
 * description: KubeJS 基础语法、常见报错与排查方法
 * ---
 *
 * （正文：沉淀下来的知识/经验/提示词）
 * ```
 *
 * 索引（name + description）常驻系统提示词，正文按需通过 loadSkill 工具加载，
 * 以此实现"低上下文污染 + 可自我沉淀迭代"。
 */
object SkillStore {
    private lateinit var dir: File

    /** 内存索引缓存，key 为技能名。仅缓存元信息，正文每次按需读盘。 */
    private val index = linkedMapOf<String, SkillMeta>()

    /** 合法技能名：字母数字、下划线、连字符，防止路径穿越。 */
    private val nameRegex = Regex("^[A-Za-z0-9_-]+$")

    /**
     * 在 onEnable 中调用一次，传入插件数据目录。随后可通过 [reload] 刷新。
     */
    fun init(dataFolder: File) {
        dir = File(dataFolder, "skills")
        reload()
    }

    /** 重新扫描技能目录，重建内存索引。/jgpt reload 时调用。 */
    @Synchronized
    fun reload() {
        if (!::dir.isInitialized) return
        index.clear()
        if (!dir.exists()) {
            dir.mkdirs()
            return
        }
        dir.listFiles { f -> f.isFile && f.extension.equals("md", ignoreCase = true) }
            ?.sortedBy { it.name }
            ?.forEach { file ->
                try {
                    val (meta, _) = parse(file.readText())
                    val name = file.nameWithoutExtension
                    val desc = meta["description"].orEmpty()
                    index[name] = SkillMeta(name, desc)
                } catch (_: Exception) {
                    // 单个文件解析失败不影响其它技能加载
                }
            }
    }

    /** 当前所有技能元信息。 */
    val all: List<SkillMeta> @Synchronized get() = index.values.toList()

    /**
     * 构建注入到系统提示词 {skills} 占位符的索引文本，仅含 name + description。
     */
    @Synchronized
    fun buildIndexPrompt(): String {
        if (index.isEmpty()) return "暂无技能"
        return index.values.joinToString("\n") { "- ${it.name}: ${it.description}" }
    }

    /**
     * 读取技能正文（不含 frontmatter）。技能不存在返回 null。
     */
    @Synchronized
    fun load(name: String): String? {
        if (!isValidName(name)) return null
        val file = File(dir, "$name.md")
        if (!file.exists()) return null
        return try {
            parse(file.readText()).second
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 新增或整篇覆盖一个技能（upsert）。迭代即"读全文→改→整篇写回"。
     * @return 失败时返回错误信息，成功返回 null
     */
    @Synchronized
    fun save(name: String, description: String, content: String): String? {
        if (!isValidName(name)) {
            return "技能名非法，只能包含字母、数字、下划线、连字符：$name"
        }
        if (!::dir.isInitialized) return "技能目录未初始化"
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, "$name.md")
        val safeDesc = description.replace('\n', ' ').trim()
        val text = buildString {
            appendLine("---")
            appendLine("name: $name")
            appendLine("description: $safeDesc")
            appendLine("---")
            appendLine()
            append(content.trim())
            appendLine()
        }
        return try {
            file.writeText(text)
            index[name] = SkillMeta(name, safeDesc)
            null
        } catch (e: Exception) {
            "写入技能文件失败：${e.message}"
        }
    }

    /**
     * 删除一个技能。
     * @return 是否删除成功（技能不存在返回 false）
     */
    @Synchronized
    fun delete(name: String): Boolean {
        if (!isValidName(name)) return false
        val file = File(dir, "$name.md")
        index.remove(name)
        return if (file.exists()) file.delete() else false
    }

    private fun isValidName(name: String): Boolean = nameRegex.matches(name)

    /**
     * 解析 frontmatter。返回 (元信息键值对, 正文)。
     * 无 frontmatter 时元信息为空，正文为全文。
     */
    private fun parse(raw: String): Pair<Map<String, String>, String> {
        val text = raw.replace("\r\n", "\n")
        if (!text.startsWith("---")) {
            return emptyMap<String, String>() to text.trim()
        }
        val lines = text.split("\n")
        // 第一行是 ---，找到下一处 --- 作为 frontmatter 结束
        val endIdx = (1 until lines.size).firstOrNull { lines[it].trim() == "---" }
            ?: return emptyMap<String, String>() to text.trim()
        val meta = mutableMapOf<String, String>()
        for (i in 1 until endIdx) {
            val line = lines[i]
            val sep = line.indexOf(':')
            if (sep > 0) {
                val key = line.substring(0, sep).trim()
                val value = line.substring(sep + 1).trim()
                meta[key] = value
            }
        }
        val body = lines.subList(endIdx + 1, lines.size).joinToString("\n").trim()
        return meta to body
    }
}
