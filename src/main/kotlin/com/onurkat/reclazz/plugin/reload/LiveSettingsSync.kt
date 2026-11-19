/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.plugin.reload

import java.security.MessageDigest

/** Correlated CONFIG synchronization; transport and time are supplied by ReloadManager. */
internal class LiveSettingsSync(
    private val desired: () -> Desired,
    private val send: (Long, String) -> Boolean,
    private val report: (String) -> Unit,
    private val defer: (Long, () -> Unit) -> Unit
) {
    data class Desired(val options: Map<String, Boolean>, val watchExtensions: String)
    private enum class Kind { GET, SET, POLL }
    private data class Request(val id: String, val kind: Kind, val epoch: Long, val base: Long?)
    private var generation = -1L
    private var epoch = 0L
    private var serial = 0L
    private var awaited: Request? = null
    private var revision: Long? = null
    private var effective = emptyMap<String, Boolean>()
    private var supported = emptySet<String>()
    private var watchHash = ""
    private var conflictRetries = 0
    private var pendingRevision: Long? = null
    private var pendingEpoch = -1L
    private var pendingRequest: Request? = null

    @Synchronized fun connected(connection: Long) {
        generation = connection; epoch++; awaited = null; revision = null
        effective = emptyMap(); supported = emptySet(); pendingRevision = null; pendingRequest = null; conflictRetries = 0
        get()
    }
    @Synchronized fun disconnected(connection: Long) {
        if (generation != connection) return
        generation = connection; epoch++; awaited = null; revision = null; pendingRevision = null; pendingRequest = null
        report("No running agent connected; saved settings will be sent on reconnect.")
        generation = -1
    }
    @Synchronized fun apply() {
        epoch++; awaited = null; conflictRetries = 0
        if (generation < 0) {
            report("No running agent connected; saved settings will be sent on reconnect.")
        } else if (pendingRevision != null) get(Kind.POLL)
        else if (revision == null) get()
        else reconcile()
    }
    private fun get(kind: Kind = Kind.GET) = request(kind, null) { "CONFIG GET $it" }
    private fun request(kind: Kind, base: Long?, command: (String) -> String) {
        val current = Request("cfg-${generation}-${++serial}", kind, epoch, base)
        awaited = current
        if (!send(generation, command(current.id))) {
            awaited = null
            report("No running agent connected; saved settings will be sent on reconnect.")
            return
        }
        val connection = generation
        defer(if (kind == Kind.SET) 15000 else 5000) { timeout(connection, current) }
    }
    @Synchronized private fun timeout(connection: Long, request: Request) {
        if (connection != generation || awaited != request || request.epoch != epoch) return
        awaited = null
        if (request.kind == Kind.SET || request.kind == Kind.POLL || pendingRevision != null) {
            if (pendingRevision == null) { pendingRevision = request.base?.plus(1); pendingEpoch = request.epoch }; if (request.kind == Kind.SET) pendingRequest = request
            report("Settings change is pending; reading the running agent without resending it.")
            poll()
        } else report("This agent does not apply settings live; restart to apply.")
    }
    private fun poll() {
        val connection = generation; val currentEpoch = epoch
        defer(1000) { synchronized(this) {
            if (generation == connection && epoch == currentEpoch && awaited == null && generation >= 0 &&
                (pendingRequest != null || pendingRevision != null)) get(Kind.POLL)
        } }
    }
    @Synchronized fun receive(connection: Long, message: String): Boolean {
        if (!message.startsWith("CONFIG ")) return false
        val parts = message.split(' ')
        if (connection != generation || parts.size < 4) return true
        val current = awaited?.takeIf { it.id == parts[1] } ?: pendingRequest?.takeIf { it.id == parts[1] } ?: return true
        val terminalPending = current == pendingRequest && parts[2] != "PENDING"
        if (current.epoch != epoch) {
            if (terminalPending) { pendingRequest = null; pendingRevision = null; awaited = null; revision = null; get() }
            return true
        }
        if (current == awaited) awaited = null
        if (terminalPending) { pendingRequest = null; pendingRevision = null; if (awaited?.kind == Kind.POLL) awaited = null }
        val fields = parts.drop(3).mapNotNull { part -> part.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
        when (parts[2]) {
            "ERROR" -> {
                if (parts[3] == "unavailable" && current.kind != Kind.SET && pendingRequest == null) {
                    report("Agent is starting; waiting to apply saved settings.")
                    val connection = generation; val currentEpoch = epoch
                    defer(1000) { synchronized(this) {
                        if (generation == connection && epoch == currentEpoch && awaited == null && generation >= 0) get()
                    } }
                } else report("Live settings: ${parts.drop(3).joinToString(" ")}")
            }
            "PENDING" -> {
                pendingRevision = current.base?.plus(1)
                pendingEpoch = current.epoch
                pendingRequest = current
                report("Settings change is pending; running revision ${fields["rev"] ?: "unknown"}.")
                poll()
            }
            "CONFLICT" -> {
                if (conflictRetries++ == 0) get()
                else report("Live settings changed concurrently; Apply again to refresh them.")
            }
            "OK" -> {
                val value = fields["rev"]?.toLongOrNull()
                if (value == null || fields["live"] == null) {
                    report("This agent does not report effective live settings; restart to apply.")
                    return true
                }
                revision = value
                effective = KEYS.mapNotNull { key -> fields[key]?.let { text ->
                    when (text) { "true" -> key to true; "false" -> key to false; else -> null }
                } }.toMap()
                supported = fields.getValue("live").split(',').filter { it in KEYS }.toSet()
                if (!effective.keys.containsAll(supported)) {
                    report("The running agent did not report its effective settings; restart to apply.")
                    return true
                }
                watchHash = fields["watchExtensions.sha256"].orEmpty()
                if (current.kind == Kind.SET) showEffective()
                else if (pendingRequest != null) { showEffective(" Settings change is still pending."); poll() }
                else if (pendingRevision?.let { value < it } == true) {
                    report("Settings change is pending; running revision $value."); poll()
                } else {
                    val changedSincePending = pendingRevision == null || pendingEpoch != epoch
                    pendingRevision = null
                    if (changedSincePending) reconcile() else showEffective()
                }
            }
        }
        return true
    }
    private fun reconcile() {
        val base = revision ?: return get()
        val target = desired()
        val changes = target.options.filter { (key, value) -> key in supported && effective[key] != value }
        if (changes.isEmpty()) showEffective()
        else request(Kind.SET, base) { id ->
            "CONFIG SET $id base=$base " + KEYS.filter { it in changes }.joinToString(" ") { "$it=${changes[it]}" }
        }
    }
    private fun showEffective(suffix: String = "") {
        val mismatch = watchHash.isNotEmpty() && watchHash != extensionHash(desired().watchExtensions)
        val labels = mapOf("autoCompile" to "Internal compilation", "autoImpex" to "ImpEx import", "impexAllowRemove" to "REMOVE",
            "autoUpdateRunningSystem" to "Update Running System", "autoSolrReindex" to "Solr update", "backofficeConfigReset" to "Backoffice reset")
        val state = KEYS.filter { it in effective && it in supported }.joinToString(", ") { "${labels[it]}: ${if (effective[it] == true) "on" else "off"}" }
        report("Running agent: $state" + (if (mismatch) "; Watch extensions requires a restart." else "") + suffix)
    }
    companion object {
        val KEYS = listOf("autoCompile", "autoImpex", "impexAllowRemove", "autoUpdateRunningSystem", "autoSolrReindex", "backofficeConfigReset")
        fun extensionHash(value: String): String {
            val names = value.split(';').map { it.trim() }.filter { it.isNotEmpty() }.distinct().sorted().ifEmpty { listOf("*") }
            return MessageDigest.getInstance("SHA-256").digest(names.joinToString(";").toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 255) }
        }
    }
}
