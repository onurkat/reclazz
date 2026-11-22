/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.spring.xml;
import com.onurkat.reclazz.platform.ApplicationContextHolder;
import com.onurkat.reclazz.ui.RestartLedger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.config.DestructionAwareBeanPostProcessor;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

/** Kept guards while the FactoryBean callback/consumer transaction receives independent plan review. */
class XmlFactoryBeanDefinitionReloadTest {
 @TempDir Path temp;
 public static class Product {final String value;boolean closed;Product(String value){this.value=value;}}
 public static class Factory implements FactoryBean<Product> {static int created,products,types;static boolean fail;String value;Product product;public Factory(){created++;}public void setValue(String value){this.value=value;}public Product getObject(){products++;if(fail)throw new IllegalStateException("synthetic candidate failure");if(product==null)product=new Product(value);return product;}public Class<?> getObjectType(){types++;return Product.class;}}
 public static class NonSingleton extends Factory {@Override public boolean isSingleton(){return false;}}
 public static class Consumer {final Product product;public Consumer(Product value){product=value;}}
 static class NativeDisposal implements DestructionAwareBeanPostProcessor {public boolean requiresDestruction(Object value){return value instanceof Factory;}public void postProcessBeforeDestruction(Object value,String name){Factory f=(Factory)value;if(f.product!=null)f.product.closed=true;}}
 @BeforeEach void before(){ApplicationContextHolder.clear();RestartLedger.clear();Factory.created=Factory.products=Factory.types=0;Factory.fail=false;}
 @AfterEach void after(){ApplicationContextHolder.clear();RestartLedger.clear();Factory.fail=false;}
 static String beans(Class<?> type,String value){return "<bean id='factory' class='"+type.getName()+"' lazy-init='true'><property name='value' value='"+value+"'/></bean><bean id='consumer' class='"+Consumer.class.getName()+"' lazy-init='true'><constructor-arg ref='factory'/></bean>";}
 @Test void warmedPlainFactoryWithNativeDestroyAdapterIsRefusedBeforeClosingOldProduct()throws Exception {
  try(var s=new XmlDefinitionTestScope(temp.resolve("callback.xml"),beans(Factory.class,"before"))){var f=s.context.getBeanFactory();f.addBeanPostProcessor(new NativeDisposal());Consumer held=s.context.getBean("consumer",Consumer.class);Object factory=s.context.getBean("&factory"),raw=f.getBeanDefinition("factory"),merged=f.getMergedBeanDefinition("factory");String[] edges=f.getDependenciesForBean("consumer");int created=Factory.created,products=Factory.products,types=Factory.types;
   s.reload(beans(Factory.class,"after"));assertTrue(s.warned());assertSame(raw,f.getBeanDefinition("factory"));assertSame(merged,f.getMergedBeanDefinition("factory"));assertSame(factory,s.context.getBean("&factory"));assertSame(held,s.context.getBean("consumer"));assertSame(held.product,s.context.getBean("factory"));assertFalse(held.product.closed);assertEquals("before",held.product.value);assertArrayEquals(edges,f.getDependenciesForBean("consumer"));assertEquals(created,Factory.created);assertEquals(products,Factory.products);assertEquals(types,Factory.types);
  }
 }
 @Test void guardedEditDoesNotTryPotentiallyFailingCandidateProductCreation()throws Exception {
  try(var s=new XmlDefinitionTestScope(temp.resolve("failure.xml"),beans(Factory.class,"before"))){Consumer held=s.context.getBean("consumer",Consumer.class);int products=Factory.products,created=Factory.created;Object raw=s.context.getBeanFactory().getBeanDefinition("factory");Factory.fail=true;s.reload(beans(Factory.class,"after"));assertTrue(s.warned());assertEquals(products,Factory.products);assertEquals(created,Factory.created);assertSame(raw,s.context.getBeanFactory().getBeanDefinition("factory"));assertSame(held.product,s.context.getBean("factory"));assertFalse(held.product.closed);}
 }
 @Test void nonSingletonProductRemainsGuardedWithoutQueryingOrCreatingProductOnSave()throws Exception {
  try(var s=new XmlDefinitionTestScope(temp.resolve("nonsingleton.xml"),beans(NonSingleton.class,"before"))){s.context.getBean("consumer");int products=Factory.products;Object raw=s.context.getBeanFactory().getBeanDefinition("factory");s.reload(beans(NonSingleton.class,"after"));assertTrue(s.warned());assertEquals(products,Factory.products);assertSame(raw,s.context.getBeanFactory().getBeanDefinition("factory"));}
 }
 @Test void sharedXmlKeepsBothFactoriesCachedProductsAndConsumerGraphsUnchanged()throws Exception {
  Path xml=temp.resolve("owners.xml");try(var a=new XmlDefinitionTestScope(xml,beans(Factory.class,"before"));var b=new XmlDefinitionTestScope(xml,beans(Factory.class,"before"))){Consumer ca=a.context.getBean("consumer",Consumer.class),cb=b.context.getBean("consumer",Consumer.class);Object fa=a.context.getBean("&factory"),fb=b.context.getBean("&factory");Object ra=a.context.getBeanFactory().getBeanDefinition("factory"),rb=b.context.getBeanFactory().getBeanDefinition("factory");a.reload(beans(Factory.class,"after"));assertTrue(a.warned());assertSame(ra,a.context.getBeanFactory().getBeanDefinition("factory"));assertSame(rb,b.context.getBeanFactory().getBeanDefinition("factory"));assertSame(fa,a.context.getBean("&factory"));assertSame(fb,b.context.getBean("&factory"));assertSame(ca,a.context.getBean("consumer"));assertSame(cb,b.context.getBean("consumer"));assertSame(ca.product,a.context.getBean("factory"));assertSame(cb.product,b.context.getBean("factory"));}
 }
}
