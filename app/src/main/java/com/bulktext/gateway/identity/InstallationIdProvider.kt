package com.bulktext.gateway.identity

interface InstallationIdProvider {
    fun getInstallationId(): String
    fun resetInstallationId(): String
}
