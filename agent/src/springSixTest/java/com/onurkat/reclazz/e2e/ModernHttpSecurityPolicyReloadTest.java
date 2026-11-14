/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.e2e;
class ModernHttpSecurityPolicyReloadTest extends HttpSecurityPolicyReloadTest {
    @Override String namespace(){return "jakarta";}
    @Override String matcher(){return "securityMatcher";}
    @Override String rules(){return "authorizeHttpRequests";}
    @Override String paths(){return "requestMatchers";}
    private String requestMatcher(String path) {
        String modern = "org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher";
        try {
            Class.forName(modern, false, getClass().getClassLoader());
            return modern + ".withDefaults().matcher(\"" + path + "\")";
        } catch (ClassNotFoundException unavailable) {
            return "new org.springframework.security.web.util.matcher.AntPathRequestMatcher(\"" + path + "\")";
        }
    }
    @Override String config(int stage){
        return super.config(stage)
            .replace("securityMatcher(\"/gate/**\")", "securityMatcher(" + requestMatcher("/gate/**") + ")")
            .replace("requestMatchers(\"/metrics\",\"/gate/**\")", "requestMatchers(" + requestMatcher("/metrics") + "," + requestMatcher("/gate/**") + ")")
            .replace("requestMatchers(\"/ignored\")", "requestMatchers(" + requestMatcher("/ignored") + ")");
    }
}
