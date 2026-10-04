/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.example;

import com.onurkat.reclazz.platform.ApplicationContextHolder;
import com.onurkat.reclazz.platform.SapProcessResources;
import com.onurkat.reclazz.watcher.ChangeKind;
import de.hybris.platform.processengine.definition.ProcessDefinitionResource;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.UrlResource;
import java.nio.file.*;
import java.util.Set;

/** Genuine SDK registration check; no process engine, tenant or process execution. */
public class ProcessResourceSdkProbe {
    private static int checks;
    private static void check(boolean condition) { checks++; if (!condition) throw new AssertionError("Check " + checks); }
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        Path known = root.resolve("flow.xml"), unrelated = root.resolve("other.xml");
        Files.writeString(known, "<process name='example'/>");
        Files.writeString(unrelated, "<process name='example'/>");
        ApplicationContextHolder.clear();
        try (var context = new GenericApplicationContext()) {
            var registration = new ProcessDefinitionResource(); registration.setResource(new FileSystemResource(known));
            context.getBeanFactory().registerSingleton("process", registration);
            context.registerBean("lazy", ProcessDefinitionResource.class, () -> { throw new AssertionError("Lazy bean created"); },
                    definition -> definition.setLazyInit(true));
            var remote = new ProcessDefinitionResource(); remote.setResource(new UrlResource("https://example.invalid/flow.xml"));
            context.getBeanFactory().registerSingleton("remote", remote);
            context.refresh(); ApplicationContextHolder.register(context);
            check(SapProcessResources.registeredFiles().equals(Set.of(known)));
            check(ChangeKind.of(known).name().equals("SAP_PROCESS_XML"));
            check(ChangeKind.of(unrelated) == ChangeKind.UNKNOWN);
            check(ChangeKind.of(known.getFileName().toString()) == ChangeKind.UNKNOWN);
            Files.delete(known);
            check(ChangeKind.of(known).name().equals("SAP_PROCESS_XML"));
            check(!context.getBeanFactory().containsSingleton("lazy"));
            context.close();
            check(SapProcessResources.registeredFiles().isEmpty());
            check(ChangeKind.of(known) == ChangeKind.UNKNOWN);
        } finally { ApplicationContextHolder.clear(); }
        System.out.println("PASS: " + checks + " SDK process resource checks; Spring " + org.springframework.core.SpringVersion.getVersion());
    }
}
