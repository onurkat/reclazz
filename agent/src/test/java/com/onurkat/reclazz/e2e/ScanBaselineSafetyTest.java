/* Copyright 2026 Onur Kat; SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.e2e;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.tools.ToolProvider;
import static org.junit.jupiter.api.Assertions.*;

class ScanBaselineSafetyTest {
    @TempDir Path root;
    @Test void scanNeverAutoImportsBaselineImpex() throws Exception {
        Path jar=Path.of(com.onurkat.reclazz.e2e.harness.WatchedApp.agentJarOrSkip());
        Path extension=Files.createDirectories(root.resolve("bin/custom/example"));
        Files.createDirectories(extension.resolve("classes"));
        Files.createDirectories(extension.resolve("resources"));
        Files.writeString(extension.resolve("extensioninfo.xml"),"<extensioninfo><extension name='example'/></extensioninfo>");
        Files.createDirectories(root.resolve("bin/platform"));
        Files.createDirectories(root.resolve("config"));
        Files.writeString(root.resolve("config/localextensions.xml"),"<hybrisconfig><extensions><extension name='example'/></extensions></hybrisconfig>");
        Path impex=extension.resolve("resources/example.impex");
        Files.writeString(impex,"INSERT_UPDATE Example;code[unique=true]\n;synthetic\n");
        Files.setLastModifiedTime(impex,FileTime.fromMillis(1000));
        Path src=Files.createDirectories(root.resolve("fixture-src"));
        Path classes=Files.createDirectories(root.resolve("fixture-classes"));
        Map<String,String> sources=Map.of(
                "Registry","package de.hybris.platform.core; public class Registry { public static boolean hasCurrentTenant(){return true;} public static void activateMasterTenant(){} }",
                "ImpExResource","package de.hybris.platform.servicelayer.impex; public interface ImpExResource {}",
                "StreamBasedImpExResource","package de.hybris.platform.servicelayer.impex.impl; public class StreamBasedImpExResource implements de.hybris.platform.servicelayer.impex.ImpExResource { public StreamBasedImpExResource(java.io.InputStream input,String encoding) {} }");
        List<String> command=new ArrayList<>(List.of("-proc:none","-cp",jar.toString(),"-d",classes.toString()));
        for(var entry:sources.entrySet()) { Path file=src.resolve(entry.getKey()+".java");Files.writeString(file,entry.getValue());command.add(file.toString()); }
        Path probe=src.resolve("ScanImportProbe.java");
        try(var input=getClass().getResourceAsStream("/scan-baseline/ScanImportProbe.java")) { assertNotNull(input); Files.write(probe,input.readAllBytes()); }
        command.add(probe.toString());
        assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,null,null,command.toArray(String[]::new)));
        Path log=root.resolve("child.log"), portFile=root.resolve("agent.port");
        Process child=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin/java").toString(),
                "-javaagent:"+jar+"=platform=hybris,hybrisHome="+root+",autoImpex=true,startupDelaySec=0,debounceMs=100,portFile="+portFile,
                "-cp",jar+java.io.File.pathSeparator+classes,"com.example.ScanImportProbe",root.toString())
                .directory(root.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            await(log,"PROBE_READY"); await(log,"Watching ");
            int port=Integer.parseInt(Files.readString(portFile).strip());
            scan(port);Files.createFile(root.resolve("probe0"));await(log,"COUNT0=");
            assertTrue(Files.readString(log).contains("COUNT0=0"),Files.readString(log));
            Files.writeString(impex,Files.readString(impex)+"# changed\n");
            scan(port);Files.createFile(root.resolve("probe1"));await(log,"COUNT1=");
            assertTrue(Files.readString(log).contains("COUNT1=1"),Files.readString(log));
            scan(port);Files.createFile(root.resolve("probe2"));await(log,"COUNT2=");
            assertTrue(Files.readString(log).contains("COUNT2=1"),Files.readString(log));
            assertTrue(child.waitFor(10,TimeUnit.SECONDS));assertEquals(0,child.exitValue(),Files.readString(log));
        } finally { if(child.isAlive())child.destroyForcibly().waitFor(); }
    }
    private static void scan(int port) throws Exception {
        try(var socket=new Socket("127.0.0.1",port)) {
            socket.getOutputStream().write("SCAN\n".getBytes(StandardCharsets.UTF_8));socket.getOutputStream().flush();
        }
    }
    private static void await(Path log,String text) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
        while(System.nanoTime()<end) { if(Files.readString(log).contains(text))return;Thread.sleep(25); }
        fail("Missing "+text+": "+Files.readString(log));
    }
}
