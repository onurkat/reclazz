/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.ext;

import com.onurkat.reclazz.platform.XmlBeanLifecycle;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class XmlBeanLifecycleAdmissionTest {
    @Test void preservedSingletonsRouteOnlyAdmittedOwner() throws Exception {
        Object owner = new Object();
        var absent = new PremiumModules(c -> new PremiumAdmission.Admission(true, ""));
        assertEquals(Set.of(), absent.preservedSingletons(owner));
        for (boolean allowed : List.of(false, true)) {
            var modules = new PremiumModules(c -> new PremiumAdmission.Admission(allowed, ""));
            assertTrue(modules.register(() -> new PremiumModule() {
                public Set<Capability> capabilities() { return Set.of(Capability.INTERCEPTOR_REFRESH); }
                public com.onurkat.reclazz.platform.PreservedSingletons preservedSingletons() {
                    assertTrue(allowed); return actual -> { assertSame(owner, actual); return Set.of("canonical"); };
                }
            }));
            assertEquals(allowed ? Set.of("canonical") : Set.of(), modules.preservedSingletons(owner));
        }
    }
    @Test void pairsCompletionWithOneAdmissionAndExactOwner() {
        List<String> order=new ArrayList<>();Object owner=new Object(),old=new Object(),next=new Object();
        var modules=new PremiumModules(c->{order.add("admit");return new PremiumAdmission.Admission(true,"");});
        assertTrue(modules.register(()->new PremiumModule() {
            public Set<Capability> capabilities() {return Set.of(Capability.INTERCEPTOR_REFRESH);}
            public XmlBeanLifecycle xmlBeanLifecycle() {return (context,name,previous)->{
                assertSame(owner,context);assertSame(old,previous);assertEquals("probe",name);order.add("before");
                return (current,applied)->{assertSame(next,current);assertTrue(applied);order.add("after");};
            };}
        }));
        var pending=modules.beforeXmlBean(owner,"probe",old);order.add("mutation");pending.complete(next,true);
        assertEquals(List.of("admit","before","mutation","after"),order);
    }
    @Test void absentOrDeniedModuleDoesNotInvokePlatformHook() {
        for(boolean present:List.of(false,true)) {
            var modules=new PremiumModules(c->new PremiumAdmission.Admission(false,"denied"));
            if(present) assertTrue(modules.register(()->new PremiumModule() {
                public Set<Capability> capabilities() {return Set.of(Capability.INTERCEPTOR_REFRESH);}
                public XmlBeanLifecycle xmlBeanLifecycle() {throw new AssertionError("denied hook invoked");}
            }));
            modules.beforeXmlBean(new Object(),"probe",null).complete(new Object(),true);
        }
    }
}
