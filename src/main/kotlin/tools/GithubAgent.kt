package top.jie65535.mirai.tools

import com.aallam.openai.api.chat.Tool
import com.aallam.openai.api.core.Parameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import top.jie65535.mirai.config.PluginConfig
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/**
 * Provides a single, authenticated, read-only entry point to the GitHub CLI.
 *
 * The model can use the breadth of gh's query commands without receiving a
 * general shell. Only read-oriented command families are accepted below.
 */
class GithubAgent : BaseAgent(
    tool = Tool.function(
        name = "github",
        description = "使用已认证的 GitHub CLI 只读查询 GitHub。可读取用户、仓库、代码、Issue、" +
                "Pull Request、Release 和 Actions；args 是不包含 gh 的参数数组，可多轮先搜索再查看详情，" +
                "优先使用 --json。禁止写入、登录、扩展、本地仓库操作和非 GitHub API 访问。",
        parameters = Parameters.buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("args") {
                    put("type", "array")
                    put("minItems", 1)
                    put("maxItems", MAX_ARGUMENTS)
                    putJsonObject("items") {
                        put("type", "string")
                    }
                    put(
                        "description",
                        "gh 命令参数数组，不含 gh。例如 [\"repo\", \"view\", \"owner/repo\", \"--json\", \"name,description,url\"]"
                    )
                }
            }
            putJsonArray("required") {
                add("args")
            }
        }
    )
) {
    override val isEnabled: Boolean
        get() = PluginConfig.githubToken.isNotBlank() && PluginConfig.githubCliPath.isNotBlank()

    override val loadingMessage: String
        get() = "查询 GitHub 中..."

    override suspend fun execute(args: JsonObject?): String {
        requireNotNull(args)
        val argsJson = args["args"] as? JsonArray
            ?: return "GitHub 工具参数错误：args 必须是字符串数组"
        if (argsJson.isEmpty()) return "GitHub 工具参数错误：args 不能为空"

        val cliArgs = buildList {
            argsJson.forEachIndexed { index, value ->
                val primitive = value as? JsonPrimitive
                    ?: return "GitHub 工具参数错误：args[$index] 必须是字符串"
                if (!primitive.isString) {
                    return "GitHub 工具参数错误：args[$index] 必须是字符串"
                }
                val content = primitive.contentOrNull
                    ?: return "GitHub 工具参数错误：args[$index] 必须是字符串"
                add(content)
            }
        }

        val validationError = validateReadOnlyArgs(cliArgs)
        if (validationError != null) return "GitHub 工具拒绝执行：$validationError"

        return runCli(cliArgs)
    }

    private suspend fun runCli(args: List<String>): String = withContext(Dispatchers.IO) {
        coroutineScope {
            val executable = PluginConfig.githubCliPath.trim()
            val process = try {
                ProcessBuilder(listOf(executable) + args)
                    .redirectErrorStream(true)
                    .apply {
                        environment()["GH_TOKEN"] = PluginConfig.githubToken
                        environment()["GH_PROMPT_DISABLED"] = "1"
                        environment()["GH_PAGER"] = ""
                        environment()["NO_COLOR"] = "1"
                    }
                    .start()
            } catch (e: IOException) {
                return@coroutineScope "无法启动 GitHub CLI，请检查 githubCliPath 和 gh 安装：${e.message}"
            }
            process.outputStream.close()

            val outputReader = async(Dispatchers.IO) { captureOutput(process) }
            val finished = process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                runCatching { process.inputStream.close() }
                outputReader.cancelAndJoin()
                return@coroutineScope "GitHub CLI 执行超时（超过 ${PROCESS_TIMEOUT_SECONDS} 秒）"
            }

            val captured = outputReader.await()
            val suffix = if (captured.truncated) {
                "\n\n[GitHub CLI 输出已截断，原始结果超过 ${MAX_OUTPUT_BYTES} 字节]"
            } else {
                ""
            }
            if (process.exitValue() == 0 || captured.truncated) {
                (captured.content.ifEmpty { "GitHub CLI 未返回内容" }) + suffix
            } else {
                "GitHub CLI 执行失败（退出码 ${process.exitValue()}）：\n" +
                        (captured.content.ifEmpty { "未返回错误信息" }) + suffix
            }
        }
    }

    private fun captureOutput(process: Process): CapturedOutput {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var totalBytes = 0
        var truncated = false
        try {
            while (true) {
                val count = process.inputStream.read(buffer)
                if (count < 0) break
                val accepted = minOf(count, MAX_OUTPUT_BYTES - totalBytes)
                if (accepted > 0) {
                    output.write(buffer, 0, accepted)
                    totalBytes += accepted
                }
                if (totalBytes >= MAX_OUTPUT_BYTES) {
                    truncated = true
                    process.destroy()
                    break
                }
            }
        } catch (_: IOException) {
            // The stream may close while the process is terminated for a timeout or output cap.
        }
        return CapturedOutput(
            content = output.toString(StandardCharsets.UTF_8.name()).trim(),
            truncated = truncated,
        )
    }

    companion object {
        internal const val MAX_ARGUMENTS = 48
        internal const val MAX_ARGUMENT_LENGTH = 1000
        internal const val MAX_TOTAL_ARGUMENT_LENGTH = 8000
        internal const val MAX_OUTPUT_BYTES = 512 * 1024
        private const val PROCESS_TIMEOUT_SECONDS = 30L
        private val searchCommands = setOf("repos", "code", "issues", "prs", "commits")
        private val readSubcommands = mapOf(
            "repo" to setOf("list", "view"),
            "issue" to setOf("list", "status", "view"),
            "pr" to setOf("checks", "diff", "list", "status", "view"),
            "release" to setOf("list", "view"),
            "run" to setOf("list", "view"),
            "workflow" to setOf("list", "view"),
            "gist" to setOf("list", "view"),
            "org" to setOf("list"),
        )
        private val blockedArguments = setOf(
            "--web",
            "--hostname",
            "--method",
            "-X",
            "--input",
            "--raw-field",
            "--field",
            "-f",
            "-F",
            "--header",
            "-H",
            "--cache",
        )

        /** Returns null for an accepted read-only command, otherwise a user-readable rejection reason. */
        internal fun validateReadOnlyArgs(args: List<String>): String? {
            if (args.isEmpty()) return "args 不能为空"
            if (args.size > MAX_ARGUMENTS) return "参数数量不能超过 $MAX_ARGUMENTS"
            if (args.any { it.isEmpty() }) return "参数不能包含空字符串"
            if (args.any { it.length > MAX_ARGUMENT_LENGTH }) {
                return "单个参数长度不能超过 $MAX_ARGUMENT_LENGTH 个字符"
            }
            if (args.sumOf { it.length } > MAX_TOTAL_ARGUMENT_LENGTH) {
                return "参数总长度不能超过 $MAX_TOTAL_ARGUMENT_LENGTH 个字符"
            }
            if (args.any { it.any(Char::isISOControl) }) return "参数不能包含控制字符"

            val blocked = args.firstOrNull { argument ->
                val option = argument.substringBefore('=')
                option in blockedArguments
            }
            if (blocked != null) return "不允许使用参数 $blocked"

            val accepted = when (args.first()) {
                "help" -> args.size <= 2
                "search" -> args.getOrNull(1) in searchCommands
                else -> readSubcommands[args.first()]?.contains(args.getOrNull(1)) == true
            }
            if (!accepted && args.first() != "api") {
                return "只允许 GitHub 搜索和读取类 gh 子命令"
            }

            validateLimit(args)?.let { return it }
            if (args.first() == "api") return validateApiArgs(args)
            return null
        }

        private fun validateLimit(args: List<String>): String? {
            args.forEachIndexed { index, argument ->
                if (argument == "--limit") {
                    val value = args.getOrNull(index + 1)
                        ?: return "--limit 缺少数值"
                    val limit = value.toIntOrNull()
                        ?: return "--limit 必须是整数"
                    if (limit !in 1..MAX_RESULTS) return "--limit 必须在 1 到 $MAX_RESULTS 之间"
                } else if (argument.startsWith("--limit=")) {
                    val limit = argument.substringAfter('=').toIntOrNull()
                        ?: return "--limit 必须是整数"
                    if (limit !in 1..MAX_RESULTS) return "--limit 必须在 1 到 $MAX_RESULTS 之间"
                }
            }
            return null
        }

        private fun validateApiArgs(args: List<String>): String? {
            val endpoint = args.getOrNull(1)
                ?: return "gh api 需要提供 endpoint"
            if (!isRelativeRestEndpoint(endpoint)) {
                return "gh api 仅允许访问相对 GitHub REST endpoint"
            }

            var index = 2
            while (index < args.size) {
                when (args[index]) {
                    "--jq", "--template" -> {
                        if (args.getOrNull(index + 1).isNullOrBlank()) {
                            return "${args[index]} 缺少表达式"
                        }
                        index += 2
                    }

                    "--paginate", "--slurp" -> index++

                    else -> return "gh api 只允许 endpoint、--jq、--template、--paginate 和 --slurp"
                }
            }
            return null
        }

        private fun isRelativeRestEndpoint(endpoint: String): Boolean {
            val path = endpoint.substringBefore('?')
            if (path.isBlank() || path.startsWith("-") || path.startsWith('/') || path.startsWith('\\')) return false
            if (path.contains("://") || path == "graphql" || path.startsWith("graphql/") || endpoint.contains('\\')) {
                return false
            }
            return path.split('/').none { it.isBlank() || it == "." || it == ".." }
        }

        private const val MAX_RESULTS = 50

        private data class CapturedOutput(
            val content: String,
            val truncated: Boolean,
        )
    }
}
