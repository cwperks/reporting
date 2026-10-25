/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.reportsscheduler.scheduler

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.opensearch.core.action.ActionListener
import org.opensearch.jobscheduler.spi.JobExecutionContext
import org.opensearch.jobscheduler.spi.LockModel
import org.opensearch.jobscheduler.spi.ScheduledJobParameter
import org.opensearch.jobscheduler.spi.ScheduledJobRunner
import org.opensearch.reportsscheduler.ReportsSchedulerPlugin.Companion.LOG_PREFIX
import org.opensearch.reportsscheduler.index.ReportInstancesIndex
import org.opensearch.reportsscheduler.model.ReportDefinitionDetails
import org.opensearch.reportsscheduler.model.ReportInstance
import org.opensearch.reportsscheduler.util.logger
import java.time.Instant

internal object ReportDefinitionJobRunner : ScheduledJobRunner {
    private val log by logger(ReportDefinitionJobRunner::class.java)
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
    private const val LOCAL_ACTIVE_JOB_DISPLAY_MILLIS = 30_000L

    override fun runJob(job: ScheduledJobParameter, context: JobExecutionContext) {
        if (job !is ReportDefinitionDetails) {
            log.warn("$LOG_PREFIX:job is not of type ReportDefinitionDetails:${job.javaClass.name}")
            throw IllegalArgumentException("job is not of type ReportDefinitionDetails:${job.javaClass.name}")
        }
        context.lockService.acquireLock(
            job,
            context,
            ActionListener.wrap(
                { lock ->
                    if (lock == null) {
                        log.info("$LOG_PREFIX:runJob-skipping ${context.jobId}; another scheduler owns the lock")
                        return@wrap
                    }
                    runReportJob(job, context, lock)
                },
                { exception -> log.warn("$LOG_PREFIX:runJob-lock acquisition failed for ${context.jobId}", exception) }
            )
        )
    }

    private fun runReportJob(job: ReportDefinitionDetails, context: JobExecutionContext, lock: LockModel) {
        scope.launch {
            try {
                val reportDefinitionDetails: ReportDefinitionDetails = job
                val currentTime = Instant.now()
                val endTime = context.expectedExecutionTime
                val beginTime = endTime.minus(reportDefinitionDetails.reportDefinition.format.duration)
                val reportInstance = ReportInstance(
                    context.jobId,
                    currentTime,
                    currentTime,
                    beginTime,
                    endTime,
                    job.tenant,
                    job.access,
                    reportDefinitionDetails,
                    ReportInstance.Status.Success
                ) // TODO: Revert to Scheduled when background job execution supported
                val id = ReportInstancesIndex.createReportInstance(reportInstance)
                if (id == null) {
                    log.warn("$LOG_PREFIX:runJob-job creation failed for $reportInstance")
                } else {
                    log.info("$LOG_PREFIX:runJob-created job:$id")
                }
                delay(LOCAL_ACTIVE_JOB_DISPLAY_MILLIS)
            } finally {
                context.lockService.release(
                    lock,
                    ActionListener.wrap(
                        { released -> log.info("$LOG_PREFIX:runJob-released lock for ${context.jobId}: $released") },
                        { exception -> log.warn("$LOG_PREFIX:runJob-lock release failed for ${context.jobId}", exception) }
                    )
                )
            }
        }
    }
}
