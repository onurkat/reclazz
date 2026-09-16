/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.e2e;

class SecurityProxyValueReloadTest extends TransactionProxyValueReloadTest {
    @Override String applicationSource() {
        return super.applicationSource()
                .replace("@EnableTransactionManagement", "@org.springframework.security.config.annotation.method.configuration.EnableGlobalMethodSecurity(prePostEnabled=true)\n@EnableTransactionManagement")
                .replace("public long setting() {", "@org.springframework.security.access.prepost.PreAuthorize(\"hasAuthority('READ')\") @org.springframework.security.access.prepost.PostAuthorize(\"returnObject > 0\") public long setting() {")
                .replace("@Transactional public void save", "@org.springframework.security.access.prepost.PreAuthorize(\"hasAuthority('WRITE')\") @Transactional public void save")
                .replace("static void exercise(Api bean) {", """
                    static void exercise(Api bean) {
                        org.springframework.security.core.context.SecurityContextHolder.clearContext();
                        try { bean.setting(); throw new AssertionError("unauthenticated body returned"); }
                        catch(org.springframework.security.authentication.AuthenticationCredentialsNotFoundException expected) { }
                        login("NONE");
                        try { bean.setting(); throw new AssertionError("unauthorized body returned"); }
                        catch(org.springframework.security.access.AccessDeniedException expected) { }
                        try { bean.save(false); throw new AssertionError("unauthorized write returned"); }
                        catch(org.springframework.security.access.AccessDeniedException expected) { }
                        login("READ","WRITE");
                    """)
                .replace("static String result(Holder h,JdbcTemplate jdbc) {", """
                    static void login(String... roles) {
                        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                            new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("alice","unused",
                                org.springframework.security.core.authority.AuthorityUtils.createAuthorityList(roles)));
                    }
                    static String result(Holder h,JdbcTemplate jdbc) {
                    """);
    }
}
