/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import org.springframework.aop.framework.Advised;

/** Legacy fixture kept separate so modern security tests do not verify obsolete API bodies. */
final class LegacyMethodSecuritySupport {
    private LegacyMethodSecuritySupport() { }
    static void openAllMethods(Advised proxy) throws Exception {
        Object advisor=Arrays.stream(proxy.getAdvisors()).filter(SpringSecurityAdvice::isAdvisor).findFirst().orElseThrow();
        var interceptor=(org.springframework.security.access.intercept.aopalliance.MethodSecurityInterceptor)SpringSecurityAdvice.interceptor(advisor);
        interceptor.setSecurityMetadataSource(new org.springframework.security.access.method.AbstractMethodSecurityMetadataSource() {
            public Collection<org.springframework.security.access.ConfigAttribute> getAttributes(java.lang.reflect.Method m,Class<?> t) { return List.of(); }
            public Collection<org.springframework.security.access.ConfigAttribute> getAllConfigAttributes() { return List.of(); }
        });
    }
}
