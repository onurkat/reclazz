/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.config;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
class LiveOptionsTest {
    @Test void startupArgumentsInitializeThePublishedSnapshot() {
        var config=AgentConfig.parse("autoCompile=true,autoImpex=true,impexAllowRemove=true,autoUpdateRunningSystem=true,autoSolrReindex=true,backofficeConfigReset=false");
        var options=config.liveOptions(); assertTrue(options.autoCompile());assertTrue(options.autoImpex());assertTrue(options.impexAllowRemove());assertTrue(options.autoUpdateRunningSystem());assertTrue(options.autoSolrReindex());assertFalse(options.backofficeConfigReset());
    }
    @Test void anOperationKeepsItsSnapshotAfterPublication() {
        var config=AgentConfig.parse(null);var operation=config.liveOptions();
        config.publishLiveOptions(operation.with(Map.of("autoImpex",true,"impexAllowRemove",true)));
        assertFalse(operation.autoImpex());assertFalse(operation.impexAllowRemove());assertTrue(config.isAutoImpex());assertTrue(config.isImpexAllowRemove());
        assertThrows(UnsupportedOperationException.class,()->operation.values().put("autoImpex",true));
    }
    @Test void readersNeverSeeHalfOfAMultiOptionPublication()throws Exception {
        var config=AgentConfig.parse(null);var off=config.liveOptions();var on=off.with(Map.of("autoCompile",true,"autoImpex",true));var failure=new AtomicReference<Throwable>();
        var reader=new Thread(()->{try{for(int i=0;i<10000;i++){var value=config.liveOptions();assertEquals(value.autoCompile(),value.autoImpex());}}catch(Throwable t){failure.set(t);}});reader.start();
        for(int i=0;i<10000;i++)config.publishLiveOptions((i&1)==0?on:off);reader.join();assertNull(failure.get());
    }
}
