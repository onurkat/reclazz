/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.spring.xml;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.RuntimeBeanReference;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class ConverterDependencySnapshotTest {
    public static class Converter {
        public List<Object> members = List.of(new Object());
    }

    @Test void failedSecondOwnerKeepsBothConvertersAndOldDependencyEdges() throws Exception {
        var first = new DefaultListableBeanFactory();
        var second = new DefaultListableBeanFactory();
        for (var factory : List.of(first, second)) {
            factory.registerBeanDefinition("converter", new RootBeanDefinition(Converter.class));
            factory.registerSingleton("stable", new Object());
            factory.registerDependentBean("stable", "converter");
            factory.registerDependentBean("converter", "consumer");
        }
        Converter retained = first.getBean("converter", Converter.class);
        Converter other = second.getBean("converter", Converter.class);
        List<Object> firstList = retained.members;
        List<Object> secondList = other.members;
        var one = new ConverterDependencySnapshot(first, "converter", Set.of("newMember", "stable"));
        var two = new ConverterDependencySnapshot(second, "converter", Set.of("newMember", "stable"));
        first.registerBeanDefinition("newMember", new RootBeanDefinition(Object.class));
        var ref = new RuntimeBeanReference("newMember");
        SpringReflection.resolveValue(first, "converter", first.getBeanDefinition("converter"), "populators", ref);
        assertArrayEquals(new String[]{"converter"}, first.getDependentBeans("newMember"));
        assertThrows(Exception.class, () -> SpringReflection.resolveValue(second, "converter",
                second.getBeanDefinition("converter"), "populators", ref));
        assertTrue(two.undoResolution().isEmpty());
        assertTrue(one.undoResolution().isEmpty());
        first.removeBeanDefinition("newMember");
        assertSame(retained, first.getSingleton("converter"));
        assertSame(other, second.getSingleton("converter"));
        assertSame(firstList, retained.members);
        assertSame(secondList, other.members);
        for (var factory : List.of(first, second)) {
            assertArrayEquals(new String[]{"stable"}, factory.getDependenciesForBean("converter"));
            assertArrayEquals(new String[]{"converter"}, factory.getDependentBeans("stable"));
            assertArrayEquals(new String[]{"consumer"}, factory.getDependentBeans("converter"));
            assertEquals(0, factory.getDependentBeans("newMember").length);
        }
    }
}
