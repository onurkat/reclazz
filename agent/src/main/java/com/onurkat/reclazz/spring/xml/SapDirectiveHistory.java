/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.spring.xml;

import com.onurkat.reclazz.util.Reflect;
import java.lang.ref.WeakReference;
import java.util.*;
import static com.onurkat.reclazz.spring.xml.SapPopulatorDirectives.*;

/** Observations from the native processors. Keys and snapshots do not retain a retired tenant. */
public final class SapDirectiveHistory {
    private SapDirectiveHistory() { }
    record Target(String name, boolean direct) { }
    private static final Map<Object, Map<Target, History>> factories = new WeakHashMap<>();
    private static final Map<Object, String> modifyNames = new WeakHashMap<>();
    private record Pending(History history, String name, List<?> list) { }
    static final class History {
        final WeakReference<Object> owner;
        final String kind;
        final List<String> order = new ArrayList<>();
        List<WeakReference<Object>> base;
        List<WeakReference<Object>> last;
        boolean incomplete;
        boolean invalid;
        History(Object owner, String kind, List<?> list) {
            this.owner = new WeakReference<>(owner);
            this.kind = kind;
            base = snapshot(list);
            last = base;
        }
        boolean matches(List<?> live) {
            if (last.size() != live.size()) return false;
            for (int i = 0; i < last.size(); i++) {
                WeakReference<?> ref = last.get(i);
                if (ref == null ? live.get(i) != null : ref.get() == null || ref.get() != live.get(i)) return false;
            }
            return true;
        }
        List<Object> baseCopy() {
            List<Object> result = new ArrayList<>(base.size());
            for (WeakReference<Object> ref : base) {
                Object item = ref == null ? null : ref.get();
                if (ref != null && item == null) throw new IllegalStateException("startup list element is no longer available");
                result.add(item);
            }
            return result;
        }
    }

    private static List<WeakReference<Object>> snapshot(List<?> list) {
        List<WeakReference<Object>> refs = new ArrayList<>(list.size());
        for (Object item : list) refs.add(item == null ? null : new WeakReference<>(item));
        return refs;
    }

    public static synchronized void nameModify(Object directive, String name) {
        if (directive != null && MODIFY.equals(directive.getClass().getName())) modifyNames.put(directive, name);
    }

    public static Object beforeMerge(List<?> list, Object processor, Object directive, String name, Object owner, String bean) {
        try {
            if (list == null || !SapListMergeDirectives.TYPE.equals(directive.getClass().getName())) return null;
            Object descriptor = call(directive, "getListPropertyDescriptor");
            if (descriptor != null && !"populators".equals(descriptor) || call(directive, "getFieldName") != null) return null;
            Object factory = Reflect.readField(processor, "beanFactory");
            return begin(factory, owner, new Target((String) call(factory, "canonicalName", bean), descriptor == null),
                    SapListMergeDirectives.TYPE, name, list);
        } catch (Exception | LinkageError unavailable) { return null; }
    }

    public static Object beforeModify(Object processor, Object directive, Object owner) {
        try {
            String name;
            synchronized (SapDirectiveHistory.class) { name = modifyNames.get(directive); }
            if (name == null || call(directive, "getList") != owner) return null;
            Object factory = Reflect.readField(processor, "beanFactory");
            Object definition = SpringReflection.getBeanDefinition(factory, name);
            Object ref = rawValue(definition, "list");
            if (!localReference(ref)) return null;
            String bean = (String) call(factory, "canonicalName", call(ref, "getBeanName"));
            return begin(factory, owner, new Target(bean, false), MODIFY, name, populators(owner));
        } catch (Exception | LinkageError unavailable) { return null; }
    }

    private static Pending begin(Object factory, Object owner, Target target, String kind, String name, List<?> list) {
        if (factory == null || owner == null || list.getClass() != ArrayList.class) return null;
        History record;
        synchronized (SapDirectiveHistory.class) {
            record = factories.computeIfAbsent(factory, ignored -> new HashMap<>())
                    .computeIfAbsent(target, ignored -> new History(owner, kind, list));
        }
        synchronized (record) {
            if (record.incomplete || record.owner.get() != owner || !record.kind.equals(kind)
                    || record.order.contains(name) || !record.matches(list)) {
                record.invalid = true;
                return null;
            }
            record.incomplete = true;
            return new Pending(record, name, list);
        }
    }

    public static void after(Object token) {
        if (!(token instanceof Pending pending)) return;
        History record = pending.history();
        synchronized (record) {
            try {
                if (record.invalid) return;
                record.last = snapshot(pending.list());
                record.order.add(pending.name());
                record.incomplete = false;
            } catch (RuntimeException unavailable) { record.incomplete = true; }
        }
    }

    static synchronized History get(Object factory, Target target) {
        Map<Target, History> records = factories.get(factory);
        return records == null ? null : records.get(target);
    }

    static void publishedBase(History history, List<?> base, List<?> live) {
        var nextBase = snapshot(base);
        var nextLive = snapshot(live);
        history.base = nextBase;
        history.last = nextLive;
    }

    static void published(History history, List<?> elements) { history.last = snapshot(elements); }
}
