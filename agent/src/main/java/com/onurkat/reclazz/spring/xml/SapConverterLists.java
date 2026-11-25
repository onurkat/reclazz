/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.spring.xml;

import com.onurkat.reclazz.ui.StatusReporter;
import java.lang.ref.WeakReference;
import java.util.*;
import static com.onurkat.reclazz.spring.xml.SapPopulatorDirectives.*;

/** A change to a converter's base list has to settle across every owning Spring context. */
final class SapConverterLists {
    static final String TYPE = "de.hybris.platform.converters.impl.AbstractPopulatingConverter";
    private static final String PROPERTY = "populators";
    private static final String MANAGED_LIST = "org.springframework.beans.factory.support.ManagedList";

    private SapConverterLists() { }

    record Owner(Object factory, Object parsed, BeanDefinitionDiff diff, Runnable activate) { }
    record Edit(String name, Object singleton, Class<?> base, Object before, Object candidate, Set<String> refs,
                String refusal) { }

    static boolean classify(Object factory, Object parsed, String name, Object before, Object candidate, BeanDefinitionDiff diff) {
        if (before == null) return false;
        Object live = SpringReflection.getExistingSingleton(factory, name);
        if (live == null) return false;
        Class<?> base = live.getClass();
        while (base != null && !base.getName().equals(TYPE)) base = base.getSuperclass();
        if (base == null) return false;
        Set<String> refs = new LinkedHashSet<>();
        String refusal = null;
        try {
            Object oldList = rawValue(before, PROPERTY), newList = rawValue(candidate, PROPERTY);
            if (oldList == null && newList == null) return false;
            ClassLoader loader = (ClassLoader) call(factory, "getBeanClassLoader");
            var cmp = new XmlRawValueComparator(candidate.getClass().getClassLoader());
            if (cmp.same(oldList, newList, loader)) return false;
            require(newList instanceof List<?> && MANAGED_LIST.equals(newList.getClass().getName()), "an explicit native list is required");
            for (Object item : (List<?>) newList) {
                if (localReference(item)) refs.add((String) call(factory, "canonicalName", call(item, "getBeanName")));
                else require(item != null && (item.getClass().getName().equals("org.springframework.beans.factory.config.BeanDefinitionHolder")
                        || Class.forName("org.springframework.beans.factory.config.BeanDefinition", false, loader).isInstance(item)),
                        "only local bean references or simple inner beans are supported");
            }
            require(!Boolean.TRUE.equals(call(newList, "isMergeEnabled"))
                    && !(oldList != null && Boolean.TRUE.equals(call(oldList, "isMergeEnabled"))), "merged base list requires restart");
            require(Class.forName(TYPE, false, loader) == base, "converter classloader mismatch");
            require(live.getClass().getMethod("setPopulators", List.class).getDeclaringClass() == base
                    && live.getClass().getMethod("getPopulators").getDeclaringClass() == base
                    && live.getClass().getMethod("removePopulatorsDuplicates").getDeclaringClass() == base,
                    "overridden converter list behavior");
            require(XmlSafetyClassifier.unchangedUnsupported(factory, parsed, name,
                    withEditable(before, candidate, List.of(PROPERTY)), withEditable(candidate, candidate, List.of(PROPERTY))),
                    "other declaration or alias changed");
        } catch (Exception | LinkageError failure) {
            refusal = SpringReflection.rootCause(failure);
        }
        diff.converterEdits.add(new Edit(name, live, base, before, candidate, Set.copyOf(refs), refusal));
        return true;
    }

    private static final class Prepared {
        final Owner owner;
        final Edit edit;
        final List<Object> previous;
        final Object rawBefore;
        final Object rawAfter;
        final ConverterDependencySnapshot dependencies;
        final SapSharedDirectives.Group directives;
        final List<WeakReference<Object>> oldBase, oldLast;
        List<Object> baseList, desired;
        boolean wroteRaw, wroteList;

        Prepared(Owner owner, Edit edit) throws Exception {
            this.owner = owner;
            this.edit = edit;
            this.previous = populators(edit.singleton);
            require(previous != null, "running converter list unavailable");
            Object values = call(edit.before, "getPropertyValues");
            this.rawBefore = call(values, "getPropertyValue", PROPERTY);
            this.rawAfter = call(call(edit.candidate, "getPropertyValues"), "getPropertyValue", PROPERTY);
            require(rawBefore != null && rawAfter != null, "missing raw list metadata");
            directives = SapSharedDirectives.forConverter(owner.factory, edit.name, edit.singleton);
            if (directives == null) {
                oldBase = oldLast = null;
            } else {
                synchronized (directives.history()) {
                    oldBase = directives.history().base;
                    oldLast = directives.history().last;
                }
            }
            dependencies = new ConverterDependencySnapshot(owner.factory, edit.name, edit.refs);
        }

        void resolve() throws Exception {
            Object raw = rawValue(edit.candidate, PROPERTY);
            Object resolved = SpringReflection.resolveValue(owner.factory, edit.name, edit.candidate, PROPERTY, raw);
            require(resolved instanceof List<?>, "Spring did not resolve the list");
            Class<?> populator = Class.forName("de.hybris.platform.converters.Populator", false, edit.base.getClassLoader());
            for (Object member : (List<?>) resolved) require(populator.isInstance(member), "list member is not a Populator");
            Object detached = edit.base.getConstructor().newInstance();
            edit.base.getMethod("setPopulators", List.class).invoke(detached, new ArrayList<>((List<?>) resolved));
            edit.base.getMethod("removePopulatorsDuplicates").invoke(detached);
            baseList = populators(detached);
            desired = new ArrayList<>(baseList);
            if (directives != null) SapSharedDirectives.replay(owner.factory, directives, desired, null, null);
        }

        void publish() throws Exception {
            var current = SapSharedDirectives.forConverter(owner.factory, edit.name, edit.singleton);
            require(Objects.equals(current, directives), "converter directive ownership changed during preparation");
            if (directives == null) {
                publishList();
                return;
            }
            var history = directives.history();
            synchronized (history) {
                require(history.base == oldBase && history.last == oldLast && !history.invalid && !history.incomplete
                        && history.order.equals(directives.names()) && history.matches(previous), "converter directive history changed");
                publishList();
                SapDirectiveHistory.publishedBase(history, baseList, desired);
            }
        }

        void publishList() throws Exception {
            require(SpringReflection.getExistingSingleton(owner.factory, edit.name) == edit.singleton
                    && populators(edit.singleton) == previous, "running converter changed during preparation");
            Object values = call(edit.before, "getPropertyValues");
            call(values, "addPropertyValue", rawAfter);
            wroteRaw = true;
            edit.base.getMethod("setPopulators", List.class).invoke(edit.singleton, desired);
            wroteList = true;
        }

        void rollback() throws Exception {
            if (wroteList && directives != null) {
                var history = directives.history();
                synchronized (history) {
                    edit.base.getMethod("setPopulators", List.class).invoke(edit.singleton, previous);
                    history.base = oldBase;
                    history.last = oldLast;
                }
            } else if (wroteList) {
                edit.base.getMethod("setPopulators", List.class).invoke(edit.singleton, previous);
            }
            if (wroteRaw) call(call(edit.before, "getPropertyValues"), "addPropertyValue", rawBefore);
            for (String inner : dependencies.undoResolution()) call(owner.factory, "destroySingleton", inner);
        }
    }

    private record NewMember(Owner owner, BeanDefinitionDiff.NewBean bean) { }

    static Map<Object, Integer> apply(List<Owner> owners, boolean completeOwners) {
        Map<Object,Integer> counts = new IdentityHashMap<>();
        if (owners.stream().allMatch(o -> o.diff.converterEdits.isEmpty())) return counts;
        List<Prepared> work = new ArrayList<>();
        List<NewMember> staged = new ArrayList<>();
        List<NewMember> needed = new ArrayList<>();
        Map<String, Set<String>> targets = new LinkedHashMap<>();
        for (Owner owner : owners) for (Edit edit : owner.diff.converterEdits)
            targets.computeIfAbsent(edit.name, key -> new HashSet<>()).addAll(edit.refs);
        for (Owner owner : owners) {
            Set<String> refs = new HashSet<>();
            for (var entry : targets.entrySet()) {
                if (SpringReflection.containsBeanDefinition(owner.parsed, entry.getKey())
                        && SpringReflection.containsBeanDefinition(owner.factory, entry.getKey()))
                    refs.addAll(entry.getValue());
            }
            owner.diff.added.removeIf(add -> {
                if (!refs.contains(add.beanName)) return false;
                needed.add(new NewMember(owner, add));
                return true;
            });
        }
        try {
            require(completeOwners, "not all owning contexts could be prepared");
            for (Owner owner : owners) {
                owner.activate.run();
                for (String name : targets.keySet()) {
                    if (owner.diff.converterEdits.stream().anyMatch(edit -> edit.name.equals(name))
                            || !SpringReflection.containsBeanDefinition(owner.factory, name)
                            || !SpringReflection.containsBeanDefinition(owner.parsed, name)) continue;
                    Object before = SpringReflection.getBeanDefinition(owner.factory, name);
                    Object next = SpringReflection.getBeanDefinition(owner.parsed, name);
                    ClassLoader loader = (ClassLoader) call(owner.factory, "getBeanClassLoader");
                    require(new XmlRawValueComparator(next.getClass().getClassLoader()).same(
                            rawValue(before, PROPERTY), rawValue(next, PROPERTY), loader),
                            name + " does not have a prepared converter in every owning context");
                }
            }
            for (Owner owner : owners) for (Edit edit : owner.diff.converterEdits) {
                owner.activate.run();
                require(edit.refusal == null, edit.refusal);
                work.add(new Prepared(owner, edit));
            }
            for (NewMember member : needed) {
                member.owner.activate.run();
                Object factory = member.owner.factory;
                require(!SpringReflection.containsBeanDefinition(factory, member.bean.beanName)
                        && SpringReflection.getExistingSingleton(factory, member.bean.beanName) == null, "new member already exists");
                ClassLoader loader = (ClassLoader) call(factory, "getBeanClassLoader");
                new XmlRawValueComparator(loader).requireSimpleInner(member.bean.newBeanDefinition, loader);
                SpringReflection.registerBeanDefinition(factory, member.bean.beanName, member.bean.newBeanDefinition);
                staged.add(member);
            }
            for (Prepared prepared : work) { prepared.owner.activate.run(); prepared.resolve(); }
            for (Prepared prepared : work) { prepared.owner.activate.run(); prepared.publish(); }
            for (Prepared prepared : work) {
                counts.merge(prepared.owner.factory, 1, Integer::sum);
                StatusReporter.success("Spring property updated: " + prepared.edit.name + ".populators");
            }
            for (NewMember member : staged) {
                counts.merge(member.owner.factory, 1, Integer::sum);
                StatusReporter.success("Spring bean added: " + member.bean.beanName);
            }
        } catch (Exception | LinkageError failure) {
            List<String> rollbackFailures = new ArrayList<>();
            for (int i = work.size() - 1; i >= 0; i--) {
                var prepared = work.get(i);
                try { prepared.owner.activate.run(); prepared.rollback(); }
                catch (Exception | LinkageError ex) { rollbackFailures.add(SpringReflection.rootCause(ex)); }
            }
            for (int i = staged.size() - 1; i >= 0; i--) {
                var member = staged.get(i);
                try {
                    member.owner.activate.run();
                    require(((String[])call(member.owner.factory, "getDependentBeans", member.bean.beanName)).length == 0,
                            "staged member has a live dependent");
                    call(member.owner.factory, "removeBeanDefinition", member.bean.beanName);
                } catch (Exception | LinkageError ex) { rollbackFailures.add(SpringReflection.rootCause(ex)); }
            }
            String reason = "populators: " + SpringReflection.rootCause(failure)
                    + (rollbackFailures.isEmpty() ? "" : "; rollback incomplete: " + rollbackFailures);
            for (Owner owner : owners) for (Edit edit : owner.diff.converterEdits)
                owner.diff.unsafe.add(new BeanDefinitionDiff.UnsafeChange(edit.name, reason));
        }
        return counts;
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new IllegalStateException(message);
    }
}
