package com.subscriptionoptimizer.app.domain

import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date
import java.util.Locale

object Formatters {
    private val indiaLocale: Locale = Locale.Builder().setLanguage("en").setRegion("IN").build()

    /** "₹1,00,000.00" style INR amounts, matching the backend's currency. */
    fun money(amount: Double): String =
        NumberFormat.getCurrencyInstance(indiaLocale).apply {
            maximumFractionDigits = 2
            minimumFractionDigits = 2
        }.format(amount)

    fun percent(value: Double): String = String.format(Locale.US, "%.1f%%", value)

    fun score(value: Double): String = String.format(Locale.US, "%.1f", value)

    fun dateTime(millis: Long): String =
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis))
}
