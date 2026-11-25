/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.spring.xml;

import com.onurkat.reclazz.util.Reflect;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.*;
import static com.onurkat.reclazz.spring.xml.SapPopulatorDirectives.*;

/** Replay a complete native startup sequence against its original elements before publishing. */
final class SapSharedDirectives {
    private SapSharedDirectives() { }
    private record Group(SapDirectiveHistory.History history, SapDirectiveHistory.Target key,
                         Object owner, List<String> names) { }

    private static SapDirectiveHistory.Target target(Object factory, Object definition, Object live) throws Exception {
        String bean;
        boolean direct = false;
        if (MODIFY.equals(live.getClass().getName())) {
            Object ref = rawValue(definition, "list");
            if (!localReference(ref)) throw new IllegalStateException("shared modify directive needs a local list reference");
            bean = (String) call(ref, "getBeanName");
        } else {
            String[] dependencies = (String[]) call(definition, "getDependsOn");
            if (dependencies == null || dependencies.length != 1) throw new IllegalStateException("shared merge directive needs one list dependency");
            bean = dependencies[0];
            Object property = call(live, "getListPropertyDescriptor");
            direct = property == null;
            if ((!direct && !"populators".equals(property)) || call(live, "getFieldName") != null)
                throw new IllegalStateException("unsupported shared list property");
        }
        return new SapDirectiveHistory.Target((String) call(factory, "canonicalName", bean), direct);
    }

    private static Group inspect(Object factory, String name, Object definition, Object live) throws Exception {
        String peer = SapListMergeDirectives.sharedDirective(factory, name, definition, live);
        if (peer == null) return null;
        var key = target(factory, definition, live);
        Object owner = SpringReflection.getExistingSingleton(factory, key.name());
        var history = SapDirectiveHistory.get(factory, key);
        if (owner == null || history == null || history.owner.get() != owner)
            throw refused(peer, "the list has no verified startup capture");
        List<String> names;
        synchronized (history) {
            if (history.incomplete || history.invalid || !history.kind.equals(live.getClass().getName()) || !history.order.contains(name))
                throw refused(peer, "incomplete or mixed native startup sequence");
            names = List.copyOf(history.order);
            List<?> current = key.direct() ? (List<?>) owner : populators(owner);
            requireWritable(current);
            if (!history.matches(current)) throw refused(peer, "the list was changed outside its directives since startup");
        }
        for (String member : names) {
            Object raw = SpringReflection.getBeanDefinition(factory, member);
            Object instance = SpringReflection.getExistingSingleton(factory, member);
            if (raw == null || instance == null || !history.kind.equals(instance.getClass().getName())
                    || !history.kind.equals(SpringReflection.getBeanClassName(raw)) || SpringReflection.getParentName(raw) != null
                    || Boolean.TRUE.equals(call(raw, "isLazyInit")) || !key.equals(target(factory, raw, instance)))
                throw refused(member, "directive is not an exact eager local owner");
            if (MODIFY.equals(history.kind) && call(instance, "getList") != owner)
                throw refused(member, "modify target ownership changed");
            if (!MODIFY.equals(history.kind)) {
                for (String property : List.of("BeforeValues", "AfterValues", "BeforeClasses", "AfterClasses"))
                    if (call(instance, "get" + property) != null) throw refused(member, "unsupported merge positions");
            }
        }
        String unobserved = SapListMergeDirectives.sharedDirective(factory, name, definition, live, Set.copyOf(names));
        if (unobserved != null || !names.contains(peer)) throw refused(unobserved == null ? peer : unobserved, "directive outside the verified local sequence");
        return new Group(history, key, owner, names);
    }

    static String refusal(Object factory, String name, Object definition, Object live) {
        try { inspect(factory, name, definition, live); return null; }
        catch (Exception | LinkageError failure) { return "SAP list directive edit requires restart: " + failure.getMessage(); }
    }

    private static IllegalStateException refused(String name, String reason) {
        return new IllegalStateException("shared target with directive " + name + ": " + reason);
    }

    static boolean apply(Object factory, Edit edit, Object live) throws Exception {
        Object definition = SpringReflection.getBeanDefinition(factory, edit.beanName());
        Group group = inspect(factory, edit.beanName(), definition, live);
        if (group == null) return false;
        TargetSnapshot snapshot = new TargetSnapshot(() -> SpringReflection.getExistingSingleton(factory, group.key().name()), group.key().direct());
        Map<String,Object> properties = new LinkedHashMap<>();
        properties.put("add", resolve(factory, rawValue(edit.next(), "add")));
        boolean modify = MODIFY.equals(group.history().kind);
        if (modify) properties.put("remove", resolve(factory, rawValue(edit.next(), "remove")));
        else {
            properties.put("beforeBeanNames", SapListMergeDirectives.names(rawValue(edit.next(), "beforeBeanNames")));
            properties.put("afterBeanNames", SapListMergeDirectives.names(rawValue(edit.next(), "afterBeanNames")));
        }
        Object replacement = live.getClass().getConstructor().newInstance();
        for (var entry : properties.entrySet()) {
            String key = entry.getKey();
            set(replacement, "set" + Character.toUpperCase(key.charAt(0)) + key.substring(1), entry.getValue());
        }
        List<Object> desired = group.history().baseCopy();
        for (String name : group.names()) {
            Object member = name.equals(edit.beanName()) ? replacement : SpringReflection.getExistingSingleton(factory, name);
            if (modify) replayModify(member, desired);
            else SapListMergeDirectives.replay(factory, member, desired, name);
        }
        Group current = inspect(factory, edit.beanName(), definition, live);
        if (!group.equals(current)) throw new IllegalStateException("shared directive ownership changed during reload");
        synchronized (group.history()) {
            if (group.history().incomplete || group.history().invalid || !group.history().order.equals(group.names())
                    || !group.history().matches(snapshot.copy())) throw new IllegalStateException("shared directive history changed during reload");
            publish(factory, edit, live, properties, snapshot, desired);
            SapDirectiveHistory.published(group.history(), desired);
        }
        return true;
    }

    // The native processor only invokes getPopulators on this detached target.
    private static void replayModify(Object directive, List<Object> copy) throws Exception {
        ClassLoader loader = directive.getClass().getClassLoader();
        Class<?> target = Class.forName("de.hybris.platform.converters.PopulatorList", false, loader);
        Object detached = Proxy.newProxyInstance(loader, new Class<?>[]{target}, (proxy, method, args) -> {
            if (method.getName().equals("getPopulators") && method.getParameterCount() == 0) return copy;
            throw new IllegalStateException("unexpected native modify operation " + method.getName());
        });
        Class<?> processor = Class.forName("de.hybris.platform.converters.impl.ModifyPopulatorListBeanPostProcessor", false, loader);
        Method process = Reflect.findMethod(processor, "processOnTarget", directive.getClass(), target);
        if (process == null) throw new IllegalStateException("native ModifyPopulatorList.processOnTarget unavailable");
        process.invoke(processor.getConstructor().newInstance(), directive, detached);
    }
}
