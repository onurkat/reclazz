/* Copyright 2026 Onur Kat; SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.spring;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.amqp.rabbit.annotation.*;
import java.util.*;
import java.lang.reflect.Proxy;
import com.onurkat.reclazz.platform.PlatformContext;
import static org.junit.jupiter.api.Assertions.*;
class ModernOptionalRabbitStackTest {
 public static class Plain {final int seed;public Plain(@org.springframework.beans.factory.annotation.Value("${plain.seed:10}") int value){seed=value;}}
 @Configuration(proxyBeanMethods=false) @EnableRabbit static class Config {}
 static AnnotationConfigApplicationContext scope(int seed){
  var c=new AnnotationConfigApplicationContext();
  c.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("native-seed",Map.of("plain.seed",String.valueOf(seed))));
  c.register(Config.class);c.registerBean("plain",Plain.class);
  c.registerBean("extraRabbitProcessor",RabbitListenerAnnotationBeanPostProcessor.class);c.refresh();return c;
 }
 @Test void nativeFreshControlsAndManualRecreationWorkWithTwoProcessors(){
  for(int seed:new int[]{10,20})try(var c=scope(seed)){assertEquals(2,c.getBeanNamesForType(RabbitListenerAnnotationBeanPostProcessor.class).length);assertEquals(seed,c.getBean(Plain.class).seed);}
  try(var c=scope(10)){Object old=c.getBean("plain");c.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("next-native",Map.of("plain.seed","20")));c.getDefaultListableBeanFactory().destroySingleton("plain");assertNotSame(old,c.getBean("plain"));assertEquals(20,c.getBean(Plain.class).seed);}
 }
 @Test void unrelatedClassRefreshMustNotBeBlockedByOptionalRabbitShape(){
  try(var c=scope(10)){
   Object old=c.getBean("plain");c.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("next-native",Map.of("plain.seed","20")));
   PlatformContext p=(PlatformContext)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{PlatformContext.class},(proxy,method,args)->method.getName().equals("getAllApplicationContexts")?List.of(c):null);
   new SpringBeanReloader(p).refreshBean(Plain.class.getName(),Plain.class);
   assertNotSame(old,c.getBean("plain"),"OPTIONAL_RABBIT_BLOCKED_UNRELATED_CLASS");assertEquals(20,c.getBean(Plain.class).seed);
  }
 }
 @Test void unrelatedPropertyRefreshMustNotBeBlockedByOptionalRabbitShape(){
  try(var c=scope(10)){
   Object old=c.getBean("plain");var result=new SpringPropertyRebinder(List.of(c)).apply(Map.of("plain.seed","20"));
   assertEquals(PropertyChangeOutcome.State.APPLIED,result.state(),"OPTIONAL_RABBIT_BLOCKED_UNRELATED_PROPERTY");assertNotSame(old,c.getBean("plain"));assertEquals(20,c.getBean(Plain.class).seed);
  }
 }
}
