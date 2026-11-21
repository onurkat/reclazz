/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.reload;

import com.onurkat.reclazz.bootstrap.RecordSchema;
import com.onurkat.reclazz.ui.RestartLedger;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class RecordReadinessTest {
    @TempDir Path dir;
    private Path compile(String components)throws Exception {
        Path source=dir.resolve("P.java");Files.writeString(source,"package com.example; public record P("+components+") {}");
        assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,null,null,"--release","17","-d",dir.toString(),source.toString()));
        return dir.resolve("com/example/P.class");
    }
    @Test void readinessAndSubstrateNeverBypassSaveSpecificAdmission()throws Exception {
        RestartLedger.clear();Path file=compile("int a");
        try(var loader=new URLClassLoader(new java.net.URL[]{dir.toUri().toURL()},null)) {
            Class<?> owner=loader.loadClass("com.example.P");
            // The substrate can exist and even have a committed local snapshot; that is not an
            // admission proof and must never bypass the real save preflight.
            var slot=RecordSchema.slot(owner);
            try(var prepared=slot.prepare(slot.current().members())){assertTrue(prepared.commit());}
            file=compile("int a,String b");byte[] changed=Files.readAllBytes(file);
            var refused=new RefusalTaint().preflight(Map.of(owner.getName(),changed),Map.of(owner.getName(),file),new Class<?>[]{owner});
            assertEquals(List.of("record components of com.example.P changed (a JVM limit): restart to apply"),List.copyOf(refused.values()));
            assertEquals(1,owner.getRecordComponents().length);assertEquals(1,RestartLedger.size());
            assertTrue(RecordReloadPlan.ready(RecordReloadPlan.Route.NATIVE));
            assertFalse(RecordReloadPlan.ready(RecordReloadPlan.Route.STOCK));
            assertFalse(RecordReloadPlan.canAdmitShape(owner, changed));
        } finally {RestartLedger.clear();}
    }
}
