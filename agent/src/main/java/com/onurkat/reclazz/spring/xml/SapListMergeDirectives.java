/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.spring.xml;

import java.lang.reflect.Method;
import java.util.*;
import static com.onurkat.reclazz.spring.xml.SapPopulatorDirectives.*;

/** Exact single-insert inverse, then the installed SDK's own add/sort on a detached list. */
final class SapListMergeDirectives {
    static final String TYPE="de.hybris.platform.spring.config.ListMergeDirective";
    private static final List<String> EDITABLE=List.of("add","beforeBeanNames","afterBeanNames");
    private SapListMergeDirectives(){}

    static boolean supported(Object factory,String name,Object previous,Object next,Object live)throws Exception {
        if(!localReference(rawValue(next,"add")))return false;
        for(String key:List.of("beforeBeanNames","afterBeanNames"))names(rawValue(next,key));
        // Other position sources and target metadata require their own inverse provenance proof.
        for(String key:List.of("BeforeValues","AfterValues","BeforeClasses","AfterClasses")) {
            Object value=call(live,"get"+key);if(value!=null)return false; // Empty value/class lists also enable native sorting.
        }
        if(call(live,"getFieldName")!=null)return false;
        Object descriptor=call(live,"getListPropertyDescriptor");
        if(descriptor!=null&&!descriptor.equals("populators"))return false;
        var comparator=new XmlRawValueComparator(next.getClass().getClassLoader());
        if(!comparator.verifiedStandardDefinition(withEditable(previous,next,EDITABLE)).equals(comparator.verifiedStandardDefinition(withEditable(next,next,EDITABLE))))return false;
        List<Object> list=target(factory,previous,live);requireWritable(list);uniqueAddition(list,call(live,"getAdd"));
        Object add=SpringReflection.getExistingSingleton(factory,(String)call(rawValue(next,"add"),"getBeanName"));
        if(add!=null)for(Object item:list)if(item!=call(live,"getAdd")&&Objects.equals(item,add))return false;
        return SapSharedDirectives.refusal(factory,name,previous,live)==null;
    }
    static List<String> names(Object raw)throws Exception {
        if(raw==null)return null; // The SDK distinguishes absent positions from an explicitly empty list.
        var result=new ArrayList<String>();
        if(!(raw instanceof List<?> values))throw new IllegalArgumentException("bean-name positions require a literal list");
        for(Object item:values) {
            if(item!=null&&item.getClass().getName().equals("org.springframework.beans.factory.config.TypedStringValue")) {
                Object type=call(item,"getTargetTypeName");
                if(type!=null&&!type.equals("java.lang.String"))throw new IllegalArgumentException("position bean names require string values");
            }
            String value=item instanceof String string?string:item!=null&&item.getClass().getName().equals("org.springframework.beans.factory.config.TypedStringValue")?(String)call(item,"getValue"):null;
            if(value==null||value.contains("${")||value.contains("#{"))throw new IllegalArgumentException("position bean names must be literal");
            result.add(value);
        }
        return result;
    }
    @SuppressWarnings("unchecked")
    private static List<Object> target(Object factory,Object definition,Object directive)throws Exception {
        Object target=owner(factory,definition);
        return call(directive,"getListPropertyDescriptor")==null?(List<Object>)target:populators(target);
    }
    private static Object owner(Object factory,Object definition)throws Exception {
        String[] dependencies=(String[])call(definition,"getDependsOn");
        if(dependencies==null||dependencies.length!=1)throw new IllegalArgumentException("one initialized list dependency is required");
        String name=(String)call(factory,"canonicalName",dependencies[0]);
        Object target=SpringReflection.getExistingSingleton(factory,name);
        if(target==null)throw new IllegalArgumentException("list dependency is not an initialized singleton");
        return target;
    }
    private static Object existing(Object factory,String name)throws Exception {
        name=(String)call(factory,"canonicalName",name);
        if(Boolean.TRUE.equals(call(factory,"containsLocalBean",name)))return SpringReflection.getExistingSingleton(factory,name);
        Object parent=call(factory,"getParentBeanFactory");return parent==null?null:existing(parent,name);
    }

    /** A single insert has no restart-equivalent inverse when another directive owns this list. */
    static String sharedDirective(Object factory,String name,Object definition,Object live)throws Exception {
        return sharedDirective(factory,name,definition,live,Set.of());
    }
    static String sharedDirective(Object factory,String name,Object definition,Object live,Set<String> observed)throws Exception {
        boolean modify=MODIFY.equals(live.getClass().getName());
        Object targetOwner=modify?call(live,"getList"):owner(factory,definition);
        List<Object> targetList=modify?populators(targetOwner):target(factory,definition,live);
        var factories=Collections.newSetFromMap(new IdentityHashMap<Object,Boolean>());factories.add(factory);
        for(Object ctx:com.onurkat.reclazz.platform.ApplicationContextHolder.getAllContexts()) {
            try{factories.add(call(ctx,"getBeanFactory"));}catch(Exception closed){/* A closed context has no live ownership. */}
        }
        for(Object candidateFactory:new ArrayList<>(factories)) {
            Object parent=call(candidateFactory,"getParentBeanFactory");
            while(parent!=null){factories.add(parent);parent=call(parent,"getParentBeanFactory");}
        }
        for(Object otherFactory:factories)for(String other:(String[])call(otherFactory,"getBeanDefinitionNames")) {
            if(otherFactory==factory&&(other.equals(name)||observed.contains(other)))continue;
            Object raw=call(otherFactory,"getBeanDefinition",other);
            if(Boolean.TRUE.equals(call(raw,"isAbstract")))continue;
            Class<?> type=(Class<?>)otherFactory.getClass().getMethod("getType",String.class,boolean.class).invoke(otherFactory,other,false);String kind=null;
            for(Class<?> c=type;c!=null;c=c.getSuperclass())if(TYPE.equals(c.getName())||MODIFY.equals(c.getName())){kind=c.getName();break;}
            if(kind==null)continue;
            Object merged=call(otherFactory,"getMergedBeanDefinition",other);
            Object instance=SpringReflection.getExistingSingleton(otherFactory,other);
            if(MODIFY.equals(kind)) {
                Object otherOwner;
                if(instance!=null)otherOwner=call(instance,"getList");
                else {
                    Object ref=rawValue(merged,"list");
                    if(!localReference(ref))return other; // Cannot prove a distinct target without initializing it.
                    otherOwner=existing(otherFactory,(String)call(ref,"getBeanName"));
                }
                if(otherOwner==targetOwner||(otherOwner!=null&&populators(otherOwner)==targetList))return other;
            } else {
                String[] dependencies=(String[])call(merged,"getDependsOn");
                if(dependencies==null)continue;
                Object descriptor=instance;
                if(descriptor==null) {
                    descriptor=Class.forName(TYPE,false,live.getClass().getClassLoader()).getConstructor().newInstance();
                    for(String property:List.of("listPropertyDescriptor","fieldName")) {
                        Object value=rawValue(merged,property);
                        if(value!=null&&value.getClass().getName().equals("org.springframework.beans.factory.config.TypedStringValue"))value=call(value,"getValue");
                        if(value!=null&&!(value instanceof String))return other;
                        set(descriptor,"set"+Character.toUpperCase(property.charAt(0))+property.substring(1),value);
                    }
                }
                Class<?> processorType=Class.forName("de.hybris.platform.spring.config.ListMergeDirectiveBeanPostProcessor",false,live.getClass().getClassLoader());
                Object processor=processorType.getConstructor().newInstance();
                Method resolve=processorType.getDeclaredMethod("resolveListBean",Class.forName(TYPE,false,live.getClass().getClassLoader()),String.class,Object.class,String.class);resolve.setAccessible(true);
                for(String dependency:dependencies) {
                    Object otherOwner=existing(otherFactory,dependency);
                    if(otherOwner==targetOwner)return other;
                    if(otherOwner!=null&&resolve.invoke(processor,descriptor,other,otherOwner,dependency)==targetList)return other;
                }
            }
        }
        return null;
    }
    static void apply(Object factory,SapPopulatorDirectives.Edit edit,Object live)throws Exception {
        Object previous=SpringReflection.getBeanDefinition(factory,edit.beanName());
        if(!supported(factory,edit.beanName(),previous,edit.next(),live))throw new IllegalArgumentException("list merge inverse is not uniquely established");
        TargetSnapshot snapshot=new TargetSnapshot(()->owner(factory,previous),call(live,"getListPropertyDescriptor")==null);
        List<Object> copy=snapshot.copy();
        copy.remove(uniqueAddition(copy,call(live,"getAdd")));
        Object addition=resolve(factory,rawValue(edit.next(),"add"));
        if(addition==null||copy.contains(addition))throw new IllegalArgumentException("new addition is missing or already present");
        Object candidate=live.getClass().getConstructor().newInstance();
        var properties=new LinkedHashMap<String,Object>();properties.put("add",addition);
        properties.put("beforeBeanNames",names(rawValue(edit.next(),"beforeBeanNames")));
        properties.put("afterBeanNames",names(rawValue(edit.next(),"afterBeanNames")));
        for(var entry:properties.entrySet())set(candidate,"set"+Character.toUpperCase(entry.getKey().charAt(0))+entry.getKey().substring(1),entry.getValue());
        replay(factory,candidate,copy,edit.beanName());
        String shared=sharedDirective(factory,edit.beanName(),previous,live);
        if(shared!=null)throw new IllegalStateException("SAP list directive edit requires restart; shared target with directive "+shared);
        publish(factory,edit,live,properties,snapshot,copy);
    }
    static void replay(Object factory,Object directive,List<Object> copy,String name)throws Exception {
        ClassLoader loader=directive.getClass().getClassLoader();String prefix="de.hybris.platform.spring.config.";
        Class<?> contract=Class.forName(prefix+"ListMergeDirectiveBeanPostProcessor$ListMergeDirectiveProcessor",false,loader);
        Method process=contract.getMethod("processDirective",directive.getClass(),List.class,String.class,String.class);
        var add=Class.forName(prefix+"AddListProcessor",false,loader).getDeclaredConstructor();add.setAccessible(true);
        var sort=Class.forName(prefix+"SortListProcessor",false,loader).getDeclaredConstructor(Class.forName("org.springframework.beans.factory.config.ConfigurableListableBeanFactory",false,loader));sort.setAccessible(true);
        process.invoke(add.newInstance(),directive,copy,"live list",name);
        process.invoke(sort.newInstance(factory),directive,copy,"live list",name);
    }
}
