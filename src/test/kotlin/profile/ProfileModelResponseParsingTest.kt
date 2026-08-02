package top.jie65535.mirai.profile

import kotlinx.serialization.decodeFromString
import kotlin.test.Test
import kotlin.test.assertEquals

class ProfileModelResponseParsingTest {
    @Test
    fun ignoresLegacyFieldsAndNormalizesDisplayedEvidenceReferences() {
        val response = profileResponseJson.decodeFromString<ProfileModelResponse>(
            """
            {
              "operations": [
                {
                  "action": "ADD",
                  "item_id": null,
                  "category": "interest",
                  "content": "关注 Kotlin",
                  "confidence": "low",
                  "evidence_refs": [127, "e:128", "129"]
                }
              ],
              "summary": "关注 Kotlin。",
              "unexpected": true
            }
            """.trimIndent()
        )

        assertEquals(listOf(127, 128, 129), response.operations.single().evidenceRefs)
    }

    @Test
    fun preservesMalformedEvidenceAsAnInvalidReferenceForOperationValidation() {
        val response = profileResponseJson.decodeFromString<ProfileModelResponse>(
            """{"operations":[{"action":"CONFIRM","item_ref":"P1","evidence_refs":["invalid"]}]}"""
        )

        assertEquals(listOf(0), response.operations.single().evidenceRefs)
    }
}
