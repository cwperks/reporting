/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.reportsscheduler.settings

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.opensearch.common.settings.Settings

internal class PluginSettingsTests {

    @Test
    fun `Standby mode setting should be registered and default to false`() {
        val standbyModeSetting = PluginSettings.getAllSettings()
            .firstOrNull { it.key == PluginSettings.STANDBY_MODE_KEY }

        assertTrue(standbyModeSetting != null)
        assertFalse(standbyModeSetting!!.get(Settings.EMPTY) as Boolean)
    }
}
