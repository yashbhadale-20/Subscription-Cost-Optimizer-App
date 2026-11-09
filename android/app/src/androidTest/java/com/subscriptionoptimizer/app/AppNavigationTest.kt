package com.subscriptionoptimizer.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.espresso.Espresso
import org.junit.Rule
import org.junit.Test

/** Runs the real app. None of these need the backend to be up. */
class AppNavigationTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private fun tab(label: String) {
        rule.onAllNodesWithText(label).onFirst().performClick()
        rule.waitForIdle()
    }

    private fun nameField() = rule.onAllNodes(hasSetTextAction()).onFirst()

    @Test
    fun everyTabOpens() {
        rule.onNodeWithText("Overview").assertIsDisplayed()
        tab("Assistant")
        rule.onNodeWithText("AI Assistant").assertIsDisplayed()
        tab("History")
        tab("Settings")
        rule.onNodeWithText("Backend URL").assertExists()
        tab("Compare")
        rule.onNodeWithContentDescription("Swap Plan A and Plan B").assertIsDisplayed()
    }

    @Test
    fun emptySubmitShowsFieldErrors() {
        tab("Compare")
        rule.onNode(hasText("Compare plans") and hasClickAction()).performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNode(hasText("Check", substring = true)).assertExists()
    }

    @Test
    fun swapMovesEnteredDetailsToTheOtherPlan() {
        tab("Compare")
        nameField().performTextInput("Only in A")
        rule.onNodeWithContentDescription("Swap Plan A and Plan B").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Only in A").assertDoesNotExist()
        rule.onNodeWithText("Plan B", substring = true).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Only in A").assertExists()
    }

    @Test
    fun formSurvivesLeavingTheTabAndSystemBack() {
        tab("Compare")
        nameField().performTextInput("Keep me")
        tab("Settings")
        Espresso.pressBack()
        rule.waitForIdle()
        rule.onNodeWithText("Overview").assertIsDisplayed()
        tab("Compare")
        rule.onNodeWithText("Keep me").assertExists()
    }

    @Test
    fun formSurvivesRotation() {
        tab("Compare")
        nameField().performTextInput("Rotate me")
        rule.activityRule.scenario.recreate()
        rule.waitForIdle()
        rule.onNodeWithText("Rotate me").assertExists()
    }
}
