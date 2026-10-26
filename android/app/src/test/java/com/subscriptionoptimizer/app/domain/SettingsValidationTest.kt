package com.subscriptionoptimizer.app.domain

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsValidationTest {

    @Test
    fun `accepts http and https backend URLs`() {
        assertNull(SettingsValidation.backendUrlError("http://10.0.2.2:8000", allowCleartext = true))
        assertNull(SettingsValidation.backendUrlError("https://api.example.com/", allowCleartext = true))
    }

    @Test
    fun `release builds accept only https`() {
        assertNotNull(SettingsValidation.backendUrlError("http://10.0.2.2:8000", allowCleartext = false))
        assertNull(SettingsValidation.backendUrlError("https://api.example.com", allowCleartext = false))
    }

    @Test
    fun `rejects URLs without a scheme or host`() {
        assertNotNull(SettingsValidation.backendUrlError(""))
        assertNotNull(SettingsValidation.backendUrlError("10.0.2.2:8000"))
        assertNotNull(SettingsValidation.backendUrlError("http://"))
    }

    @Test
    fun `user id follows the backend pattern`() {
        assertNull(SettingsValidation.userIdError("student_101"))
        assertNotNull(SettingsValidation.userIdError("has space"))
        assertNotNull(SettingsValidation.userIdError("a".repeat(65)))
    }
}
