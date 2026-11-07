/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.e2e;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.onurkat.reclazz.e2e.harness.WatchedApp;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real agent/watcher/module and receipts; synthetic native indexer graph, not a live Solr server. */
class SapSolrReindexTest {
    @TempDir Path temporary;

    @ParameterizedTest @ValueSource(strings = {"FieldValueProvider", "ValueResolver", "TypeValueResolver", "failure"})
    void watcherReindexesStoredDocumentsAndKeepsAppliedReceipt(String shape) throws Exception {
        boolean failing = shape.equals("failure");
        String contract = failing ? "FieldValueProvider" : shape;
        Files.createDirectories(temporary.resolve("config")); Files.createDirectories(temporary.resolve("bin/platform"));
        Files.writeString(temporary.resolve("config/localextensions.xml"),
                "<hybrisconfig><extensions><extension name='example'/></extensions></hybrisconfig>");
        Path extension = Files.createDirectories(temporary.resolve("bin/custom/example"));
        Files.writeString(extension.resolve("extensioninfo.xml"), "<extensioninfo><extension name='example'/></extensioninfo>");
        Path port = temporary.resolve("status.port");
        var builder = WatchedApp.in(extension).classpath(WatchedApp.springClasspath() + File.pathSeparator + WatchedApp.agentJarOrSkip())
                .agentArgs("platform=hybris,hybrisHome=" + temporary
                        + ",autoSolrReindex=true,startupDelaySec=0,debounceMs=100,portFile=" + port)
                .jvmArgs("-Dexample.typeResolver=" + contract.equals("TypeValueResolver"), "-Dexample.fail=" + failing);
        for (String path : List.of(
                "com/onurkat/reclazz/hybris/SolrNativeFixture.java",
                "de/hybris/platform/core/Registry.java", "de/hybris/platform/core/PK.java", "de/hybris/platform/jalo/JaloSession.java",
                "de/hybris/platform/solrfacetsearch/provider/impl/DefaultValueProviderSelectionStrategy.java",
                "de/hybris/platform/solrfacetsearch/solr/impl/SolrStandaloneSearchProvider.java",
                "de/hybris/platform/solrfacetsearch/indexer/strategies/impl/DefaultIndexerStrategy.java",
                "de/hybris/platform/solrfacetsearch/config/IndexOperation.java",
                "org/apache/solr/client/solrj/SolrQuery.java", "org/apache/solr/client/solrj/request/schema/SchemaRequest.java")) {
            builder.with(Path.of(path).getFileName().toString().replace(".java", ""), Files.readString(Path.of("src/solrTest/java/" + path)));
        }
        String source = "package app; public class Provider extends com.onurkat.reclazz.hybris.SolrNativeFixture.Provider implements "
                + "de.hybris.platform.solrfacetsearch.provider." + contract
                + " { public String value() { return \"v1\"; } }";
        builder.with(contract, "package de.hybris.platform.solrfacetsearch.provider; public interface " + contract + " {}")
                .with("Provider", source).with("App", APP);
        try (var app = builder.start()) {
            app.awaitOrFail("SOLR_READY v1", "fixture did not start"); app.awaitOrFail("] Watching ", "watcher missing");
            app.rewrite("Provider", source.replace("v1", "v2"));
            app.awaitOrFail("Reloaded app.Provider", "class reload did not land");
            app.awaitOrFail(failing ? "SAP Solr owner1/config1/type1: FAILED" : ": FINISHED ", "native operation did not finish");
            if (!failing) {
                app.awaitOrFail("STORED={11=v2, 22=v2}", "stored fixture values stayed stale");
                app.awaitOrFail("OP=" + (contract.equals("TypeValueResolver") ? "UPDATE" : "PARTIAL_UPDATE"), "wrong native operation");
            } else app.awaitOrFail("STORED={11=v1, 22=v1}", "failed operation changed fixture values");
            byte[] bytes = Files.readAllBytes(app.classesDir().resolve("app/Provider.class"));
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            var json = new ObjectMapper();
            try (var socket = new Socket("127.0.0.1", Integer.parseInt(Files.readString(port).trim()))) {
                socket.setSoTimeout(5000);
                socket.getOutputStream().write(("VERIFY solr app.Provider " + hash + "\n").getBytes(StandardCharsets.UTF_8));
                var input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                String prefix = "VERIFY_RESULT solr "; boolean seen = false;
                for (int i = 0; i < 200; i++) {
                    String line = input.readLine(); assertNotNull(line);
                    String message = json.readTree(line).path("message").asText();
                    if (!message.startsWith(prefix)) continue;
                    var receipt = json.readTree(message.substring(prefix.length()));
                    assertEquals("applied", receipt.path("status").asText(), receipt.toString());
                    assertEquals(hash, receipt.path("observedSha256").asText()); seen = true; break;
                }
                assertTrue(seen, "matching reload receipt missing");
            }
            assertFalse(app.output().stream().anyMatch(line -> line.contains("[WARN]") && line.contains("SAP Solr")), app.tail());
        }
    }

    private static final String APP = """
            package app;
            public class App {
                public static void main(String[] args) throws Exception {
                    var f = new com.onurkat.reclazz.hybris.SolrNativeFixture();
                    f.context.getDefaultListableBeanFactory().removeBeanDefinition("provider");
                    var bd = new org.springframework.beans.factory.support.RootBeanDefinition(Provider.class);
                    bd.setScope("prototype"); f.context.registerBeanDefinition("provider", bd);
                    if (Boolean.getBoolean("example.typeResolver")) f.type.fieldsProvider="providerAlias";
                    f.throwExecution=Boolean.getBoolean("example.fail");
                    System.out.println("SOLR_READY " + new Provider().value());
                    while (true) {
                        System.out.println("STORED=" + f.documents);
                        if (!f.executions.isEmpty()) System.out.println("OP=" + f.executions.get(0).operation());
                        Thread.sleep(100);
                    }
                }
            }
            """;
}
