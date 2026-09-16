/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.e2e;

/** Same real-agent authorization contract, using fixed application policy markers. */
class ComposedMethodSecurityReloadTest extends AddedMethodSecurityReloadTest {
    @Override String store(int version) {
        return super.store(version)
                .replace("@org.springframework.security.access.prepost.PreAuthorize(\"hasAuthority('WRITE') and #p0 == authentication.name\")", "@Policies.NestedWriter")
                .replace("@org.springframework.security.access.prepost.PreAuthorize(\"hasAuthority('ADMIN')\")", "@Policies.Admin")
                .replace("@org.springframework.security.access.prepost.PostAuthorize(\"returnObject == 'v3:' + authentication.name\")", "@Policies.OwnResult")
                + """
                class Policies {
                    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
                    @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('WRITE') and #p0 == authentication.name")
                    public @interface Writer { }
                    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
                    @Writer public @interface NestedWriter { }
                    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
                    @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('ADMIN')")
                    public @interface Admin { }
                    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
                    @org.springframework.security.access.prepost.PostAuthorize("returnObject == 'v3:' + authentication.name")
                    public @interface OwnResult { }
                }
                """;
    }
}
