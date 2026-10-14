/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.e2e;
class ModernSecuredMetadataReloadTest extends SecuredMetadataReloadTest { @Override String configuration(){return "@org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity(prePostEnabled=false,securedEnabled=true,proxyTargetClass=true)";} }
