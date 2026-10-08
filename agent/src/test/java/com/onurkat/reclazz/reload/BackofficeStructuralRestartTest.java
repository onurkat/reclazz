/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.reload;

import com.onurkat.reclazz.config.AgentConfig;
import com.onurkat.reclazz.platform.PlatformContext;
import com.onurkat.reclazz.transform.*;
import com.onurkat.reclazz.ui.RestartLedger;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.lang.reflect.Proxy;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** Real class redefinition with a synthetic annotation shape; no native SAP UI. */
class BackofficeStructuralRestartTest extends TransformTestBase {
    @ParameterizedTest @ValueSource(strings={"body", "helper", "socket"})
    void onlyAddedSocketRegistrationCreatesPendingRestart(String edit) throws Exception {
        String name="CommerceWidget"+edit;
        var context=new TransformContext();context.addWatched(name);
        var config=AgentConfig.parse(null);
        var transformer=new ReclazzTransformer(context,config);
        var original=compile(new SourceFile(name,source(name,"before","")),annotation());
        byte[] transformed=transformer.transform(getClass().getClassLoader(),name,null,null,original.get(name));
        original.put(name,transformed);
        Class<?> type=defineAndLoad(original,name);Object instance=type.getConstructor().newInstance();
        assertEquals("before",type.getMethod("run").invoke(instance));
        String added=switch(edit) {
            case "socket" -> "@com.hybris.cockpitng.annotations.SocketEvent public void input() {}";
            case "helper" -> "public void helper() {}";
            default -> "";
        };
        byte[] saved=compile(new SourceFile(name,source(name,"after",added)),annotation()).get(name);
        var platform=(PlatformContext)Proxy.newProxyInstance(PlatformContext.class.getClassLoader(),new Class<?>[]{PlatformContext.class},
                (p,m,a)->switch(m.getName()) {
                    case "getPlatformId" -> PlatformContext.Platform.HYBRIS;
                    case "getAllApplicationContexts" -> List.of();
                    case "getApplicationContext" -> null;
                    default -> throw new UnsupportedOperationException(m.getName());
                });
        var inst=ByteBuddyAgent.install();
        var reloader=new StructuralReloader(inst,context,config,platform);reloader.setTransformer(transformer);
        RestartLedger.clear();inst.addTransformer(transformer,true);
        try {
            var result=reloader.reload(name,saved);assertTrue(result.isSuccess(),result.getError());
            assertEquals("after",type.getMethod("run").invoke(instance),"body still applies to the original object");
            assertEquals(edit.equals("socket")?1:0,RestartLedger.size(),RestartLedger.digest().toString());
            if(edit.equals("socket"))assertTrue(RestartLedger.digest().stream().anyMatch(s->s.contains("SocketEvent")));
        } finally { inst.removeTransformer(transformer);RestartLedger.clear(); }
    }
    private static String source(String name,String value,String added) {
        return "public class "+name+" { public String run() {return \""+value+"\";} "+added+" }";
    }
    private static SourceFile annotation() {
        return new SourceFile("com.hybris.cockpitng.annotations.SocketEvent","""
            package com.hybris.cockpitng.annotations;
            @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
            @java.lang.annotation.Target(java.lang.annotation.ElementType.METHOD)
            public @interface SocketEvent {}
            """);
    }
}
