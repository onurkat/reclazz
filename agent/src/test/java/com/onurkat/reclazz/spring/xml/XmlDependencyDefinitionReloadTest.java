/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.spring.xml;

import com.onurkat.reclazz.platform.ApplicationContextHolder;
import com.onurkat.reclazz.ui.RestartLedger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.DisposableBean;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class XmlDependencyDefinitionReloadTest {
    @TempDir Path temp;
    static final List<String> created=new ArrayList<>();
    public static class First {public First(){created.add("A");}}
    public static class Second {public Second(){created.add("B");}}
    public static class Value implements DisposableBean {static int closed;First first;public Value(){created.add("T");}public void setFirst(First value){first=value;}public void destroy(){closed++;}}
    @BeforeEach void before(){ApplicationContextHolder.clear();RestartLedger.clear();created.clear();Value.closed=0;}
    @AfterEach void after(){ApplicationContextHolder.clear();RestartLedger.clear();}
    private static String depends(String names){return names.equals("~")?"":"depends-on='"+names+"'";}
    private static String beans(String attrs){return "<bean id='a' class='"+First.class.getName()+"' lazy-init='true'/><bean id='b' class='"+Second.class.getName()+"' lazy-init='true'/><bean id='value' class='"+Value.class.getName()+"' lazy-init='true' "+attrs+"/>";}
    @ParameterizedTest @CsvSource(value={"~|a|A,T","a|~|T","a|b|B,T","a,b|b,a|B,A,T"},delimiter='|')
    void coldDependsOnAddRemoveRetargetAndOrderPublishWithoutCreation(String from,String to,String expected)throws Exception {
        try(var s=new XmlDefinitionTestScope(temp.resolve("depends.xml"),beans(depends(from)))){
            var f=s.context.getBeanFactory();f.getMergedBeanDefinition("value");s.reload(beans(depends(to)));assertFalse(s.warned(),s.events.toString());assertEquals(List.of(),created);assertFalse(f.containsSingleton("value"));assertEquals(0,Value.closed);
            s.context.getBean("value");assertEquals(Arrays.asList(expected.split(",")),created);
            assertArrayEquals(to.equals("~")?new String[0]:to.split(","),f.getDependenciesForBean("value"));
        }
    }
    @ParameterizedTest @CsvSource({"no,byType,true","byType,no,false","byName,byType,true"})
    void coldAutowireModeUsesNewPolicyAtFirstCreation(String from,String to,boolean injected)throws Exception {
        try(var s=new XmlDefinitionTestScope(temp.resolve("autowire.xml"),beans("autowire='"+from+"'"))){
            s.reload(beans("autowire='"+to+"'"));assertFalse(s.warned(),s.events.toString());assertEquals(List.of(),created);Value v=s.context.getBean("value",Value.class);assertEquals(injected,v.first!=null);
            if(injected)assertSame(s.context.getBean("a"),v.first);assertEquals(0,Value.closed);
        }
    }
    @Test void warmSingletonPolicyEditKeepsExactDefinitionIdentityAndGraph()throws Exception {
        try(var s=new XmlDefinitionTestScope(temp.resolve("warm.xml"),beans("depends-on='a' autowire='byType'"))){
            var f=s.context.getBeanFactory();Value held=s.context.getBean("value",Value.class);Object raw=f.getBeanDefinition("value"),merged=f.getMergedBeanDefinition("value");List<String> before=List.copyOf(created);String[] edges=f.getDependenciesForBean("value");
            s.reload(beans("depends-on='b' autowire='no'"));assertTrue(s.warned());assertSame(raw,f.getBeanDefinition("value"));assertSame(merged,f.getMergedBeanDefinition("value"));assertSame(held,s.context.getBean("value"));assertArrayEquals(edges,f.getDependenciesForBean("value"));assertEquals(before,created);assertEquals(0,Value.closed);
        }
    }
    @Test void previouslyCreatedThenDestroyedSingletonIsNotTreatedAsCold()throws Exception {
        try(var s=new XmlDefinitionTestScope(temp.resolve("destroyed.xml"),beans("depends-on='a'"))){var f=s.context.getBeanFactory();s.context.getBean("value");s.context.getDefaultListableBeanFactory().destroySingleton("value");Object raw=f.getBeanDefinition("value");s.reload(beans("depends-on='b'"));assertTrue(s.warned());assertSame(raw,f.getBeanDefinition("value"));assertFalse(f.containsSingleton("value"));assertEquals(1,Value.closed);}
    }
    @Test void coldPolicyRevertBeforeCreationPublishesNoObjects()throws Exception {
        try(var s=new XmlDefinitionTestScope(temp.resolve("repeat.xml"),beans("depends-on='a'"))){s.reload(beans("depends-on='b'"));assertFalse(s.warned(),s.events.toString());s.reload(beans("depends-on='a'"));assertFalse(s.warned(),s.events.toString());assertEquals(List.of(),created);s.context.getBean("value");assertEquals(List.of("A","T"),created);}
    }
    @Test void warmOwnerPreventsColdOwnerPublicationBeforeEitherMutates()throws Exception {
        Path xml=temp.resolve("owners.xml");try(var a=new XmlDefinitionTestScope(xml,beans("depends-on='a'"));var b=new XmlDefinitionTestScope(xml,beans("depends-on='a'"))){Object raw=a.context.getBeanFactory().getBeanDefinition("value");Value held=b.context.getBean("value",Value.class);a.reload(beans("depends-on='b'"));assertTrue(a.warned());assertSame(raw,a.context.getBeanFactory().getBeanDefinition("value"));assertSame(held,b.context.getBean("value"));assertEquals(0,Value.closed);}
    }
    @Test void prototypeDependencyPolicyAffectsOnlyFutureCreation()throws Exception {
        String before=beans("depends-on='a' autowire='no'").replace("lazy-init='true' depends-on", "scope='prototype' lazy-init='true' depends-on");
        try(var s=new XmlDefinitionTestScope(temp.resolve("prototype-policy.xml"),before)){Value old=s.context.getBean("value",Value.class);created.clear();s.reload(before.replace("depends-on='a' autowire='no'","depends-on='b' autowire='byType'"));assertFalse(s.warned(),s.events.toString());assertEquals(List.of(),created);assertNull(old.first);Value fresh=s.context.getBean("value",Value.class);assertNotSame(old,fresh);assertSame(s.context.getBean("a"),fresh.first);assertTrue(created.contains("B"));assertEquals(0,Value.closed);}
    }
    @Test void primaryOrCandidateChangeRemainsGuardedEvenForColdDefinition()throws Exception {
        try(var s=new XmlDefinitionTestScope(temp.resolve("unsupported.xml"),beans("depends-on='a' primary='false'"))){var f=s.context.getBeanFactory();Object raw=f.getBeanDefinition("value");s.reload(beans("depends-on='b' primary='true'"));assertTrue(s.warned());assertSame(raw,f.getBeanDefinition("value"));assertEquals(List.of(),created);}
    }
}
