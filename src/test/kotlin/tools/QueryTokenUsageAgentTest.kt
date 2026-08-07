package top.jie65535.mirai.tools

import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import top.jie65535.mirai.config.ModelDefinition
import top.jie65535.mirai.config.ModelProviderDefinition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QueryTokenUsageAgentTest {
    @Test
    fun scopesUsageToCurrentGroupOrPrivateSender() {
        val group = QueryTokenUsageAgent.queryScope(
            botId = 1,
            senderId = 10,
            currentGroupId = 100,
            requestedUserId = 20,
        )
        assertEquals(1, group.botId)
        assertEquals(100, group.groupId)
        assertEquals(20, group.userId)
        assertFalse(group.privateOnly)

        val private = QueryTokenUsageAgent.queryScope(
            botId = 1,
            senderId = 10,
            currentGroupId = null,
            requestedUserId = 999,
        )
        assertEquals(1, private.botId)
        assertEquals(null, private.groupId)
        assertEquals(10, private.userId)
        assertTrue(private.privateOnly)
    }

    @Test
    fun recognizesOnlyOfficialDeepSeekHosts() {
        assertTrue(QueryTokenUsageAgent.isDeepSeekApi("https://api.deepseek.com/v1/"))
        assertTrue(QueryTokenUsageAgent.isDeepSeekApi("https://cn.api.deepseek.com/v1"))
        assertFalse(QueryTokenUsageAgent.isDeepSeekApi("https://deepseek.example.com/v1"))
        assertFalse(QueryTokenUsageAgent.isDeepSeekApi("not a url"))
    }

    @Test
    fun parsesDeepSeekBalanceResponse() {
        val result = QueryTokenUsageAgent.parseDeepSeekBalance(
            """
            {
              "is_available": true,
              "balance_infos": [
                {
                  "currency": "CNY",
                  "total_balance": "12.34",
                  "granted_balance": "2.00",
                  "topped_up_balance": "10.34"
                }
              ]
            }
            """.trimIndent()
        )

        assertTrue(result.getValue("available").jsonPrimitive.boolean)
        val balance = result.getValue("balances").jsonArray.single().jsonObject
        assertEquals("CNY", balance.getValue("currency").jsonPrimitive.content)
        assertEquals("12.34", balance.getValue("total").jsonPrimitive.content)
        assertEquals("2.00", balance.getValue("granted").jsonPrimitive.content)
        assertEquals("10.34", balance.getValue("toppedUp").jsonPrimitive.content)
    }

    @Test
    fun collectsReferencedDeepSeekAccountsAndDeduplicatesCredentials() {
        val accounts = QueryTokenUsageAgent.collectDeepSeekBalanceAccounts(
            providers = listOf(
                ModelProviderDefinition("deepseek-a", "openai", "https://api.deepseek.com/v1/", "same-token"),
                ModelProviderDefinition("deepseek-b", "openai", "https://cn.api.deepseek.com/v1/", "same-token"),
                ModelProviderDefinition("unused", "openai", "https://api.deepseek.com/v1/", "unused-token"),
                ModelProviderDefinition("dashscope", "dashscope", "https://dashscope.aliyuncs.com/api/v1/", "dash-token"),
            ),
            models = listOf(
                ModelDefinition("chat", "deepseek-a", "deepseek-chat"),
                ModelDefinition("reasoning", "deepseek-b", "deepseek-reasoner"),
                ModelDefinition("image", "dashscope", "qwen-image"),
            ),
            legacyAccounts = listOf(
                QueryTokenUsageAgent.Companion.BalanceAccount(
                    "legacy-chat",
                    "https://api.deepseek.com/v1/",
                    "same-token",
                )
            ),
        )

        assertEquals(1, accounts.size)
        assertEquals("deepseek-a", accounts.single().name)
        assertEquals("same-token", accounts.single().token)
    }
}
