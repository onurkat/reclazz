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
    @Override String config(int stage){
        return super.config(stage)
            .replace("securityMatcher(\"/gate/**\")", "securityMatcher(new org.springframework.security.web.util.matcher.AntPathRequestMatcher(\"/gate/**\"))")
            .replace("requestMatchers(\"/metrics\",\"/gate/**\")", "requestMatchers(new org.springframework.security.web.util.matcher.AntPathRequestMatcher(\"/metrics\"),new org.springframework.security.web.util.matcher.AntPathRequestMatcher(\"/gate/**\"))")
            .replace("requestMatchers(\"/ignored\")", "requestMatchers(new org.springframework.security.web.util.matcher.AntPathRequestMatcher(\"/ignored\"))");
    }
}
