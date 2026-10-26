package com.subscriptionoptimizer.app.domain

import com.subscriptionoptimizer.app.preview.PreviewFixtures
import org.junit.Assert.assertTrue
import org.junit.Test

class ProsConsDeriverTest {

    @Test
    fun `cheaper plan gets a cost pro and the other a cost con`() {
        val (a, b) = ProsConsDeriver.derive(PreviewFixtures.sampleResult)
        assertTrue(b.pros.any { "cheaper in the first year" in it })
        assertTrue(a.cons.any { "more expensive in the first year" in it })
    }

    @Test
    fun `intro offer is listed as a con and ongoing discount as a pro`() {
        val (a, b) = ProsConsDeriver.derive(PreviewFixtures.sampleResult)
        assertTrue(a.cons.any { it.startsWith("Intro price lasts 3 months") })
        assertTrue(b.pros.any { it.startsWith("Ongoing") })
    }

    @Test
    fun `equal scores produce no pro or con for that dimension`() {
        val (a, b) = ProsConsDeriver.derive(PreviewFixtures.sampleResult)
        // Usage scores are 50 vs 50 in the sample.
        assertTrue((a.pros + a.cons + b.pros + b.cons).none { "usage limits" in it })
    }
}
