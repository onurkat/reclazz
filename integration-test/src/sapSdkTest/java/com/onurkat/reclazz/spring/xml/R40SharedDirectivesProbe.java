/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.spring.xml;

import com.onurkat.reclazz.platform.ApplicationContextHolder;
import com.onurkat.reclazz.ui.RestartLedger;
import de.hybris.platform.converters.impl.AbstractPopulatingConverter;
import de.hybris.platform.converters.impl.ModifyPopulatorList;
import de.hybris.platform.spring.config.ListMergeDirective;
import java.lang.instrument.Instrumentation;
import java.nio.file.*;
import java.util.*;
import static com.onurkat.reclazz.spring.xml.R28PublicationSdkProbe.*;

public final class R40SharedDirectivesProbe {
    public static void premain(String ignored, Instrumentation agent) {
        agent.addTransformer(new SapListDirectiveTransformer());
    }
    private static String xml(boolean modify, int count, int changed, String add, String anchor, boolean remove) {
        StringBuilder xml = new StringBuilder(ListDirectiveSdkProbe.HEAD);
        for (String id : List.of("A","B","C","D","E","F","X")) xml.append(ListDirectiveSdkProbe.mark(id));
        xml.append(ListDirectiveSdkProbe.converter("converter"));
        for (int i=0;i<count;i++) {
            String item = i == changed ? add : List.of("B","C","D").get(i);
            String kind = modify ? ModifyPopulatorList.class.getName() : ListMergeDirective.class.getName();
            xml.append("<bean id='d").append(i).append("' class='").append(kind).append("'");
            if (!modify) xml.append(" depends-on='converter'");
            xml.append("><property name='add' ref='").append(item).append("'/>");
            if (modify) {
                xml.append("<property name='list' ref='converter'/>");
                if (remove && i==changed) xml.append("<property name='remove' ref='A'/>");
            } else {
                xml.append("<property name='listPropertyDescriptor' value='populators'/>");
                if (anchor != null && i==changed) xml.append("<property name='").append(anchor)
                        .append("BeanNames'><list><value>A</value></list></property>");
            }
            xml.append("</bean>");
        }
        return xml.append("</beans>").toString();
    }

    @SuppressWarnings({"unchecked","rawtypes"})
    private static void scenario(Path root, boolean modify, int count, boolean late) throws Exception {
        Files.createDirectories(root);
        Path path = root.resolve("live.xml"), fresh = root.resolve("fresh.xml");
        int changed = count == 2 ? 0 : 1;
        Files.writeString(path, xml(modify,count,-1,"B",null,false));
        try (var ctx=context(path,true)) {
            String original = output(ctx);
            var factory=ctx.getBeanFactory();
            Object live=ctx.getBean("d"+changed);
            var raw=factory.getBeanDefinition("d"+changed);
            Object oldRaw=raw.getPropertyValues().getPropertyValue("add");
            Object oldAdd=SapPopulatorDirectives.call(live,"getAdd");
            for (int edit=0;edit<2;edit++) {
                String input=xml(modify,count,changed,edit==0?"E":"F",edit==0?"before":"after",edit==0);
                Files.writeString(fresh,input);
                String expected;
                try(var startup=context(fresh,false)) { expected=output(startup); }
                check(!expected.equals(original),"edit differs from startup");
                Files.writeString(path,input);
                RestartLedger.clear(); reloader(ctx).reloadLoaded(path);
                if (late) {
                    check(output(ctx).equals(original),"late attach preserves list");
                    check(SapPopulatorDirectives.call(live,"getAdd")==oldAdd,"late attach preserves directive");
                    check(raw.getPropertyValues().getPropertyValue("add")==oldRaw,"late attach preserves definition");
                    check(RestartLedger.digest().stream().anyMatch(s->s.contains("startup capture")),"late attach has named refusal: "+RestartLedger.digest());
                    break;
                }
                check(output(ctx).equals(expected),"edited list equals native startup: got "+output(ctx)+", expected "+expected+", "+RestartLedger.digest());
                check(SapPopulatorDirectives.call(live,"getAdd")==ctx.getBean(edit==0?"E":"F"),"native directive updated");
            }
            if (late) return;
            var converter=(AbstractPopulatingConverter)ctx.getBean("converter");
            List list=converter.getPopulators(); list.add(ctx.getBean("X"));
            String external=output(ctx);
            Object before=raw.getPropertyValues().getPropertyValue("add");
            Files.writeString(path,xml(modify,count,changed,"B",null,false));RestartLedger.clear();reloader(ctx).reloadLoaded(path);
            check(converter.getPopulators()==list && output(ctx).equals(external),"external list preserved");
            check(SapPopulatorDirectives.call(live,"getAdd")==ctx.getBean("F") && raw.getPropertyValues().getPropertyValue("add")==before,"external edit refusal preserves directive and definition");
            check(RestartLedger.digest().stream().anyMatch(s->s.contains("outside its directives")),"external edit refusal named");
        } finally { ApplicationContextHolder.clear(); RestartLedger.clear(); }
    }
    private static String converterXml(boolean modify, int count, int changed, String add) {
        // The installed converter deduplicates in its native Jakarta @PostConstruct callback.
        return xml(modify, count, changed, add, null, false).replace("</beans>",
                "<bean id='annotations' class='org.springframework.context.annotation.CommonAnnotationBeanPostProcessor'/></beans>");
    }

    private static void converterEdit(Path root, boolean modify, int count, boolean late) throws Exception {
        Files.createDirectories(root);
        Path live = root.resolve("live.xml"), fresh = root.resolve("fresh.xml");
        String initial = converterXml(modify, count, -1, "B");
        String oldBase = "<list><ref bean='A'/></list>";
        String newBase = "<list><ref bean='D'/><ref bean='A'/><ref bean='D'/></list>";
        Files.writeString(live, initial);
        try (var ctx = context(live, true)) {
            var converter = (AbstractPopulatingConverter) ctx.getBean("converter");
            var previous = converter.getPopulators();
            String original = output(ctx);
            String edited = initial.replace(oldBase, newBase);
            Files.writeString(fresh, edited);
            String expected;
            try (var restart = context(fresh, false)) { expected = output(restart); }
            check(!expected.equals(original), "native revised converter output differs");
            Files.writeString(live, edited);
            RestartLedger.clear();
            reloader(ctx).reloadLoaded(live);
            if (late) {
                check(converter.getPopulators() == previous && original.equals(output(ctx)), "uncaptured directive changed converter");
                check(RestartLedger.digest().stream().anyMatch(m -> m.contains("d0") && m.contains("populators")),
                        "uncaptured directive refusal must name the member and property: " + RestartLedger.digest());
                return;
            }
            check(ctx.getBean("converter") == converter && expected.equals(output(ctx)),
                    "new base plus native directive order: expected " + expected + ", got " + output(ctx) + " " + RestartLedger.digest());
            if (count > 1) {
                String next = converterXml(modify, count, 0, "E").replace(oldBase, newBase);
                Files.writeString(fresh, next);
                try (var restart = context(fresh, false)) { expected = output(restart); }
                Files.writeString(live, next);
                reloader(ctx).reloadLoaded(live);
                check(expected.equals(output(ctx)), "subsequent directive used stale base: " + output(ctx));
            }
        } finally { ApplicationContextHolder.clear(); RestartLedger.clear(); }
    }

    public static void main(String[] args) throws Exception {
        boolean late = args[1].equals("late");
        if (args.length > 2 && args[2].equals("converter-replay")) {
            for (boolean modify : List.of(false, true)) for (int count : List.of(1, 2))
                converterEdit(Path.of(args[0]).resolve(modify+"-"+count), modify, count, late);
        } else {
            for (boolean modify : List.of(false,true)) for (int count : List.of(2,3))
                scenario(Path.of(args[0]).resolve(modify+"-"+count),modify,count,late);
        }
        System.out.println("R40 "+args[1]+": "+checks+" checks passed");
    }
}
