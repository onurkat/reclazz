/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.hybris.codegen;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** HAC can be deployed at the server root or under a configured context path. */
final class HacUpdateLocation {
    static final String DEFAULT = "default URL: https://localhost:9002/hac/platform/update";

    private HacUpdateLocation() { }

    static String read(ClassLoader loader) {
        if (loader == null || !com.onurkat.reclazz.platform.PlatformTenant.ensureActive(loader)) return DEFAULT;
        try {
            Class<?> config = Class.forName("de.hybris.platform.util.Config", false, loader);
            var parameter = config.getMethod("getParameter", String.class);
            String port = (String) parameter.invoke(null, "tomcat.ssl.port");
            String context = (String) parameter.invoke(null, "hac.webroot");
            if (context == null) {
                Class<?> util = Class.forName("de.hybris.bootstrap.config.ConfigUtil", false, loader);
                Object platform = util.getMethod("getPlatformConfig", Class.class).invoke(null, config);
                Object hac = platform.getClass().getMethod("getExtensionInfo", String.class).invoke(platform, "hac");
                if (hac == null) return DEFAULT;
                Object module = hac.getClass().getMethod("getWebModule").invoke(hac);
                if (module == null) return DEFAULT;
                context = (String) module.getClass().getMethod("getWebRoot").invoke(module);
            }
            return format(port, context);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            return DEFAULT;
        }
    }

    static String format(String port, String context) {
        if (context == null || port == null || !port.matches("[0-9]{1,5}")) return DEFAULT;
        int httpsPort = Integer.parseInt(port);
        if (httpsPort < 1 || httpsPort > 65535 || context.contains("//")
                || context.indexOf('\\') >= 0 || context.chars().anyMatch(Character::isISOControl)) return DEFAULT;
        var url = new StringBuilder("URL: https://localhost:").append(httpsPort);
        for (String part : context.split("/")) {
            if (part.isEmpty()) continue;
            if (part.equals(".") || part.equals("..")) return DEFAULT;
            url.append('/').append(URLEncoder.encode(part, StandardCharsets.UTF_8)
                    .replace("+", "%20").replace("*", "%2A"));
        }
        return url.append("/platform/update").toString();
    }
}
