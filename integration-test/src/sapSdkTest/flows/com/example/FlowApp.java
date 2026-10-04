/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.example;

import com.sun.net.httpserver.HttpServer;
import de.hybris.platform.cronjob.model.CronJobModel;
import de.hybris.platform.servicelayer.internal.model.attribute.impl.DefaultDynamicAttributesProvider;
import de.hybris.platform.servicelayer.model.AbstractItemModel;
import de.hybris.platform.servicelayer.model.ItemModelInternalContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.SpringVersion;

import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** Local SDK acceptance only: persistence/type resolution and scheduler entry are isolated. */
public class FlowApp {
    public static int gets, sets, jobs;
    public static String lastJob;

    private static ItemModelInternalContext persistence(DefaultDynamicAttributesProvider provider) {
        Map<String, Object> values = new HashMap<>();
        return (ItemModelInternalContext) Proxy.newProxyInstance(FlowApp.class.getClassLoader(),
                new Class<?>[]{ItemModelInternalContext.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getDynamicValue" -> provider.get((AbstractItemModel) args[0], (String) args[1]);
                    case "setDynamicValue" -> { provider.set((AbstractItemModel) args[0], (String) args[1], args[2]); yield null; }
                    case "getPropertyValue" -> values.get((String) args[0]);
                    case "setPropertyValue" -> { values.put((String) args[0], args[1]); yield null; }
                    default -> throw new AssertionError("Unexpected persistence call: " + method.getName());
                });
    }

    public static void main(String[] args) throws Exception {
        var context = new GenericApplicationContext();
        context.registerBean("dynamic", DynamicHandler.class);
        context.registerBean("job", ProbeJob.class);
        context.registerBean("stable", Object.class);
        context.refresh();
        Object stable = context.getBean("stable");
        DynamicHandler heldHandler = context.getBean(DynamicHandler.class);
        ProbeJob heldJob = context.getBean(ProbeJob.class);
        var heldProvider = new DefaultDynamicAttributesProvider(Map.of("computed", heldHandler));
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String nonce = exchange.getRequestURI().getRawQuery();
            int status = 200;
            String response;
            try {
                if (!"GET".equals(exchange.getRequestMethod()) || nonce == null || !nonce.matches("[a-z0-9-]{1,60}"))
                    throw new IllegalArgumentException("Invalid synthetic request");
                String common = "nonce=" + nonce + ";pid=" + ProcessHandle.current().pid()
                        + ";spring=" + SpringVersion.getVersion() + ";stable=" + (stable == context.getBean("stable"));
                if (exchange.getRequestURI().getPath().equals("/dynamic")) {
                    gets = sets = 0;
                    var retained = new ProbeItem(persistence(heldProvider), nonce);
                    var current = new ProbeItem(persistence(new DefaultDynamicAttributesProvider(
                            Map.of("computed", context.getBean(DynamicHandler.class)))), nonce);
                    retained.setComputed(nonce); current.setComputed(nonce);
                    String a = retained.getComputed(), b = current.getComputed();
                    response = common + ";held=" + a + ";current=" + b + ";stored=" + retained.stored
                            + ";currentStored=" + current.stored + ";gets=" + gets + ";sets=" + sets;
                } else if (exchange.getRequestURI().getPath().equals("/job")) {
                    jobs = 0;
                    var model = new CronJobModel(persistence(null));
                    model.setCode(nonce);
                    var a = heldJob.perform(model); String first = lastJob;
                    ProbeJob current = context.getBean(ProbeJob.class);
                    var b = current.perform(model);
                    response = common + ";held=" + first + ";current=" + lastJob + ";result=" + a.getResult().getCode()
                            + ";currentResult=" + b.getResult().getCode() + ";status=" + a.getStatus().getCode()
                            + ";currentStatus=" + b.getStatus().getCode() + ";jobs=" + jobs
                            + ";performable=" + current.isPerformable() + ";abortable=" + current.isAbortable();
                } else throw new IllegalArgumentException("Unknown synthetic flow");
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
        System.out.println("FLOW_PORT=" + server.getAddress().getPort());
    }
}
