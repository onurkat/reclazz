/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring.xml;

import com.onurkat.reclazz.platform.*;
import de.hybris.platform.webservicescommons.mapping.FieldSetBuilder;
import de.hybris.platform.webservicescommons.mapping.config.FieldSetLevelMapping;
import de.hybris.platform.webservicescommons.mapping.impl.*;
import de.hybris.platform.webservicescommons.mapping.filters.GeneralFieldFilter;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.cache.annotation.AnnotationCacheOperationSource;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.cache.interceptor.CacheInterceptor;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.io.FileSystemResource;
import java.nio.file.*;
import java.util.*;

/** Real installed SDK, synthetic DTOs, actual XML reload and cached mapping output. No SAP runtime. */
public final class OccFieldMappingSdkProbe {
    public static class Dto {
        private String code, extra;
        public String getCode() { return code; }
        public void setCode(String value) { code = value; }
        public String getExtra() { return extra; }
        public void setExtra(String value) { extra = value; }
    }
    public static class OtherDto extends Dto { }
    public static class CustomHelper extends DefaultFieldSetLevelHelper { }
    public static class Source {
        public String getCode() { return "sku"; }
        public String getExtra() { return "detail"; }
    }
    private record Consumer(DefaultFieldSetLevelHelper helper, DefaultDataMapper mapper,
                            ConcurrentMapCacheManager caches) { }
    private static int assertions;
    private static void equal(Object expected, Object actual) {
        assertions++;
        if (!Objects.equals(expected, actual)) throw new AssertionError("Expected " + expected + ", got " + actual);
    }
    private static String definition(String name, Class<?> type, String fields) {
        StringBuilder levels = new StringBuilder();
        for (String level : List.of("BASIC", "DEFAULT", "FULL"))
            levels.append("<entry key='").append(level).append("' value='").append(fields).append("'/>");
        return "<bean id='" + name + "' class='" + FieldSetLevelMapping.class.getName() + "'>"
                + "<property name='dtoClass' value='" + type.getName() + "'/>"
                + "<property name='levelMapping'><map>" + levels + "</map></property></bean>";
    }
    private static String xml(String fields) {
        return "<beans xmlns='http://www.springframework.org/schema/beans'>"
                + definition("mapping", Dto.class, fields)
                + definition("unrelatedMapping", OtherDto.class, "extra") + "</beans>";
    }
    private static GenericApplicationContext context(Path path, GenericApplicationContext parent) {
        var context = new GenericApplicationContext();
        if (parent != null) context.setParent(parent);
        if (path != null) {
            var reader = new XmlBeanDefinitionReader(context);
            reader.setValidating(false);
            var resource = new FileSystemResource(path);
            reader.loadBeanDefinitions(resource);
            SpringXmlResources.record(reader, resource);
        }
        context.refresh();
        ApplicationContextHolder.register(context);
        return context;
    }
    private static Consumer consumer(GenericApplicationContext context) {
        var helper = new DefaultFieldSetLevelHelper(); helper.setApplicationContext(context);
        context.getBeanFactory().registerSingleton("fieldSetLevelHelper", helper);
        var target = new DefaultFieldSetBuilder();
        target.setFieldSetLevelHelper(helper); target.setSimpleClassSet(Set.of(String.class));
        target.setDefaultMaxFieldSetSize(500); target.setDefaultRecurrencyLevel(4);
        var caches = new ConcurrentMapCacheManager("fieldSetCache", "unrelatedCache");
        context.getBeanFactory().registerSingleton("cacheManager", caches);
        caches.getCache("unrelatedCache").put("sentinel", "keep");
        var advice = new CacheInterceptor(); advice.setCacheManager(caches);
        advice.setCacheOperationSources(new AnnotationCacheOperationSource());
        advice.afterPropertiesSet(); advice.afterSingletonsInstantiated();
        var proxy = new ProxyFactory(target); proxy.addAdvice(advice);
        var mapper = new DefaultDataMapper(); mapper.setFieldSetBuilder((FieldSetBuilder) proxy.getProxy());
        mapper.setApplicationContext(context);
        var filter = new GeneralFieldFilter(); filter.setFieldSelectionStrategy(new DefaultFieldSelectionStrategy());
        mapper.addFilter(filter);
        return new Consumer(helper, mapper, caches);
    }
    private static void output(Consumer consumer, boolean extra) {
        for (String level : List.of("BASIC", "DEFAULT", "FULL")) {
            Dto value = consumer.mapper.map(new Source(), Dto.class, level);
            equal("sku", value.getCode()); equal(extra ? "detail" : null, value.getExtra());
            OtherDto other = consumer.mapper.map(new Source(), OtherDto.class, level);
            equal(null, other.getCode()); equal("detail", other.getExtra());
        }
        equal("keep", consumer.caches.getCache("unrelatedCache").get("sentinel").get());
    }
    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args[0]); Files.createDirectories(directory);
        Path file = directory.resolve("occ-fields.xml"), other = directory.resolve("other-fields.xml");
        Files.writeString(file, xml("code")); Files.writeString(other, xml("code"));
        try (var owner = context(file, null); var child = context(null, owner); var unrelated = context(other, null)) {
            Consumer direct = consumer(owner), inherited = consumer(child), isolated = consumer(unrelated);
            output(direct, false); output(inherited, false); output(isolated, false);
            equal(6, ((Map<?, ?>) direct.caches.getCache("fieldSetCache").getNativeCache()).size());
            isolated.caches.getCache("fieldSetCache").put("sentinel", "isolated");
            PlatformContext platform = (PlatformContext) java.lang.reflect.Proxy.newProxyInstance(
                    OccFieldMappingSdkProbe.class.getClassLoader(), new Class<?>[]{PlatformContext.class},
                    (p, m, a) -> switch (m.getName()) {
                        case "getApplicationContext" -> owner;
                        case "getPlatformId" -> PlatformContext.Platform.GENERIC;
                        default -> null;
                    });
            var reloader = new SpringXmlReloader(platform);
            for (String fields : List.of("code,extra", "code", "code,extra")) {
                Files.writeString(file, xml(fields));
                // Exercise the existing cache-computation seam across the actual XML mutation.
                // Real-agent cache interception itself is covered by the portable e2e suite.
                var pendingCache = direct.caches.getCache("fieldSetCache");
                com.onurkat.reclazz.bootstrap.CacheDependencyLedger.open();
                try {
                    com.onurkat.reclazz.bootstrap.CacheDependencyLedger.cachesInUse(List.of(pendingCache));
                    reloader.reloadLoaded(file);
                    pendingCache.put("late-computation", "stale");
                } finally { com.onurkat.reclazz.bootstrap.CacheDependencyLedger.close(); }
                equal(null, pendingCache.get("late-computation"));
                output(direct, fields.contains("extra")); output(inherited, fields.contains("extra")); output(isolated, false);
                equal("isolated", isolated.caches.getCache("fieldSetCache").get("sentinel").get());
                Object map = direct.helper.getLevelMap();
                reloader.reloadLoaded(file); // unchanged save must not rebuild or append definitions
                equal(true, map == direct.helper.getLevelMap());
                output(direct, fields.contains("extra"));
            }
            // Multiple declarations for one DTO must not accumulate merged fields on repeated refresh.
            Path shared = directory.resolve("merged-fields.xml");
            String header = "<beans xmlns='http://www.springframework.org/schema/beans'>";
            Files.writeString(shared, header + definition("first", Dto.class, "code")
                    + definition("second", Dto.class, "extra") + "</beans>");
            try (var merged = context(shared, null)) {
                Consumer mergedConsumer = consumer(merged);
                equal("sku", mergedConsumer.mapper.map(new Source(), Dto.class, "FULL").getCode());
                equal("detail", mergedConsumer.mapper.map(new Source(), Dto.class, "FULL").getExtra());
                Files.writeString(shared, header + definition("first", Dto.class, "code")
                        + definition("second", Dto.class, "code") + "</beans>");
                reloader.reloadLoaded(shared);
                equal(null, mergedConsumer.mapper.map(new Source(), Dto.class, "FULL").getExtra());
                reloader.reloadLoaded(shared);
                equal("code,code", mergedConsumer.helper.getLevelDefinitionForClass(Dto.class, "FULL"));
            }
            // Custom helpers are not reinitialized speculatively; the normal report must say restart.
            Path customFile = directory.resolve("custom-fields.xml");
            Files.writeString(customFile, xml("code"));
            try (var custom = context(customFile, null)) {
                var customHelper = new CustomHelper(); customHelper.setApplicationContext(custom);
                custom.getBeanFactory().registerSingleton("customHelper", customHelper);
                com.onurkat.reclazz.ui.RestartLedger.clear();
                Files.writeString(customFile, xml("code,extra")); reloader.reloadLoaded(customFile);
                equal("code", customHelper.getLevelDefinitionForClass(Dto.class, "FULL"));
                equal(true, com.onurkat.reclazz.ui.RestartLedger.digest().stream()
                        .anyMatch(line -> line.contains("custom field-set helper requires restart")));
            }
            // A referenced Map may already have been mutated by SDK merging; do not replay it.
            Path referenced = directory.resolve("referenced-fields.xml");
            String maps = "<bean id='oldLevels' class='java.util.LinkedHashMap'><constructor-arg><map>"
                    + "<entry key='FULL' value='code'/></map></constructor-arg></bean>"
                    + "<bean id='newLevels' class='java.util.LinkedHashMap'><constructor-arg><map>"
                    + "<entry key='FULL' value='code,extra'/></map></constructor-arg></bean>";
            String mapping = "<bean id='mapping' class='" + FieldSetLevelMapping.class.getName() + "'>"
                    + "<property name='dtoClass' value='" + Dto.class.getName() + "'/>"
                    + "<property name='levelMapping' ref='oldLevels'/></bean>";
            String referencedXml = "<beans xmlns='http://www.springframework.org/schema/beans'>" + maps + mapping + "</beans>";
            Files.writeString(referenced, referencedXml);
            try (var ctx = context(referenced, null)) {
                Consumer c = consumer(ctx);
                com.onurkat.reclazz.ui.RestartLedger.clear();
                Files.writeString(referenced, referencedXml.replace("ref='oldLevels'", "ref='newLevels'"));
                reloader.reloadLoaded(referenced);
                equal("code", c.helper.getLevelDefinitionForClass(Dto.class, "FULL"));
                equal(true, com.onurkat.reclazz.ui.RestartLedger.digest().stream()
                        .anyMatch(line -> line.contains("requires an inline levelMapping map")));
            }
        } finally { ApplicationContextHolder.clear(); }
        System.out.println("PASS: " + assertions + " OCC mapping assertions; Spring " + org.springframework.core.SpringVersion.getVersion());
    }
}
