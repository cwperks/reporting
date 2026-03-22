/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.integTest.rest

import java.time.Duration
import org.apache.logging.log4j.LogManager
import org.awaitility.Awaitility
import org.opensearch.core.rest.RestStatus
import org.opensearch.integTest.PluginRestTestCase
import org.opensearch.integTest.constructReportDefinitionRequest
import org.opensearch.integTest.validateErrorResponse
import org.opensearch.reportsscheduler.ReportsSchedulerPlugin.Companion.BASE_REPORTS_URI
import org.opensearch.reportsscheduler.resources.Utils
import org.opensearch.rest.RestRequest

/**
 * Integration tests for resource sharing feature.
 * Tests the behavior difference between backend_roles filtering and resource-sharing:
 * - Without resource-sharing: Users with same backend_role have default access
 * - With resource-sharing: Users need explicit access grants, regardless of roles
 */
class ResourceSharingIT : PluginRestTestCase() {

    private val log = LogManager.getLogger(ResourceSharingIT::class.java)

    /** Logs a labelled step so the test output shows exactly where time is spent. */
    private fun step(msg: String) = log.info("STEP: $msg")

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Waits until [predicate] returns true, polling every 200 ms for up to 15 s.
     * Shorter than the default 30 s — if something is going to work it usually does within a few seconds.
     */
    private fun awaitCondition(description: String, predicate: () -> Boolean) {
        step("waiting for: $description")
        val start = System.currentTimeMillis()
        Awaitility.await(description)
            .atMost(Duration.ofSeconds(15))
            .pollInterval(Duration.ofMillis(200))
            .until { predicate() }
        log.info("'$description' satisfied in ${System.currentTimeMillis() - start} ms")
    }

    /** Waits until the given endpoint returns 200 for [client]. */
    private fun awaitVisible(baseUri: String, path: String, client: org.opensearch.client.RestClient) {
        awaitCondition("$path visible") {
            try {
                waitForSharingVisibility(RestRequest.Method.GET.name, "$baseUri/$path", null, client) != null
            } catch (_: Exception) { false }
        }
    }

    /** Waits until the definitions list for [client] returns exactly [count] hits. */
    private fun awaitDefinitionCount(baseUri: String, client: org.opensearch.client.RestClient, count: Int) {
        awaitCondition("definitions list == $count") {
            try {
                executeRequest(client, RestRequest.Method.GET.name, "$baseUri/definitions", "", RestStatus.OK.status)
                    .get("totalHits").asInt == count
            } catch (_: Exception) { false }
        }
    }

    /** Waits until the instances list for [client] returns at least [minCount] hits. */
    private fun awaitInstanceCount(baseUri: String, client: org.opensearch.client.RestClient, minCount: Int) {
        awaitCondition("instances list >= $minCount") {
            try {
                executeRequest(client, RestRequest.Method.GET.name, "$baseUri/instances", "", RestStatus.OK.status)
                    .get("totalHits").asInt >= minCount
            } catch (_: Exception) { false }
        }
    }

    // ------------------------------------------------------------------
    // Report definition CRUD
    // ------------------------------------------------------------------

    @Suppress("LongMethod")
    private fun testReportDefinitionCRUDWithResourceSharing(baseUri: String) {
        if (!isHttps()) return
        if (!isResourceSharingFeatureEnabled()) return

        step("create definition and verify owner access")
        val reportDefinitionId = createReportDefinitionAndVerifyOwnerAccess(baseUri)

        step("verify no access without sharing")
        verifyNoAccessWithoutSharing(baseUri, reportDefinitionId)

        step("test read-only access sharing")
        testReadOnlyAccessSharing(baseUri, reportDefinitionId)

        step("test full access sharing")
        testFullAccessSharing(baseUri, reportDefinitionId)

        step("cleanup")
        cleanupReportDefinition(baseUri, reportDefinitionId)
    }

    private fun createReportDefinitionAndVerifyOwnerAccess(baseUri: String): String {
        val createResponse = executeRequest(
            reportsFullClient, RestRequest.Method.POST.name, "$baseUri/definition",
            constructReportDefinitionRequest(), RestStatus.OK.status
        )
        val id = createResponse.get("reportDefinitionId").asString
        assertNotNull("reportDefinitionId should be generated", id)
        log.info("created definition $id")

        // Wait for the sharing record + all_shared_principals to be written before asserting
        step("wait for owner visibility of definition $id")
        awaitVisible(baseUri, "definition/$id", reportsFullClient)

        val ownerGet = executeRequest(reportsFullClient, RestRequest.Method.GET.name, "$baseUri/definition/$id", "", RestStatus.OK.status)
        assertEquals(id, ownerGet.get("reportDefinitionDetails").asJsonObject.get("id").asString)
        return id
    }

    private fun verifyNoAccessWithoutSharing(baseUri: String, id: String) {
        validateErrorResponse(
            executeRequest(reportsReadClient, RestRequest.Method.GET.name, "$baseUri/definition/$id", "", RestStatus.FORBIDDEN.status),
            RestStatus.FORBIDDEN.status, "security_exception"
        )
        validateErrorResponse(
            executeRequest(reportsNoAccessClient, RestRequest.Method.GET.name, "$baseUri/definition/$id", "", RestStatus.FORBIDDEN.status),
            RestStatus.FORBIDDEN.status, "security_exception"
        )
    }

    private fun testReadOnlyAccessSharing(baseUri: String, id: String) {
        shareConfig(reportsFullClient, shareWithUserPayload(id, Utils.REPORT_DEFINITION_TYPE, reportReadOnlyAccessLevel, reportReadUser))
        awaitVisible(baseUri, "definition/$id", reportsReadClient)

        val readGet = executeRequest(reportsReadClient, RestRequest.Method.GET.name, "$baseUri/definition/$id", "", RestStatus.OK.status)
        assertEquals(id, readGet.get("reportDefinitionDetails").asJsonObject.get("id").asString)

        validateErrorResponse(
            executeRequest(reportsReadClient, RestRequest.Method.PUT.name, "$baseUri/definition/$id",
                constructReportDefinitionRequest(name = "read_user_update_attempt"), RestStatus.FORBIDDEN.status),
            RestStatus.FORBIDDEN.status, "security_exception"
        )
        validateErrorResponse(
            executeRequest(reportsReadClient, RestRequest.Method.DELETE.name, "$baseUri/definition/$id", "", RestStatus.FORBIDDEN.status),
            RestStatus.FORBIDDEN.status, "security_exception"
        )
    }

    private fun testFullAccessSharing(baseUri: String, id: String) {
        shareConfig(reportsFullClient, shareWithUserPayload(id, Utils.REPORT_DEFINITION_TYPE, reportFullAccessLevel, reportNoAccessUser))
        awaitVisible(baseUri, "definition/$id", reportsNoAccessClient)

        val noAccessGet = executeRequest(reportsNoAccessClient, RestRequest.Method.GET.name, "$baseUri/definition/$id", "", RestStatus.OK.status)
        assertEquals(id, noAccessGet.get("reportDefinitionDetails").asJsonObject.get("id").asString)

        val update = executeRequest(reportsNoAccessClient, RestRequest.Method.PUT.name, "$baseUri/definition/$id",
            constructReportDefinitionRequest(name = "no_access_user_updated"), RestStatus.OK.status)
        assertEquals(id, update.get("reportDefinitionId").asString)

        val verify = executeRequest(reportsFullClient, RestRequest.Method.GET.name, "$baseUri/definition/$id", "", RestStatus.OK.status)
        assertEquals("no_access_user_updated",
            verify.get("reportDefinitionDetails").asJsonObject.get("reportDefinition").asJsonObject.get("name").asString)
    }

    private fun cleanupReportDefinition(baseUri: String, id: String) {
        val del = executeRequest(reportsFullClient, RestRequest.Method.DELETE.name, "$baseUri/definition/$id", "", RestStatus.OK.status)
        assertEquals(id, del.get("reportDefinitionId").asString)
    }

    fun `test report definition CRUD with resource sharing`() {
        testReportDefinitionCRUDWithResourceSharing(BASE_REPORTS_URI)
    }

    // ------------------------------------------------------------------
    // List report definitions
    // ------------------------------------------------------------------

    @Suppress("LongMethod")
    private fun testListReportDefinitionsWithResourceSharing(baseUri: String) {
        if (!isHttps()) return
        if (!isResourceSharingFeatureEnabled()) return

        step("create 3 definitions")
        fun createDef(name: String): String {
            val resp = executeRequest(reportsFullClient, RestRequest.Method.POST.name, "$baseUri/definition",
                constructReportDefinitionRequest(name = name), RestStatus.OK.status)
            return resp.get("reportDefinitionId").asString.also { log.info("created definition '$name' -> $it") }
        }
        val def1Id = createDef("definition_1")
        val def2Id = createDef("definition_2")
        val def3Id = createDef("definition_3")

        step("wait for owner to see all 3")
        awaitDefinitionCount(baseUri, reportsFullClient, 3)

        step("verify read user sees 0 before sharing")
        assertEquals(0, executeRequest(reportsReadClient, RestRequest.Method.GET.name, "$baseUri/definitions", "", RestStatus.OK.status)
            .get("totalHits").asInt)

        step("no-access user gets forbidden on list")
        executeRequest(reportsNoAccessClient, RestRequest.Method.GET.name, "$baseUri/definitions", "", RestStatus.FORBIDDEN.status)

        step("share def1 with read user, def2 with no-access user")
        shareConfig(reportsFullClient, shareWithUserPayload(def1Id, Utils.REPORT_DEFINITION_TYPE, reportReadOnlyAccessLevel, reportReadUser))
        shareConfig(reportsFullClient, shareWithUserPayload(def2Id, Utils.REPORT_DEFINITION_TYPE, reportFullAccessLevel, reportNoAccessUser))

        step("wait for read user to see def1")
        awaitDefinitionCount(baseUri, reportsReadClient, 1)

        step("wait for no-access user to see def2 via GET")
        awaitVisible(baseUri, "definition/$def2Id", reportsNoAccessClient)

        step("assert read user sees exactly def1")
        val readList = executeRequest(reportsReadClient, RestRequest.Method.GET.name, "$baseUri/definitions", "", RestStatus.OK.status)
        assertEquals(1, readList.get("totalHits").asInt)
        assertEquals(def1Id, readList.get("reportDefinitionDetailsList").asJsonArray[0].asJsonObject.get("id").asString)

        step("assert no-access user still forbidden on list but can GET def2")
        executeRequest(reportsNoAccessClient, RestRequest.Method.GET.name, "$baseUri/definitions", "", RestStatus.FORBIDDEN.status)
        val noAccessDef = executeRequest(reportsNoAccessClient, RestRequest.Method.GET.name, "$baseUri/definition/$def2Id", "", RestStatus.OK.status)
        assertEquals(def2Id, noAccessDef.get("reportDefinitionDetails").asJsonObject.get("id").asString)

        step("cleanup")
        listOf(def1Id, def2Id, def3Id).forEach {
            executeRequest(reportsFullClient, RestRequest.Method.DELETE.name, "$baseUri/definition/$it", "", RestStatus.OK.status)
        }
    }

    fun `test list report definitions with resource sharing`() {
        testListReportDefinitionsWithResourceSharing(BASE_REPORTS_URI)
    }

    // ------------------------------------------------------------------
    // Patch sharing operations
    // ------------------------------------------------------------------

    private fun testPatchSharingOperations(baseUri: String) {
        if (!isHttps()) return
        if (!isResourceSharingFeatureEnabled()) return

        step("create definition for patch test")
        val createResponse = executeRequest(reportsFullClient, RestRequest.Method.POST.name, "$baseUri/definition",
            constructReportDefinitionRequest(name = "patch_test"), RestStatus.OK.status)
        val id = createResponse.get("reportDefinitionId").asString
        log.info("created definition $id")

        step("wait for sharing record to be created before patching")
        awaitVisible(baseUri, "definition/$id", reportsFullClient)

        step("patch share with read user")
        patchSharingInfo(reportsFullClient, PatchSharingInfoPayloadBuilder()
            .configId(id).configType(Utils.REPORT_DEFINITION_TYPE)
            .apply { share(mutableMapOf(Recipient.USERS to mutableSetOf(reportReadUser)), reportReadOnlyAccessLevel) }
            .build())

        step("wait for read user to see definition")
        awaitVisible(baseUri, "definition/$id", reportsReadClient)
        assertNotNull(executeRequest(reportsReadClient, RestRequest.Method.GET.name, "$baseUri/definition/$id", "", RestStatus.OK.status))

        step("patch revoke from read user")
        patchSharingInfo(reportsFullClient, PatchSharingInfoPayloadBuilder()
            .configId(id).configType(Utils.REPORT_DEFINITION_TYPE)
            .apply { revoke(mutableMapOf(Recipient.USERS to mutableSetOf(reportReadUser)), reportReadOnlyAccessLevel) }
            .build())

        step("wait for revocation to propagate")
        waitForRevokeNonVisibility(RestRequest.Method.GET.name, "$baseUri/definition/$id", null, reportsReadClient)

        validateErrorResponse(
            executeRequest(reportsReadClient, RestRequest.Method.GET.name, "$baseUri/definition/$id", "", RestStatus.FORBIDDEN.status),
            RestStatus.FORBIDDEN.status, "security_exception"
        )

        step("cleanup")
        executeRequest(reportsFullClient, RestRequest.Method.DELETE.name, "$baseUri/definition/$id", "", RestStatus.OK.status)
    }

    fun `test patch sharing operations`() {
        testPatchSharingOperations(BASE_REPORTS_URI)
    }

    // ------------------------------------------------------------------
    // Report instance with resource sharing
    // ------------------------------------------------------------------

    private fun testReportInstanceWithResourceSharing(baseUri: String) {
        if (!isHttps()) return
        if (!isResourceSharingFeatureEnabled()) return

        step("create definition")
        val defId = executeRequest(reportsFullClient, RestRequest.Method.POST.name, "$baseUri/definition",
            constructReportDefinitionRequest(), RestStatus.OK.status).get("reportDefinitionId").asString
        log.info("created definition $defId")

        step("wait for owner visibility of definition")
        awaitVisible(baseUri, "definition/$defId", reportsFullClient)

        step("generate instance")
        val instanceId = executeRequest(reportsFullClient, RestRequest.Method.POST.name, "$baseUri/on_demand/$defId",
            "{}", RestStatus.OK.status).get("reportInstance").asJsonObject.get("id").asString
        log.info("created instance $instanceId")

        step("wait for owner visibility of instance")
        awaitVisible(baseUri, "instance/$instanceId", reportsFullClient)

        step("read user cannot access instance before sharing")
        validateErrorResponse(
            executeRequest(reportsReadClient, RestRequest.Method.GET.name, "$baseUri/instance/$instanceId", "", RestStatus.FORBIDDEN.status),
            RestStatus.FORBIDDEN.status, "security_exception"
        )

        step("share instance with read user")
        shareConfig(reportsFullClient, shareWithUserPayload(instanceId, Utils.REPORT_INSTANCE_TYPE, reportInstanceReadOnlyAccessLevel, reportReadUser))

        step("wait for read user to see instance")
        awaitVisible(baseUri, "instance/$instanceId", reportsReadClient)

        step("assert read user can GET instance")
        val readGet = executeRequest(reportsReadClient, RestRequest.Method.GET.name, "$baseUri/instance/$instanceId", "", RestStatus.OK.status)
        assertEquals(instanceId, readGet.get("reportInstance").asJsonObject.get("id").asString)

        step("cleanup")
        executeRequest(reportsFullClient, RestRequest.Method.DELETE.name, "$baseUri/definition/$defId", "", RestStatus.OK.status)
    }

    fun `test report instance with resource sharing`() {
        testReportInstanceWithResourceSharing(BASE_REPORTS_URI)
    }

    // ------------------------------------------------------------------
    // List report instances with resource sharing
    // ------------------------------------------------------------------

    private fun testListReportInstancesWithResourceSharing(baseUri: String) {
        if (!isHttps()) return
        if (!isResourceSharingFeatureEnabled()) return

        step("create definition")
        val defId = executeRequest(reportsFullClient, RestRequest.Method.POST.name, "$baseUri/definition",
            constructReportDefinitionRequest(), RestStatus.OK.status).get("reportDefinitionId").asString
        log.info("created definition $defId")

        step("wait for owner visibility of definition")
        awaitVisible(baseUri, "definition/$defId", reportsFullClient)

        step("generate 2 instances")
        val instance1Id = executeRequest(reportsFullClient, RestRequest.Method.POST.name, "$baseUri/on_demand/$defId",
            "{}", RestStatus.OK.status).get("reportInstance").asJsonObject.get("id").asString
        val instance2Id = executeRequest(reportsFullClient, RestRequest.Method.POST.name, "$baseUri/on_demand/$defId",
            "{}", RestStatus.OK.status).get("reportInstance").asJsonObject.get("id").asString
        log.info("created instances $instance1Id, $instance2Id")

        step("wait for owner to see both instances")
        awaitInstanceCount(baseUri, reportsFullClient, 2)

        step("read user sees 0 before sharing")
        assertEquals(0, executeRequest(reportsReadClient, RestRequest.Method.GET.name, "$baseUri/instances", "", RestStatus.OK.status)
            .get("totalHits").asInt)

        step("share instance1 with read user")
        shareConfig(reportsFullClient, shareWithUserPayload(instance1Id, Utils.REPORT_INSTANCE_TYPE, reportInstanceReadOnlyAccessLevel, reportReadUser))

        step("wait for read user to see instance1 in list")
        awaitInstanceCount(baseUri, reportsReadClient, 1)

        step("assert read user sees only instance1")
        val readList = executeRequest(reportsReadClient, RestRequest.Method.GET.name, "$baseUri/instances", "", RestStatus.OK.status)
        assertEquals(1, readList.get("totalHits").asInt)
        assertEquals(instance1Id, readList.get("reportInstanceList").asJsonArray[0].asJsonObject.get("id").asString)

        step("cleanup")
        executeRequest(reportsFullClient, RestRequest.Method.DELETE.name, "$baseUri/definition/$defId", "", RestStatus.OK.status)
    }

    fun `test list report instances with resource sharing`() {
        testListReportInstancesWithResourceSharing(BASE_REPORTS_URI)
    }

    // ------------------------------------------------------------------
    // Parent-hierarchy access: instances visible via definition sharing
    // ------------------------------------------------------------------

    private fun testListInstancesViaParentDefinitionAccess(baseUri: String) {
        if (!isHttps()) return
        if (!isResourceSharingFeatureEnabled()) return

        step("create definition")
        val defId = executeRequest(reportsFullClient, RestRequest.Method.POST.name, "$baseUri/definition",
            constructReportDefinitionRequest(name = "parent_hierarchy_def"), RestStatus.OK.status)
            .get("reportDefinitionId").asString
        log.info("created definition $defId")

        // Wait for sharing record to be written before generating instances (on_demand checks definition access)
        step("wait for owner visibility of definition")
        awaitVisible(baseUri, "definition/$defId", reportsFullClient)

        step("generate 2 instances")
        val instance1Id = executeRequest(reportsFullClient, RestRequest.Method.POST.name, "$baseUri/on_demand/$defId",
            "{}", RestStatus.OK.status).get("reportInstance").asJsonObject.get("id").asString
        val instance2Id = executeRequest(reportsFullClient, RestRequest.Method.POST.name, "$baseUri/on_demand/$defId",
            "{}", RestStatus.OK.status).get("reportInstance").asJsonObject.get("id").asString
        log.info("created instances $instance1Id, $instance2Id")

        step("wait for owner to see both instances")
        awaitInstanceCount(baseUri, reportsFullClient, 2)

        // Wait for instance sharing records to exist (needed for direct GET-by-ID via hasPermission)
        awaitVisible(baseUri, "instance/$instance1Id", reportsFullClient)
        awaitVisible(baseUri, "instance/$instance2Id", reportsFullClient)

        step("read user sees 0 instances and gets 403 on direct GET before sharing")
        assertEquals(0, executeRequest(reportsReadClient, RestRequest.Method.GET.name, "$baseUri/instances", "", RestStatus.OK.status)
            .get("totalHits").asInt)
        validateErrorResponse(
            executeRequest(reportsReadClient, RestRequest.Method.GET.name, "$baseUri/instance/$instance1Id", "", RestStatus.FORBIDDEN.status),
            RestStatus.FORBIDDEN.status, "security_exception"
        )

        step("share DEFINITION (parent) with read user — instances are NOT directly shared")
        shareConfig(reportsFullClient, shareWithUserPayload(defId, Utils.REPORT_DEFINITION_TYPE, reportReadOnlyAccessLevel, reportReadUser))

        // Wait for the definition's all_shared_principals to include the read user.
        // This is the prerequisite for the two-phase instance query to work:
        // phase 1 queries all_shared_principals on the definition doc, so we must
        // wait until that field is visible to the read user before asserting the list.
        step("wait for read user to see definition (confirms all_shared_principals is updated)")
        awaitVisible(baseUri, "definition/$defId", reportsReadClient)

        step("assert read user sees both instances via parent-inherited access")
        val readList = executeRequest(reportsReadClient, RestRequest.Method.GET.name, "$baseUri/instances", "", RestStatus.OK.status)
        assertEquals(2, readList.get("totalHits").asInt)
        val ids = readList.get("reportInstanceList").asJsonArray.map { it.asJsonObject.get("id").asString }.toSet()
        assertTrue(ids.contains(instance1Id))
        assertTrue(ids.contains(instance2Id))

        step("assert read user can GET each instance individually")
        awaitVisible(baseUri, "instance/$instance1Id", reportsReadClient)
        awaitVisible(baseUri, "instance/$instance2Id", reportsReadClient)
        assertEquals(instance1Id,
            executeRequest(reportsReadClient, RestRequest.Method.GET.name, "$baseUri/instance/$instance1Id", "", RestStatus.OK.status)
                .get("reportInstance").asJsonObject.get("id").asString)
        assertEquals(instance2Id,
            executeRequest(reportsReadClient, RestRequest.Method.GET.name, "$baseUri/instance/$instance2Id", "", RestStatus.OK.status)
                .get("reportInstance").asJsonObject.get("id").asString)

        step("cleanup")
        executeRequest(reportsFullClient, RestRequest.Method.DELETE.name, "$baseUri/definition/$defId", "", RestStatus.OK.status)
    }

    fun `test list instances via parent definition access`() {
        testListInstancesViaParentDefinitionAccess(BASE_REPORTS_URI)
    }
}
