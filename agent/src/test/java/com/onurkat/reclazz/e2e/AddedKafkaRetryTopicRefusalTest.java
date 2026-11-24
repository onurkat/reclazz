package com.onurkat.reclazz.e2e;

import com.onurkat.reclazz.e2e.harness.WatchedApp;
import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.kafka.test.EmbeddedKafkaBroker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AddedKafkaRetryTopicRefusalTest {
    @TempDir Path tmp;
    static EmbeddedKafkaBroker broker;

    @BeforeAll static void startBroker() {
        broker = new EmbeddedKafkaBroker(1, false, 1);
        broker.afterPropertiesSet();
    }

    @AfterAll static void stopBroker() {
        if (broker != null) broker.destroy();
    }

    @Test void unrelatedRetryTopicAllowsAddedListenerWithoutRetryContainers() throws Exception {
        String topic = "reclazz-unrelated-" + UUID.randomUUID();
        String destination = topic;
        broker.addTopics(destination);
        var pf = new DefaultKafkaProducerFactory<Integer, String>(KafkaTestUtils.producerProps(broker));
        var producer = new KafkaTemplate<Integer, String>(pf);
        try (var app = WatchedApp.in(tmp)
                .classpath(WatchedApp.springClasspath() + File.pathSeparator + kafkaClasspath())
                .jvmArgs("-Dtest.brokers=" + broker.getBrokersAsString(), "-Dtest.retry.topic=" + topic + "-other")
                .agentArgs("startupDelaySec=1,debounceMs=100,verbose=true")
                .with("Listener", listener(topic, false)).with("App", appWithRetryConfiguration()).start()) {
            app.awaitOrFail("PORT=", "Kafka app did not start");
            int port = Integer.parseInt(app.latest("PORT=").substring(5));
            app.awaitOrFail("Last-known-good bytecode cache:", "watcher did not start");
            app.rewrite("Listener", listener(topic, true));
            app.awaitOrFail("added @KafkaListener registered", "listener registration");
            producer.send(destination, "g1").get(10, TimeUnit.SECONDS);
            app.awaitOrFail("ADDED:g1", "delivery to the added listener");
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/ids")).GET().build();
            assertEquals("retry-added", HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString()).body());
        } finally {
            pf.destroy();
        }
    }

    @Test void matchingRetryTopicConfigurationInstallsTheNativeGraph() throws Exception {
        String topic = "reclazz-retry-" + UUID.randomUUID();
        try (var app = WatchedApp.in(tmp)
                .classpath(WatchedApp.springClasspath() + File.pathSeparator + kafkaClasspath())
                .jvmArgs("-Dtest.brokers=" + broker.getBrokersAsString(), "-Dtest.retry.topic=" + topic)
                .agentArgs("startupDelaySec=1,debounceMs=100,verbose=true")
                .with("Listener", listener(topic, false)).with("App", appWithRetryConfiguration()).start()) {
            app.awaitOrFail("PORT=", "Kafka app did not start");
            app.awaitOrFail("Last-known-good bytecode cache:", "watcher did not start");
            long before = reloads(app);
            app.rewrite("Listener", listener(topic, true));
            app.awaitOrFail("added @KafkaListener registered", "matched retry configuration must register");
            assertEquals(before + 1, reloads(app), app.tail());
            int port = Integer.parseInt(app.latest("PORT=").substring(5));
            var ids = retryIds(port);
            assertEquals(3, ids.size(), ids.toString());
            assertTrue(ids.contains("retry-added"));
            assertTrue(ids.stream().anyMatch(id -> id.startsWith("retry-added-retry")), ids.toString());
            assertTrue(ids.stream().anyMatch(id -> id.startsWith("retry-added-dlt")), ids.toString());
        }
    }

    @Test void helperRefreshRebindsAddedRetryListener() throws Exception {
        retryDependency(tmp, broker.getBrokersAsString(), false);
    }

    @Test void helperRefreshRebindsStartupRetryListener() throws Exception {
        retryDependency(tmp, broker.getBrokersAsString(), true);
    }

    @Test void lateAttachPreservesUnobservedStartupRetryGroup() throws Exception {
        retryDependency(tmp, broker.getBrokersAsString(), true, true);
    }

    static void retryDependency(Path root, String brokers, boolean startup) throws Exception {
        retryDependency(root, brokers, startup, false);
    }

    static void retryDependency(Path root, String brokers, boolean startup, boolean attach) throws Exception {
        String topic = "retry-helper-" + UUID.randomUUID();
        String helper = """
                package app;
                @org.springframework.stereotype.Component public class Helper {
                    final int seed;
                    public Helper() { seed = initial(); }
                    private int initial() { return 10; }
                }
                """;
        String constructor = """
                final int seed;
                public Listener(Helper helper) { seed = helper.seed; }
                public String use()""";
        String empty = listener(topic, false).replace("public String use()", constructor);
        String added = listener(topic, true).replace("public String use()", constructor)
                .replace("System.out.println(\"ADDED:\" + value);", "System.out.println(\"RECEIVED:\" + value + \":\" + seed);");
        var props = java.util.Map.<String,Object>of("bootstrap.servers", brokers,
                "key.serializer", org.apache.kafka.common.serialization.IntegerSerializer.class,
                "value.serializer", org.apache.kafka.common.serialization.StringSerializer.class);
        String fixture = appWithRetryConfiguration();
        if (startup && !attach) {
            fixture = fixture.replace(".maxAttempts(2)", ".includeTopic(System.getProperty(\"test.retry.topic\") + \"-dlt\")"
                    + ".includeTopic(System.getProperty(\"test.retry.topic\") + \"-independent\").maxAttempts(2)");
        }
        if (startup) {
            // Exactly the same name and class as a native product, but supplied by
            // the application before its context refresh. Retirement must preserve it.
            fixture = fixture.replace("new org.springframework.context.annotation.AnnotationConfigApplicationContext(App.class);", """
                    new org.springframework.context.annotation.AnnotationConfigApplicationContext();
                    String existingTopic = System.getProperty("test.retry.topic");
                    var existing = new org.springframework.kafka.support.TopicForRetryable(existingTopic, 1, (short)1);
                    ctx.getBeanFactory().registerSingleton(existingTopic + "-topicRegistrationBean", existing);
                    ctx.register(App.class);
                    ctx.refresh();
                    if (ctx.getBeanFactory().containsSingleton("__reclazz$kafkaStartupRetry"))
                        throw new IllegalStateException("startup receipt became an application bean");""");
            fixture = fixture.replace("server.start();", """
                    server.createContext("/kept", exchange -> {
                        boolean same = existing == ctx.getBeanFactory().getSingleton(existingTopic + "-topicRegistrationBean");
                        byte[] body = Boolean.toString(same).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(200, body.length);
                        exchange.getResponseBody().write(body); exchange.close();
                    });
                    server.start();""");
        }
        fixture = fixture.replace("server.start();", "System.out.println(\"APP_PID=\" + ProcessHandle.current().pid()); server.start();");
        if (attach) {
            // A context that completed refresh before attach is not in the refresh
            // interceptor's inventory. Pass this actual context to the same preflight
            // used by SpringBeanReloader and exercise the watched save separately.
            fixture = fixture.replace("server.start();", """
                    server.createContext("/preflight", exchange -> {
                        String result;
                        try {
                            var method = Class.forName("com.onurkat.reclazz.spring.SpringKafkaReloader")
                                    .getDeclaredMethod("prepareDependencyRefresh", java.util.List.class, Object.class, String.class);
                            method.setAccessible(true);
                            method.invoke(null, java.util.List.of(ctx), ctx, "helper");
                            result = "accepted";
                        } catch (java.lang.reflect.InvocationTargetException error) {
                            result = error.getCause().toString();
                        } catch (ReflectiveOperationException error) {
                            result = error.toString();
                        }
                        byte[] body = result.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(200, body.length);
                        exchange.getResponseBody().write(body);
                        exchange.close();
                    });
                    server.start();""");
        }
        var builder = WatchedApp.in(root).classpath(System.getProperty("java.class.path"))
                .jvmArgs("-Dtest.brokers=" + brokers, "-Dtest.retry.topic=" + topic)
                .with("Helper", helper).with("Listener", startup ? added : empty).with("App", fixture);
        if (startup && !attach) {
            builder.with("Other", listener(topic, false).replace("class Listener", "class Other"));
            builder.with("Sister", listener(topic, false).replace("class Listener", "class Sister"));
        }
        if (attach) builder.withoutAgent();
        else builder.agentArgs("startupDelaySec=1,debounceMs=100,verbose=true");
        try (var producer = new org.apache.kafka.clients.producer.KafkaProducer<Integer,String>(props);
             var app = builder.start()) {
            app.awaitOrFail("PORT=", "retry dependency fixture startup");
            int port = Integer.parseInt(app.latest("PORT=").substring(5));
            if (attach) {
                var vm = com.sun.tools.attach.VirtualMachine.attach(app.latest("APP_PID=").substring(8));
                try {
                    vm.loadAgent(WatchedApp.agentJarOrSkip(), "watchDirs=" + app.classesDir()
                            + ",startupDelaySec=0,debounceMs=100,verbose=true,portFile=" + root.resolve("agent.port"));
                } finally { vm.detach(); }

            }
            app.awaitOrFail("Last-known-good bytecode cache:", "watcher ready");
            if (!startup) {
                app.rewrite("Listener", added);
                app.awaitOrFail("added @KafkaListener registered", "retry listener installed");
            }
            var ids = retryIds(port);
            assertEquals(3, ids.size());
            var products = retryState(port, "topics");
            awaitAssignment(port, topic, app);
            producer.send(new org.apache.kafka.clients.producer.ProducerRecord<>(topic, "before")).get(10, TimeUnit.SECONDS);
            app.awaitOrFail("RECEIVED:before:10", "original helper reaches listener");
            if (attach) {
                var probe = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/preflight")).GET().build();
                String reason = HttpClient.newHttpClient().send(probe, HttpResponse.BodyHandlers.ofString()).body();
                assertTrue(reason.contains("Kafka retry registration ownership unavailable"), reason);
            }
            if (startup && !attach) {
                // Two listeners using a single RetryTopicConfiguration can own disjoint routes.
                String sister = listener(topic + "-independent", true).replace("class Listener", "class Sister")
                        .replace("retry-added", "retry-sister");
                app.rewrite("Sister", sister);
                app.awaitOrFail("added @KafkaListener registered", "independent listener shares the configuration");
                assertEquals(6, retryIds(port).size());
                app.rewrite("Other", listener(topic + "-dlt", true).replace("class Listener", "class Other")
                        .replace("retry-added", "retry-overlap"));
                app.awaitOrFail("Kafka retry route already belongs to another listener: " + topic + "-dlt",
                        "new main overlaps only the startup listener's DLT");
                assertEquals(6, retryIds(port).size());
                app.rewrite("Sister", listener(topic, false).replace("class Listener", "class Sister"));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
                while ((!retryIds(port).equals(ids) || !retryState(port, "topics").equals(products))
                        && System.nanoTime() < deadline) Thread.sleep(50);
                assertEquals(ids, retryIds(port));
                assertEquals(products, retryState(port, "topics"));
            }
            app.rewrite("Helper", helper.replace("return 10", "return 20"));
            if (attach) {
                app.awaitOrFail("Reloaded app.Helper", "watched save of preloaded helper");
                assertEquals(ids, retryIds(port));
                assertEquals(products, retryState(port, "topics"));
                assertEquals(java.util.Set.of("true"), retryState(port, "kept"));
                producer.send(new org.apache.kafka.clients.producer.ProducerRecord<>(topic, "retained")).get(10, TimeUnit.SECONDS);
                app.awaitOrFail("RECEIVED:retained:10", "old receiver still consumes after refusal");
                return;
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (app.output().stream().noneMatch(line -> line.contains("dependent Kafka consumers re-created")
                    || line.contains("Failed to refresh Spring bean for app.Helper")) && System.nanoTime() < deadline)
                Thread.sleep(50);
            assertTrue(app.output().stream().anyMatch(line -> line.contains("dependent Kafka consumers re-created")),
                    () -> "helper change must retire and restore the retry group: "
                            + app.output().stream().filter(line -> line.contains("Kafka startup retry")).toList() + app.tail());
            producer.send(new org.apache.kafka.clients.producer.ProducerRecord<>(topic, "after")).get(10, TimeUnit.SECONDS);
            app.awaitOrFail("RECEIVED:after:20", "retry listener uses the newly constructed owner");
            assertEquals(ids, retryIds(port));
            assertEquals(products, retryState(port, "topics"));
            if (startup) {
                long before = reloads(app);
                app.rewrite("Listener", added.replace("RECEIVED:", "EDITED:"));
                long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
                while (reloads(app) == before && System.nanoTime() < limit) Thread.sleep(50);
                assertTrue(reloads(app) > before, app.tail());
                producer.send(new org.apache.kafka.clients.producer.ProducerRecord<>(topic, "body")).get(10, TimeUnit.SECONDS);
                app.awaitOrFail("EDITED:body:20", "startup listener uses its edited body and refreshed helper");
                assertEquals(ids, retryIds(port));
            }
            app.rewrite("Listener", empty);
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            var remainingTopics = startup ? java.util.Set.of(topic) : java.util.Set.of();
            while ((!retryIds(port).isEmpty() || !retryState(port, "topics").equals(remainingTopics)) && System.nanoTime() < end)
                Thread.sleep(50);
            assertTrue(retryIds(port).isEmpty(), app.tail());
            assertEquals(remainingTopics, retryState(port, "topics"));
            if (startup) assertEquals(java.util.Set.of("true"), retryState(port, "kept"));
        }
    }

    @Test void partialRetryRegistrationLeavesNoContainersOrProducts() throws Exception {
        partialRegistration(tmp, broker.getBrokersAsString());
    }

    static void partialRegistration(Path root, String brokers) throws Exception {
        String topic = "partial-retry-" + UUID.randomUUID();
        // Fail after the native registry has inserted the second container. The first
        // container and the one whose put threw must both be retired before reporting.
        String fixture = appWithRetryConfiguration().replace("var server =", """
                var registryForFault = ctx.getBean(org.springframework.kafka.config.KafkaListenerEndpointRegistry.class);
                var mapField = registryForFault.getClass().getDeclaredField("listenerContainers");
                mapField.setAccessible(true);
                var original = (java.util.Map<String,Object>) mapField.get(registryForFault);
                var faulty = new java.util.concurrent.ConcurrentHashMap<String,Object>(original) {
                    int insertions;
                    @Override public Object put(String id, Object container) {
                        Object previous = super.put(id, container);
                        if (++insertions == 2) throw new IllegalStateException("failure after second retry container insertion");
                        return previous;
                    }
                };
                mapField.set(registryForFault, faulty);
                var server =""");
        try (var app = WatchedApp.in(root)
                .classpath(System.getProperty("java.class.path"))
                .jvmArgs("-Dtest.brokers=" + brokers, "-Dtest.retry.topic=" + topic)
                .agentArgs("startupDelaySec=1,debounceMs=100,verbose=true")
                .with("Listener", listener(topic, false))
                .with("Other", listener(topic, false).replace("class Listener", "class Other"))
                .with("App", fixture).start()) {
            app.awaitOrFail("PORT=", "partial registration fixture startup");
            int port = Integer.parseInt(app.latest("PORT=").substring(5));
            app.awaitOrFail("Last-known-good bytecode cache:", "watcher ready");
            app.rewrite("Listener", listener(topic, true));
            app.awaitOrFail("failure after second retry container insertion", "injected native registration failure");
            assertTrue(retryIds(port).isEmpty(), "partial registration left a container");
            assertTrue(retryState(port, "topics").isEmpty(), "partial registration left a generated singleton");
            app.rewrite("Other", listener(topic, true).replace("class Listener", "class Other"));
            app.awaitOrFail("added @KafkaListener registered", "same id and routes can be installed after rollback");
            assertEquals(3, retryIds(port).size());
        }
    }

    @Test void substitutedRetryCallbackRefusesBeforeRegistryInsertion() throws Exception {
        substitutedRetryCallback(tmp, broker.getBrokersAsString());
    }

    static void substitutedRetryCallback(Path root, String brokers) throws Exception {
        String topic = "retry-callback-" + java.util.UUID.randomUUID();
        String fixture = appWithRetryConfiguration().replace("return factory;", """
                factory.setContainerCustomizer(container -> {
                    if ("retry-added".equals(container.getListenerId()))
                        container.setupMessageListener((org.springframework.kafka.listener.MessageListener<Integer,String>)
                                record -> System.out.println("SUBSTITUTED:" + record.value()));
                });
                return factory;""");
        fixture = com.onurkat.reclazz.e2e.harness.ListenerRegistryProbe.observe(fixture,
                "ctx.getBean(org.springframework.kafka.config.KafkaListenerEndpointRegistry.class)");
        try (var app = WatchedApp.in(root).classpath(System.getProperty("java.class.path"))
                .jvmArgs("-Dtest.brokers=" + brokers, "-Dtest.retry.topic=" + topic)
                .agentArgs("startupDelaySec=1,debounceMs=100,verbose=true")
                .with("Listener",listener(topic,false)).with("App",fixture).start()) {
            app.awaitOrFail("PORT=", "retry application startup");
            int port = Integer.parseInt(app.latest("PORT=").substring(5));
            app.awaitOrFail("Last-known-good bytecode cache:", "watcher ready");
            app.rewrite("Listener",listener(topic,true));
            app.awaitOrFail("could not apply", "substituted retry callback must refuse");
            assertFalse(app.output().contains("STAGED:retry-added"), app.tail());
            assertTrue(retryIds(port).isEmpty(), app.tail());
            assertTrue(retryState(port,"topics").isEmpty(), app.tail());
        }
    }

    @Test void startFailureRollsBackTheWholeRetryGroup() throws Exception {
        startFailure(tmp, broker.getBrokersAsString(), false);
    }

    @Test void stopTimeoutQuarantinesRetryGroupAndRecordsRestart() throws Exception {
        startFailure(tmp, broker.getBrokersAsString(), true);
    }

    static void startFailure(Path root, String brokers, boolean timeout) throws Exception {
        String topic = "retry-start-" + UUID.randomUUID();
        String fixture = appWithRetryConfiguration().replace("var server =", """
                var registryForFault = ctx.getBean(org.springframework.kafka.config.KafkaListenerEndpointRegistry.class);
                var field = registryForFault.getClass().getDeclaredField("listenerContainers");
                field.setAccessible(true);
                var original = (java.util.Map<String,Object>) field.get(registryForFault);
                var faulty = new java.util.concurrent.ConcurrentHashMap<String,Object>(original) {
                    int registered;
                    @Override public Object put(String id, Object container) {
                        int index = ++registered;
                        var type = org.springframework.kafka.listener.MessageListenerContainer.class;
                        var wrapper = java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                                (proxy, method, arguments) -> {
                                    if (method.getName().equals("start") && index == 2)
                                        throw new IllegalStateException("injected retry start failure");
                                    if (method.getName().equals("stop") && method.getParameterCount() == 1 && index == 1
                                            && Boolean.getBoolean("test.stop.timeout")) {
                                        ((org.springframework.kafka.listener.MessageListenerContainer)container).stop(() -> { });
                                        return null;
                                    }
                                    try { return method.invoke(container, arguments); }
                                    catch (java.lang.reflect.InvocationTargetException e) { throw e.getCause(); }
                                });
                        return super.put(id, index <= 3 ? wrapper : container);
                    }
                };
                field.set(registryForFault, faulty);
                var server =""").replace("server.start();", """
                server.createContext("/ledger", exchange -> {
                    try {
                        var digest = Class.forName("com.onurkat.reclazz.ui.RestartLedger").getMethod("digest");
                        byte[] body = digest.invoke(null).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(200, body.length);
                        exchange.getResponseBody().write(body); exchange.close();
                    } catch (ReflectiveOperationException error) { throw new java.io.IOException(error); }
                });
                server.start();""");
        try (var app = WatchedApp.in(root).classpath(System.getProperty("java.class.path"))
                .jvmArgs("-Dtest.brokers=" + brokers, "-Dtest.retry.topic=" + topic, "-Dtest.stop.timeout=" + timeout)
                .agentArgs("startupDelaySec=1,debounceMs=100,verbose=true")
                .with("Listener", listener(topic,false)).with("App", fixture)
                .with("Other", listener(topic,false).replace("class Listener", "class Other")).start()) {
            app.awaitOrFail("PORT=", "fault fixture startup");
            int port = Integer.parseInt(app.latest("PORT=").substring(5));
            app.awaitOrFail("Last-known-good bytecode cache:", "watcher ready");
            app.rewrite("Listener", listener(topic,true));
            app.awaitOrFail("injected retry start failure", "second container failed during activation");
            if (timeout) {
                assertEquals(3, retryIds(port).size(), "retained container identities");
                assertFalse(retryState(port, "topics").isEmpty(), "published routes still need their topic products");
                var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/ledger")).GET().build();
                String ledger = HttpClient.newHttpClient().send(request,HttpResponse.BodyHandlers.ofString()).body();
                assertTrue(ledger.contains("Kafka retry group retry-added cleanup incomplete; restart required"), ledger);
                assertTrue(ledger.contains("did not finish stopping"), ledger);
                int previous = app.output().size();
                app.rewrite("Listener", listener(topic, false));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WatchedApp.DEFAULT_TIMEOUT_SEC);
                java.util.function.BooleanSupplier refused = () -> app.output().stream().skip(previous).anyMatch(
                        line -> line.contains("bean recreation deferred") && line.contains("retry-added cleanup incomplete"));
                while (!refused.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(50);
                assertTrue(refused.getAsBoolean(), () -> "the next save must encounter the quarantined group: " + app.tail());
                assertEquals(3, retryIds(port).size());
            } else {
                assertTrue(retryIds(port).isEmpty(), app.tail());
                assertTrue(retryState(port, "topics").isEmpty());
                app.rewrite("Other", listener(topic,true).replace("class Listener", "class Other"));
                app.awaitOrFail("added @KafkaListener registered", "re-add after failed activation");
                assertEquals(3, retryIds(port).size());
            }
        }
    }

    @Test void retryDeliveryMatchesNativeStartupAcrossEditAndRemoval() throws Exception {
        retryLifecycle(tmp, broker.getBrokersAsString());
    }

    static void retryLifecycle(Path root, String brokers) throws Exception {
        for (boolean nativeOnly : new boolean[]{true, false}) {
            String topic = "retry-proof-" + UUID.randomUUID();
            var props = new java.util.HashMap<String,Object>();
            props.put("bootstrap.servers", brokers);
            props.put("key.serializer", org.apache.kafka.common.serialization.IntegerSerializer.class);
            props.put("value.serializer", org.apache.kafka.common.serialization.StringSerializer.class);
            var producer = new org.apache.kafka.clients.producer.KafkaProducer<Integer,String>(props);
            String failing = listener(topic, true).replace("System.out.println(\"ADDED:\" + value);",
                    "System.out.println(\"ATTEMPT:\" + value); throw new IllegalStateException(\"retry me\");");
            String fixtures = appWithRetryConfiguration().replace("@org.springframework.kafka.annotation.EnableKafka",
                            "@org.springframework.kafka.annotation.EnableKafkaRetryTopic")
                    .replace(".maxAttempts(2)", ".fixedBackOff(50).maxAttempts(2).dltHandlerMethod(\"sink\", \"ended\")")
                    .replace("public static void main", """
                            public static class Sink { public void ended(String value) { System.out.println("DLT:" + value); } }
                            @org.springframework.context.annotation.Bean public Sink sink() { return new Sink(); }
                            @org.springframework.context.annotation.Bean public org.springframework.scheduling.TaskScheduler scheduler() {
                                return new org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler();
                            }
                            public static void main""");
            var build = WatchedApp.in(root.resolve(nativeOnly ? "native" : "reload"))
                    .classpath(System.getProperty("java.class.path"))
                    .jvmArgs("-Dtest.brokers=" + brokers, "-Dtest.retry.topic=" + topic)
                    .with("Listener", nativeOnly ? failing : listener(topic, false))
                    .with("Other", listener(topic, false).replace("class Listener", "class Other")).with("App", fixtures);
            if (nativeOnly) build.withoutAgent();
            else build.agentArgs("startupDelaySec=1,debounceMs=100,verbose=true");
            try (var app = build.start()) {
                app.awaitOrFail("PORT=", "retry application startup");
                int port = Integer.parseInt(app.latest("PORT=").substring(5));
                if (!nativeOnly) {
                    app.awaitOrFail("Last-known-good bytecode cache:", "watcher ready");
                    app.rewrite("Listener", failing);
                    app.awaitOrFail("added @KafkaListener registered", "native main, retry and DLT route must register");
                }
                var ids = retryIds(port);
                assertEquals(3, ids.size(), ids.toString());
                assertTrue(ids.contains("retry-added"));
                var topics = retryState(port,"topics");
                assertEquals(3, topics.size(), topics.toString());
                producer.send(new org.apache.kafka.clients.producer.ProducerRecord<>(topic, "first")).get(10, TimeUnit.SECONDS);
                app.awaitOrFail("DLT:first", "retry exhausts into the configured DLT method");
                assertEquals(2, app.output().stream().filter("ATTEMPT:first"::equals).count(), app.tail());
                if (!nativeOnly) {
                    app.rewrite("Other", listener(topic, true).replace("class Listener", "class Other")
                            .replace("retry-added", "competing-listener"));
                    app.awaitOrFail("Kafka retry route already belongs to another listener", "overlap must refuse before publication");
                    assertEquals(ids,retryIds(port), "overlap changed the working graph");
                    long before = reloads(app);
                    String edited = listener(topic, true).replace("System.out.println(\"ADDED:\" + value);",
                            "if (value.startsWith(\"fail\")) { System.out.println(\"EDIT-ATTEMPT:\" + value);"
                            + " throw new IllegalStateException(\"new failure\"); } System.out.println(\"ADDED:\" + value);");
                    app.rewrite("Listener", edited);
                    long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
                    while (reloads(app) == before && System.nanoTime() < limit) Thread.sleep(50);
                    assertTrue(reloads(app) > before, app.tail());
                    producer.send(new org.apache.kafka.clients.producer.ProducerRecord<>(topic, "edited")).get(10, TimeUnit.SECONDS);
                    app.awaitOrFail("ADDED:edited", "replacement listener handles the message");
                    assertFalse(app.output().contains("ATTEMPT:edited"), app.tail());
                    producer.send(new org.apache.kafka.clients.producer.ProducerRecord<>(topic, "fail-edited")).get(10, TimeUnit.SECONDS);
                    app.awaitOrFail("DLT:fail-edited", "replacement listener still routes failures to DLT");
                    assertEquals(2, app.output().stream().filter("EDIT-ATTEMPT:fail-edited"::equals).count(), app.tail());
                    app.rewrite("Listener", listener(topic, false));
                    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
                    while (!retryIds(port).isEmpty() && System.nanoTime() < end) Thread.sleep(50);
                    assertTrue(retryIds(port).isEmpty(), app.tail());
                    while (!retryState(port,"topics").isEmpty() && System.nanoTime() < end) Thread.sleep(50);
                    assertTrue(retryState(port,"topics").isEmpty(), "generated topic singletons outlived the graph");
                    app.rewrite("Listener", failing);
                    end = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
                    while (!retryIds(port).equals(ids) && System.nanoTime() < end) Thread.sleep(50);
                    assertEquals(ids, retryIds(port));
                    assertEquals(topics,retryState(port,"topics"));
                    producer.send(new org.apache.kafka.clients.producer.ProducerRecord<>(topic, "again")).get(10, TimeUnit.SECONDS);
                    app.awaitOrFail("DLT:again", "re-added graph delivers through DLT");
                    assertEquals(2, app.output().stream().filter("ATTEMPT:again"::equals).count());
                }
            } finally { producer.close(); }
        }
    }

    private static void awaitAssignment(int port, String topic, WatchedApp app) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WatchedApp.DEFAULT_TIMEOUT_SEC);
        while (!retryState(port, "assigned").contains(topic) && System.nanoTime() < deadline) Thread.sleep(50);
        assertTrue(retryState(port, "assigned").contains(topic), () -> "main consumer partition assignment: " + app.tail());
    }

    private static java.util.Set<String> retryIds(int port) throws Exception {
        return retryState(port,"ids");
    }

    private static java.util.Set<String> retryState(int port, String kind) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/" + kind)).GET().build();
        String body = HttpClient.newHttpClient().send(request,HttpResponse.BodyHandlers.ofString()).body();
        return body.isEmpty() ? java.util.Set.of() : java.util.Set.of(body.split(","));
    }

    private static long reloads(WatchedApp app) {
        return app.output().stream().filter(s -> s.contains("Reloaded app.Listener ") || s.contains("Structural reload: app.Listener ")).count();
    }

    private static String listener(String topic, boolean added) {
        return """
                package app;
                @org.springframework.stereotype.Component public class Listener {
                    public String use() { return "used"; }
                    %s
                }
                """.formatted(added ? """
                    @org.springframework.kafka.annotation.KafkaListener(id="retry-added", topics="%s", groupId="%s-group")
                    public void added(String value) { System.out.println("ADDED:" + value); }
                    """.formatted(topic, topic) : "");
    }

    private static String kafkaClasspath() {
        return String.join(File.pathSeparator, Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .filter(s -> {
                    String name = Path.of(s).getFileName().toString();
                    return name.endsWith(".jar") && (name.startsWith("spring-") && !name.startsWith("spring-kafka-test")
                            || name.startsWith("kafka-clients-") || name.startsWith("slf4j-api-")
                            || name.startsWith("lz4-java-") || name.startsWith("snappy-java-") || name.startsWith("zstd-jni-"));
                }).toList());
    }

    private static String appWithRetryConfiguration() {
        return APP.replace("public static void main", """
                @org.springframework.context.annotation.Bean
                public org.springframework.kafka.core.KafkaTemplate<Integer,String> retryTemplate() {
                    var props = new java.util.HashMap<String,Object>();
                    props.put("bootstrap.servers", System.getProperty("test.brokers"));
                    props.put("key.serializer", org.apache.kafka.common.serialization.IntegerSerializer.class);
                    props.put("value.serializer", org.apache.kafka.common.serialization.StringSerializer.class);
                    return new org.springframework.kafka.core.KafkaTemplate<>(new org.springframework.kafka.core.DefaultKafkaProducerFactory<>(props));
                }
                @org.springframework.context.annotation.Bean
                public org.springframework.kafka.retrytopic.RetryTopicConfiguration retryConfiguration(
                        org.springframework.kafka.core.KafkaTemplate<Integer,String> retryTemplate) {
                    return org.springframework.kafka.retrytopic.RetryTopicConfigurationBuilder.newInstance()
                            .includeTopic(System.getProperty("test.retry.topic")).maxAttempts(2).create(retryTemplate);
                }
                public static void main
                """);
    }

    private static final String APP = """
            package app;
            @org.springframework.context.annotation.Configuration(proxyBeanMethods=false)
            @org.springframework.context.annotation.ComponentScan("app")
            @org.springframework.kafka.annotation.EnableKafka
            public class App {
                @org.springframework.context.annotation.Bean
                public org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory<Integer,String> kafkaListenerContainerFactory() {
                    var props = new java.util.HashMap<String,Object>();
                    props.put("bootstrap.servers", System.getProperty("test.brokers"));
                    props.put("key.deserializer", org.apache.kafka.common.serialization.IntegerDeserializer.class);
                    props.put("value.deserializer", org.apache.kafka.common.serialization.StringDeserializer.class);
                    props.put("auto.offset.reset", "earliest"); props.put("enable.auto.commit", false);
                    var factory = new org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory<Integer,String>();
                    factory.setConsumerFactory(new org.springframework.kafka.core.DefaultKafkaConsumerFactory<>(props));
                    factory.getContainerProperties().setAckMode(org.springframework.kafka.listener.ContainerProperties.AckMode.RECORD);
                    factory.getContainerProperties().setPollTimeout(100);
                    return factory;
                }
                public static void main(String[] args) throws Exception {
                    var ctx = new org.springframework.context.annotation.AnnotationConfigApplicationContext(App.class);
                    var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
                    server.createContext("/topics", exchange -> {
                        var factory = ctx.getBeanFactory();
                        var names = new java.util.TreeSet<String>();
                        for (String name : factory.getSingletonNames())
                            if (factory.getSingleton(name) instanceof org.apache.kafka.clients.admin.NewTopic topic) names.add(topic.name());
                        byte[] body = String.join(",",names).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(200,body.length);
                        exchange.getResponseBody().write(body); exchange.close();
                    });
                    server.createContext("/assigned", exchange -> {
                        var registry = ctx.getBean(org.springframework.kafka.config.KafkaListenerEndpointRegistry.class);
                        var main = registry.getListenerContainer("retry-added");
                        var topics = new java.util.TreeSet<String>();
                        var partitions = main == null ? null : main.getAssignedPartitions();
                        if (partitions != null) partitions.forEach(p -> topics.add(p.topic()));
                        byte[] body = String.join(",", topics).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(200, body.length);
                        exchange.getResponseBody().write(body); exchange.close();
                    });
                    server.createContext("/ids", exchange -> {
                        var registry = ctx.getBean(org.springframework.kafka.config.KafkaListenerEndpointRegistry.class);
                        var ids = String.join(",", registry.getListenerContainerIds()).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(200, ids.length);
                        exchange.getResponseBody().write(ids);
                        exchange.close();
                    });
                    server.start(); System.out.println("PORT=" + server.getAddress().getPort());
                }
            }
            """;
}
