package top.jie65535.mirai.tools

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.utils.io.cancel
import io.ktor.utils.io.readAvailable
import okhttp3.Dns
import java.awt.Color
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.UnknownHostException
import java.util.Base64
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageReader
import javax.imageio.ImageWriteParam
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 将公网图片安全下载到机器人侧，并转换为视觉模型可直接接收的 Base64 Data URL。
 *
 * 百炼通过公网 URL 拉取图片时要求源站返回正确的 Content-Length 与 Content-Type，
 * QQ CDN 链接不总能满足该条件。改由机器人下载后上传可避免百炼二次拉取失败。
 */
internal class VisualImageResolver {
    data class ImagePayload(
        val dataUrl: String,
        val mimeType: String,
        val payloadSize: Int,
    )

    data class Result(
        val images: List<ImagePayload>,
        val sourceSize: Int,
        val transcoded: Boolean,
        val orderHint: String? = null,
    ) {
        val payloadSize: Int
            get() = images.sumOf { it.payloadSize }
    }

    private data class ImageInfo(
        val width: Int,
        val height: Int,
        val readerFormat: String,
    )

    private enum class ImageFormat(val mimeType: String) {
        BMP("image/bmp"),
        JPEG("image/jpeg"),
        PNG("image/png"),
        TIFF("image/tiff"),
        WEBP("image/webp"),
        HEIC("image/heic"),
        GIF("image/gif"),
    }

    private val httpClient = HttpClient(OkHttp) {
        followRedirects = false
        expectSuccess = false
        install(HttpTimeout) {
            requestTimeoutMillis = DOWNLOAD_TIMEOUT_MILLIS
            connectTimeoutMillis = CONNECT_TIMEOUT_MILLIS
            socketTimeoutMillis = DOWNLOAD_TIMEOUT_MILLIS
        }
        engine {
            config {
                dns(PublicOnlyDns)
            }
        }
    }

    suspend fun resolve(rawUrl: String): Result = prepare(downloadOriginal(rawUrl))

    /** 下载原始字节；收藏与编辑不能使用识图流程产生的缩放图或长图切片。 */
    suspend fun downloadOriginal(
        rawUrl: String,
        maxBytes: Int = MAX_DOWNLOAD_BYTES,
        timeoutMillis: Long = DOWNLOAD_TIMEOUT_MILLIS,
    ): ByteArray {
        var currentUrl = validateUrl(rawUrl)

        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            val response = httpClient.get(currentUrl.toASCIIString()) {
                header(HttpHeaders.Accept, "image/*")
                header(HttpHeaders.UserAgent, USER_AGENT)
                timeout {
                    requestTimeoutMillis = timeoutMillis
                    socketTimeoutMillis = timeoutMillis
                }
            }

            if (response.status.value in REDIRECT_STATUS_CODES) {
                response.bodyAsChannel().cancel()
                if (redirectCount >= MAX_REDIRECTS) {
                    throw IllegalArgumentException("图片下载重定向次数过多")
                }
                val location = response.headers[HttpHeaders.Location]
                    ?: throw IllegalArgumentException("图片下载重定向缺少 Location")
                currentUrl = validateUrl(currentUrl.resolve(location).toString())
                return@repeat
            }

            if (!response.status.isSuccess()) {
                val errorBody = readErrorBody(response.bodyAsChannel())
                val errorNumber = response.headers["X-ErrNo"]
                throw IllegalArgumentException(
                    buildString {
                        append("图片下载失败：HTTP ").append(response.status.value)
                        if (!errorNumber.isNullOrBlank()) append("，X-ErrNo=").append(errorNumber)
                        if (errorBody.isNotBlank()) append("，响应=").append(errorBody)
                    }
                )
            }

            val declaredLength = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
            if (declaredLength != null && declaredLength > maxBytes) {
                response.bodyAsChannel().cancel()
                throw IllegalArgumentException("图片文件过大：$declaredLength 字节，下载上限为 $maxBytes 字节")
            }

            return readBodyLimited(response.bodyAsChannel(), maxBytes)
        }

        error("无法完成图片下载")
    }

    fun close() = httpClient.close()

    internal fun prepare(bytes: ByteArray, declaredContentType: String? = null): Result {
        require(bytes.isNotEmpty()) { "下载到的图片内容为空" }

        val info = inspectImage(bytes)
        val format = detectFormat(bytes)
            ?: info?.readerFormat?.let(::formatFromReaderName)
            ?: throw IllegalArgumentException(
                "无法识别图片格式${declaredContentType?.let { "（响应类型为 $it）" } ?: ""}"
            )

        if (info == null && format in IMAGE_IO_FORMATS) {
            throw IllegalArgumentException("图片文件已损坏或无法解码：${format.mimeType}")
        }

        validateDimensions(info)

        if (shouldSplitLongImage(info)) {
            return splitLongImage(bytes, info!!, format)
        }

        val needsTranscode = format == ImageFormat.GIF ||
                !fitsDataUrl(bytes, format.mimeType) ||
                needsGeometryNormalization(info)

        if (!needsTranscode) {
            return Result(
                images = listOf(buildPayload(bytes, format.mimeType)),
                sourceSize = bytes.size,
                transcoded = false,
            )
        }

        val decoded = decodeImage(bytes, info)
            ?: throw IllegalArgumentException("图片需要转换，但当前 JVM 无法解码 ${format.mimeType} 格式")
        val normalized = normalizeSize(decoded)
        val preferPng = format == ImageFormat.PNG || format == ImageFormat.GIF || normalized.colorModel.hasAlpha()
        return Result(
            images = listOf(encodeTranscoded(normalized, preferPng)),
            sourceSize = bytes.size,
            transcoded = true,
        )
    }

    private suspend fun readBodyLimited(channel: io.ktor.utils.io.ByteReadChannel, maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
        var total = 0
        try {
            while (true) {
                val count = channel.readAvailable(buffer)
                if (count < 0) break
                if (count == 0) continue
                total += count
                if (total > maxBytes) {
                    throw IllegalArgumentException("图片文件超过下载上限 $maxBytes 字节")
                }
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        } finally {
            channel.cancel()
        }
    }

    private suspend fun readErrorBody(channel: io.ktor.utils.io.ByteReadChannel): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
        var total = 0
        try {
            while (total < MAX_ERROR_RESPONSE_BYTES) {
                val count = channel.readAvailable(
                    buffer,
                    0,
                    min(buffer.size, MAX_ERROR_RESPONSE_BYTES - total)
                )
                if (count < 0) break
                if (count == 0) continue
                output.write(buffer, 0, count)
                total += count
            }
        } finally {
            channel.cancel()
        }
        return output.toByteArray()
            .toString(Charsets.UTF_8)
            .replace(Regex("[\\r\\n]+"), " ")
            .trim()
    }

    private fun buildPayload(bytes: ByteArray, mimeType: String): ImagePayload {
        val encoded = Base64.getEncoder().encodeToString(bytes)
        val dataUrl = "data:$mimeType;base64,$encoded"
        require(dataUrl.length <= MAX_DATA_URL_LENGTH) {
            "图片 Base64 编码后超过百炼 10MB 限制"
        }
        return ImagePayload(
            dataUrl = dataUrl,
            mimeType = mimeType,
            payloadSize = dataUrl.length,
        )
    }

    private fun encodeTranscoded(image: BufferedImage, preferPng: Boolean): ImagePayload {
        // PNG 常用于截图、表情和带透明通道的图片，先尝试无损编码，避免文字细节被 JPEG 损伤。
        if (preferPng || image.colorModel.hasAlpha()) {
            val png = encodePng(image)
            if (fitsDataUrl(png, ImageFormat.PNG.mimeType)) {
                return buildPayload(png, ImageFormat.PNG.mimeType)
            }
        }

        var candidate = image
        repeat(MAX_COMPRESSION_ROUNDS) {
            for (quality in JPEG_QUALITIES) {
                val jpeg = encodeJpeg(candidate, quality)
                if (fitsDataUrl(jpeg, ImageFormat.JPEG.mimeType)) {
                    return buildPayload(jpeg, ImageFormat.JPEG.mimeType)
                }
            }

            val nextWidth = max(MIN_IMAGE_DIMENSION + 1, (candidate.width * DOWNSCALE_FACTOR).roundToInt())
            val nextHeight = max(MIN_IMAGE_DIMENSION + 1, (candidate.height * DOWNSCALE_FACTOR).roundToInt())
            if (nextWidth == candidate.width && nextHeight == candidate.height) {
                return@repeat
            }
            candidate = scale(candidate, nextWidth, nextHeight, alpha = false)
        }

        throw IllegalArgumentException("图片压缩后仍超过百炼 Base64 10MB 限制")
    }

    private fun fitsDataUrl(bytes: ByteArray, mimeType: String): Boolean {
        val prefixLength = "data:$mimeType;base64,".length
        val encodedLength = 4L * ((bytes.size.toLong() + 2L) / 3L)
        return prefixLength + encodedLength <= MAX_DATA_URL_LENGTH
    }

    private fun inspectImage(bytes: ByteArray): ImageInfo? {
        return try {
            ImageIO.createImageInputStream(ByteArrayInputStream(bytes)).use { input ->
                val readers = ImageIO.getImageReaders(input)
                if (!readers.hasNext()) return null
                val reader = readers.next()
                try {
                    reader.input = input
                    ImageInfo(
                        width = reader.getWidth(0),
                        height = reader.getHeight(0),
                        readerFormat = reader.formatName,
                    )
                } finally {
                    reader.dispose()
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun validateDimensions(info: ImageInfo?) {
        if (info == null) return
        require(info.width > 0 && info.height > 0) { "图片宽高无效" }
    }

    private fun needsGeometryNormalization(info: ImageInfo?): Boolean {
        if (info == null) return false
        val pixels = info.width.toLong() * info.height.toLong()
        val ratio = max(info.width, info.height).toDouble() / min(info.width, info.height).toDouble()
        return min(info.width, info.height) < NORMALIZED_MIN_EDGE ||
                ratio > MAX_ASPECT_RATIO ||
                max(info.width, info.height) > NORMALIZED_MAX_EDGE ||
                pixels > NORMALIZED_MAX_PIXELS
    }

    private fun shouldSplitLongImage(info: ImageInfo?): Boolean {
        if (info == null) return false
        val longEdge = max(info.width, info.height)
        val shortEdge = min(info.width, info.height)
        val splitRatio = if (info.height > info.width) {
            VERTICAL_LONG_IMAGE_SPLIT_RATIO
        } else {
            HORIZONTAL_LONG_IMAGE_SPLIT_RATIO
        }
        return longEdge >= LONG_IMAGE_MIN_EDGE &&
                longEdge.toDouble() / shortEdge.toDouble() >= splitRatio
    }

    private fun splitLongImage(bytes: ByteArray, info: ImageInfo, format: ImageFormat): Result {
        val vertical = info.height > info.width
        val regions = calculateTileRegions(info.width, info.height, vertical)
        val payloads = mutableListOf<ImagePayload>()

        ImageIO.createImageInputStream(ByteArrayInputStream(bytes)).use { input ->
            val readers = ImageIO.getImageReaders(input)
            require(readers.hasNext()) { "当前 JVM 无法解码长图 ${format.mimeType}" }
            val reader = readers.next()
            try {
                reader.input = input
                for (region in regions) {
                    val tile = readRegion(reader, region)
                    val normalized = normalizeSize(tile)
                    val preferPng = format == ImageFormat.PNG || format == ImageFormat.GIF ||
                            normalized.colorModel.hasAlpha()
                    payloads += encodeTranscoded(normalized, preferPng)
                    require(payloads.sumOf { it.payloadSize } <= MAX_TOTAL_DATA_URL_LENGTH) {
                        "长图切片后的 Base64 总大小超过 ${MAX_TOTAL_DATA_URL_LENGTH / 1_000_000}MB 限制"
                    }
                }
            } finally {
                reader.dispose()
            }
        }

        return Result(
            images = payloads,
            sourceSize = bytes.size,
            transcoded = true,
            orderHint = if (vertical) {
                "这些图片是同一张长图按从上到下顺序切分的，相邻图片有少量重叠，请按顺序连续理解。"
            } else {
                "这些图片是同一张宽图按从左到右顺序切分的，相邻图片有少量重叠，请按顺序连续理解。"
            },
        )
    }

    private fun calculateTileRegions(width: Int, height: Int, vertical: Boolean): List<Rectangle> {
        val longEdge = if (vertical) height else width
        val shortEdge = if (vertical) width else height
        val overlap = (shortEdge * LONG_IMAGE_OVERLAP_RATIO).roundToInt()
            .coerceIn(LONG_IMAGE_MIN_OVERLAP, LONG_IMAGE_MAX_OVERLAP)
            .coerceAtMost(max(1, longEdge / 4))
        val idealTileLength = max(
            LONG_IMAGE_MIN_TILE_LENGTH,
            (shortEdge * LONG_IMAGE_TILE_RATIO).roundToInt()
        ).coerceAtMost(longEdge)
        val idealStep = max(1, idealTileLength - overlap)
        val requiredParts = ceil((longEdge - idealTileLength).coerceAtLeast(0).toDouble() / idealStep).toInt() + 1
        val partCount = requiredParts.coerceIn(2, MAX_LONG_IMAGE_PARTS)
        val tileLength = if (requiredParts <= MAX_LONG_IMAGE_PARTS) {
            idealTileLength
        } else {
            ceil((longEdge + overlap * (partCount - 1)).toDouble() / partCount).toInt()
        }.coerceAtMost(longEdge)
        val availableStartRange = longEdge - tileLength

        return List(partCount) { index ->
            val start = if (partCount == 1) {
                0
            } else {
                (availableStartRange.toDouble() * index / (partCount - 1)).roundToInt()
            }
            if (vertical) {
                Rectangle(0, start, width, min(tileLength, height - start))
            } else {
                Rectangle(start, 0, min(tileLength, width - start), height)
            }
        }
    }

    private fun readRegion(reader: ImageReader, region: Rectangle): BufferedImage {
        val param = reader.defaultReadParam
        param.sourceRegion = region
        val downscale = calculateDownscale(region.width, region.height)
        if (downscale < 1.0) {
            val subsampling = ceil(1.0 / downscale).toInt().coerceAtLeast(1)
            param.setSourceSubsampling(subsampling, subsampling, 0, 0)
        }
        return reader.read(0, param)
    }

    private fun decodeImage(bytes: ByteArray, info: ImageInfo?): BufferedImage? {
        return try {
            ImageIO.createImageInputStream(ByteArrayInputStream(bytes)).use { input ->
                val readers = ImageIO.getImageReaders(input)
                if (!readers.hasNext()) return null
                val reader = readers.next()
                try {
                    reader.input = input
                    val width = info?.width ?: reader.getWidth(0)
                    val height = info?.height ?: reader.getHeight(0)
                    val scale = calculateScale(width, height)
                    val targetWidth = max(MIN_IMAGE_DIMENSION + 1, (width * scale).roundToInt())
                    val targetHeight = max(MIN_IMAGE_DIMENSION + 1, (height * scale).roundToInt())
                    val subsampling = max(
                        1,
                        min(width / targetWidth.coerceAtLeast(1), height / targetHeight.coerceAtLeast(1))
                    )
                    val param = reader.defaultReadParam
                    if (subsampling > 1) {
                        param.setSourceSubsampling(subsampling, subsampling, 0, 0)
                    }
                    reader.read(0, param)
                } finally {
                    reader.dispose()
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun normalizeSize(image: BufferedImage): BufferedImage {
        val padded = padToAllowedAspectRatio(image)
        val scale = calculateScale(padded.width, padded.height)
        if (scale == 1.0) return padded
        val targetWidth = max(MIN_IMAGE_DIMENSION + 1, (padded.width * scale).roundToInt())
        val targetHeight = max(MIN_IMAGE_DIMENSION + 1, (padded.height * scale).roundToInt())
        val scaled = scale(padded, targetWidth, targetHeight, padded.colorModel.hasAlpha())
        // 缩放后的整数取整可能让宽高比略微越过 200:1，再补一次边保证最终输入合规。
        return padToAllowedAspectRatio(scaled)
    }

    private fun calculateScale(width: Int, height: Int): Double {
        val upperScale = calculateUpperScale(width, height)
        val lowerScale = NORMALIZED_MIN_EDGE.toDouble() / min(width, height).toDouble()
        return when {
            lowerScale > 1.0 -> min(lowerScale, upperScale)
            upperScale < 1.0 -> upperScale
            else -> 1.0
        }
    }

    private fun calculateUpperScale(width: Int, height: Int): Double {
        val edgeScale = NORMALIZED_MAX_EDGE.toDouble() / max(width, height).toDouble()
        val pixelScale = sqrt(NORMALIZED_MAX_PIXELS.toDouble() / (width.toLong() * height.toLong()).toDouble())
        return min(edgeScale, pixelScale)
    }

    private fun calculateDownscale(width: Int, height: Int): Double {
        return min(1.0, calculateUpperScale(width, height))
    }

    private fun padToAllowedAspectRatio(source: BufferedImage): BufferedImage {
        val longEdge = max(source.width, source.height)
        val shortEdge = min(source.width, source.height)
        val requiredShortEdge = ceil(longEdge / MAX_ASPECT_RATIO).toInt()
        if (shortEdge >= requiredShortEdge) return source

        val targetWidth = if (source.width < source.height) requiredShortEdge else source.width
        val targetHeight = if (source.height < source.width) requiredShortEdge else source.height
        val alpha = source.colorModel.hasAlpha()
        val type = if (alpha) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB
        val target = BufferedImage(targetWidth, targetHeight, type)
        val graphics = target.createGraphics()
        try {
            if (!alpha) {
                graphics.color = Color.WHITE
                graphics.fillRect(0, 0, targetWidth, targetHeight)
            }
            val offsetX = (targetWidth - source.width) / 2
            val offsetY = (targetHeight - source.height) / 2
            graphics.drawImage(source, offsetX, offsetY, null)
        } finally {
            graphics.dispose()
        }
        return target
    }

    private fun scale(source: BufferedImage, width: Int, height: Int, alpha: Boolean): BufferedImage {
        val type = if (alpha) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB
        val target = BufferedImage(width, height, type)
        val graphics = target.createGraphics()
        try {
            if (!alpha) {
                graphics.color = Color.WHITE
                graphics.fillRect(0, 0, width, height)
            }
            val isSmallUpscale = (width > source.width || height > source.height) &&
                    source.width <= SMALL_IMAGE_EDGE && source.height <= SMALL_IMAGE_EDGE
            graphics.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION,
                if (isSmallUpscale) {
                    RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR
                } else {
                    RenderingHints.VALUE_INTERPOLATION_BICUBIC
                }
            )
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            graphics.drawImage(source, 0, 0, width, height, null)
        } finally {
            graphics.dispose()
        }
        return target
    }

    private fun encodePng(image: BufferedImage): ByteArray {
        return ByteArrayOutputStream().use { output ->
            check(ImageIO.write(image, "png", output)) { "当前 JVM 不支持 PNG 编码" }
            output.toByteArray()
        }
    }

    private fun encodeJpeg(image: BufferedImage, quality: Float): ByteArray {
        val rgb = if (image.type == BufferedImage.TYPE_INT_RGB && !image.colorModel.hasAlpha()) {
            image
        } else {
            scale(image, image.width, image.height, alpha = false)
        }
        val writer = ImageIO.getImageWritersByFormatName("jpeg").asSequence().firstOrNull()
            ?: error("当前 JVM 不支持 JPEG 编码")
        return try {
            ByteArrayOutputStream().use { output ->
                ImageIO.createImageOutputStream(output).use { imageOutput ->
                    writer.output = imageOutput
                    val params = writer.defaultWriteParam
                    if (params.canWriteCompressed()) {
                        params.compressionMode = ImageWriteParam.MODE_EXPLICIT
                        params.compressionQuality = quality
                    }
                    writer.write(null, IIOImage(rgb, null, null), params)
                }
                output.toByteArray()
            }
        } finally {
            writer.dispose()
        }
    }

    private fun detectFormat(bytes: ByteArray): ImageFormat? {
        return when {
            bytes.startsWith(0xFF, 0xD8, 0xFF) -> ImageFormat.JPEG
            bytes.startsWith(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) -> ImageFormat.PNG
            bytes.startsWithAscii("GIF87a") || bytes.startsWithAscii("GIF89a") -> ImageFormat.GIF
            bytes.startsWithAscii("BM") -> ImageFormat.BMP
            bytes.startsWith(0x49, 0x49, 0x2A, 0x00) || bytes.startsWith(0x4D, 0x4D, 0x00, 0x2A) -> ImageFormat.TIFF
            bytes.size >= 12 && bytes.startsWithAscii("RIFF") && bytes.asciiAt(8, 4) == "WEBP" -> ImageFormat.WEBP
            bytes.size >= 12 && bytes.asciiAt(4, 4) == "ftyp" && bytes.asciiAt(8, 4) in HEIC_BRANDS -> ImageFormat.HEIC
            else -> null
        }
    }

    private fun formatFromReaderName(name: String): ImageFormat? {
        return when (name.lowercase()) {
            "bmp" -> ImageFormat.BMP
            "jpeg", "jpg" -> ImageFormat.JPEG
            "png" -> ImageFormat.PNG
            "tif", "tiff" -> ImageFormat.TIFF
            "webp" -> ImageFormat.WEBP
            "heic", "heif" -> ImageFormat.HEIC
            "gif" -> ImageFormat.GIF
            else -> null
        }
    }

    private fun validateUrl(rawUrl: String): URI {
        val uri = try {
            URI(rawUrl.trim())
        } catch (e: Exception) {
            throw IllegalArgumentException("图片地址格式无效", e)
        }
        require(uri.scheme?.lowercase() in setOf("http", "https")) { "图片地址仅支持 HTTP/HTTPS" }
        require(!uri.host.isNullOrBlank()) { "图片地址缺少有效主机名" }
        require(uri.userInfo == null) { "图片地址不能包含用户凭据" }
        val host = uri.host.lowercase()
        require(host != "localhost" && !host.endsWith(".localhost") && !host.endsWith(".local")) {
            "禁止访问本机或局域网图片地址"
        }
        return uri.normalize()
    }

    private fun ByteArray.startsWith(vararg expected: Int): Boolean {
        if (size < expected.size) return false
        return expected.indices.all { index -> this[index].toInt() and 0xFF == expected[index] }
    }

    private fun ByteArray.startsWithAscii(expected: String): Boolean = asciiAt(0, expected.length) == expected

    private fun ByteArray.asciiAt(offset: Int, length: Int): String? {
        if (offset < 0 || length < 0 || size < offset + length) return null
        return String(this, offset, length, Charsets.US_ASCII)
    }

    private object PublicOnlyDns : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val addresses = try {
                Dns.SYSTEM.lookup(hostname)
            } catch (e: UnknownHostException) {
                throw e
            }
            if (addresses.isEmpty() || addresses.any { !isPublicAddress(it) }) {
                throw UnknownHostException("图片地址解析到非公网地址，已拒绝访问")
            }
            return addresses
        }
    }

    companion object {
        private const val DOWNLOAD_TIMEOUT_MILLIS = 30_000L
        private const val CONNECT_TIMEOUT_MILLIS = 10_000L
        private const val MAX_REDIRECTS = 3
        private const val MAX_DOWNLOAD_BYTES = 20_000_000
        private const val MAX_DATA_URL_LENGTH = 10_000_000L
        private const val MAX_TOTAL_DATA_URL_LENGTH = 48_000_000
        private const val DOWNLOAD_BUFFER_SIZE = 16 * 1024
        private const val MIN_IMAGE_DIMENSION = 10
        private const val MAX_ASPECT_RATIO = 200.0
        private const val NORMALIZED_MIN_EDGE = 32
        private const val NORMALIZED_MAX_EDGE = 4096
        private const val NORMALIZED_MAX_PIXELS = 16_000_000L
        private const val SMALL_IMAGE_EDGE = 64
        private const val LONG_IMAGE_MIN_EDGE = 2048
        private const val VERTICAL_LONG_IMAGE_SPLIT_RATIO = 3.0
        private const val HORIZONTAL_LONG_IMAGE_SPLIT_RATIO = 6.0
        private const val LONG_IMAGE_TILE_RATIO = 2.2
        private const val LONG_IMAGE_OVERLAP_RATIO = 0.10
        private const val LONG_IMAGE_MIN_TILE_LENGTH = 512
        private const val LONG_IMAGE_MIN_OVERLAP = 32
        private const val LONG_IMAGE_MAX_OVERLAP = 256
        private const val MAX_LONG_IMAGE_PARTS = 16
        private const val MAX_COMPRESSION_ROUNDS = 6
        private const val MAX_ERROR_RESPONSE_BYTES = 4096
        private const val DOWNSCALE_FACTOR = 0.82
        private const val USER_AGENT = "JChatGPT/1.13 image-fetcher"
        private val JPEG_QUALITIES = floatArrayOf(0.90f, 0.82f, 0.74f, 0.66f)
        private val REDIRECT_STATUS_CODES = setOf(301, 302, 303, 307, 308)
        private val HEIC_BRANDS = setOf("heic", "heix", "hevc", "hevx", "heim", "heis", "mif1", "msf1")
        private val IMAGE_IO_FORMATS = setOf(
            ImageFormat.BMP,
            ImageFormat.JPEG,
            ImageFormat.PNG,
            ImageFormat.TIFF,
            ImageFormat.GIF,
        )

        internal fun isPublicAddress(address: InetAddress): Boolean {
            if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
                address.isSiteLocalAddress || address.isMulticastAddress
            ) {
                return false
            }

            val bytes = address.address
            if (address is Inet4Address && bytes.size == 4) {
                val first = bytes[0].toInt() and 0xFF
                val second = bytes[1].toInt() and 0xFF
                return when {
                    first == 0 -> false
                    first == 10 -> false
                    first == 100 && second in 64..127 -> false
                    first == 127 -> false
                    first == 169 && second == 254 -> false
                    first == 172 && second in 16..31 -> false
                    first == 192 && second == 168 -> false
                    first == 198 && second in 18..19 -> false
                    first >= 224 -> false
                    else -> true
                }
            }

            if (address is Inet6Address && bytes.isNotEmpty()) {
                val first = bytes[0].toInt() and 0xFF
                // fc00::/7 为 IPv6 唯一本地地址，JDK 的 isSiteLocalAddress 不覆盖该范围。
                if (first and 0xFE == 0xFC) return false
            }

            return true
        }
    }
}
