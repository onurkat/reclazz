/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.spring;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.ResolvableType;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SpringDataRepositoryTypeHintTest {
    public static class TypeFactory implements FactoryBean<Object>, InitializingBean {
        static Runnable initializing;
        static boolean broken;
        final SpringDataRefreshTest.Product product = new SpringDataRefreshTest.Product();
        public void afterPropertiesSet() { if (initializing != null) initializing.run(); }
        public Object getObject() {
            if (broken) throw new IllegalStateException("deliberate product failure");
            return product;
        }
        public Class<?> getObjectType() { return SpringDataRefreshTest.Deep.class; }
        public boolean isSingleton() { return true; }
    }

    @Test void warmProductTypeStaysVisibleDuringRecreationAndTemporaryHintIsRemoved() {
        TypeFactory.initializing=null; TypeFactory.broken=false;
        try (var ctx=new GenericApplicationContext()) {
            ctx.registerBean("repo",TypeFactory.class);ctx.refresh();
            var old=ctx.getBean(SpringDataRefreshTest.Deep.class);
            var definition=ctx.getBeanFactory().getMergedBeanDefinition("repo");
            assertNull(definition.getAttribute(FactoryBean.OBJECT_TYPE_ATTRIBUTE));
            List<String[]> lookups=new ArrayList<>();
            TypeFactory.initializing=()->{
                lookups.add(ctx.getBeanFactory().getBeanNamesForType(SpringDataRefreshTest.Deep.class,true,true));
                lookups.add(ctx.getBeanFactory().getBeanNamesForType(SpringDataRefreshTest.Repo.class,true,true));
            };
            assertTrue(new SpringDataReloader(SpringDataRefreshTest.platform(ctx)).reloadRepository(SpringDataRefreshTest.Repo.class));
            assertEquals(2,lookups.size());
            for(String[] names:lookups)assertArrayEquals(new String[]{"repo"},names,"deep and parent repository types stay discoverable inside recreation");
            assertNotSame(old,ctx.getBean(SpringDataRefreshTest.Deep.class));
            assertNull(definition.getAttribute(FactoryBean.OBJECT_TYPE_ATTRIBUTE),"temporary hint must not become declared metadata");
        } finally {TypeFactory.initializing=null;TypeFactory.broken=false;}
    }

    @Test void declaredProductTypeMetadataIsPreserved() {
        TypeFactory.initializing=null;TypeFactory.broken=false;
        var declared=ResolvableType.forClass(SpringDataRefreshTest.Deep.class);
        try(var ctx=new GenericApplicationContext()) {
            ctx.registerBean("repo",TypeFactory.class,bd->bd.setAttribute(FactoryBean.OBJECT_TYPE_ATTRIBUTE,declared));ctx.refresh();
            var old=ctx.getBean(SpringDataRefreshTest.Deep.class);
            assertTrue(new SpringDataReloader(SpringDataRefreshTest.platform(ctx)).reloadRepository(SpringDataRefreshTest.Repo.class));
            assertNotSame(old,ctx.getBean(SpringDataRefreshTest.Deep.class));
            assertSame(declared,ctx.getBeanFactory().getMergedBeanDefinition("repo").getAttribute(FactoryBean.OBJECT_TYPE_ATTRIBUTE));
        } finally {TypeFactory.initializing=null;TypeFactory.broken=false;}
    }

    @Test void failedProductCreationRemovesOnlyTheTemporaryHint() {
        TypeFactory.initializing=null;TypeFactory.broken=false;
        try(var ctx=new GenericApplicationContext()) {
            ctx.registerBean("repo",TypeFactory.class);ctx.refresh();ctx.getBean(SpringDataRefreshTest.Deep.class);
            var definition=ctx.getBeanFactory().getMergedBeanDefinition("repo");TypeFactory.broken=true;
            assertFalse(new SpringDataReloader(SpringDataRefreshTest.platform(ctx)).reloadRepository(SpringDataRefreshTest.Repo.class));
            assertNull(definition.getAttribute(FactoryBean.OBJECT_TYPE_ATTRIBUTE));
        } finally {TypeFactory.initializing=null;TypeFactory.broken=false;}
    }
}
