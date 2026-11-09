package top.jie65535.mirai.tools

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import net.mamoe.mirai.contact.Group
import net.mamoe.mirai.contact.MemberPermission
import net.mamoe.mirai.contact.NormalMember
import net.mamoe.mirai.contact.User
import net.mamoe.mirai.contact.UserOrBot
import top.jie65535.mirai.data.ImageAssetStore
import top.jie65535.mirai.media.ImageBinary
import top.jie65535.mirai.media.ImageReferences
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.lang.reflect.Proxy
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.*

class GetContactInfoAgentTest {
    @Test
    fun argumentsDefaultToSenderAndAllowSkippingAvatar() {
        assertEquals(123L to true, GetContactInfoAgent.parseArguments(null, 123))
        assertEquals(123L to true, GetContactInfoAgent.parseArguments(JsonObject(emptyMap()), 123))
        val args = buildJsonObject { put("userId", 456); put("includeAvatar", false) }
        assertEquals(456L to false, GetContactInfoAgent.parseArguments(args, 123))
    }

    @Test
    fun rejectsMalformedTargetsAndAvatarFlags() {
        listOf("0", "-1", "1.5", "9223372036854775808", "\"123\"", "true", "null", "[]").forEach { value ->
            assertFailsWith<IllegalArgumentException> {
                GetContactInfoAgent.parseArguments(Json.parseToJsonElement("""{"userId":$value}""").jsonObject, 123)
            }
        }
        listOf("0", "\"false\"", "null", "{}").forEach { value ->
            assertFailsWith<IllegalArgumentException> {
                GetContactInfoAgent.parseArguments(Json.parseToJsonElement("""{"includeAvatar":$value}""").jsonObject, 123)
            }
        }
    }

    @Test
    fun groupQueriesStayInCurrentGroupEvenWhenTargetIsAFriend() {
        val sender = member(1)
        val target = member(2)
        val botMember = member(9)
        val bot = user(9)
        val group = group(mapOf(2L to target, 9L to botMember))
        var friendLookups = 0
        val findFriend: (Long) -> User? = { friendLookups++; user(it) }
        assertSame(sender, GetContactInfoAgent.resolveContact(1, sender, group, bot, findFriend))
        assertSame(target, GetContactInfoAgent.resolveContact(2, sender, group, bot, findFriend))
        assertSame(botMember, GetContactInfoAgent.resolveContact(9, sender, group, bot, findFriend))
        assertNull(GetContactInfoAgent.resolveContact(3, sender, group, bot, findFriend))
        assertEquals(0, friendLookups)
    }

    @Test
    fun privateQueriesSupportSenderBotAndKnownFriends() {
        val sender = user(1)
        val friend = user(2)
        val bot = user(9)
        val findFriend: (Long) -> User? = { friend.takeIf { id -> id.id == it } }
        assertSame(sender, GetContactInfoAgent.resolveContact(1, sender, null, bot, findFriend))
        assertSame(bot, GetContactInfoAgent.resolveContact(9, sender, null, bot, findFriend))
        assertSame(friend, GetContactInfoAgent.resolveContact(2, sender, null, bot, findFriend))
        assertNull(GetContactInfoAgent.resolveContact(3, sender, null, bot, findFriend))
    }

    @Test
    fun avatarIsSavedUncollectedAndCanBePassedToExistingImageTools() = withStore { store ->
        val bytes = image()
        var downloads = 0
        val result = ContactInfoService(store, download = { url ->
            assertEquals("https://example.com/avatar/123", url)
            downloads++
            bytes
        }).load(user(123), null, true)
        assertEquals(123, result.getValue("userId").jsonPrimitive.int)
        assertEquals("用户123", result.getValue("nickname").jsonPrimitive.content)
        val avatar = result.getValue("avatar").jsonObject
        assertEquals(12, avatar.getValue("width").jsonPrimitive.int)
        assertEquals(8, avatar.getValue("height").jsonPrimitive.int)
        assertFalse(avatar.getValue("collected").jsonPrimitive.boolean)
        val reference = avatar.getValue("image").jsonPrimitive.content
        assertEquals("![头像]($reference)", avatar.getValue("markdown").jsonPrimitive.content)
        val args = buildJsonObject { putJsonArray("images") { add(reference) } }
        assertEquals(reference, ImageReferences.fromArguments(args, 1) { null }.single().reference)
        val saved = store.list(includeTemporary = true).single()
        assertEquals("avatar", saved.source)
        assertContentEquals(bytes, store.file(saved.id).readBytes())
        assertTrue(store.list().isEmpty())
        assertEquals(saved, ImageAssetStore(store.root).get(saved.id))
        assertEquals(1, downloads)
    }

    @Test
    fun returnsGroupNameCardAndEveryMemberRole() = withStore { store ->
        val roles = mapOf(MemberPermission.OWNER to "群主", MemberPermission.ADMINISTRATOR to "管理员", MemberPermission.MEMBER to "普通成员")
        roles.forEach { (permission, role) ->
            val result = ContactInfoService(store, download = { error("不应下载头像") })
                .load(member(123, nameCard = "群内名片", permission = permission), 456, false)
            assertEquals(456, result.getValue("groupId").jsonPrimitive.int)
            assertEquals("群内名片", result.getValue("nameCard").jsonPrimitive.content)
            assertEquals(role, result.getValue("role").jsonPrimitive.content)
            assertFalse(result.containsKey("avatar"))
        }
    }

    @Test
    fun doesNotExposeGroupDetailsOfATemporaryPrivateContact() = withStore { store ->
        val contact = member(123, failures = mapOf("getNameCard" to UnsupportedOperationException(), "getPermission" to NotImplementedError()))
        val result = ContactInfoService(store, download = { error("不应下载头像") }).load(contact, null, false)
        assertEquals("用户123", result.getValue("nickname").jsonPrimitive.content)
        assertFalse(result.containsKey("groupId"))
        assertFalse(result.containsKey("nameCard"))
        assertFalse(result.containsKey("role"))
        assertFalse(result.containsKey("warnings"))
    }

    @Test
    fun skippingAvatarDoesNotReadItsUrlOrRequireAStore() = runBlocking {
        val contact = user(123, failures = mapOf("getAvatarUrl" to NotImplementedError()))
        val result = ContactInfoService(null, download = { error("不应下载头像") }).load(contact, null, false)
        assertEquals(123, result.getValue("userId").jsonPrimitive.int)
        assertFalse(result.containsKey("avatar"))
        assertFalse(result.containsKey("avatar_error"))
        assertFalse(result.containsKey("warnings"))
    }

    @Test
    fun unavailableStorePreservesBasicInfoAndSkipsDownload() = runBlocking {
        val result = ContactInfoService(null, download = { error("不应下载头像") }).load(user(123), null, true)
        assertEquals("用户123", result.getValue("nickname").jsonPrimitive.content)
        assertContains(result.getValue("avatar_error").jsonPrimitive.content, "素材库暂不可用")
    }

    @Test
    fun failedAvatarDownloadPreservesInfoWithoutRetryingOrLeakingExceptionDetails() = withStore { store ->
        val logs = mutableListOf<String>()
        var downloads = 0
        val result = ContactInfoService(store, download = {
            downloads++
            throw IOException("https://example.com/private?signature=secret")
        }, report = { logs += it }).load(member(123), 456, true)
        assertEquals("用户123", result.getValue("nickname").jsonPrimitive.content)
        assertEquals("普通成员", result.getValue("role").jsonPrimitive.content)
        assertTrue(result.containsKey("avatar_error"))
        assertFalse(result.containsKey("avatar"))
        assertFalse(result.toString().contains("signature=secret"))
        assertFalse(logs.joinToString().contains("signature=secret"))
        assertContains(logs.single(), "IOException")
        assertEquals(1, downloads)
        assertTrue(store.list(includeTemporary = true).isEmpty())
    }

    @Test
    fun unsupportedMetadataDoesNotPreventAvatarRetrieval() = withStore { store ->
        val logs = mutableListOf<String>()
        val contact = member(123, failures = mapOf("getNameCard" to UnsupportedOperationException(), "getPermission" to NotImplementedError()))
        val result = ContactInfoService(store, download = { image() }, report = { logs += it }).load(contact, 456, true)
        assertTrue(result.containsKey("avatar"))
        assertFalse(result.containsKey("nameCard"))
        assertFalse(result.containsKey("role"))
        assertEquals(2, result.getValue("warnings").jsonArray.size)
        assertEquals(2, logs.size)
    }

    @Test
    fun unsupportedAvatarAndInvalidImageReturnBasicInfo() = withStore { store ->
        val contact = user(123, failures = mapOf("getAvatarUrl" to NotImplementedError()))
        val unsupported = ContactInfoService(store, download = { error("不应下载头像") }).load(contact, null, true)
        assertEquals(123, unsupported.getValue("userId").jsonPrimitive.int)
        assertTrue(unsupported.containsKey("avatar_error"))
        val invalid = ContactInfoService(store, download = { "not-an-image".toByteArray() }).load(user(123), null, true)
        assertTrue(invalid.containsKey("avatar_error"))
        assertTrue(store.list(includeTemporary = true).isEmpty())
    }

    @Test
    fun duplicateAvatarPreservesExistingCollectionAndChangedAvatarCreatesANewReference() = withStore { store ->
        val original = store.save(ImageBinary.inspect(image()), "已有表情包", "local", collected = true, tags = listOf("保留标签"))
        val duplicate = ContactInfoService(store, download = { image() }).load(user(123), null, true)
        assertEquals(original.reference, duplicate.getValue("avatar").jsonObject.getValue("image").jsonPrimitive.content)
        assertEquals(original, store.get(original.id))
        assertTrue(duplicate.getValue("avatar").jsonObject.getValue("collected").jsonPrimitive.boolean)
        val changed = ContactInfoService(store, download = { image(Color.RED) }).load(user(123), null, true)
        assertNotEquals(original.reference, changed.getValue("avatar").jsonObject.getValue("image").jsonPrimitive.content)
        assertEquals(1, store.list().size)
        assertEquals(2, store.list(includeTemporary = true).size)
    }

    @Test
    fun cancellationPropagatesInsteadOfReturningAnAvatarFailure() = withStore { store ->
        val logs = mutableListOf<String>()
        val service = ContactInfoService(store, download = { throw CancellationException("cancelled") }, report = { logs += it })
        assertFailsWith<CancellationException> { service.load(user(123), null, true) }
        assertTrue(logs.isEmpty())
        assertTrue(store.list(includeTemporary = true).isEmpty())
    }

    private fun user(id: Long, failures: Map<String, Throwable> = emptyMap()): User = stub(
        mapOf("getId" to id, "getNick" to "用户$id", "getAvatarUrl" to "https://example.com/avatar/$id"), failures)

    private fun member(
        id: Long,
        nameCard: String = "",
        permission: MemberPermission = MemberPermission.MEMBER,
        failures: Map<String, Throwable> = emptyMap(),
    ): NormalMember = stub(mapOf("getId" to id, "getNick" to "用户$id", "getAvatarUrl" to "https://example.com/avatar/$id",
        "getNameCard" to nameCard, "getPermission" to permission), failures)

    private fun group(members: Map<Long, NormalMember>): Group = stub(emptyMap(), handlers = mapOf(
        "get" to { args -> members[args!!.single() as Long] },
    ))

    private inline fun <reified T> stub(
        values: Map<String, Any?>,
        failures: Map<String, Throwable> = emptyMap(),
        handlers: Map<String, (Array<out Any?>?) -> Any?> = emptyMap(),
    ): T = Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { proxy, method, args ->
        failures[method.name]?.let { throw it }
        when {
            method.name == "equals" -> proxy === args?.single()
            method.name == "hashCode" -> System.identityHashCode(proxy)
            method.name == "toString" -> "ContactStub(${values["getId"]})"
            handlers.containsKey(method.name) -> handlers.getValue(method.name)(args)
            values.containsKey(method.name) -> values[method.name]
            else -> error("Unexpected contact call: ${method.name}")
        }
    } as T

    private fun image(color: Color = Color.BLUE): ByteArray {
        val image = BufferedImage(12, 8, BufferedImage.TYPE_INT_ARGB)
        image.setRGB(0, 0, color.rgb)
        return ByteArrayOutputStream().use { output -> ImageIO.write(image, "png", output); output.toByteArray() }
    }

    private fun withStore(block: suspend (ImageAssetStore) -> Unit) = runBlocking {
        val directory = Files.createTempDirectory("contact-info-").toFile()
        try { block(ImageAssetStore(directory)) } finally { directory.deleteRecursively() }
    }
}
