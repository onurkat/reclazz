/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.spring.xml;

import com.onurkat.reclazz.platform.*;
import com.onurkat.reclazz.ui.*;
import java.nio.file.*;
import java.util.*;

/** Replays exact scalar placeholders from unchanged, successfully loaded local XML. */
final class XmlPlaceholderProperties {
    private XmlPlaceholderProperties() { }
    static void apply(Map<String,String> values, List<Object> contexts) {
        if (values.isEmpty()) return;
        for (Path path : SpringXmlResources.loadedFiles()) {
            try {
                byte[] bytes=Files.readAllBytes(path);
                String text=new String(bytes,java.nio.charset.StandardCharsets.UTF_8);
                if (values.keySet().stream().noneMatch(k->text.contains("${"+k+"}"))) continue;
                byte[] digest=java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
                for(Object owner:SpringXmlResources.owners(path,contexts)) {
                    if (!SpringXmlResources.matchesLoadedContent(path,owner,digest)) {
                        warn(path.getFileName().toString(),values.keySet().toString(),"XML differs from its captured load");continue;
                    }
                    Object factory=SpringReflection.getBeanFactory(owner);
                    ClassLoader loader=(ClassLoader)factory.getClass().getMethod("getBeanClassLoader").invoke(factory);
                    Class<?> reader=SpringXmlResources.documentReader(path,owner);
                    if(reader!=null && !PlatformTenant.ensureActive(loader)) continue;
                    Object parsed=SpringReflection.newTempBeanFactory(loader);
                    Object environment=owner.getClass().getMethod("getEnvironment").invoke(owner);
                    if(parsed==null || !SpringReflection.loadXml(parsed,path.toFile(),loader,environment,reader)) continue;
                    // Recheck after parsing: a concurrent editor save cannot confer ownership.
                    if(!Arrays.equals(bytes,Files.readAllBytes(path))) continue;
                    for(String name:SpringReflection.getBeanDefinitionNames(parsed)) {
                        Object definition=SpringReflection.getBeanDefinition(parsed,name);
                        if(!XmlBeanRecreator.owns(definition,path)) continue;
                        for(Object pv:SpringReflection.getPropertyValueArray(SpringReflection.getPropertyValues(definition))) {
                            Object raw=SpringReflection.getPropertyValue(pv);
                            String expression=raw instanceof String s?s:raw!=null && raw.getClass().getName().equals("org.springframework.beans.factory.config.TypedStringValue")?(String)call(raw,"getValue"):null;
                            if(expression==null || !expression.startsWith("${") || !expression.endsWith("}")) continue;
                            String key=expression.substring(2,expression.length()-1);
                            if(!values.containsKey(key)) continue;
                            try {
                                Object live=SpringReflection.getBeanDefinition(factory,name);
                                require(live!=null && XmlBeanRecreator.owns(live,path),"definition owner unavailable");
                                plain(live);plain(definition);
                                Object singleton=SpringReflection.getExistingSingleton(factory,name);
                                if(singleton==null) continue; // Never initialize a lazy bean to update it.
                                Class<?> declared=Class.forName(SpringReflection.getBeanClassName(definition),false,loader);
                                require(singleton.getClass()==declared && Objects.equals(SpringReflection.getBeanClassName(live),declared.getName()),"proxy or changed class");
                                var diff=new BeanDefinitionDiff();
                                require(!XmlLifecyclePropertyGuard.classify(factory,parsed,name,live,definition,diff),"lifecycle/infrastructure role");
                                require(!XmlFactoryBeanPropertyGuard.classify(factory,name,live,definition,diff),"FactoryBean role");
                                ClassLoader spring=factory.getClass().getClassLoader();
                                for(String role:List.of("org.springframework.context.SmartLifecycle","org.springframework.context.ApplicationListener"))
                                    require(!Class.forName(role,false,spring).isInstance(singleton),"registration role");
                                String property=SpringReflection.getPropertyName(pv);
                                var binding=XmlNativePropertyBinding.prepare(factory,singleton,property,raw);
                                Class<?> type=binding.setter().getParameterTypes()[0];
                                require(type==String.class || type.isPrimitive() || Set.of(Boolean.class,Byte.class,Short.class,Integer.class,Long.class,Float.class,Double.class,Character.class).contains(type),"non-scalar property");
                                String value=values.get(key);require(value!=null && !value.contains("${"),"unresolved native value");
                                binding.setter().invoke(singleton,binding.convert(value));
                                StatusReporter.success("Spring XML placeholder updated: "+name+"."+property+" (key "+key+")");
                            } catch(Exception | LinkageError rejected) {warn(name,key,"setter or supported-shape check failed");}
                        }
                    }
                }
            } catch(Exception | LinkageError unavailable) {warn(path.getFileName().toString(),values.keySet().toString(),"cannot verify captured XML properties");}
        }
    }
    private static void plain(Object bd) throws Exception {
        require(Boolean.TRUE.equals(call(bd,"isSingleton")) && !Boolean.TRUE.equals(call(bd,"isAbstract")),"not a concrete singleton");
        require(call(bd,"getParentName")==null && !XmlBeanRecreator.handles(bd,bd),"construction or lifecycle metadata");
        require(Integer.valueOf(0).equals(call(bd,"getRole")) && !Boolean.TRUE.equals(call(bd,"isSynthetic")),"infrastructure metadata");
        require(Integer.valueOf(0).equals(call(bd,"getAutowireMode")) && call(bd,"getDependsOn")==null && call(bd,"getInstanceSupplier")==null && Boolean.TRUE.equals(call(call(bd,"getMethodOverrides"),"isEmpty")),"custom construction");
    }
    private static Object call(Object value,String method) throws Exception {return value.getClass().getMethod(method).invoke(value);}
    private static void require(boolean condition,String reason) {if(!condition)throw new IllegalArgumentException(reason);}
    private static void warn(String bean,String key,String reason) {
        String message="XML placeholder key "+key+", bean "+bean+": "+reason+"; restart required, Config may already be updated";
        RestartLedger.note(bean,message);StatusReporter.warn(message);
    }
}
