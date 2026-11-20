/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.plugin.reload
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class LiveSettingsSyncTest {
    private class Fixture {
        val defaults = LiveSettingsSync.KEYS.associateWith { it == "backofficeConfigReset" }
        var wanted = LiveSettingsSync.Desired(defaults, "")
        val sent = mutableListOf<Pair<Long,String>>()
        val messages = mutableListOf<String>()
        val timers = mutableListOf<Pair<Long,()->Unit>>()
        val sync = LiveSettingsSync({ wanted }, { generation, command -> sent += generation to command; true }, messages::add,
            { delay, action -> timers += delay to action })
        fun id() = sent.last().second.split(' ')[2]
        fun reply(id: String = id(), generation: Long = 1, status: String = "OK", rev: Long = 0,
                  options: Map<String,Boolean> = defaults, watch: String = "", live: List<String> = LiveSettingsSync.KEYS) {
            sync.receive(generation, "CONFIG $id $status rev=$rev " + options.entries.joinToString(" ") { "${it.key}=${it.value}" } +
                " live=${live.joinToString(",")} watchExtensions.sha256=${LiveSettingsSync.extensionHash(watch)} watchExtensions.count=1")
        }
        fun desire(key: String, value: Boolean) { wanted = wanted.copy(options = wanted.options + (key to value)) }
        fun sets() = sent.map { it.second }.filter { it.startsWith("CONFIG SET ") }
        fun timer(delay: Long) { val i=timers.indexOfFirst { it.first==delay }; assertTrue(i>=0); timers.removeAt(i).second() }
    }
    @Test fun applySendsOneSetWithBase() {
        val f=Fixture();f.desire("autoImpex",true);f.sync.connected(1);f.reply(rev=7)
        assertEquals(1,f.sets().size);assertTrue(f.sets().single().endsWith("base=7 autoImpex=true"))
    }
    @Test fun reconnectSendsOnlyDifferences() {
        val f=Fixture();f.sync.connected(1);f.reply();assertTrue(f.sets().isEmpty());f.sync.disconnected(1);f.sync.connected(3)
        f.reply(generation=3,rev=8,options=f.defaults+("autoImpex" to true));assertEquals(1,f.sets().size);assertTrue(f.sets().single().endsWith("base=8 autoImpex=false"))
    }
    @Test fun staleGetFromOldConnectionDiscarded() {
        val f=Fixture();f.desire("autoImpex",true);f.sync.connected(1);val old=f.id();f.sync.connected(2)
        f.reply(id=old,generation=1);assertTrue(f.sets().isEmpty());f.reply(generation=2);assertEquals(1,f.sets().size);assertEquals(2L,f.sent.last().first)
    }
    @Test fun staleDisconnectDoesNotClearTheNewConnection() {
        val f=Fixture();f.desire("autoImpex",true);f.sync.connected(1);f.sync.connected(2);f.sync.disconnected(1);f.reply(generation=2);assertEquals(1,f.sets().size)
    }
    @Test fun staleApplyWithinConnectionConflictRefetch() {
        val f=Fixture();f.sync.connected(1);val old=f.id();f.desire("autoImpex",true);f.sync.apply();f.reply(id=old);assertTrue(f.sets().isEmpty())
        f.reply();assertEquals(1,f.sets().size);f.reply(status="CONFLICT",rev=1);assertTrue(f.sent.last().second.startsWith("CONFIG GET "))
        f.reply(rev=1);assertEquals(2,f.sets().size);assertTrue(f.sets().last().contains("base=1"))
    }
    @Test fun conflictRefetchesOnce() {
        val f=Fixture();f.desire("autoImpex",true);f.sync.connected(1);f.reply();f.reply(status="CONFLICT",rev=1);f.reply(rev=1)
        val before=f.sent.size;f.reply(status="CONFLICT",rev=2);assertEquals(before,f.sent.size);assertTrue(f.messages.last().contains("concurrently"))
    }
    @Test fun legacyAgentShowsRestartMessage() {
        val f=Fixture();f.sync.connected(1);f.timer(5000);assertTrue(f.sets().isEmpty());assertTrue(f.messages.last().contains("restart to apply"))
    }
    @Test fun ackDoesNotEcho() {
        val f=Fixture();f.desire("autoImpex",true);f.sync.connected(1);f.reply();val before=f.sent.size;f.reply(rev=1,options=f.wanted.options)
        assertEquals(before,f.sent.size);assertTrue(f.messages.last().contains("ImpEx import: on"))
    }
    @Test fun applyAutoImpexAndWatchExtensionsSendsOnlyAutoImpex() {
        val f=Fixture();f.wanted=f.wanted.copy(watchExtensions="another");f.desire("autoImpex",true);f.sync.connected(1);f.reply()
        assertTrue(f.sets().single().endsWith("autoImpex=true"));assertFalse(f.sets().single().contains("watchExtensions"));f.reply(rev=1,options=f.wanted.options)
        assertTrue(f.messages.last().contains("Watch extensions requires a restart"))
    }
    @Test fun timeoutLateAckShownFromGetWithoutResending() {
        val f=Fixture();f.desire("autoImpex",true);f.sync.connected(1);f.reply();val set=f.id();f.sync.receive(1,"CONFIG $set PENDING rev=0")
        f.timer(1000);f.reply(rev=0);assertEquals(1,f.sets().size)
        f.timer(1000);f.reply(rev=1,options=f.wanted.options);assertEquals(1,f.sets().size);assertTrue(f.messages.last().contains("ImpEx import: on"))
        f.reply(id=set,rev=1,options=f.wanted.options);assertEquals(1,f.sets().size)
    }
    @Test fun missingEffectiveValueDoesNotBecomeAnAssumedDifference() {
        val f=Fixture();f.desire("autoImpex",true);f.sync.connected(1);f.reply(options=f.defaults-"autoCompile")
        assertTrue(f.sets().isEmpty());assertTrue(f.messages.last().contains("did not report"))
    }
    @Test fun pendingCompletionReportsExternalChangeWithoutEcho() {
        val f=Fixture();f.desire("autoImpex",true);f.sync.connected(1);f.reply();f.sync.receive(1,"CONFIG ${f.id()} PENDING rev=0")
        f.timer(1000);f.reply(rev=2,options=f.defaults)
        assertEquals(1,f.sets().size);assertTrue(f.messages.last().contains("ImpEx import: off"))
    }

    @Test fun queuedPendingConflictIsRefetchedForTheLatestApply() {
        val f=Fixture();f.desire("autoImpex",true);f.sync.connected(1);f.reply();val pending=f.id()
        f.sync.receive(1,"CONFIG $pending PENDING rev=0");f.timer(1000)
        f.reply(rev=1,options=f.defaults+("autoCompile" to true))
        f.reply(id=pending,status="CONFLICT",rev=1,options=f.defaults+("autoCompile" to true))
        assertEquals(3,f.sent.count { it.second.startsWith("CONFIG GET ") },"a late conflict belongs to the pending SET, not the intervening GET")
        f.reply(rev=1,options=f.defaults+("autoCompile" to true));assertEquals(2,f.sets().size);assertTrue(f.sets().last().contains("base=1"))
    }

    @Test fun terminalPendingAckCancelsFuturePollEcho() {
        val f=Fixture();f.desire("autoImpex",true);f.sync.connected(1);f.reply();val pending=f.id();f.sync.receive(1,"CONFIG $pending PENDING rev=0")
        f.timer(1000);f.reply(rev=0);f.reply(id=pending,rev=1,options=f.wanted.options);val before=f.sent.size;f.timer(1000);assertEquals(before,f.sent.size)
    }

    @Test fun startingAgentUnavailableRetriesTheGetOnTheSameGeneration() {
        val f=Fixture();f.desire("autoImpex",true);f.sync.connected(1);f.sync.receive(1,"CONFIG ${f.id()} ERROR unavailable Live settings are unavailable")
        assertTrue(f.messages.last().contains("starting"));f.timer(1000);f.reply();assertEquals(1,f.sets().size)
    }
    @Test fun unsupportedSapOptionsAreNotShownAsActiveInGenericAgent() {
        val f=Fixture();f.sync.connected(1);f.reply(live=listOf("autoCompile"));assertTrue(f.messages.last().contains("Internal compilation: off"));assertFalse(f.messages.last().contains("Backoffice"))
    }

    @Test fun namedScopeAndBooleansUseOneSetWhenSupported() {
        val f=Fixture();f.wanted=f.wanted.copy(watchExtensions="b;a;b");f.desire("autoCompile",true);f.sync.connected(1)
        f.reply(watch="a",live=LiveSettingsSync.KEYS+"watchExtensions")
        assertEquals(1,f.sets().size);assertTrue(f.sets().single().endsWith("base=0 autoCompile=true watchExtensions=a;b"))
        f.sync.receive(1,"CONFIG ${f.id()} OK rev=1 autoCompile=true live=autoCompile,watchExtensions watchExtensions.sha256=${LiveSettingsSync.extensionHash("a;b")} loadedUninstrumented=2 unwatchable=1")
        assertEquals(1,f.sets().size);assertTrue(f.messages.last().contains("2 already loaded classes support body edits only"));assertTrue(f.messages.last().contains("1 directories could not be watched"))
        assertFalse(f.messages.last().contains("requires a restart"))
    }
    @Test fun scopeOnlyChangeIsSentAndMatchingScopeDoesNotEcho() {
        val f=Fixture();f.wanted=f.wanted.copy(watchExtensions="b");f.sync.connected(1);f.reply(watch="a",live=LiveSettingsSync.KEYS+"watchExtensions")
        assertTrue(f.sets().single().endsWith("base=0 watchExtensions=b"));f.reply(rev=1,watch="b",live=LiveSettingsSync.KEYS+"watchExtensions");assertEquals(1,f.sets().size)
        f.sync.apply();assertEquals(1,f.sets().size)
    }
    @Test fun blankOrStarDesiredScopeKeepsRestartBoundaryWithoutBlockingBooleans() {
        for(watch in listOf("","*","a;*")) {
            val f=Fixture();f.wanted=f.wanted.copy(watchExtensions=watch);f.desire("autoCompile",true);f.sync.connected(1)
            f.reply(watch="a",live=LiveSettingsSync.KEYS+"watchExtensions");assertTrue(f.sets().single().endsWith("autoCompile=true"));assertFalse(f.sets().single().contains("watchExtensions="))
            f.reply(rev=1,watch="a",options=f.wanted.options,live=LiveSettingsSync.KEYS+"watchExtensions");assertTrue(f.messages.last().contains("requires a restart"))
        }
    }
    @Test fun invalidScopeCannotInjectAnotherCommand() {
        val f=Fixture();f.wanted=f.wanted.copy(watchExtensions="a\nCONFIG GET injected");f.sync.connected(1);f.reply(watch="a",live=LiveSettingsSync.KEYS+"watchExtensions")
        assertTrue(f.sets().isEmpty());assertTrue(f.messages.last().contains("invalid"))
    }

}
