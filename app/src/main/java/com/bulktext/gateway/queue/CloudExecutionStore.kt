package com.bulktext.gateway.queue

interface CloudExecutionStore {
    fun nextReady(deviceId: String): CloudExecutableJob?
    fun nextReadySince(deviceId: String, notBeforeMillis: Long): CloudExecutableJob?
    fun nextReadyForCampaign(deviceId: String, campaignId: String): CloudExecutableJob?
    fun nextReadyForCampaignSince(deviceId: String, campaignId: String, notBeforeMillis: Long): CloudExecutableJob?
    fun getExecutable(deviceId: String, jobId: String): CloudExecutableJob?
    fun beginSubmission(deviceId: String, jobId: String, clientAttemptId: String): Boolean
    fun markSubmitted(deviceId: String, jobId: String): Boolean
    fun markSubmissionUnknown(deviceId: String, jobId: String, note: String): Boolean
    fun noteReadyBlock(deviceId: String, jobId: String, note: String)
    fun recoverInterruptedSubmissions(): Int
    fun executionCounts(deviceId: String): CloudExecutionCounts
    fun campaignExecutionCounts(deviceId: String, campaignId: String): CloudExecutionCounts
}
