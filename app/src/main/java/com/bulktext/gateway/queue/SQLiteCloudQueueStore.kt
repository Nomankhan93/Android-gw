package com.bulktext.gateway.queue

import android.app.Activity
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Build
import android.telephony.SmsManager
import com.bulktext.gateway.cloud.GatewayAttemptEvent
import com.bulktext.gateway.cloud.GatewayQueueJob
import com.bulktext.gateway.cloud.GatewayRecoveryRequest
import com.bulktext.gateway.cloud.QueueLeaseReceipt
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Durable local cloud queue + 0.18.3 SMS attempt journal.
 *
 * Database v6 deliberately keeps the v2 execution_state CHECK unchanged and
 * records callback truth in outcome_state. This avoids rewriting the queue table
 * while preserving the irreversible SmsManager boundary from 0.17.x.
 */
class SQLiteCloudQueueStore(context: Context) : SQLiteOpenHelper(context, "cloud_queue.db", null, 7), CloudQueueLocalStore, CloudExecutionStore {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE cloud_jobs(
              job_id TEXT PRIMARY KEY,
              device_id TEXT NOT NULL,
              dispatch_id TEXT NOT NULL,
              campaign_id TEXT NOT NULL,
              source_row_number INTEGER NOT NULL,
              normalized_e164 TEXT NOT NULL,
              rendered_message TEXT NOT NULL,
              sms_encoding TEXT NOT NULL,
              segment_count INTEGER NOT NULL,
              gateway_sim_id TEXT NOT NULL,
              sim_subscription_id INTEGER NOT NULL,
              sim_slot_index INTEGER NOT NULL,
              sim_identity_hash TEXT NOT NULL,
              lease_token TEXT NOT NULL,
              lease_version INTEGER NOT NULL,
              leased_at INTEGER NOT NULL,
              lease_expires_at INTEGER NOT NULL,
              persisted_at INTEGER NOT NULL,
              ack_state TEXT NOT NULL CHECK(ack_state IN ('PENDING','ACKED')),
              acked_at INTEGER,
              execution_state TEXT NOT NULL DEFAULT 'READY' CHECK(execution_state IN ('READY','SUBMITTING','SUBMITTED','UNKNOWN')),
              execution_note TEXT,
              submission_started_at INTEGER,
              submitted_at INTEGER,
              current_attempt_id TEXT,
              current_attempt_no INTEGER,
              outcome_state TEXT NOT NULL DEFAULT 'NONE' CHECK(outcome_state IN ('NONE','PREPARED','SUBMITTED','SENT','DELIVERED','FAILED','UNKNOWN','UNKNOWN_RESOLVED')),
              outcome_note TEXT,
              retry_ready_at INTEGER,
              unknown_resolved INTEGER NOT NULL DEFAULT 0 CHECK(unknown_resolved IN (0,1)),
              sent_at INTEGER,
              delivered_at INTEGER,
              failed_at INTEGER
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX cloud_jobs_device_ack_idx ON cloud_jobs(device_id, ack_state, persisted_at)")
        db.execSQL("CREATE INDEX cloud_jobs_device_execution_idx ON cloud_jobs(device_id, ack_state, execution_state, persisted_at)")
        db.execSQL("CREATE INDEX cloud_jobs_device_outcome_idx ON cloud_jobs(device_id, outcome_state, persisted_at)")
        db.execSQL("CREATE TABLE cloud_execution_events(id INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER NOT NULL, job_id TEXT NOT NULL, detail TEXT NOT NULL)")
        db.execSQL("CREATE TABLE queue_sync_meta(device_id TEXT PRIMARY KEY, last_sync_at INTEGER, last_error TEXT)")
        createAttemptTables(db)
        createDiagnosticTables(db)
        upgradeContract7(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        var version = oldVersion
        if (version == 1 && newVersion >= 2) {
            db.execSQL("ALTER TABLE cloud_jobs ADD COLUMN execution_state TEXT NOT NULL DEFAULT 'READY' CHECK(execution_state IN ('READY','SUBMITTING','SUBMITTED','UNKNOWN'))")
            db.execSQL("ALTER TABLE cloud_jobs ADD COLUMN execution_note TEXT")
            db.execSQL("ALTER TABLE cloud_jobs ADD COLUMN submission_started_at INTEGER")
            db.execSQL("ALTER TABLE cloud_jobs ADD COLUMN submitted_at INTEGER")
            db.execSQL("CREATE INDEX cloud_jobs_device_execution_idx ON cloud_jobs(device_id, ack_state, execution_state, persisted_at)")
            db.execSQL("CREATE TABLE cloud_execution_events(id INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER NOT NULL, job_id TEXT NOT NULL, detail TEXT NOT NULL)")
            version = 2
        }
        if (version == 2 && newVersion >= 3) {
            db.execSQL("ALTER TABLE cloud_jobs ADD COLUMN current_attempt_id TEXT")
            db.execSQL("ALTER TABLE cloud_jobs ADD COLUMN current_attempt_no INTEGER")
            db.execSQL("ALTER TABLE cloud_jobs ADD COLUMN outcome_state TEXT NOT NULL DEFAULT 'NONE' CHECK(outcome_state IN ('NONE','PREPARED','SUBMITTED','SENT','DELIVERED','FAILED','UNKNOWN','UNKNOWN_RESOLVED'))")
            db.execSQL("ALTER TABLE cloud_jobs ADD COLUMN outcome_note TEXT")
            db.execSQL("ALTER TABLE cloud_jobs ADD COLUMN retry_ready_at INTEGER")
            db.execSQL("ALTER TABLE cloud_jobs ADD COLUMN unknown_resolved INTEGER NOT NULL DEFAULT 0 CHECK(unknown_resolved IN (0,1))")
            db.execSQL("ALTER TABLE cloud_jobs ADD COLUMN sent_at INTEGER")
            db.execSQL("ALTER TABLE cloud_jobs ADD COLUMN delivered_at INTEGER")
            db.execSQL("ALTER TABLE cloud_jobs ADD COLUMN failed_at INTEGER")
            db.execSQL("CREATE INDEX cloud_jobs_device_outcome_idx ON cloud_jobs(device_id, outcome_state, persisted_at)")
            // Preserve 0.17 safety state during the v2 -> v3 upgrade. Historical
            // UNKNOWN must remain blocking and historical SUBMITTED must never
            // become READY/retryable merely because callback columns are new.
            db.execSQL("UPDATE cloud_jobs SET outcome_state='UNKNOWN', outcome_note=execution_note, unknown_resolved=0 WHERE execution_state='UNKNOWN'")
            db.execSQL("UPDATE cloud_jobs SET outcome_state='SUBMITTED' WHERE execution_state='SUBMITTED'")
            createAttemptTables(db)
            version = 3
        }
        if (version == 3 && newVersion >= 4) {
            // 0.18.1 real-device safety correction. 0.18 treated an all-failed
            // SENT callback set as proof of zero external effect. A vivo/Ufone
            // acceptance test demonstrated physical delivery despite those
            // callback failures. Every callback-derived FAILED attempt is thus
            // ambiguous after SmsManager and must become UNKNOWN with no retry.
            val note = "0.18.1 safety migration: callback failure after SmsManager is ambiguous; resend is not authorized."
            val now = System.currentTimeMillis()
            val av = ContentValues().apply {
                put("state", CloudExecutionPolicy.OUTCOME_UNKNOWN)
                put("safe_retry_eligible", 0)
                put("note", note)
                put("unknown_at", now)
            }
            db.update("cloud_attempts", av, "state='FAILED'", null)
            val jv = ContentValues().apply {
                put("execution_state", CloudExecutionPolicy.UNKNOWN)
                put("outcome_state", CloudExecutionPolicy.OUTCOME_UNKNOWN)
                put("unknown_resolved", 0)
                put("execution_note", note)
                put("outcome_note", note)
            }
            db.update("cloud_jobs", jv, "outcome_state='FAILED' AND current_attempt_id IS NOT NULL", null)
            version = 4
        }
        if (version == 4 && newVersion >= 5) {
            // createDiagnosticTables() always creates the latest diagnostic shape.
            // A direct v4 -> v6 upgrade therefore already has error_code and must
            // not run the v5 ALTER again.
            createDiagnosticTables(db)
            version = if (newVersion >= 6) 6 else 5
        }
        if (version == 5 && newVersion >= 6) {
            db.execSQL("ALTER TABLE cloud_attempt_diagnostics ADD COLUMN error_code INTEGER")
            version = 6
        }
        if (version == 6 && newVersion >= 7) { upgradeContract7(db); version = 7 }
        if (version != newVersion) throw IllegalStateException("Unsupported cloud queue database upgrade $oldVersion->$newVersion")
    }

    private fun upgradeContract7(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE cloud_attempts ADD COLUMN conflict_latched INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE queue_sync_meta ADD COLUMN last_attempt_at INTEGER")
        db.execSQL("UPDATE queue_sync_meta SET last_attempt_at=last_sync_at")
        db.execSQL("UPDATE queue_sync_meta SET last_sync_at=NULL WHERE last_error IS NOT NULL")
        db.execSQL("ALTER TABLE cloud_jobs ADD COLUMN block_code TEXT")
        db.execSQL("ALTER TABLE cloud_jobs ADD COLUMN block_retry_at INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE cloud_callback_outbox ADD COLUMN delivery_format TEXT")
        db.execSQL("ALTER TABLE cloud_callback_outbox ADD COLUMN delivery_status INTEGER")
        db.execSQL("ALTER TABLE cloud_callback_outbox ADD COLUMN delivery_sha256 TEXT")
        db.execSQL("ALTER TABLE cloud_callback_outbox ADD COLUMN disposition TEXT")
        db.execSQL("ALTER TABLE cloud_callback_outbox ADD COLUMN device_id TEXT")
        db.execSQL("UPDATE cloud_callback_outbox SET device_id=(SELECT j.device_id FROM cloud_attempts a JOIN cloud_jobs j ON j.job_id=a.job_id WHERE a.client_attempt_id=cloud_callback_outbox.client_attempt_id)")
        db.execSQL("CREATE TABLE recovery_ack_outbox(recovery_id TEXT PRIMARY KEY,device_id TEXT NOT NULL,outcome TEXT NOT NULL CHECK(outcome IN ('applied','not_applicable','rejected')),acked_at INTEGER)")
        db.execSQL("INSERT INTO recovery_ack_outbox SELECT r.recovery_id,j.device_id,CASE WHEN r.action='safe_retry' THEN 'rejected' ELSE 'applied' END,NULL FROM cloud_recovery_applied r JOIN cloud_jobs j ON j.job_id=r.job_id")
        db.execSQL("UPDATE cloud_attempts SET conflict_latched=1 WHERE note LIKE 'Conflicting%'")
        // Old callbacks did not persist status-report evidence. Retain observations,
        // but require new complete evidence before making a delivery claim.
        db.execSQL("UPDATE cloud_attempt_parts SET delivery_state='PENDING' WHERE delivery_state='DELIVERED'")
        db.execSQL("UPDATE cloud_attempts SET state='SENT' WHERE state='DELIVERED'")
        db.execSQL("UPDATE cloud_jobs SET outcome_state='SENT' WHERE outcome_state='DELIVERED'")
    }

    private fun createAttemptTables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS cloud_attempts(
              client_attempt_id TEXT PRIMARY KEY,
              job_id TEXT NOT NULL,
              attempt_number INTEGER,
              expected_parts INTEGER NOT NULL CHECK(expected_parts > 0),
              state TEXT NOT NULL CHECK(state IN ('PREPARED','SUBMITTING','SUBMITTED','SENT','DELIVERED','FAILED','UNKNOWN')),
              safe_retry_eligible INTEGER NOT NULL DEFAULT 0 CHECK(safe_retry_eligible IN (0,1)),
              note TEXT,
              created_at INTEGER NOT NULL,
              submission_started_at INTEGER,
              submitted_at INTEGER,
              sent_at INTEGER,
              delivered_at INTEGER,
              failed_at INTEGER,
              unknown_at INTEGER
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS cloud_attempts_job_created_idx ON cloud_attempts(job_id, created_at DESC)")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS cloud_attempt_parts(
              client_attempt_id TEXT NOT NULL,
              part_index INTEGER NOT NULL,
              sent_state TEXT NOT NULL DEFAULT 'PENDING' CHECK(sent_state IN ('PENDING','SENT','FAILED')),
              sent_result_code INTEGER,
              sent_at INTEGER,
              delivery_state TEXT NOT NULL DEFAULT 'PENDING' CHECK(delivery_state IN ('PENDING','DELIVERED','FAILED')),
              delivery_result_code INTEGER,
              delivered_at INTEGER,
              PRIMARY KEY(client_attempt_id, part_index)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS cloud_callback_outbox(
              event_id TEXT PRIMARY KEY,
              client_attempt_id TEXT NOT NULL,
              event_type TEXT NOT NULL CHECK(event_type IN ('submitted','sent','delivery','unknown')),
              part_index INTEGER,
              result_code INTEGER,
              occurred_at INTEGER NOT NULL,
              synced_at INTEGER,
              sync_attempts INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS cloud_callback_outbox_pending_idx ON cloud_callback_outbox(synced_at, occurred_at)")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS cloud_recovery_applied(
              recovery_id TEXT PRIMARY KEY,
              job_id TEXT NOT NULL,
              action TEXT NOT NULL CHECK(action IN ('safe_retry','skip_unknown')),
              client_attempt_id TEXT NOT NULL,
              applied_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    private fun createDiagnosticTables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS cloud_attempt_diagnostics(
              diagnostic_id INTEGER PRIMARY KEY AUTOINCREMENT,
              client_attempt_id TEXT NOT NULL,
              job_id TEXT NOT NULL,
              part_index INTEGER NOT NULL,
              kind TEXT NOT NULL,
              result_code INTEGER NOT NULL,
              error_code INTEGER,
              symbolic_result TEXT NOT NULL,
              subscription_id INTEGER NOT NULL,
              slot_index INTEGER NOT NULL,
              sms_api_path TEXT NOT NULL,
              multipart INTEGER NOT NULL,
              flags INTEGER NOT NULL,
              correlation_key TEXT NOT NULL,
              duplicate_count INTEGER NOT NULL DEFAULT 0,
              elapsed_ms INTEGER,
              occurred_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS cloud_attempt_diagnostics_attempt_part_idx ON cloud_attempt_diagnostics(client_attempt_id, part_index, kind)")
    }

    @Synchronized
    override fun persist(deviceId: String, job: GatewayQueueJob): PersistResult {
        require(deviceId.isNotBlank()) { "Device id is required" }
        val db = writableDatabase
        db.beginTransaction()
        try {
            val existing = readStoredShape(db, job.jobId)
            if (existing != null && existing.first != deviceId) throw CloudQueueIntegrityException("Cloud job id is already owned by a different local gateway identity")
            val decision = CloudJobReconciler.decide(existing?.second, job)
            when (decision) {
                PersistResult.INSERTED -> db.insertOrThrow("cloud_jobs", null, values(deviceId, job, System.currentTimeMillis(), false))
                PersistResult.SAME_LEASE -> Unit
                PersistResult.NEWER_LEASE -> {
                    val v = ContentValues().apply {
                        put("lease_token", job.leaseToken); put("lease_version", job.leaseVersion)
                        put("leased_at", job.leasedAt); put("lease_expires_at", job.leaseExpiresAt)
                        put("ack_state", "PENDING"); putNull("acked_at")
                    }
                    if (db.update("cloud_jobs", v, "job_id=? AND device_id=?", arrayOf(job.jobId, deviceId)) != 1) {
                        throw CloudQueueIntegrityException("Unable to update the local cloud job lease")
                    }
                }
            }
            db.setTransactionSuccessful()
            return decision
        } finally { db.endTransaction() }
    }

    @Synchronized
    override fun pendingAckReceipts(deviceId: String, limit: Int): List<QueueLeaseReceipt> = readableDatabase.query(
        "cloud_jobs", arrayOf("job_id", "lease_token", "lease_version"), "device_id=? AND ack_state='PENDING'", arrayOf(deviceId), null, null,
        "persisted_at ASC, job_id ASC", limit.coerceIn(1, 25).toString()
    ).use { c -> buildList { while (c.moveToNext()) add(QueueLeaseReceipt(c.getString(0), c.getString(1), c.getInt(2))) } }

    @Synchronized
    override fun markAcknowledged(deviceId: String, receipts: List<QueueLeaseReceipt>): Int {
        if (receipts.isEmpty()) return 0
        val db = writableDatabase
        var count = 0
        db.beginTransaction()
        try {
            receipts.forEach { r ->
                val v = ContentValues().apply { put("ack_state", "ACKED"); put("acked_at", System.currentTimeMillis()); put("execution_state", CloudExecutionPolicy.READY) }
                count += db.update("cloud_jobs", v, "device_id=? AND job_id=? AND lease_token=? AND lease_version=?", arrayOf(deviceId, r.jobId, r.leaseToken, r.leaseVersion.toString()))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return count
    }

    @Synchronized
    override fun counts(deviceId: String): CloudQueueCounts {
        val db = readableDatabase
        val total = scalarInt(db, "SELECT count(*) FROM cloud_jobs WHERE device_id=?", arrayOf(deviceId))
        val pending = scalarInt(db, "SELECT count(*) FROM cloud_jobs WHERE device_id=? AND ack_state='PENDING'", arrayOf(deviceId))
        val lastSync = db.rawQuery("SELECT last_sync_at FROM queue_sync_meta WHERE device_id=?", arrayOf(deviceId)).use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null }
        return CloudQueueCounts(total, pending, total - pending, lastSync)
    }

    @Synchronized
    override fun recordSync(deviceId: String, atMillis: Long, error: String?) {
        val db = writableDatabase
        db.execSQL("INSERT OR IGNORE INTO queue_sync_meta(device_id) VALUES(?)", arrayOf(deviceId))
        val v = ContentValues().apply { put("last_attempt_at", atMillis); if (error == null) { put("last_sync_at", atMillis); putNull("last_error") } else put("last_error", error.take(300)) }
        db.update("queue_sync_meta", v, "device_id=?", arrayOf(deviceId))
    }

    fun syncHealth(deviceId: String): Pair<Long?,String?> = readableDatabase.rawQuery("SELECT last_attempt_at,last_error FROM queue_sync_meta WHERE device_id=?", arrayOf(deviceId)).use { c ->
        if(c.moveToFirst()) (if(c.isNull(0)) null else c.getLong(0)) to (if(c.isNull(1)) null else c.getString(1)) else null to null
    }

    @Synchronized
    override fun exportDiagnostics(deviceId: String?): String {
        val root = JSONObject().put("version", 5)
        val devices = JSONArray()
        val db = readableDatabase
        val deviceIds = if (deviceId != null) listOf(deviceId) else db.rawQuery("SELECT DISTINCT device_id FROM cloud_jobs ORDER BY device_id", null).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
        deviceIds.forEach { id ->
            val queueCounts = counts(id); val exec = executionCounts(id)
            val device = JSONObject()
                .put("deviceIdPrefix", id.take(8)).put("persistedJobs", queueCounts.persistedJobs)
                .put("unacknowledgedJobs", queueCounts.unacknowledgedJobs).put("acknowledgedJobs", queueCounts.acknowledgedJobs)
                .put("ready", exec.ready).put("submitting", exec.submitting).put("submitted", exec.submitted)
                .put("sent", exec.sent).put("delivered", exec.delivered).put("failed", exec.failed).put("unknown", exec.unknown)
            if (queueCounts.latestSyncAt != null) device.put("lastSyncAt", queueCounts.latestSyncAt)
            val jobs = JSONArray()
            db.query("cloud_jobs", DIAGNOSTIC_COLUMNS, "device_id=?", arrayOf(id), null, null, "persisted_at DESC, job_id DESC", "100").use { c ->
                while (c.moveToNext()) {
                    val row = JSONObject().put("jobId", c.getString(0)).put("dispatchId", c.getString(1)).put("campaignId", c.getString(2))
                        .put("ackState", c.getString(3)).put("executionState", c.getString(4)).put("outcomeState", c.getString(5))
                        .put("persistedAt", c.getLong(6)).put("attemptNumber", if (c.isNull(7)) JSONObject.NULL else c.getInt(7))
                        .put("attemptIdPrefix", if (c.isNull(8)) JSONObject.NULL else c.getString(8).take(8))
                    if (!c.isNull(9)) row.put("executionNote", c.getString(9))
                    if (!c.isNull(10)) row.put("outcomeNote", c.getString(10))
                    jobs.put(row)
                }
            }
            device.put("jobs", jobs)
            val callbacks = JSONArray()
            db.rawQuery(
                "SELECT o.client_attempt_id,o.event_type,o.part_index,o.result_code,o.occurred_at,o.synced_at,o.sync_attempts " +
                    "FROM cloud_callback_outbox o JOIN cloud_attempts a ON a.client_attempt_id=o.client_attempt_id " +
                    "JOIN cloud_jobs j ON j.job_id=a.job_id WHERE j.device_id=? ORDER BY o.occurred_at DESC LIMIT 100",
                arrayOf(id)
            ).use { c ->
                while (c.moveToNext()) {
                    callbacks.put(JSONObject()
                        .put("attemptIdPrefix", c.getString(0).take(8))
                        .put("eventType", c.getString(1))
                        .put("partIndex", if (c.isNull(2)) JSONObject.NULL else c.getInt(2))
                        .put("resultCode", if (c.isNull(3)) JSONObject.NULL else c.getInt(3))
                        .put("occurredAt", c.getLong(4))
                        .put("syncedAt", if (c.isNull(5)) JSONObject.NULL else c.getLong(5))
                        .put("syncAttempts", c.getInt(6)))
                }
            }
            device.put("callbacks", callbacks)
            val callbackDiagnostics = JSONArray()
            db.rawQuery(
                "SELECT d.client_attempt_id,d.job_id,d.part_index,d.kind,d.result_code,d.error_code,d.symbolic_result,d.subscription_id,d.slot_index,d.sms_api_path,d.duplicate_count,d.elapsed_ms,d.occurred_at " +
                    "FROM cloud_attempt_diagnostics d JOIN cloud_attempts a ON a.client_attempt_id=d.client_attempt_id " +
                    "JOIN cloud_jobs j ON j.job_id=a.job_id WHERE j.device_id=? ORDER BY d.occurred_at DESC LIMIT 100",
                arrayOf(id)
            ).use { c ->
                while (c.moveToNext()) {
                    callbackDiagnostics.put(JSONObject()
                        .put("attemptIdPrefix", c.getString(0).take(8))
                        .put("jobIdPrefix", c.getString(1).take(8))
                        .put("partIndex", c.getInt(2))
                        .put("kind", c.getString(3))
                        .put("resultCode", c.getInt(4))
                        .put("errorCode", if (c.isNull(5)) JSONObject.NULL else c.getInt(5))
                        .put("symbolicResult", c.getString(6))
                        .put("subscriptionId", c.getInt(7))
                        .put("slotIndex", c.getInt(8))
                        .put("smsApiPath", c.getString(9))
                        .put("duplicateCount", c.getInt(10))
                        .put("elapsedMs", if (c.isNull(11)) JSONObject.NULL else c.getLong(11))
                        .put("occurredAt", c.getLong(12)))
                }
            }
            device.put("callbackDiagnostics", callbackDiagnostics)
            devices.put(device)
        }
        root.put("devices", devices)
        root.put("systemHealth", JSONObject()
            .put("androidVersion", Build.VERSION.RELEASE)
            .put("sdkInt", Build.VERSION.SDK_INT)
            .put("manufacturer", Build.MANUFACTURER)
            .put("model", Build.MODEL))
        root.put("privacy", "Recipient phone numbers, SMS bodies, gateway credentials and Supabase keys are excluded.")
        return root.toString(2)
    }

    private fun values(deviceId: String, job: GatewayQueueJob, persistedAt: Long, acknowledged: Boolean): ContentValues = ContentValues().apply {
        put("job_id", job.jobId); put("device_id", deviceId); put("dispatch_id", job.dispatchId); put("campaign_id", job.campaignId)
        put("source_row_number", job.sourceRowNumber); put("normalized_e164", job.normalizedE164); put("rendered_message", job.renderedMessage)
        put("sms_encoding", job.smsEncoding); put("segment_count", job.segmentCount); put("gateway_sim_id", job.gatewaySimId)
        put("sim_subscription_id", job.simSubscriptionId); put("sim_slot_index", job.simSlotIndex); put("sim_identity_hash", job.simIdentityHash)
        put("lease_token", job.leaseToken); put("lease_version", job.leaseVersion); put("leased_at", job.leasedAt); put("lease_expires_at", job.leaseExpiresAt)
        put("persisted_at", persistedAt); put("ack_state", if (acknowledged) "ACKED" else "PENDING"); putNull("acked_at")
        put("execution_state", CloudExecutionPolicy.READY); putNull("execution_note"); putNull("submission_started_at"); putNull("submitted_at")
        putNull("current_attempt_id"); putNull("current_attempt_no"); put("outcome_state", CloudExecutionPolicy.OUTCOME_NONE); putNull("outcome_note")
        putNull("retry_ready_at"); put("unknown_resolved", 0); putNull("sent_at"); putNull("delivered_at"); putNull("failed_at")
    }

    private fun readStoredShape(db: SQLiteDatabase, jobId: String): Pair<String, CloudJobReconciler.StoredShape>? = db.query(
        "cloud_jobs", arrayOf("device_id", "dispatch_id", "campaign_id", "source_row_number", "normalized_e164", "rendered_message", "sms_encoding", "segment_count", "gateway_sim_id", "sim_subscription_id", "sim_slot_index", "sim_identity_hash", "lease_token", "lease_version", "leased_at", "lease_expires_at", "ack_state"),
        "job_id=?", arrayOf(jobId), null, null, null, "1"
    ).use { c ->
        if (!c.moveToFirst()) return@use null
        val stored = GatewayQueueJob(jobId, c.getString(1), c.getString(2), c.getInt(3), c.getString(4), c.getString(5), c.getString(6), c.getInt(7), c.getString(12), c.getInt(13), c.getLong(14), c.getLong(15), c.getString(8), c.getInt(9), c.getInt(10), c.getString(11))
        c.getString(0) to CloudJobReconciler.StoredShape(stored, c.getString(16) == "ACKED")
    }

    @Synchronized override fun nextReady(deviceId: String): CloudExecutableJob? = queryExecutable("device_id=? AND ack_state='ACKED' AND execution_state='READY' AND (block_code IS NULL OR (block_code='sim_gate' AND block_retry_at<=strftime('%s','now')*1000))", arrayOf(deviceId))
    @Synchronized override fun nextReadySince(deviceId: String, notBeforeMillis: Long): CloudExecutableJob? = queryExecutable("device_id=? AND ack_state='ACKED' AND execution_state='READY' AND (block_code IS NULL OR (block_code='sim_gate' AND block_retry_at<=strftime('%s','now')*1000)) AND (persisted_at>=? OR retry_ready_at>=?)", arrayOf(deviceId, notBeforeMillis.toString(), notBeforeMillis.toString()))
    @Synchronized override fun nextReadyForCampaign(deviceId: String, campaignId: String): CloudExecutableJob? = queryExecutable("device_id=? AND campaign_id=? AND ack_state='ACKED' AND execution_state='READY' AND (block_code IS NULL OR (block_code='sim_gate' AND block_retry_at<=strftime('%s','now')*1000))", arrayOf(deviceId, campaignId))
    @Synchronized override fun nextReadyForCampaignSince(deviceId: String, campaignId: String, notBeforeMillis: Long): CloudExecutableJob? = queryExecutable("device_id=? AND campaign_id=? AND ack_state='ACKED' AND execution_state='READY' AND (block_code IS NULL OR (block_code='sim_gate' AND block_retry_at<=strftime('%s','now')*1000)) AND (persisted_at>=? OR retry_ready_at>=?)", arrayOf(deviceId, campaignId, notBeforeMillis.toString(), notBeforeMillis.toString()))
    @Synchronized override fun getExecutable(deviceId: String, jobId: String): CloudExecutableJob? = readableDatabase.query("cloud_jobs", EXECUTION_COLUMNS, "device_id=? AND job_id=? AND ack_state='ACKED'", arrayOf(deviceId, jobId), null, null, null, "1").use { c -> if (c.moveToFirst()) executionJob(c) else null }

    private fun queryExecutable(where: String, args: Array<String>): CloudExecutableJob? = readableDatabase.query("cloud_jobs", EXECUTION_COLUMNS, where, args, null, null, "COALESCE(retry_ready_at,persisted_at) ASC, job_id ASC", "1").use { c -> if (c.moveToFirst()) executionJob(c) else null }

    /** Creates one durable client attempt before any server/SmsManager boundary. */
    @Synchronized
    fun ensurePreparedAttempt(deviceId: String, jobId: String, expectedParts: Int): LocalCloudAttempt {
        require(expectedParts > 0)
        val db = writableDatabase
        db.beginTransaction()
        try {
            val row = db.query("cloud_jobs", arrayOf("current_attempt_id", "execution_state", "outcome_state"), "device_id=? AND job_id=? AND ack_state='ACKED'", arrayOf(deviceId, jobId), null, null, null, "1").use { c ->
                if (!c.moveToFirst()) null else Triple(if (c.isNull(0)) null else c.getString(0), c.getString(1), c.getString(2))
            } ?: throw IllegalStateException("Cloud job is not durably ACKed on this device")
            require(row.second == CloudExecutionPolicy.READY) { "Cloud job is ${row.second}; a new attempt cannot begin." }
            row.first?.let { id ->
                val existing = readAttempt(db, id)
                if (existing != null && existing.state == CloudExecutionPolicy.OUTCOME_PREPARED && existing.expectedParts == expectedParts) {
                    db.setTransactionSuccessful(); return existing
                }
            }
            val clientAttemptId = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            val av = ContentValues().apply {
                put("client_attempt_id", clientAttemptId); put("job_id", jobId); put("expected_parts", expectedParts)
                put("state", CloudExecutionPolicy.OUTCOME_PREPARED); put("safe_retry_eligible", 0); put("created_at", now)
            }
            db.insertOrThrow("cloud_attempts", null, av)
            for (part in 0 until expectedParts) {
                val pv = ContentValues().apply { put("client_attempt_id", clientAttemptId); put("part_index", part) }
                db.insertOrThrow("cloud_attempt_parts", null, pv)
            }
            val jv = ContentValues().apply {
                put("current_attempt_id", clientAttemptId); putNull("current_attempt_no"); put("outcome_state", CloudExecutionPolicy.OUTCOME_PREPARED)
                putNull("outcome_note"); put("unknown_resolved", 0); putNull("retry_ready_at")
                putNull("sent_at"); putNull("delivered_at"); putNull("failed_at")
            }
            if (db.update("cloud_jobs", jv, "device_id=? AND job_id=? AND execution_state='READY' AND (block_code IS NULL OR (block_code='sim_gate' AND block_retry_at<=strftime('%s','now')*1000))", arrayOf(deviceId, jobId)) != 1) {
                throw IllegalStateException("Cloud job changed before attempt preparation")
            }
            executionEvent(db, jobId, "Prepared immutable client attempt before server/SmsManager boundary")
            db.setTransactionSuccessful()
            return LocalCloudAttempt(clientAttemptId, jobId, expectedParts, null, CloudExecutionPolicy.OUTCOME_PREPARED, false)
        } finally { db.endTransaction() }
    }

    @Synchronized
    fun bindServerAttempt(clientAttemptId: String, attemptNumber: Int) {
        require(attemptNumber > 0)
        val db = writableDatabase
        db.beginTransaction()
        try {
            val jobId = db.rawQuery("SELECT job_id FROM cloud_attempts WHERE client_attempt_id=?", arrayOf(clientAttemptId)).use { c -> if (c.moveToFirst()) c.getString(0) else null }
                ?: throw IllegalStateException("Local attempt not found")
            val av = ContentValues().apply { put("attempt_number", attemptNumber) }
            if (db.update("cloud_attempts", av, "client_attempt_id=? AND (attempt_number IS NULL OR attempt_number=?)", arrayOf(clientAttemptId, attemptNumber.toString())) != 1) {
                throw IllegalStateException("Attempt number conflict")
            }
            val jv = ContentValues().apply { put("current_attempt_no", attemptNumber); putNull("block_code"); put("block_retry_at", 0) }
            if (db.update("cloud_jobs", jv, "job_id=? AND current_attempt_id=?", arrayOf(jobId, clientAttemptId)) != 1) throw IllegalStateException("Current attempt binding changed")
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    @Synchronized
    override fun beginSubmission(deviceId: String, jobId: String, clientAttemptId: String): Boolean {
        val db = writableDatabase; val at = System.currentTimeMillis(); db.beginTransaction()
        try {
            val attempt = readAttempt(db, clientAttemptId) ?: return false
            if (attempt.jobId != jobId || attempt.attemptNumber == null || attempt.state != CloudExecutionPolicy.OUTCOME_PREPARED) return false
            val jv = ContentValues().apply { put("execution_state", CloudExecutionPolicy.SUBMITTING); putNull("execution_note"); put("submission_started_at", at); putNull("submitted_at") }
            val changed = db.update("cloud_jobs", jv, "device_id=? AND job_id=? AND ack_state='ACKED' AND execution_state='READY' AND (block_code IS NULL OR (block_code='sim_gate' AND block_retry_at<=strftime('%s','now')*1000)) AND current_attempt_id=?", arrayOf(deviceId, jobId, clientAttemptId))
            if (changed != 1) return false
            val av = ContentValues().apply { put("state", "SUBMITTING"); put("submission_started_at", at) }
            db.update("cloud_attempts", av, "client_attempt_id=?", arrayOf(clientAttemptId))
            executionEvent(db, jobId, "Persisted attempt + SUBMITTING before SmsManager")
            db.setTransactionSuccessful(); return true
        } finally { db.endTransaction() }
    }

    @Synchronized
    override fun markSubmitted(deviceId: String, jobId: String): Boolean {
        val db = writableDatabase; val at = System.currentTimeMillis(); db.beginTransaction()
        try {
            val attemptId = currentAttemptId(db, deviceId, jobId) ?: return false
            val jv = ContentValues().apply { put("execution_state", CloudExecutionPolicy.SUBMITTED); put("submitted_at", at); putNull("execution_note") }
            val changed = db.update("cloud_jobs", jv, "device_id=? AND job_id=? AND execution_state='SUBMITTING' AND current_attempt_id=?", arrayOf(deviceId, jobId, attemptId))
            if (changed != 1) return false
            val state = readAttempt(db, attemptId)?.state
            if (state == "SUBMITTING" || state == CloudExecutionPolicy.OUTCOME_PREPARED || state == CloudExecutionPolicy.OUTCOME_SUBMITTED) {
                val av = ContentValues().apply { put("state", CloudExecutionPolicy.OUTCOME_SUBMITTED); put("submitted_at", at) }
                db.update("cloud_attempts", av, "client_attempt_id=?", arrayOf(attemptId))
                val ov = ContentValues().apply { put("outcome_state", CloudExecutionPolicy.OUTCOME_SUBMITTED) }
                db.update("cloud_jobs", ov, "job_id=? AND current_attempt_id=? AND outcome_state IN ('PREPARED','SUBMITTED')", arrayOf(jobId, attemptId))
            }
            enqueueEvent(db, UUID.randomUUID().toString(), attemptId, "submitted", null, null, at)
            executionEvent(db, jobId, "SmsManager returned normally; SUBMITTED recorded; awaiting complete SENT callbacks")
            db.setTransactionSuccessful(); return true
        } finally { db.endTransaction() }
    }

    @Synchronized
    override fun markSubmissionUnknown(deviceId: String, jobId: String, note: String): Boolean {
        val db = writableDatabase; val safe = note.take(500); val at = System.currentTimeMillis(); db.beginTransaction()
        try {
            val attemptId = currentAttemptId(db, deviceId, jobId) ?: return false
            val changed = markUnknownInternal(db, deviceId, jobId, attemptId, safe, at, requireSubmitting = true)
            if (changed) enqueueEvent(db, UUID.randomUUID().toString(), attemptId, "unknown", null, null, at)
            db.setTransactionSuccessful(); return changed
        } finally { db.endTransaction() }
    }

    @Synchronized
    fun markAttemptCallbackTimeout(deviceId: String, jobId: String, clientAttemptId: String, note: String): Boolean {
        val db = writableDatabase; val at = System.currentTimeMillis(); db.beginTransaction()
        try {
            val current = currentAttemptId(db, deviceId, jobId)
            if (current != clientAttemptId) return false
            val state = readAttempt(db, clientAttemptId)?.state ?: return false
            if (state !in setOf(CloudExecutionPolicy.OUTCOME_SUBMITTED, "SUBMITTING", CloudExecutionPolicy.OUTCOME_PREPARED)) return false
            val changed = markUnknownInternal(db, deviceId, jobId, clientAttemptId, note.take(500), at, requireSubmitting = false)
            if (changed) enqueueEvent(db, UUID.randomUUID().toString(), clientAttemptId, "unknown", null, null, at)
            db.setTransactionSuccessful(); return changed
        } finally { db.endTransaction() }
    }

    private fun markUnknownInternal(db: SQLiteDatabase, deviceId: String, jobId: String, attemptId: String, note: String, at: Long, requireSubmitting: Boolean): Boolean {
        val where = if (requireSubmitting) "device_id=? AND job_id=? AND execution_state='SUBMITTING' AND current_attempt_id=?" else "device_id=? AND job_id=? AND current_attempt_id=? AND execution_state IN ('SUBMITTING','SUBMITTED')"
        val jv = ContentValues().apply { put("execution_state", CloudExecutionPolicy.UNKNOWN); put("execution_note", note); put("outcome_state", CloudExecutionPolicy.OUTCOME_UNKNOWN); put("outcome_note", note); put("unknown_resolved", 0) }
        val changed = db.update("cloud_jobs", jv, "$where AND unknown_resolved=0", arrayOf(deviceId, jobId, attemptId)) == 1
        if (changed) {
            val av = ContentValues().apply { put("state", CloudExecutionPolicy.OUTCOME_UNKNOWN); put("safe_retry_eligible", 0); put("note", note); put("unknown_at", at) }
            db.update("cloud_attempts", av, "client_attempt_id=?", arrayOf(attemptId))
            executionEvent(db, jobId, "UNKNOWN: ${note.take(300)}")
        }
        return changed
    }

    @Synchronized
    fun recordAttemptCallback(clientAttemptId: String, eventId: String, partIndex: Int, kind: String, resultCode: Int, errorCode: Int? = null, atMillis: Long = System.currentTimeMillis(), deliveryConfirmed: Boolean = false, deliveryEvidence: com.bulktext.gateway.DeliveryEvidence? = null): LocalAttemptOutcome {
        require(kind == "sent" || kind == "delivery")
        val db = writableDatabase; db.beginTransaction()
        try {
            val attempt = readAttempt(db, clientAttemptId) ?: throw IllegalStateException("Callback attempt not found")
            require(partIndex in 0 until attempt.expectedParts) { "Callback part index is outside the attempt" }
            val jobId = attempt.jobId

            val jobInfo: Array<Any> = db.query("cloud_jobs", arrayOf("sim_subscription_id", "sim_slot_index", "submission_started_at", "persisted_at"), "job_id=?", arrayOf(jobId), null, null, null, "1").use { c ->
                if (c.moveToFirst()) arrayOf<Any>(c.getInt(0), c.getInt(1), if (c.isNull(2)) (if (c.isNull(3)) 0L else c.getLong(3)) else c.getLong(2)) else arrayOf<Any>(0, 0, 0L)
            }
            val subId = jobInfo[0] as Int
            val slotIdx = jobInfo[1] as Int
            val startedAt = jobInfo[2] as Long
            val elapsed = if (startedAt > 0L) atMillis - startedAt else null

            val diagExisting = db.query("cloud_attempt_diagnostics", arrayOf("diagnostic_id", "duplicate_count"), "client_attempt_id=? AND part_index=? AND kind=?", arrayOf(clientAttemptId, partIndex.toString(), kind), null, null, null, "1").use { c ->
                if (c.moveToFirst()) arrayOf(c.getLong(0), c.getInt(1)) else null
            }
            val symbolic = symbolicResultCode(resultCode)
            val flags = com.bulktext.gateway.CloudCallbackPolicy.pendingIntentFlags()
            val correlationKey = "com.bulktext.gateway.CLOUD_SMS_${kind.uppercase()}_${clientAttemptId}_${partIndex}"
            if (diagExisting != null) {
                val diagId = diagExisting[0] as Long
                val dupCount = (diagExisting[1] as Int) + 1
                val dv = ContentValues().apply {
                    put("result_code", resultCode)
                    if (errorCode == null) putNull("error_code") else put("error_code", errorCode)
                    put("symbolic_result", symbolic)
                    put("duplicate_count", dupCount)
                    if (elapsed != null) put("elapsed_ms", elapsed)
                    put("occurred_at", atMillis)
                }
                db.update("cloud_attempt_diagnostics", dv, "diagnostic_id=?", arrayOf(diagId.toString()))
            } else {
                val dv = ContentValues().apply {
                    put("client_attempt_id", clientAttemptId)
                    put("job_id", jobId)
                    put("part_index", partIndex)
                    put("kind", kind)
                    put("result_code", resultCode)
                    if (errorCode == null) putNull("error_code") else put("error_code", errorCode)
                    put("symbolic_result", symbolic)
                    put("subscription_id", subId)
                    put("slot_index", slotIdx)
                    put("sms_api_path", "sendMultipartTextMessage")
                    put("multipart", 1)
                    put("flags", flags)
                    put("correlation_key", correlationKey)
                    put("duplicate_count", 0)
                    if (elapsed != null) put("elapsed_ms", elapsed)
                    put("occurred_at", atMillis)
                }
                db.insertOrThrow("cloud_attempt_diagnostics", null, dv)
            }

            val part = db.query("cloud_attempt_parts", arrayOf("sent_state", "sent_result_code", "delivery_state", "delivery_result_code"), "client_attempt_id=? AND part_index=?", arrayOf(clientAttemptId, partIndex.toString()), null, null, null, "1").use { c ->
                if (!c.moveToFirst()) throw IllegalStateException("Callback part not found")
                arrayOf(c.getString(0), if (c.isNull(1)) null else c.getInt(1), c.getString(2), if (c.isNull(3)) null else c.getInt(3))
            }
            var conflict = false
            if (kind == "sent") {
                val next = if (resultCode == Activity.RESULT_OK) "SENT" else "FAILED"
                val oldState = part[0] as String; val oldCode = part[1] as Int?
                if (oldState == "PENDING") {
                    val pv = ContentValues().apply { put("sent_state", next); put("sent_result_code", resultCode); put("sent_at", atMillis) }
                    db.update("cloud_attempt_parts", pv, "client_attempt_id=? AND part_index=?", arrayOf(clientAttemptId, partIndex.toString()))
                } else if (oldState != next || oldCode != resultCode) conflict = true
            } else {
                val next = if (resultCode == Activity.RESULT_OK) deliveryEvidence?.state() ?: "PENDING" else "PENDING"
                val oldState = part[2] as String; val oldCode = part[3] as Int?
                if (oldState == "PENDING") {
                    val pv = ContentValues().apply { put("delivery_state", next); put("delivery_result_code", resultCode); put("delivered_at", atMillis) }
                    db.update("cloud_attempt_parts", pv, "client_attempt_id=? AND part_index=?", arrayOf(clientAttemptId, partIndex.toString()))
                } else if (next != "PENDING" && oldState != next) conflict = true
            }
            enqueueEvent(db, eventId, clientAttemptId, kind, partIndex, resultCode, atMillis, deliveryEvidence)
            val outcome = if (conflict) {
                db.execSQL("UPDATE cloud_attempts SET conflict_latched=1 WHERE client_attempt_id=?", arrayOf(clientAttemptId))
                setAttemptOutcome(db, clientAttemptId, jobId, CloudExecutionPolicy.OUTCOME_UNKNOWN, false, "Conflicting duplicate ${kind.uppercase()} callback; operator review required.", atMillis)
            } else aggregateAttempt(db, clientAttemptId, jobId, atMillis)
            db.setTransactionSuccessful(); return outcome
        } finally { db.endTransaction() }
    }

    private fun aggregateAttempt(db: SQLiteDatabase, clientAttemptId: String, jobId: String, at: Long): LocalAttemptOutcome {
        val attempt = readAttempt(db, clientAttemptId) ?: throw IllegalStateException("Attempt not found")
        if (scalarInt(db, "SELECT conflict_latched FROM cloud_attempts WHERE client_attempt_id=?", arrayOf(clientAttemptId)) == 1)
            return setAttemptOutcome(db, clientAttemptId, jobId, CloudExecutionPolicy.OUTCOME_UNKNOWN, false, "Conflicting callback evidence; operator review required.", at)
        val stats = db.rawQuery(
            "SELECT sum(CASE WHEN sent_state='SENT' THEN 1 ELSE 0 END), sum(CASE WHEN sent_state='FAILED' THEN 1 ELSE 0 END), sum(CASE WHEN sent_state='PENDING' THEN 1 ELSE 0 END), sum(CASE WHEN delivery_state='DELIVERED' THEN 1 ELSE 0 END) FROM cloud_attempt_parts WHERE client_attempt_id=?",
            arrayOf(clientAttemptId)
        ).use { c -> c.moveToFirst(); intArrayOf(c.getInt(0), c.getInt(1), c.getInt(2), c.getInt(3)) }
        val sentOk = stats[0]; val sentFailed = stats[1]; val pending = stats[2]; val delivered = stats[3]
        return when {
            sentOk == attempt.expectedParts && delivered == attempt.expectedParts -> setAttemptOutcome(db, clientAttemptId, jobId, CloudExecutionPolicy.OUTCOME_DELIVERED, false, null, at)
            sentOk == attempt.expectedParts -> setAttemptOutcome(db, clientAttemptId, jobId, CloudExecutionPolicy.OUTCOME_SENT, false, null, at)
            CloudExecutionPolicy.allSentCallbacksFailed(sentOk, sentFailed, attempt.expectedParts, pending) -> setAttemptOutcome(db, clientAttemptId, jobId, CloudExecutionPolicy.OUTCOME_UNKNOWN, false, "All SENT callbacks reported failure after SmsManager. Real-device delivery can still occur, so resend is unsafe without proof of zero external effect.", at)
            pending == 0 && sentOk > 0 && sentFailed > 0 -> setAttemptOutcome(db, clientAttemptId, jobId, CloudExecutionPolicy.OUTCOME_UNKNOWN, false, "Mixed multipart SENT results; resend could duplicate successful parts.", at)
            else -> currentAttemptOutcome(clientAttemptId) ?: LocalAttemptOutcome(CloudExecutionPolicy.OUTCOME_SUBMITTED, false, null)
        }
    }

    private fun setAttemptOutcome(db: SQLiteDatabase, attemptId: String, jobId: String, state: String, safeRetry: Boolean, note: String?, at: Long): LocalAttemptOutcome {
        val av = ContentValues().apply {
            put("state", state); put("safe_retry_eligible", if (safeRetry) 1 else 0); if (note == null) putNull("note") else put("note", note.take(500))
            when (state) {
                CloudExecutionPolicy.OUTCOME_SENT -> put("sent_at", at)
                CloudExecutionPolicy.OUTCOME_DELIVERED -> { put("sent_at", at); put("delivered_at", at) }
                CloudExecutionPolicy.OUTCOME_FAILED -> put("failed_at", at)
                CloudExecutionPolicy.OUTCOME_UNKNOWN -> put("unknown_at", at)
            }
        }
        db.update("cloud_attempts", av, "client_attempt_id=?", arrayOf(attemptId))
        val jv = ContentValues().apply {
            put("outcome_state", state); if (note == null) putNull("outcome_note") else put("outcome_note", note.take(500))
            when (state) {
                CloudExecutionPolicy.OUTCOME_SENT -> put("sent_at", at)
                CloudExecutionPolicy.OUTCOME_DELIVERED -> { put("sent_at", at); put("delivered_at", at) }
                CloudExecutionPolicy.OUTCOME_FAILED -> put("failed_at", at)
                CloudExecutionPolicy.OUTCOME_UNKNOWN -> { if (scalarInt(db, "SELECT unknown_resolved FROM cloud_jobs WHERE job_id=?", arrayOf(jobId)) == 0) put("execution_state", CloudExecutionPolicy.UNKNOWN); put("execution_note", note ?: "Ambiguous callback outcome") }
            }
        }
        db.update("cloud_jobs", jv, "job_id=? AND current_attempt_id=?", arrayOf(jobId, attemptId))
        executionEvent(db, jobId, "Attempt outcome $state${if (safeRetry) " (safe retry eligible)" else ""}")
        return LocalAttemptOutcome(state, safeRetry, note)
    }

    @Synchronized
    fun currentAttemptOutcome(clientAttemptId: String): LocalAttemptOutcome? = readableDatabase.query("cloud_attempts", arrayOf("state", "safe_retry_eligible", "note"), "client_attempt_id=?", arrayOf(clientAttemptId), null, null, null, "1").use { c ->
        if (!c.moveToFirst()) null else LocalAttemptOutcome(c.getString(0), c.getInt(1) == 1, if (c.isNull(2)) null else c.getString(2))
    }

    @Synchronized
    fun pendingAttemptEvents(deviceId: String, limit: Int = 25): List<PendingAttemptEvent> = readableDatabase.query(
        "cloud_callback_outbox", arrayOf("event_id", "client_attempt_id", "event_type", "part_index", "result_code", "occurred_at", "sync_attempts", "delivery_format", "delivery_status", "delivery_sha256"),
        "synced_at IS NULL AND disposition IS NULL AND device_id=?", arrayOf(deviceId), null, null,
        "occurred_at ASC, event_id ASC", limit.coerceIn(1, 25).toString()
    ).use { c -> buildList {
        while (c.moveToNext()) add(PendingAttemptEvent(GatewayAttemptEvent(c.getString(0), c.getString(1), c.getString(2), if (c.isNull(3)) null else c.getInt(3), if (c.isNull(4)) null else c.getInt(4), c.getLong(5),
            if(c.isNull(7)) null else c.getString(7), if(c.isNull(8)) null else c.getInt(8), if(c.isNull(9)) null else c.getString(9)), c.getInt(6)))
    } }

    @Synchronized
    fun completeReport(eventId: String, report: com.bulktext.gateway.cloud.GatewayAttemptReport) {
        val db = writableDatabase; db.beginTransaction()
        try {
            val v = ContentValues().apply { put("disposition", report.outcome); put("synced_at", System.currentTimeMillis()) }
            db.update("cloud_callback_outbox", v, "event_id=?", arrayOf(eventId))
            val attempt = readAttempt(db, report.clientAttemptId)
            if (attempt != null && report.outcome !in setOf("tombstoned", "rejected")) {
                if (report.conflict) {
                    db.execSQL("UPDATE cloud_attempts SET conflict_latched=1 WHERE client_attempt_id=?", arrayOf(attempt.clientAttemptId))
                    setAttemptOutcome(db, attempt.clientAttemptId, attempt.jobId, "UNKNOWN", false, "Conflicting cloud callback evidence", System.currentTimeMillis())
                }
                if (report.resolved) db.execSQL("UPDATE cloud_jobs SET unknown_resolved=1 WHERE job_id=?", arrayOf(attempt.jobId))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    @Synchronized
    fun blockBeforeSubmission(deviceId: String, jobId: String, code: String) {
        val v = ContentValues().apply { put("block_code", code); put("block_retry_at", System.currentTimeMillis()+30_000); put("execution_note", "Sending blocked: $code") }
        writableDatabase.update("cloud_jobs", v, "device_id=? AND job_id=? AND submission_started_at IS NULL", arrayOf(deviceId,jobId))
    }

    @Synchronized
    fun markAttemptEventSynced(eventId: String) {
        val v = ContentValues().apply { put("synced_at", System.currentTimeMillis()) }
        writableDatabase.update("cloud_callback_outbox", v, "event_id=? AND synced_at IS NULL", arrayOf(eventId))
    }

    @Synchronized
    fun noteAttemptEventSyncFailure(eventId: String) {
        writableDatabase.execSQL("UPDATE cloud_callback_outbox SET sync_attempts=sync_attempts+1 WHERE event_id=? AND synced_at IS NULL", arrayOf<Any>(eventId))
    }

    private fun enqueueEvent(db: SQLiteDatabase, eventId: String, clientAttemptId: String, eventType: String, partIndex: Int?, resultCode: Int?, at: Long, evidence: com.bulktext.gateway.DeliveryEvidence? = null) {
        val v = ContentValues().apply {
            put("event_id", eventId); put("client_attempt_id", clientAttemptId); put("event_type", eventType)
            val owner = db.rawQuery("SELECT j.device_id FROM cloud_jobs j JOIN cloud_attempts a ON a.job_id=j.job_id WHERE a.client_attempt_id=?", arrayOf(clientAttemptId)).use { c -> check(c.moveToFirst()); c.getString(0) }
            put("device_id", owner)
            evidence?.let { put("delivery_format", it.format); put("delivery_status", it.status); put("delivery_sha256", it.sha256) }
            if (partIndex == null) putNull("part_index") else put("part_index", partIndex)
            if (resultCode == null) putNull("result_code") else put("result_code", resultCode)
            put("occurred_at", at); putNull("synced_at"); put("sync_attempts", 0)
        }
        val columns = arrayOf("client_attempt_id", "event_type", "part_index", "result_code", "occurred_at", "device_id", "delivery_format", "delivery_status", "delivery_sha256")
        val exists = db.query("cloud_callback_outbox", columns, "event_id=?", arrayOf(eventId), null, null, null).use { c ->
            if (!c.moveToFirst()) false else {
                columns.forEachIndexed { index, column ->
                    check((if (c.isNull(index)) null else c.getString(index)) == v.getAsString(column)) { "Callback UUID payload changed" }
                }
                true
            }
        }
        if (!exists) db.insertOrThrow("cloud_callback_outbox", null, v)
    }

    @Synchronized
    fun applyRecoveryRequest(deviceId: String, request: GatewayRecoveryRequest): RecoveryApplyResult {
        val db = writableDatabase; db.beginTransaction()
        try {
            val saved = db.rawQuery("SELECT outcome FROM recovery_ack_outbox WHERE recovery_id=? AND device_id=?", arrayOf(request.recoveryId,deviceId)).use { c -> if(c.moveToFirst()) c.getString(0) else null }
            if (saved != null) { db.setTransactionSuccessful(); return when(saved) { "applied" -> RecoveryApplyResult.ALREADY_APPLIED; "rejected" -> RecoveryApplyResult.REJECTED_UNSAFE; else -> RecoveryApplyResult.NOT_APPLICABLE } }
            val result = applyRecoveryLocally(deviceId, request)
            val outcome = when(result) { RecoveryApplyResult.APPLIED, RecoveryApplyResult.ALREADY_APPLIED -> "applied"; RecoveryApplyResult.REJECTED_UNSAFE -> "rejected"; else -> "not_applicable" }
            db.execSQL("INSERT INTO recovery_ack_outbox(recovery_id,device_id,outcome) VALUES(?,?,?)", arrayOf(request.recoveryId,deviceId,outcome))
            db.setTransactionSuccessful(); return result
        } finally { db.endTransaction() }
    }

    fun pendingRecoveryAcks(deviceId: String): List<Pair<String,String>> = readableDatabase.rawQuery("SELECT recovery_id,outcome FROM recovery_ack_outbox WHERE device_id=? AND acked_at IS NULL ORDER BY rowid LIMIT 25", arrayOf(deviceId)).use { c -> buildList { while(c.moveToNext()) add(c.getString(0) to c.getString(1)) } }
    fun markRecoveryAcked(deviceId: String, recoveryId: String) {
        writableDatabase.execSQL("UPDATE recovery_ack_outbox SET acked_at=? WHERE recovery_id=? AND device_id=?", arrayOf(System.currentTimeMillis(),recoveryId,deviceId))
    }

    private fun applyRecoveryLocally(deviceId: String, request: GatewayRecoveryRequest): RecoveryApplyResult {
        val db = writableDatabase; val now = System.currentTimeMillis(); db.beginTransaction()
        try {
            val seen = scalarInt(db, "SELECT count(*) FROM cloud_recovery_applied WHERE recovery_id=?", arrayOf(request.recoveryId)) > 0
            if (seen) { db.setTransactionSuccessful(); return RecoveryApplyResult.ALREADY_APPLIED }
            val job = db.query("cloud_jobs", arrayOf("current_attempt_id", "current_attempt_no", "outcome_state", "execution_state"), "device_id=? AND job_id=?", arrayOf(deviceId, request.jobId), null, null, null, "1").use { c ->
                if (!c.moveToFirst()) null else arrayOf(if (c.isNull(0)) null else c.getString(0), if (c.isNull(1)) null else c.getInt(1), c.getString(2), c.getString(3))
            } ?: run { db.setTransactionSuccessful(); return RecoveryApplyResult.NOT_APPLICABLE }
            if (job[0] != request.failedClientAttemptId || job[1] != request.attemptNumber) { db.setTransactionSuccessful(); return RecoveryApplyResult.NOT_APPLICABLE }
            val attempt = readAttempt(db, request.failedClientAttemptId) ?: run { db.setTransactionSuccessful(); return RecoveryApplyResult.NOT_APPLICABLE }
            val applied = when (request.action) {
                "safe_retry" -> {
                    // 0.18.1 defense in depth: callback-derived failure after
                    // SmsManager never proves zero external effect. Consume the
                    // obsolete request without changing the job to READY.
                    executionEvent(db, request.jobId, "Rejected unsafe callback-derived safe_retry request under 0.18.1")
                    false
                }
                "skip_unknown" -> {
                    if (attempt.state != CloudExecutionPolicy.OUTCOME_UNKNOWN || job[3] != CloudExecutionPolicy.UNKNOWN) false else {
                        val v = ContentValues().apply {
                            put("execution_state", CloudExecutionPolicy.SUBMITTED); put("outcome_state", CloudExecutionPolicy.OUTCOME_UNKNOWN_RESOLVED)
                            put("unknown_resolved", 1); put("execution_note", "UNKNOWN resolved by explicit Web action without resend.")
                            put("outcome_note", "Operator continued without resending this recipient.")
                        }
                        db.update("cloud_jobs", v, "device_id=? AND job_id=? AND current_attempt_id=?", arrayOf(deviceId, request.jobId, request.failedClientAttemptId)) == 1
                    }
                }
                else -> false
            }
            if (!applied) {
                if (request.action == "safe_retry") {
                    val rv = ContentValues().apply { put("recovery_id", request.recoveryId); put("job_id", request.jobId); put("action", request.action); put("client_attempt_id", request.failedClientAttemptId); put("applied_at", now) }
                    db.insertOrThrow("cloud_recovery_applied", null, rv)
                    db.setTransactionSuccessful()
                    return RecoveryApplyResult.REJECTED_UNSAFE
                }
                db.setTransactionSuccessful(); return RecoveryApplyResult.NOT_APPLICABLE
            }
            val rv = ContentValues().apply { put("recovery_id", request.recoveryId); put("job_id", request.jobId); put("action", request.action); put("client_attempt_id", request.failedClientAttemptId); put("applied_at", now) }
            db.insertOrThrow("cloud_recovery_applied", null, rv)
            executionEvent(db, request.jobId, "Applied explicit Web recovery: ${request.action}")
            db.setTransactionSuccessful(); return RecoveryApplyResult.APPLIED
        } finally { db.endTransaction() }
    }

    @Synchronized
    override fun noteReadyBlock(deviceId: String, jobId: String, note: String) {
        val db = writableDatabase; val safe = note.take(500); db.beginTransaction()
        try {
            val v = ContentValues().apply { put("execution_note", safe) }
            val changed = db.update("cloud_jobs", v, "device_id=? AND job_id=? AND ack_state='ACKED' AND execution_state='READY' AND (block_code IS NULL OR (block_code='sim_gate' AND block_retry_at<=strftime('%s','now')*1000))", arrayOf(deviceId, jobId))
            if (changed == 1) executionEvent(db, jobId, "Pre-submission blocked: ${safe.take(300)}")
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    @Synchronized
    override fun recoverInterruptedSubmissions(): Int {
        val db = writableDatabase
        val rows = db.rawQuery("SELECT job_id, device_id, current_attempt_id FROM cloud_jobs WHERE execution_state='SUBMITTING'", null).use { c -> buildList { while (c.moveToNext()) add(Triple(c.getString(0), c.getString(1), if (c.isNull(2)) null else c.getString(2))) } }
        if (rows.isEmpty()) return 0
        db.beginTransaction()
        try {
            rows.forEach { (jobId, deviceId, attemptId) ->
                val note = "Process restarted after SUBMITTING was persisted; SMS submission is ambiguous. Never auto-retry."
                if (attemptId != null) {
                    if (markUnknownInternal(db, deviceId, jobId, attemptId, note, System.currentTimeMillis(), requireSubmitting = true)) enqueueEvent(db, UUID.randomUUID().toString(), attemptId, "unknown", null, null, System.currentTimeMillis())
                } else {
                    val v = ContentValues().apply { put("execution_state", CloudExecutionPolicy.UNKNOWN); put("execution_note", note); put("outcome_state", CloudExecutionPolicy.OUTCOME_UNKNOWN); put("outcome_note", note) }
                    db.update("cloud_jobs", v, "job_id=? AND execution_state='SUBMITTING'", arrayOf(jobId)); executionEvent(db, jobId, "Recovered legacy interrupted SUBMITTING as UNKNOWN")
                }
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return rows.size
    }

    /** Marks stale callback waits UNKNOWN; never creates a retry. */
    @Synchronized
    fun recoverCallbackTimeouts(deviceId: String, cutoffMillis: Long): Int {
        val db = writableDatabase
        val rows = db.rawQuery(
            "SELECT job_id, current_attempt_id FROM cloud_jobs WHERE device_id=? AND execution_state='SUBMITTED' AND outcome_state='SUBMITTED' AND current_attempt_id IS NOT NULL AND submitted_at IS NOT NULL AND submitted_at<=?",
            arrayOf(deviceId, cutoffMillis.toString())
        ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0) to c.getString(1)) } }
        var changed = 0
        for ((jobId, attemptId) in rows) if (markAttemptCallbackTimeout(deviceId, jobId, attemptId, "SENT callback timeout after SmsManager submission; delivery is ambiguous. No automatic retry.")) changed++
        return changed
    }

    @Synchronized
    override fun executionCounts(deviceId: String): CloudExecutionCounts = executionCountsWhere("device_id=?", arrayOf(deviceId))
    @Synchronized
    override fun campaignExecutionCounts(deviceId: String, campaignId: String): CloudExecutionCounts = executionCountsWhere("device_id=? AND campaign_id=?", arrayOf(deviceId, campaignId))

    private fun executionCountsWhere(scope: String, args: Array<String>): CloudExecutionCounts {
        val db = readableDatabase
        fun exec(state: String) = scalarInt(db, "SELECT count(*) FROM cloud_jobs WHERE $scope AND ack_state='ACKED' AND execution_state=?", args + state)
        fun outcome(state: String) = scalarInt(db, "SELECT count(*) FROM cloud_jobs WHERE $scope AND ack_state='ACKED' AND outcome_state=?", args + state)
        val unresolvedUnknown = scalarInt(db, "SELECT count(*) FROM cloud_jobs WHERE $scope AND ack_state='ACKED' AND outcome_state='UNKNOWN' AND unknown_resolved=0", args)
        return CloudExecutionCounts(
            ready = scalarInt(db, "SELECT count(*) FROM cloud_jobs WHERE $scope AND ack_state='ACKED' AND execution_state='READY' AND block_code IS NULL", args),
            submitting = exec(CloudExecutionPolicy.SUBMITTING),
            submitted = outcome(CloudExecutionPolicy.OUTCOME_SUBMITTED),
            prepared = outcome(CloudExecutionPolicy.OUTCOME_PREPARED),
            sent = outcome(CloudExecutionPolicy.OUTCOME_SENT),
            delivered = outcome(CloudExecutionPolicy.OUTCOME_DELIVERED),
            failed = outcome(CloudExecutionPolicy.OUTCOME_FAILED),
            unknown = unresolvedUnknown,
            blocked = scalarInt(db, "SELECT count(*) FROM cloud_jobs WHERE $scope AND block_code IS NOT NULL", args)
        )
    }

    private fun readAttempt(db: SQLiteDatabase, clientAttemptId: String): LocalCloudAttempt? = db.query(
        "cloud_attempts", arrayOf("job_id", "expected_parts", "attempt_number", "state", "safe_retry_eligible"), "client_attempt_id=?", arrayOf(clientAttemptId), null, null, null, "1"
    ).use { c -> if (!c.moveToFirst()) null else LocalCloudAttempt(clientAttemptId, c.getString(0), c.getInt(1), if (c.isNull(2)) null else c.getInt(2), c.getString(3), c.getInt(4) == 1) }

    private fun symbolicResultCode(code: Int): String = when (code) {
        Activity.RESULT_OK -> "RESULT_OK"
        1 -> "RESULT_ERROR_GENERIC_FAILURE"
        2 -> "RESULT_ERROR_RADIO_OFF"
        3 -> "RESULT_ERROR_NULL_PDU"
        4 -> "RESULT_ERROR_NO_SERVICE"
        5 -> "RESULT_ERROR_SHORT_CODE_NOT_ALLOWED"
        6 -> "RESULT_ERROR_SHORT_CODE_SPAM_CHECK"
        else -> "UNKNOWN_CODE_$code"
    }

    @Synchronized
    fun latestCallbackDiagnostics(deviceId: String, limit: Int = 6): List<CallbackDiagnostic> = readableDatabase.rawQuery(
        "SELECT d.client_attempt_id,d.job_id,d.part_index,d.kind,d.result_code,d.symbolic_result,d.error_code,d.duplicate_count,d.elapsed_ms,d.occurred_at " +
            "FROM cloud_attempt_diagnostics d JOIN cloud_attempts a ON a.client_attempt_id=d.client_attempt_id " +
            "JOIN cloud_jobs j ON j.job_id=a.job_id WHERE j.device_id=? ORDER BY d.occurred_at DESC LIMIT ?",
        arrayOf(deviceId, limit.coerceIn(1, 20).toString())
    ).use { c -> buildList {
        while (c.moveToNext()) add(CallbackDiagnostic(
            clientAttemptId = c.getString(0),
            jobId = c.getString(1),
            partIndex = c.getInt(2),
            kind = c.getString(3),
            resultCode = c.getInt(4),
            symbolicResult = c.getString(5),
            errorCode = if (c.isNull(6)) null else c.getInt(6),
            duplicateCount = c.getInt(7),
            elapsedMs = if (c.isNull(8)) null else c.getLong(8),
            occurredAt = c.getLong(9)
        ))
    } }

    private fun maskPhoneNumber(phone: String?): String {
        if (phone.isNullOrBlank() || phone.length < 8) return "***"
        return phone.take(4) + "*****" + phone.takeLast(4)
    }

    private fun currentAttemptId(db: SQLiteDatabase, deviceId: String, jobId: String): String? = db.query("cloud_jobs", arrayOf("current_attempt_id"), "device_id=? AND job_id=?", arrayOf(deviceId, jobId), null, null, null, "1").use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null }

    private fun executionJob(c: Cursor): CloudExecutableJob = CloudExecutableJob(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5), c.getInt(6), c.getString(7), c.getInt(8), c.getInt(9), c.getString(10), c.getString(11), if (c.isNull(12)) null else c.getString(12))

    private fun executionEvent(db: SQLiteDatabase, jobId: String, detail: String) {
        val v = ContentValues().apply { put("at", System.currentTimeMillis()); put("job_id", jobId); put("detail", detail.take(500)) }
        db.insertOrThrow("cloud_execution_events", null, v)
    }

    private fun scalarInt(db: SQLiteDatabase, sql: String, args: Array<String>): Int = db.rawQuery(sql, args).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    companion object {
        private val EXECUTION_COLUMNS = arrayOf("job_id", "device_id", "dispatch_id", "campaign_id", "normalized_e164", "rendered_message", "segment_count", "gateway_sim_id", "sim_subscription_id", "sim_slot_index", "sim_identity_hash", "execution_state", "execution_note")
        private val DIAGNOSTIC_COLUMNS = arrayOf("job_id", "dispatch_id", "campaign_id", "ack_state", "execution_state", "outcome_state", "persisted_at", "current_attempt_no", "current_attempt_id", "execution_note", "outcome_note")
    }
}
