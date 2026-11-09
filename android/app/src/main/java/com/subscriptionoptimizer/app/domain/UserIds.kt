package com.subscriptionoptimizer.app.domain

import com.subscriptionoptimizer.app.data.model.AppSettings
import java.util.UUID

object UserIds {
    /**
     * The backend gives a user ID to the first client that asks for it. A shared default would
     * lock out every other device, and this one too after a reinstall, so each install gets its
     * own. An install that already has a session under the old default keeps it, along with its
     * history.
     */
    fun initial(hasLegacySession: Boolean, random: () -> UUID = UUID::randomUUID): String =
        if (hasLegacySession) {
            AppSettings.DEFAULT_USER_ID
        } else {
            "android_" + random().toString().replace("-", "").take(12)
        }
}
