/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.spring;

import org.junit.jupiter.api.Test;
import org.springframework.cache.annotation.*;
import org.springframework.transaction.annotation.*;
import java.lang.annotation.*;
import java.lang.reflect.Method;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class OperationMetadataExpectationTest {
    @CacheConfig(cacheNames="default",keyGenerator="gen",cacheManager="manager",cacheResolver="resolver")
    @Transactional(readOnly=true,rollbackFor=IllegalArgumentException.class)
    static class Owner {
        @Cacheable(value={"b","a"}, key="#p0",condition="true",unless="#result == null")
        public String cached(String s){return s;}
        @Cacheable public String defaults(){return "";}
        @Caching(cacheable=@Cacheable("read"),evict=@CacheEvict(value="evict",allEntries=true,beforeInvocation=true),put=@CachePut(value="put",unless="false"))
        public String grouped(){return "";}
        @Transactional(readOnly=false,propagation=Propagation.REQUIRES_NEW,isolation=Isolation.SERIALIZABLE,timeout=42,transactionManager="tx",label={"b","a"},rollbackFor=IllegalStateException.class,rollbackForClassName="Synthetic",noRollbackFor=IllegalArgumentException.class,noRollbackForClassName="Other")
        public void tx(){}
        @Transactional(timeoutString="${limit}")public void unresolved(){}
        public void inheritedClassTransaction(){}
    }
    @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.METHOD) @Transactional @interface Composed {}
    static class MetaOwner { @Composed public void read(){} }
    @Test void standardSourceWithNonPublicPolicyIsUnsupported() {
        assertEquals("non-public operation resolution",OperationMetadataInspection.sourceConfiguration(new AnnotationCacheOperationSource(false),false));
        assertNull(OperationMetadataInspection.sourceConfiguration(new AnnotationCacheOperationSource(),false));
    }
    @Test void globalRollbackDefaultsAreUnsupportedWhenTheVersionProvidesThem()throws Exception {
        var source=new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource();
        assertNull(OperationMetadataInspection.sourceConfiguration(source,true));
        try {
            source.getClass().getMethod("addDefaultRollbackRule",org.springframework.transaction.interceptor.RollbackRuleAttribute.class)
                .invoke(source,new org.springframework.transaction.interceptor.RollbackRuleAttribute(RuntimeException.class));
            assertEquals("configured default rollback rules",OperationMetadataInspection.sourceConfiguration(source,true));
        }catch(NoSuchMethodException legacy){assertFalse(OperationMetadataExpectation.typedRules(source.getClass().getClassLoader()));}
    }
    @Test void directCacheAliasesExpressionsAndDefaultsMatchNative() throws Exception {compare(Owner.class,"cached",false,String.class);compare(Owner.class,"defaults",false);}
    @Test void cachingContainerAndEvictionFlagsMatchNative() throws Exception {compare(Owner.class,"grouped",false);}
    @Test void methodOverridesClassAndEveryTransactionFieldMatchesNative() throws Exception {compare(Owner.class,"tx",true);compare(Owner.class,"inheritedClassTransaction",true);}
    @Test void unresolvedAndComposedAreUnsupported() throws Exception {
        assertNotNull(OperationMetadataExpectation.read(Owner.class,Owner.class.getMethod("unresolved"),true).unsupported());
        assertNotNull(OperationMetadataExpectation.read(MetaOwner.class,MetaOwner.class.getMethod("read"),true).unsupported());
    }
    @Test void emptyAnnotationExpectationIsEmpty() throws Exception {
        assertEquals(List.of(),OperationMetadataExpectation.read(OperationMetadataRepairTest.RemovalOwner.class,
            Object.class.getMethod("toString"),true).operations());
    }
    private void compare(Class<?> owner,String name,boolean tx,Class<?>...args) throws Exception {
        Method method=owner.getMethod(name,args);
        var expected=OperationMetadataExpectation.read(owner,method,tx);
        assertNull(expected.unsupported(),expected::unsupported);
        Object actual=tx?new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource().getTransactionAttribute(method,owner)
            :new AnnotationCacheOperationSource().getCacheOperations(method,owner);
        assertEquals(expected.operations(),OperationMetadataInspection.answer(actual,tx));
    }
}
