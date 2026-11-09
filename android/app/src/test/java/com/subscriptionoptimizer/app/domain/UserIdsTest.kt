package com.subscriptionoptimizer.app.domain

import com.subscriptionoptimizer.app.data.model.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UserIdsTest {

    @Test
    fun `new installs get different ids the backend accepts`() {
        val first = UserIds.initial(hasLegacySession = false)
        val second = UserIds.initial(hasLegacySession = false)
        assertNotEquals(first, second)
        assertNull(SettingsValidation.userIdError(first))
    }

    @Test
    fun `an install with a session under the old default keeps it`() {
        assertEquals(AppSettings.DEFAULT_USER_ID, UserIds.initial(hasLegacySession = true))
    }
}
