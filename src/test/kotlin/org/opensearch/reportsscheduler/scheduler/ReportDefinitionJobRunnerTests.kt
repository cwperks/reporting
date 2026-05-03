/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.reportsscheduler.scheduler

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.opensearch.core.xcontent.ToXContent
import org.opensearch.core.xcontent.XContentBuilder
import org.opensearch.jobscheduler.spi.JobDocVersion
import org.opensearch.jobscheduler.spi.JobExecutionContext
import org.opensearch.jobscheduler.spi.ScheduledJobParameter
import org.opensearch.jobscheduler.spi.schedule.Schedule
import org.opensearch.reportsscheduler.createReportDefinitionDetails
import org.opensearch.reportsscheduler.settings.PluginSettings
import java.time.Instant

internal class ReportDefinitionJobRunnerTests {

    @Test
    fun `Run job should skip scheduled report execution in standby mode`() {
        PluginSettings.standbyModeEnabled = true

        try {
            assertDoesNotThrow {
                ReportDefinitionJobRunner.runJob(createReportDefinitionDetails(), createJobExecutionContext())
            }
        } finally {
            PluginSettings.standbyModeEnabled = false
        }
    }

    @Test
    fun `Run job should reject non report definition job while active`() {
        PluginSettings.standbyModeEnabled = false

        assertThrows<IllegalArgumentException> {
            ReportDefinitionJobRunner.runJob(InvalidScheduledJobParameter, createJobExecutionContext())
        }
    }

    private fun createJobExecutionContext(): JobExecutionContext {
        return JobExecutionContext(
            Instant.now(),
            JobDocVersion(1L, 1L, 1L),
            null,
            "report-definition-index",
            "report-definition-id"
        )
    }

    private object InvalidScheduledJobParameter : ScheduledJobParameter {
        override fun getName(): String = "invalid"

        override fun getLastUpdateTime(): Instant = Instant.now()

        override fun getEnabledTime(): Instant = Instant.now()

        override fun getSchedule(): Schedule? = null

        override fun isEnabled(): Boolean = true

        override fun toXContent(builder: XContentBuilder, params: ToXContent.Params): XContentBuilder {
            return builder.startObject().endObject()
        }
    }
}
