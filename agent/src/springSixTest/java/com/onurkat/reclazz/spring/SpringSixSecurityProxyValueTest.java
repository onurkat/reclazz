/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.Advised;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import static com.onurkat.reclazz.spring.TransactionProxyValueTest.*;
import static org.junit.jupiter.api.Assertions.*;

class SpringSixSecurityProxyValueTest extends SecurityProxyValueTest {
    @Configuration(proxyBeanMethods=false) @EnableMethodSecurity
    static class ModernJdk { }
    @Configuration(proxyBeanMethods=false) @EnableMethodSecurity(proxyTargetClass=true)
    static class ModernCglib { }
    @Override Class<?> securityConfiguration(boolean cglib) { return cglib?ModernCglib.class:ModernJdk.class; }
    @Override void customizeSecurity(Advised proxy) throws Exception {
        Object advisor=Arrays.stream(proxy.getAdvisors()).filter(SpringModernSecurityAdvice::isAdvisor).findFirst().orElseThrow();
        if(advisor.getClass().getName().endsWith("$AdvisorWrapper")) advisor=com.onurkat.reclazz.util.Reflect.readField(advisor,"advisor");
        Object supplier=com.onurkat.reclazz.util.Reflect.readField(advisor,"delegate");
        Object interceptor=com.onurkat.reclazz.util.Reflect.readField(supplier,"singletonInstance");
        assertNotNull(interceptor);
        org.springframework.security.authorization.AuthorizationManager<org.aopalliance.intercept.MethodInvocation> custom=
                (authentication,invocation)->new org.springframework.security.authorization.AuthorizationDecision(true);
        com.onurkat.reclazz.util.Reflect.findField(interceptor.getClass(),"authorizationManager").set(interceptor,custom);
    }
    @Override void makeSecurityCold(Advised proxy,AtomicBoolean initialized) throws Exception {
        Object advisor=Arrays.stream(proxy.getAdvisors()).filter(SpringModernSecurityAdvice::isAdvisor).findFirst().orElseThrow();
        if(advisor.getClass().getName().endsWith("$AdvisorWrapper")) advisor=com.onurkat.reclazz.util.Reflect.readField(advisor,"advisor");
        assertTrue(advisor.getClass().getName().endsWith("DeferringMethodInterceptor"));
        var supplier=org.springframework.util.function.SingletonSupplier.of((java.util.function.Supplier<Object>)()->{
            initialized.set(true); throw new AssertionError("candidate initialized modern security advice");
        });
        com.onurkat.reclazz.util.Reflect.findField(advisor.getClass(),"delegate").set(advisor,supplier);
    }
    @ParameterizedTest @ValueSource(ints={1,2})
    void losingEitherPolicyHoldsTheCandidate(int missing) throws Exception {
        try(var f=new Fixture(false,true,true)) {
            Api original=f.bean(); deniedAndAllowed(f,5000,true); Advised proxy=(Advised)original;
            for(var advisor:proxy.getAdvisors()) if(SpringModernSecurityAdvice.isAdvisor(advisor)
                    && SpringModernSecurityAdvice.inspect(advisor).policies()==missing) proxy.removeAdvisor(advisor);
            var result=f.apply("8"); assertEquals(PropertyChangeOutcome.State.UNCHECKABLE,result.state());
            assertSame(original,f.bean()); assertEquals(0,Service.destroyed); assertEquals("5",f.context.getEnvironment().getProperty("cfg.seconds"));
        }
    }
}
