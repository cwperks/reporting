/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.integTest.rest

import org.junit.Assert.assertEquals
import org.junit.Test
import org.opensearch.integTest.PluginRestTestCase
import org.opensearch.reportsscheduler.settings.PluginSettings

class ReportsStandbyModeIT : PluginRestTestCase() {

    @Test
    fun `Standby mode setting should be dynamically updateable`() {
        updateClusterSettings(ClusterSetting("persistent", PluginSettings.STANDBY_MODE_KEY, true))

        val settings = getAllClusterSettings()
        val persistentSettings = settings!!.getAsJsonObject("persistent")

        assertEquals("true", persistentSettings.get(PluginSettings.STANDBY_MODE_KEY).asString)
    }
}
