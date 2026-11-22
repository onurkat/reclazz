/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.spring.xml;
import com.onurkat.reclazz.platform.ApplicationContextHolder;
import com.onurkat.reclazz.ui.RestartLedger;
import org.junit.jupiter.api.*;import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.DisposableBean;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
/** R30 retains removal diagnostics; supported removal continues independently in R30b. */
class XmlBeanRemovalDiagnosticsTest {
 @TempDir Path temp;
 public static class Value implements DisposableBean {static int created,closed;public Value(){created++;}public void destroy(){closed++;}}
 public static class Holder {final Value value;public Holder(Value value){this.value=value;}}
 @BeforeEach void before(){ApplicationContextHolder.clear();RestartLedger.clear();Value.created=Value.closed=0;}
 @AfterEach void after(){ApplicationContextHolder.clear();RestartLedger.clear();}
 static String bean(){return "<bean id='value' class='"+Value.class.getName()+"' lazy-init='true'/>";}
 @Test void uncreatedRemovalRetainsOriginalRawMergedAndLookupWithoutAnyCallback()throws Exception {
  try(var s=new XmlDefinitionTestScope(temp.resolve("cold.xml"),bean())){var f=s.context.getBeanFactory();Object raw=f.getBeanDefinition("value"),merged=f.getMergedBeanDefinition("value");s.reload("");assertTrue(s.warned());assertSame(raw,f.getBeanDefinition("value"));assertSame(merged,f.getMergedBeanDefinition("value"));assertTrue(f.containsBeanDefinition("value"));assertFalse(f.containsSingleton("value"));assertEquals(0,Value.created);assertEquals(0,Value.closed);}
 }
 @Test void warmRemovalRetainsExactInstanceAndDoesNotDestroy()throws Exception {
  try(var s=new XmlDefinitionTestScope(temp.resolve("warm.xml"),bean())){var f=s.context.getBeanFactory();Object held=s.context.getBean("value"),raw=f.getBeanDefinition("value"),merged=f.getMergedBeanDefinition("value");s.reload("");assertTrue(s.warned());assertSame(raw,f.getBeanDefinition("value"));assertSame(merged,f.getMergedBeanDefinition("value"));assertSame(held,s.context.getBean("value"));assertEquals(1,Value.created);assertEquals(0,Value.closed);}
 }
 @Test void usedPrototypeRemovalKeepsDefinitionAndDoesNotDestroyOldObject()throws Exception {
  try(var s=new XmlDefinitionTestScope(temp.resolve("prototype.xml"),bean().replace("lazy-init='true'","scope='prototype'"))){Object held=s.context.getBean("value"),raw=s.context.getBeanFactory().getBeanDefinition("value");s.reload("");assertTrue(s.warned());assertSame(raw,s.context.getBeanFactory().getBeanDefinition("value"));assertNotNull(held);assertEquals(1,Value.created);assertEquals(0,Value.closed);}
 }
 @Test void aliasRemovalStaysGuardedWithOriginalCanonicalDefinition()throws Exception {
  try(var s=new XmlDefinitionTestScope(temp.resolve("alias.xml"),bean()+"<alias name='value' alias='heldAlias'/>")){Object raw=s.context.getBeanFactory().getBeanDefinition("value");s.reload("");assertTrue(s.warned());assertSame(raw,s.context.getBeanFactory().getBeanDefinition("value"));assertTrue(s.context.isAlias("heldAlias"));assertEquals(0,Value.created);}
 }
 @Test void sharedXmlRemovalKeepsBothOwnersOriginalDefinitions()throws Exception {
  Path xml=temp.resolve("shared.xml");try(var a=new XmlDefinitionTestScope(xml,bean());var b=new XmlDefinitionTestScope(xml,bean())){Object ra=a.context.getBeanFactory().getBeanDefinition("value"),rb=b.context.getBeanFactory().getBeanDefinition("value");a.reload("");assertTrue(a.warned());assertSame(ra,a.context.getBeanFactory().getBeanDefinition("value"));assertSame(rb,b.context.getBeanFactory().getBeanDefinition("value"));assertEquals(0,Value.created);assertEquals(0,Value.closed);}
 }
 @Test void originalConsumerReferenceRemainsUntouchedOnRemoval()throws Exception {
  String holder="<bean id='holder' class='"+Holder.class.getName()+"'><constructor-arg ref='value'/></bean>";
  try(var s=new XmlDefinitionTestScope(temp.resolve("consumer.xml"),bean()+holder)){Holder held=s.context.getBean("holder",Holder.class);Object old=held.value,raw=s.context.getBeanFactory().getBeanDefinition("value");s.reload(holder);assertTrue(s.warned());assertSame(raw,s.context.getBeanFactory().getBeanDefinition("value"));assertSame(held,s.context.getBean("holder"));assertSame(old,held.value);assertEquals(1,Value.created);assertEquals(0,Value.closed);}
 }
}
