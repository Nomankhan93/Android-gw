package com.bulktext.gateway.identity

import android.content.Context
import java.util.UUID

class InstallationIdStore(context: Context) : InstallationIdProvider {
    private val prefs = context.getSharedPreferences("gateway_installation_prefs", Context.MODE_PRIVATE)

    @Synchronized
    override fun getInstallationId(): String {
        val existing = prefs.getString(KEY_INSTALLATION_ID, null)
        if (!existing.isNullOrBlank()) return existing

        val generated = UUID.randomUUID().toString()
        persistOrThrow(generated)
        return generated
    }

    @Synchronized
    override fun resetInstallationId(): String {
        val generated = UUID.randomUUID().toString()
        persistOrThrow(generated)
        return generated
    }

    private fun persistOrThrow(value: String) {
        val committed = prefs.edit().putString(KEY_INSTALLATION_ID, value).commit()
        if (!committed) {
            throw IllegalStateException("Unable to persist gateway installation identity")
        }
    }

    companion object {
        private const val KEY_INSTALLATION_ID = "installation_id"
    }
}
