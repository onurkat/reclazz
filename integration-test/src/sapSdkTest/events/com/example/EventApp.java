/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.example;

import com.sun.net.httpserver.HttpServer;
import de.hybris.platform.servicelayer.cluster.ClusterService;
import de.hybris.platform.servicelayer.tenant.TenantService;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.SpringVersion;

import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

/** Local SAP event-listener acceptance: genuine AbstractEventListener, no tenant/cluster runtime. */
public class EventApp {
    public static int deliveries;
    public static String lastResult;

    @SuppressWarnings("unchecked")
    private static <T> T stub(Class<T> type, ClassLoader loader) {
        return (T) Proxy.newProxyInstance(loader, new Class<?>[]{type}, (proxy, method, args) -> switch (method.getName()) {
            case "getCurrentTenantId" -> "junit";
            case "getClusterIslandId" -> 0L;
            case "getClusterId" -> 0;
            case "getClusterGroups" -> Collections.emptyList();
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            case "toString" -> "stub:" + type.getSimpleName();
            default -> method.getReturnType() == boolean.class ? Boolean.FALSE
                    : method.getReturnType() == long.class ? Long.valueOf(0)
                    : method.getReturnType() == int.class ? Integer.valueOf(0) : null;
        });
    }

    public static void main(String[] args) throws Exception {
        var context = new GenericApplicationContext();
        ClassLoader loader = EventApp.class.getClassLoader();
        var dependency = new Dependency();
        context.getBeanFactory().registerSingleton("dependency", dependency);
        context.getBeanFactory().registerSingleton("stable", new Object());
        var listener = new ProbeListener(dependency);
        listener.setClusterService(stub(ClusterService.class, loader));
        listener.setTenantService(stub(TenantService.class, loader));
        listener.setApplicationContext(context);
        // Registered before refresh so Spring's multicaster delivers published events to it.
        context.getBeanFactory().registerSingleton("listener", listener);
        context.refresh();

        Object stable = context.getBean("stable");
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String nonce = exchange.getRequestURI().getRawQuery();
            int status = 200;
            String response;
            try {
                if (!"GET".equals(exchange.getRequestMethod()) || nonce == null || !nonce.matches("[a-z0-9-]{1,60}"))
                    throw new IllegalArgumentException("Invalid synthetic request");
                deliveries = 0;
                lastResult = null;
                context.publishEvent(new ProbeEvent(nonce));
                boolean heldStable = listener == context.getBean("listener") && stable == context.getBean("stable");
                response = "nonce=" + nonce + ";pid=" + ProcessHandle.current().pid()
                        + ";spring=" + SpringVersion.getVersion() + ";held=" + heldStable
                        + ";deliveries=" + deliveries + ";result=" + lastResult;
            } catch (Throwable failed) {
                status = 500;
                response = failed.getClass().getSimpleName() + ": " + failed.getMessage();
                failed.printStackTrace();
            }
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        System.out.println("EVENT_PORT=" + server.getAddress().getPort());
    }
}
