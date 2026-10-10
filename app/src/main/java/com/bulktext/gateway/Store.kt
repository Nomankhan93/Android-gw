package com.bulktext.gateway

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class Job(val id: String, val phone: String, val body: String, val status: String, val parts: Int, val note: String)
data class Session(val id: String, val sub: Int, val simKey: String, val status: String)

class Store(context: Context) : SQLiteOpenHelper(context, "gateway.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE sessions(id TEXT PRIMARY KEY, sub INTEGER NOT NULL, simkey TEXT NOT NULL, status TEXT NOT NULL, created INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE jobs(id TEXT PRIMARY KEY, session TEXT NOT NULL, phone TEXT NOT NULL, body TEXT NOT NULL, status TEXT NOT NULL, parts INTEGER NOT NULL, note TEXT NOT NULL DEFAULT '', UNIQUE(session,phone))")
        db.execSQL("CREATE TABLE parts(job TEXT NOT NULL, idx INTEGER NOT NULL, sent TEXT NOT NULL DEFAULT 'PENDING', delivery TEXT NOT NULL DEFAULT 'PENDING', code INTEGER, PRIMARY KEY(job,idx))")
        db.execSQL("CREATE TABLE events(id INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER NOT NULL, job TEXT NOT NULL, detail TEXT NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) = error("Explicit migration required")
    private fun event(db: SQLiteDatabase, job: String, detail: String) {
        db.execSQL("INSERT INTO events(at,job,detail) VALUES(?,?,?)", arrayOf<Any>(System.currentTimeMillis(),job,detail))
    }
    @Synchronized fun recover() {
        val db=writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("UPDATE jobs SET status='UNKNOWN', note='Process ended during submission; never automatically resent' WHERE status='SUBMITTING'")
            db.execSQL("UPDATE sessions SET status='PAUSED' WHERE status='RUNNING'")
            event(db,"system","Process started; interrupted submissions are UNKNOWN; sessions require manual resume")
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    @Synchronized fun create(sub: Int,key: String,phones: List<String>,body: String,count: Int): String {
        Policy.checkBudget(phones.size,count)
        require(body.isNotBlank())
        require(latest()?.status !in listOf("RUNNING","READY","PAUSED")) { "Finish or cancel the current session first." }
        val id=UUID.randomUUID().toString(); val db=writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("INSERT INTO sessions VALUES(?,?,?,?,?)",arrayOf<Any>(id,sub,key,"READY",System.currentTimeMillis()))
            phones.forEach { phone ->
                val jid=UUID.randomUUID().toString()
                db.execSQL("INSERT INTO jobs(id,session,phone,body,status,parts) VALUES(?,?,?,?,?,?)",arrayOf<Any>(jid,id,phone,body,"QUEUED",count))
            }
            event(db,id,"Session confirmed: ${phones.size} recipients, ${phones.size*count} segments")
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return id
    }
    @Synchronized fun latest(): Session? = readableDatabase.rawQuery("SELECT id,sub,simkey,status FROM sessions ORDER BY created DESC,rowid DESC LIMIT 1",null).use {
        if(it.moveToFirst()) Session(it.getString(0),it.getInt(1),it.getString(2),it.getString(3)) else null
    }
    @Synchronized fun status(id: String): String? = readableDatabase.rawQuery("SELECT status FROM sessions WHERE id=?",arrayOf(id)).use { if(it.moveToFirst()) it.getString(0) else null }
    @Synchronized fun start(id: String): Boolean {
        val cv=ContentValues().apply { put("status","RUNNING") }
        return writableDatabase.update("sessions",cv,"id=? AND status IN ('READY','PAUSED')",arrayOf(id))==1
    }
    @Synchronized fun stop(id: String,status: String) {
        require(status in listOf("PAUSED","CANCELLED","COMPLETE"))
        val db=writableDatabase; db.beginTransaction()
        try {
            db.execSQL("UPDATE sessions SET status=? WHERE id=? AND status IN ('READY','RUNNING','PAUSED')", arrayOf<Any>(status,id))
            if(status=="CANCELLED") db.execSQL("UPDATE jobs SET status='CANCELLED' WHERE session=? AND status='QUEUED'",arrayOf<Any>(id))
            event(db,id,"Session $status");db.setTransactionSuccessful()
        } finally {db.endTransaction()}
    }
    @Synchronized fun jobs(session: String): List<Job> = readableDatabase.rawQuery("SELECT id,phone,body,status,parts,note FROM jobs WHERE session=? ORDER BY rowid",arrayOf(session)).use {
        buildList { while(it.moveToNext()) add(Job(it.getString(0),it.getString(1),it.getString(2),it.getString(3),it.getInt(4),it.getString(5))) }
    }
    @Synchronized fun claim(session: String): Job? {
        val db=writableDatabase; db.beginTransaction()
        try {
            if(status(session)!="RUNNING") return null
            val job=jobs(session).firstOrNull { Policy.claimable(it.status) } ?: return null
            db.execSQL("UPDATE jobs SET status='SUBMITTING' WHERE id=? AND status='QUEUED'",arrayOf<Any>(job.id))
            repeat(job.parts) { db.execSQL("INSERT INTO parts(job,idx) VALUES(?,?)",arrayOf<Any>(job.id,it)) }
            event(db,job.id,"Submission intent persisted BEFORE Android API")
            db.setTransactionSuccessful();return job
        } finally {db.endTransaction()}
    }
    @Synchronized fun annotate(session: String, reason: String) {
        writableDatabase.execSQL("UPDATE jobs SET note=? WHERE session=? AND status='QUEUED'",arrayOf<Any>(reason,session))
        event(writableDatabase,session,reason)
    }
    @Synchronized fun unknown(job: String, reason: String) {
        writableDatabase.execSQL("UPDATE jobs SET status='UNKNOWN',note=? WHERE id=? AND status='SUBMITTING'",arrayOf<Any>(reason,job))
        event(writableDatabase,job,reason)
    }
    @Synchronized fun callback(job: String,idx: Int,kind: String,ok: Boolean,code: Int) {
        if(kind !in listOf("sent","delivery")) return
        val db=writableDatabase;db.beginTransaction()
        try {
            val exists=db.rawQuery("SELECT sent,delivery FROM parts WHERE job=? AND idx=?",arrayOf(job,idx.toString())).use {
                if(!it.moveToFirst()) false else it.getString(if(kind=="sent") 0 else 1)=="PENDING"
            }
            if(!exists) return // Duplicate callbacks cannot regress a terminal part.
            db.execSQL("UPDATE parts SET $kind=?,code=? WHERE job=? AND idx=?",arrayOf<Any>(if(ok) "OK" else "FAILED",code,job,idx))
            val states=db.rawQuery("SELECT sent,delivery FROM parts WHERE job=? ORDER BY idx",arrayOf(job)).use {
                buildList { while(it.moveToNext()) add(when {
                    it.getString(1)=="OK" -> "DELIVERED"
                    it.getString(0)=="FAILED" -> "FAILED"
                    it.getString(0)=="OK" -> "SENT"
                    else -> "PENDING"
                }) }
            }
            val previous=db.rawQuery("SELECT status FROM jobs WHERE id=?",arrayOf(job)).use {it.moveToFirst();it.getString(0)}
            val aggregate=Policy.aggregate(states,previous=="UNKNOWN")
            db.execSQL("UPDATE jobs SET status=?,note=? WHERE id=?",arrayOf<Any>(aggregate,"$kind part ${idx+1}: result $code",job))
            event(db,job,"$kind part ${idx+1}: result $code; aggregate $aggregate")
            db.setTransactionSuccessful()
        } finally {db.endTransaction()}
    }
    @Synchronized fun export(): String {
        val out=JSONObject().put("version","0.3.0").put("exportedAt",System.currentTimeMillis())
        val arrays=mapOf("sessions" to "SELECT * FROM sessions", "jobs" to "SELECT id,session,status,parts,note FROM jobs", "parts" to "SELECT * FROM parts", "events" to "SELECT * FROM events")
        arrays.forEach { (name,sql) ->
            val rows=JSONArray()
            readableDatabase.rawQuery(sql,null).use {c -> while(c.moveToNext()) {
                val row=JSONObject();c.columnNames.forEachIndexed {i,col -> row.put(col,if(c.isNull(i)) JSONObject.NULL else c.getString(i)) };rows.put(row)
            } }
            out.put(name,rows)
        }
        return out.toString(2) // No recipient numbers or message body in diagnostics.
    }
    @Synchronized fun erase() {
        require(latest()?.status!="RUNNING") { "Stop sending first" }
        writableDatabase.beginTransaction()
        try { listOf("parts","jobs","sessions","events").forEach {writableDatabase.delete(it,null,null)};writableDatabase.setTransactionSuccessful() }
        finally {writableDatabase.endTransaction()}
    }
}
