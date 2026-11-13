/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.platform.PlatformContext;
import com.onurkat.reclazz.platform.SpringXmlResources;
import com.onurkat.reclazz.platform.SpringXmlAliases;
import com.onurkat.reclazz.spring.xml.SpringXmlReloader;
import java.nio.file.*;
import java.util.*;
import org.springframework.beans.factory.parsing.*;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.core.io.FileSystemResource;
import org.springframework.web.context.support.GenericWebApplicationContext;
import org.springframework.mock.web.*;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.core.context.SecurityContextHolder;

public class SpringSixXmlSecurityReloadTest {
 public static class AliasContext {
  final Object reader,resource;AliasContext(Object r,Object s){reader=r;resource=s;}
  public Object getReader(){return reader;}public Object getResource(){return resource;}
 }
 public static class NativeEvents implements ReaderEventListener {
  final AliasContext context;NativeEvents(AliasContext c){context=c;}
  public void defaultsRegistered(DefaultsDefinition d){} public void importProcessed(ImportDefinition d){}
  public void aliasRegistered(AliasDefinition d){SpringXmlAliases.alias(context,d.getBeanName(),d.getAlias());}
  public void componentRegistered(ComponentDefinition c){SpringXmlAliases.component(context,c);}
 }
 static class Context implements PlatformContext {
  final Object value;Context(Object value){this.value=value;}
  public Platform getPlatformId(){return Platform.GENERIC;} public void initialize(){}
  public Map<String,List<Path>> getClassOutputDirs(){return Map.of();}public Map<String,List<Path>> getSourceDirs(){return Map.of();}public Map<String,List<Path>> getResourceDirs(){return Map.of();}
  public String resolveClasspath(){return "";}public String resolveClassName(Path p){return null;}public Path resolveOutputDir(Path p){return null;}public Object getApplicationContext(){return value;}
 }
 static List<Integer> matrix(FilterChainProxy proxy,String password)throws Exception{
  List<Integer> out=new ArrayList<>();
  for(String user:List.of("anonymous","c53a","c53b"))for(String path:List.of("probe","control")){
   String uri="/v2/test/batch/c53/"+path;var request=new MockHttpServletRequest("GET",uri);request.setServletPath(uri);request.setSecure(true);request.setScheme("https");request.setServerPort(443);
   if(!user.equals("anonymous"))request.addHeader("Authorization","Basic "+Base64.getEncoder().encodeToString((user+":"+password).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
   var response=new MockHttpServletResponse();
   proxy.doFilter(request,response,(req,res)->{res.setContentType("text/plain");res.getWriter().write(path.equals("control")?"control":SecurityContextHolder.getContext().getAuthentication().getName());});
   out.add(response.getStatus());
   if(response.getStatus()==200&&!path.equals("control")&&!response.getContentAsString().equals(user))throw new AssertionError("Principal mismatch");
  }
  return out;
 }
 @org.junit.jupiter.api.io.TempDir Path directory;
 @org.junit.jupiter.api.Test
 void xmlAccessRuleMustChangeLiveMatrix() throws Exception {
  Path xml=directory.resolve("security.xml");String password="synthetic-local-password";
  Files.writeString(xml, XML);
  try (var live=new GenericWebApplicationContext()) {
   live.setServletContext(new MockServletContext());
   var reader=new XmlBeanDefinitionReader(live.getDefaultListableBeanFactory());var resource=new FileSystemResource(xml);
   reader.setEventListener(new NativeEvents(new AliasContext(reader,resource)));
   SpringXmlAliases.begin(reader,resource);reader.loadBeanDefinitions(resource);SpringXmlResources.record(reader,resource);live.refresh();
   FilterChainProxy proxy=live.getBean(FilterChainProxy.class);
   org.junit.jupiter.api.Assertions.assertEquals(List.of(401,200,200,200,403,200),matrix(proxy,password));
   Files.writeString(xml,XML.replace("hasAuthority('ROLE_C53_A')","hasAuthority('ROLE_C53_B')"));
   new SpringXmlReloader(new Context(live)).reload(xml);
   org.junit.jupiter.api.Assertions.assertEquals(List.of(401,200,403,200,200,200),matrix(proxy,password));
   org.junit.jupiter.api.Assertions.assertSame(proxy,live.getBean(FilterChainProxy.class));
  }
 }
 static final String PASSWORD="synthetic-local-password";
 static final List<Integer> A=List.of(401,200,200,200,403,200), B=List.of(401,200,403,200,200,200);
 static String policyB(){return XML.replace("hasAuthority('ROLE_C53_A')","hasAuthority('ROLE_C53_B')");}
 static void load(GenericWebApplicationContext context,Path file,boolean observed)throws Exception {
  var reader=new XmlBeanDefinitionReader(context.getDefaultListableBeanFactory());var resource=new FileSystemResource(file);
  if(observed){reader.setEventListener(new NativeEvents(new AliasContext(reader,resource)));SpringXmlAliases.begin(reader,resource);}
  reader.loadBeanDefinitions(resource);if(observed)SpringXmlResources.record(reader,resource);
 }
 GenericWebApplicationContext context(Path file,boolean observed)throws Exception {
  var context=new GenericWebApplicationContext();context.setServletContext(new MockServletContext());
  load(context,file,observed);context.refresh();return context;
 }
 void save(GenericWebApplicationContext context,Path file,String content)throws Exception {
  Files.writeString(file,content);new SpringXmlReloader(new Context(context)).reload(file);
 }
 @org.junit.jupiter.api.Test
 void generatedNameOffsetAndForeignChainArePreserved()throws Exception {
  Path target=directory.resolve("target.xml"),foreign=directory.resolve("foreign.xml");Files.writeString(target,XML);
  Files.writeString(foreign,XML.substring(0,XML.indexOf(" <security:http"))+"<security:http pattern=\"/foreign/**\" request-matcher=\"ant\" security=\"none\"/></beans>");
  try(var live=new GenericWebApplicationContext()) {
   live.setServletContext(new MockServletContext());load(live,foreign,true);load(live,target,true);live.refresh();
   var proxy=live.getBean(FilterChainProxy.class);var oldForeign=proxy.getFilterChains().get(0);
   Object auth=live.getBean("reclazzC53AuthenticationManager"),users=live.getBean("reclazzC53Users");
   var factory=live.getDefaultListableBeanFactory();Map<String,Object> definitions=new LinkedHashMap<>();
   for(String name:factory.getBeanDefinitionNames())definitions.put(name,factory.getBeanDefinition(name));
   save(live,target,policyB());org.junit.jupiter.api.Assertions.assertEquals(B,matrix(proxy,PASSWORD));
   org.junit.jupiter.api.Assertions.assertSame(oldForeign,proxy.getFilterChains().get(0));
   org.junit.jupiter.api.Assertions.assertSame(auth,live.getBean("reclazzC53AuthenticationManager"));
   org.junit.jupiter.api.Assertions.assertSame(users,live.getBean("reclazzC53Users"));
   org.junit.jupiter.api.Assertions.assertSame(proxy,live.getBean(FilterChainProxy.class));
   for(var e:definitions.entrySet())org.junit.jupiter.api.Assertions.assertSame(e.getValue(),factory.getBeanDefinition(e.getKey()));
   var request=new MockHttpServletRequest("GET","/foreign/probe");request.setServletPath("/foreign/probe");
   var response=new MockHttpServletResponse();proxy.doFilter(request,response,(a,b)->b.getWriter().write("foreign"));
   org.junit.jupiter.api.Assertions.assertEquals("foreign",response.getContentAsString());
  }
 }
 @org.junit.jupiter.api.Test
 void failedCandidateRetainsOldPolicyAndDefinitions()throws Exception {
  Path file=directory.resolve("target.xml");Files.writeString(file,XML);
  try(var live=context(file,true)) {
   var proxy=live.getBean(FilterChainProxy.class);var old=proxy.getFilterChains();Object raw=com.onurkat.reclazz.util.Reflect.readField(proxy,"filterChains");
   var factory=live.getDefaultListableBeanFactory();Map<String,Object> definitions=new LinkedHashMap<>(),singletons=new LinkedHashMap<>();
   for(String name:factory.getBeanDefinitionNames())definitions.put(name,factory.getBeanDefinition(name));
   for(String name:factory.getSingletonNames())singletons.put(name,factory.getSingleton(name));
   save(live,file,XML.replace("hasAuthority('ROLE_C53_A')","hasAuthority("));
   org.junit.jupiter.api.Assertions.assertEquals(A,matrix(proxy,PASSWORD));
   org.junit.jupiter.api.Assertions.assertSame(raw,com.onurkat.reclazz.util.Reflect.readField(proxy,"filterChains"));
   // Inject a write refusal on a second proxy after the first proxy is edited.
   RefusingProxy.filterChains.clear();RefusingProxy.filterChains.addAll(old);
   live.getDefaultListableBeanFactory().registerSingleton("refusingProxy",new RefusingProxy(old));
   save(live,file,policyB());
   org.junit.jupiter.api.Assertions.assertEquals(A,matrix(proxy,PASSWORD));
   org.junit.jupiter.api.Assertions.assertSame(raw,com.onurkat.reclazz.util.Reflect.readField(proxy,"filterChains"));
   for(var e:definitions.entrySet())org.junit.jupiter.api.Assertions.assertSame(e.getValue(),factory.getBeanDefinition(e.getKey()));
   for(var e:singletons.entrySet())org.junit.jupiter.api.Assertions.assertSame(e.getValue(),factory.getSingleton(e.getKey()));
  } finally {RefusingProxy.filterChains.clear();}
 }
 public static class RefusingProxy extends FilterChainProxy {
  public static final List<org.springframework.security.web.SecurityFilterChain> filterChains=new ArrayList<>();
  RefusingProxy(List<org.springframework.security.web.SecurityFilterChain> original){super(original);}
 }
 @org.junit.jupiter.api.Test
 void unobservedOrChangedScopeIsRefused()throws Exception {
  Path file=directory.resolve("target.xml");Files.writeString(file,XML);
  try(var live=context(file,false)) {var proxy=live.getBean(FilterChainProxy.class);save(live,file,policyB());org.junit.jupiter.api.Assertions.assertEquals(A,matrix(proxy,PASSWORD));}
  Files.writeString(file,XML);
  try(var live=context(file,true)) {
   var proxy=live.getBean(FilterChainProxy.class);
   save(live,file,policyB().replace("/v2/test/batch/c53/**","/changed/**"));
   org.junit.jupiter.api.Assertions.assertEquals(A,matrix(proxy,PASSWORD));
   save(live,file,policyB().replace("ROLE_C53_A\"/>","ROLE_CHANGED\"/>"));
   org.junit.jupiter.api.Assertions.assertEquals(A,matrix(proxy,PASSWORD));
  }
 }
 @org.junit.jupiter.api.Test
 void repeatedSaveAndMalformedXml()throws Exception {
  Path file=directory.resolve("target.xml");Files.writeString(file,XML);
  try(var live=context(file,true)) {
   var proxy=live.getBean(FilterChainProxy.class);int names=live.getBeanDefinitionCount();
   save(live,file,policyB());org.junit.jupiter.api.Assertions.assertEquals(B,matrix(proxy,PASSWORD));
   Object served=proxy.getFilterChains().get(0);
   save(live,file,policyB());org.junit.jupiter.api.Assertions.assertSame(served,proxy.getFilterChains().get(0));
   save(live,file,"<beans>");org.junit.jupiter.api.Assertions.assertEquals(B,matrix(proxy,PASSWORD));
   save(live,file,XML);org.junit.jupiter.api.Assertions.assertEquals(A,matrix(proxy,PASSWORD));
   org.junit.jupiter.api.Assertions.assertEquals(names,live.getBeanDefinitionCount());
   org.junit.jupiter.api.Assertions.assertSame(proxy,live.getBean(FilterChainProxy.class));
  }
 }
 static final String XML = """
<?xml version="1.0" encoding="UTF-8"?>
<beans xmlns="http://www.springframework.org/schema/beans" xmlns:security="http://www.springframework.org/schema/security" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://www.springframework.org/schema/beans http://www.springframework.org/schema/beans/spring-beans.xsd http://www.springframework.org/schema/security http://www.springframework.org/schema/security/spring-security.xsd">
 <security:http pattern="/v2/test/batch/c53/**" request-matcher="ant" use-expressions="true" use-authorization-manager="true" create-session="stateless" authentication-manager-ref="reclazzC53AuthenticationManager">
  <security:intercept-url pattern="/v2/test/batch/c53/control" access="permitAll()"/>
  <security:intercept-url pattern="/v2/test/batch/c53/probe" access="hasAuthority('ROLE_C53_A')"/>
  <security:intercept-url pattern="/**" access="denyAll()"/>
  <security:http-basic/>
 </security:http>
 <security:authentication-manager alias="reclazzC53AuthenticationManager">
  <security:authentication-provider>
   <security:user-service id="reclazzC53Users">
    <security:user name="c53a" password="{noop}synthetic-local-password" authorities="ROLE_C53_A"/>
    <security:user name="c53b" password="{noop}synthetic-local-password" authorities="ROLE_C53_B"/>
   </security:user-service>
  </security:authentication-provider>
 </security:authentication-manager>
</beans>
""";
}
