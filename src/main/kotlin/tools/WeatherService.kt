package top.jie65535.mirai.tools

import com.aallam.openai.api.chat.Tool
import com.aallam.openai.api.core.Parameters
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.*
import net.i2p.crypto.eddsa.EdDSAEngine
import net.i2p.crypto.eddsa.EdDSAPrivateKey
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
import top.jie65535.mirai.JChatGPT
import top.jie65535.mirai.config.PluginConfig
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Instant
import java.util.Base64

class WeatherService : BaseAgent(
    tool = Tool.function(
        name = "queryWeather",
        description = "查询指定地区的和风天气数据，包括实时天气、每日预报、逐小时预报、分钟级降水和正在生效的官方天气预警。" +
                "普通天气查询也会同时返回当地正在生效的预警。",
        parameters = Parameters.buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("city") {
                    put("type", "string")
                    put("description", "城市、区县或地区名称，如\"深圳市\"、\"深圳南山区\"")
                }
                putJsonObject("adm") {
                    put("type", "string")
                    put("description", "可选的上级行政区名称，用于区分重名地区，如\"北京市\"、\"广东省\"")
                }
                putJsonObject("query_type") {
                    put("type", "string")
                    putJsonArray("enum") {
                        add("now")
                        add("daily")
                        add("hourly")
                        add("minutely")
                        add("warning")
                    }
                    put("description", "查询类型：实时天气、每日预报、逐小时预报、未来2小时分钟级降水或官方天气预警，默认now")
                }
                putJsonObject("range") {
                    put("type", "string")
                    putJsonArray("enum") {
                        add("3d")
                        add("7d")
                        add("10d")
                        add("15d")
                        add("30d")
                        add("24h")
                        add("72h")
                        add("168h")
                    }
                    put("description", "daily或hourly的预报范围；daily默认3d，hourly默认24h")
                }
            }
            putJsonArray("required") {
                add("city")
            }
        }
    )
) {
    companion object {
        private const val JWT_LIFETIME_SECONDS = 900L
        private const val JWT_REFRESH_AHEAD_SECONDS = 60L
        private val DAILY_RANGES = setOf("3d", "7d", "10d", "15d", "30d")
        private val HOURLY_RANGES = setOf("24h", "72h", "168h")
        private val json = Json { ignoreUnknownKeys = true }
    }

    @Volatile
    private var cachedJwt: String? = null

    @Volatile
    private var cachedJwtExpiresAt: Long = 0L

    @Volatile
    private var cachedJwtConfig: String = ""

    private val jwtLock = Any()

    override val isEnabled: Boolean
        get() = PluginConfig.qWeatherApiHost.isNotBlank() &&
                PluginConfig.qWeatherProjectId.isNotBlank() &&
                PluginConfig.qWeatherCredentialId.isNotBlank() &&
                PluginConfig.qWeatherPrivateKeyPath.isNotBlank() &&
                JChatGPT.resolveConfigFile(PluginConfig.qWeatherPrivateKeyPath).isFile

    override val loadingMessage: String
        get() = "观天中..."

    override suspend fun execute(args: JsonObject?): String {
        requireNotNull(args)
        val city = args.getValue("city").jsonPrimitive.content
        val adm = args["adm"]?.jsonPrimitive?.contentOrNull
        val queryType = args["query_type"]?.jsonPrimitive?.contentOrNull ?: "now"
        val range = args["range"]?.jsonPrimitive?.contentOrNull

        require(queryType in setOf("now", "daily", "hourly", "minutely", "warning")) {
            "不支持的天气查询类型：$queryType"
        }

        val location = resolveLocation(city, adm)
        val locationId = location.getValue("id").jsonPrimitive.content
        val latitude = location.getValue("lat").jsonPrimitive.content
        val longitude = location.getValue("lon").jsonPrimitive.content

        val warningPath = "/weatheralert/v1/current/$latitude/$longitude"
        val (data, activeWarning) = if (queryType == "warning") {
            request(warningPath, mapOf("lang" to "zh")) to null
        } else coroutineScope {
            val weatherDeferred = async {
                when (queryType) {
                    "daily" -> {
                        val days = range?.takeIf { it in DAILY_RANGES } ?: "3d"
                        request("/v7/weather/$days", mapOf("location" to locationId, "lang" to "zh"))
                    }

                    "hourly" -> {
                        val hours = range?.takeIf { it in HOURLY_RANGES } ?: "24h"
                        request("/v7/weather/$hours", mapOf("location" to locationId, "lang" to "zh"))
                    }

                    "minutely" -> request(
                        "/v7/minutely/5m",
                        mapOf("location" to "$longitude,$latitude", "lang" to "zh")
                    )

                    else -> request("/v7/weather/now", mapOf("location" to locationId, "lang" to "zh"))
                }
            }
            val warningDeferred = async {
                try {
                    request(warningPath, mapOf("lang" to "zh"))
                } catch (e: Throwable) {
                    JChatGPT.logger.warning("天气预警查询失败，继续返回天气：${e.message}")
                    null
                }
            }
            weatherDeferred.await() to warningDeferred.await()?.takeIf(::hasActiveWarnings)
        }

        return buildJsonObject {
            put("queryType", queryType)
            putJsonObject("location") {
                put("name", location["name"]?.jsonPrimitive?.contentOrNull ?: city)
                put("adm2", location["adm2"]?.jsonPrimitive?.contentOrNull ?: "")
                put("adm1", location["adm1"]?.jsonPrimitive?.contentOrNull ?: "")
                put("country", location["country"]?.jsonPrimitive?.contentOrNull ?: "")
            }
            put("attribution", "天气服务由和风天气驱动")
            put("data", data)
            activeWarning?.let { put("warning", it) }
        }.toString()
    }

    private fun hasActiveWarnings(response: JsonObject): Boolean {
        return response["alerts"]?.jsonArray?.isNotEmpty() == true
    }

    private suspend fun resolveLocation(city: String, adm: String?): JsonObject {
        val parameters = buildMap {
            put("location", city)
            put("number", "1")
            put("lang", "zh")
            if (!adm.isNullOrBlank()) put("adm", adm)
        }
        val response = request("/geo/v2/city/lookup", parameters)
        val locations = response["location"]?.jsonArray
        require(!locations.isNullOrEmpty()) { "未找到地区：$city" }
        return locations.first().jsonObject
    }

    private suspend fun request(path: String, parameters: Map<String, String>): JsonObject {
        var response = requestOnce(path, parameters, forceRefreshJwt = false)
        if (response.first == HttpStatusCode.Unauthorized) {
            invalidateJwt()
            response = requestOnce(path, parameters, forceRefreshJwt = true)
        }

        val status = response.first
        val body = response.second
        require(status.isSuccess()) {
            "和风天气请求失败：HTTP ${status.value} ${status.description}，响应：${body.take(500)}"
        }

        val result = try {
            json.parseToJsonElement(body).jsonObject
        } catch (e: Throwable) {
            throw IllegalStateException("和风天气返回了无法解析的数据：${body.take(500)}", e)
        }

        val code = result["code"]?.jsonPrimitive?.contentOrNull
        require(code == null || code == "200") {
            "和风天气返回错误码 $code：${body.take(500)}"
        }
        return result
    }

    private suspend fun requestOnce(
        path: String,
        parameters: Map<String, String>,
        forceRefreshJwt: Boolean
    ): Pair<HttpStatusCode, String> {
        val response = httpClient.get(apiBaseUrl() + path) {
            header(HttpHeaders.Authorization, "Bearer ${jwt(forceRefreshJwt)}")
            parameters.forEach { (name, value) -> parameter(name, value) }
        }
        return response.status to response.bodyAsText()
    }

    private fun apiBaseUrl(): String {
        val host = PluginConfig.qWeatherApiHost.trim().trimEnd('/')
        require(!host.startsWith("http://", ignoreCase = true)) {
            "和风天气 API Host 必须使用 HTTPS"
        }
        return when {
            host.startsWith("https://", ignoreCase = true) -> host
            else -> "https://$host"
        }
    }

    private fun jwt(forceRefresh: Boolean): String = synchronized(jwtLock) {
        val now = Instant.now().epochSecond
        val privateKeyFile = JChatGPT.resolveConfigFile(PluginConfig.qWeatherPrivateKeyPath)
        val config = listOf(
            PluginConfig.qWeatherProjectId,
            PluginConfig.qWeatherCredentialId,
            privateKeyFile.absolutePath,
            privateKeyFile.lastModified().toString()
        ).joinToString("|")

        cachedJwt?.takeIf {
            !forceRefresh && cachedJwtConfig == config && now < cachedJwtExpiresAt - JWT_REFRESH_AHEAD_SECONDS
        }?.let { return@synchronized it }

        require(privateKeyFile.isFile) { "和风天气私钥文件不存在：${privateKeyFile.absolutePath}" }
        val privateKeyPem = privateKeyFile.readText()
        val privateKeyBase64 = privateKeyPem
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .filterNot(Char::isWhitespace)
        require(privateKeyBase64.isNotEmpty()) { "和风天气私钥文件内容为空" }

        val privateKey = try {
            EdDSAPrivateKey(PKCS8EncodedKeySpec(Base64.getDecoder().decode(privateKeyBase64)))
        } catch (e: Throwable) {
            throw IllegalArgumentException("无法读取和风天气 Ed25519 私钥：${privateKeyFile.absolutePath}", e)
        }

        val issuedAt = now - 30
        val expiresAt = issuedAt + JWT_LIFETIME_SECONDS
        val header = buildJsonObject {
            put("alg", "EdDSA")
            put("kid", PluginConfig.qWeatherCredentialId)
        }.toString()
        val payload = buildJsonObject {
            put("sub", PluginConfig.qWeatherProjectId)
            put("iat", issuedAt)
            put("exp", expiresAt)
        }.toString()

        val encoder = Base64.getUrlEncoder().withoutPadding()
        val encodedHeader = encoder.encodeToString(header.toByteArray(StandardCharsets.UTF_8))
        val encodedPayload = encoder.encodeToString(payload.toByteArray(StandardCharsets.UTF_8))
        val signingInput = "$encodedHeader.$encodedPayload"

        val spec = EdDSANamedCurveTable.ED_25519_CURVE_SPEC
        val signer = EdDSAEngine(MessageDigest.getInstance(spec.hashAlgorithm))
        signer.initSign(privateKey)
        signer.update(signingInput.toByteArray(StandardCharsets.UTF_8))
        val signature = encoder.encodeToString(signer.sign())

        "$signingInput.$signature".also {
            cachedJwt = it
            cachedJwtExpiresAt = expiresAt
            cachedJwtConfig = config
        }
    }

    private fun invalidateJwt() = synchronized(jwtLock) {
        cachedJwt = null
        cachedJwtExpiresAt = 0L
        cachedJwtConfig = ""
    }
}
