package top.jie65535.mirai.profile

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProfileAnalysisRunGateTest {
    @Test
    fun stopInvalidatesExistingTokensButNotFutureRuns() {
        val gate = ProfileAnalysisRunGate()
        val existing = gate.newToken()

        gate.stopCurrentRuns()

        assertFalse(gate.canContinue(existing))
        assertTrue(gate.canContinue(gate.newToken()))
    }
}
