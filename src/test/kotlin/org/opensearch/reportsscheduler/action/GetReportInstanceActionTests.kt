/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.reportsscheduler.action

import com.nhaarman.mockitokotlin2.mock
import com.nhaarman.mockitokotlin2.verify
import com.nhaarman.mockitokotlin2.whenever
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.opensearch.action.support.ActionFilters
import org.opensearch.core.xcontent.NamedXContentRegistry
import org.opensearch.tasks.TaskManager
import org.opensearch.transport.TransportService

internal class GetReportInstanceActionTests {
    @Test
    fun `registers legacy get as an alias of the resource action`() {
        val transportService = mock<TransportService>()
        whenever(transportService.taskManager).thenReturn(mock<TaskManager>())

        GetReportInstanceAction(transportService, mock(), ActionFilters(emptySet()), NamedXContentRegistry.EMPTY)

        assertEquals("report_instance:get", GetReportInstanceAction.ACTION_TYPE.name())
        assertEquals(
            setOf("cluster:admin/opendistro/reports/instance/get"),
            GetReportInstanceAction.ACTION_TYPE.legacyActionNames()
        )
        verify(transportService).registerRequestHandlerAlias(
            "cluster:admin/opendistro/reports/instance/get",
            "report_instance:get"
        )
    }
}
