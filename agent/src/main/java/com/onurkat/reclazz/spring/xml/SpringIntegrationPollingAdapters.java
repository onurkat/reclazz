/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring.xml;

import static com.onurkat.reclazz.spring.xml.SpringIntegrationEndpoints.*;
import com.onurkat.reclazz.platform.SpringXmlResources;
import com.onurkat.reclazz.ui.StatusReporter;
import java.lang.reflect.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;

/** Standard file adapter regex/rate only; keep the source, queue and AcceptOnce history. */
final class SpringIntegrationPollingAdapters {
    private static final String CONFIG="org.springframework.integration.config.";
    private static final String FILE="org.springframework.integration.file.";
    private static final String ADAPTER=CONFIG+"SourcePollingChannelAdapterFactoryBean";
    private static final String PERIODIC="org.springframework.scheduling.support.PeriodicTrigger";
    private SpringIntegrationPollingAdapters() { }

    private record Graph(String adapter, String source, String filter, String trigger,
                         Object endpoint, Object sourceDefinition, Object filterDefinition,
                         Object triggerDefinition, Object poller) {
        List<String> names() {return List.of(adapter,source,filter,trigger);}
        List<Object> definitions() {return List.of(endpoint,sourceDefinition,filterDefinition,triggerDefinition);}
    }

    static SpringIntegrationEndpoints.Outcome apply(Object context,Object live,Object parsed,Path xml,String label) {
        int count=0;var failures=new ArrayList<BeanDefinitionDiff.UnsafeChange>();
        record Pair(Graph before,Graph after) { }
        var pairs=new ArrayList<Pair>();
        for(String name:SpringReflection.getBeanDefinitionNames(parsed)) {
            Object next=SpringReflection.getBeanDefinition(parsed,name);
            Object old=SpringReflection.getBeanDefinition(live,name);
            if(next==null||old==null||!ADAPTER.equals(SpringReflection.getBeanClassName(next))
                ||!ADAPTER.equals(SpringReflection.getBeanClassName(old))||generated(name))continue;
            try {pairs.add(new Pair(graph(live,name),graph(parsed,name)));}
            catch(Exception failure) {failures.add(new BeanDefinitionDiff.UnsafeChange(name,
                "Spring Integration polling adapter requires restart: "+SpringReflection.rootCause(failure)));}
        }
        var parsedNodes=new HashSet<String>();var liveNodes=new HashSet<String>();
        boolean unique=true;
        for(var pair:pairs)for(int i=0;i<4;i++) {
            unique&=parsedNodes.add(pair.after.names().get(i));unique&=liveNodes.add(pair.before.names().get(i));
        }
        for(var pair:pairs) {
            Graph before=pair.before,after=pair.after;String name=before.adapter;
            Object next=after.endpoint;
            try {
                require(unique,"shared adapter graph nodes");
                require(SpringXmlResources.owners(xml,List.of(context)).contains(context),"uncaptured XML owner");
                require(owned(before.endpoint,xml,live),"adapter belongs to another XML resource");
                require(name.endsWith(".adapter")&&reference(value(next,"outputChannel")).equals(name.substring(0,name.length()-8)),"implicit/custom adapter id");
                // Generated names are paired ONLY through this explicit adapter's graph.
                require(new HashSet<>(before.names()).size()==4&&new HashSet<>(after.names()).size()==4,"shared graph nodes");
                for(String target:before.names())require(parsedNodes.contains(target)||!SpringReflection.containsBeanDefinition(parsed,target),"generated name collision");
                var replacements=new HashMap<String,String>();
                for(int i=0;i<4;i++)replacements.put(after.names().get(i),before.names().get(i));
                List<Object> aligned=new ArrayList<>();
                for(Object d:after.definitions())aligned.add(normalize(d,replacements));
                Object oldFilter=copy(before.filterDefinition),newFilter=copy(aligned.get(2));
                Object oldTrigger=copy(before.triggerDefinition),newTrigger=copy(aligned.get(3));
                String previousRegex=literal(value(oldFilter,"filenameRegex")),requestedRegex=literal(value(newFilter,"filenameRegex"));
                long previousRate=rate(oldTrigger),requestedRate=rate(newTrigger);
                remove(oldFilter,"filenameRegex");remove(newFilter,"filenameRegex");
                remove(oldTrigger,"fixedRateValue");remove(newTrigger,"fixedRateValue");
                require(same(before.endpoint,aligned.get(0)),"adapter metadata changed");
                standard(before);standard(after);
                // Validated boot XML includes XSD defaults; the isolated nonvalidating
                // parser omits them. Compare only after verifying those exact defaults.
                Object oldSource=copy(before.sourceDefinition),newSource=copy(aligned.get(1));
                for(String key:List.of("watchEvents","autoCreateDirectory","scanEachPoll")) {
                    remove(oldSource,key);remove(newSource,key);
                }
                require(same(oldSource,newSource),"source metadata changed");
                require(same(oldFilter,newFilter),"filter metadata changed");
                require(same(oldTrigger,newTrigger),"trigger metadata changed");
                if(!previousRegex.equals(requestedRegex)||previousRate!=requestedRate) {
                    reconfigure(live,before,after,previousRegex,requestedRegex,previousRate,requestedRate);
                    count++;
                    StatusReporter.info("Spring Integration polling adapter "+name+label+" reconfigured: source and duplicate history retained");
                }
            } catch(Exception failure) {
                failures.add(new BeanDefinitionDiff.UnsafeChange(name,"Spring Integration polling adapter requires restart: "+SpringReflection.rootCause(failure)));
            }
        }
        // Remove all parse-order names before adding live names: #0 in this parse
        // may be another owned adapter's live #0. A failed edit aligns to old metadata.
        for(String name:parsedNodes)SpringReflection.removeBeanDefinition(parsed,name);
        for(var pair:pairs)for(int i=0;i<4;i++)try {
            SpringReflection.registerBeanDefinition(parsed,pair.before.names().get(i),copy(pair.before.definitions().get(i)));
        } catch(Exception failure) {failures.add(new BeanDefinitionDiff.UnsafeChange(pair.before.adapter,
            "polling graph alignment failed: "+SpringReflection.rootCause(failure)));}
        return new SpringIntegrationEndpoints.Outcome(count,Set.of(),failures);
    }

    private static Graph graph(Object factory,String name) throws Exception {
        Object endpoint=definition(factory,name,ADAPTER);
        String source=reference(value(endpoint,"source"));
        Object sourceDefinition=definition(factory,source,FILE+"config.FileReadingMessageSourceFactoryBean");
        String filter=reference(value(sourceDefinition,"filter"));
        Object filterDefinition=definition(factory,filter,FILE+"config.FileListFilterFactoryBean");
        Object poller=value(endpoint,"pollerMetadata");
        require(poller!=null&&"org.springframework.integration.scheduling.PollerMetadata".equals(SpringReflection.getBeanClassName(poller)),"custom/ref poller metadata");
        String trigger=reference(value(poller,"trigger"));
        return new Graph(name,source,filter,trigger,endpoint,sourceDefinition,filterDefinition,
            definition(factory,trigger,CONFIG+"PeriodicTriggerFactoryBean"),poller);
    }
    private static void standard(Graph g) throws Exception {
        require(g.source.equals(g.adapter+".source")&&g.filter.startsWith(FILE+"config.FileListFilterFactoryBean#")
            &&g.trigger.startsWith(CONFIG+"PeriodicTriggerFactoryBean#"),"nonstandard parser names");
        for(Object d:g.definitions()) {
            require(Boolean.TRUE.equals(call(d,"isSingleton"))&&!Boolean.TRUE.equals(call(d,"isAbstract"))
                &&call(d,"getParentName")==null&&call(d,"getFactoryBeanName")==null&&call(d,"getFactoryMethodName")==null
                &&call(d,"getInitMethodName")==null&&call(d,"getDestroyMethodName")==null
                &&Integer.valueOf(0).equals(call(d,"getAutowireMode")),"custom definition lifecycle/construction");
        }
        require(keys(g.endpoint).equals(Set.of("source","outputChannel","pollerMetadata")),"custom adapter settings");
        require(Set.of("watchEvents","directory","autoCreateDirectory","scanEachPoll","filter").containsAll(keys(g.sourceDefinition))
            &&keys(g.sourceDefinition).containsAll(Set.of("directory","filter")),"custom source settings");
        for(var entry:Map.of("watchEvents","CREATE","autoCreateDirectory","true","scanEachPoll","false").entrySet()) {
            Object raw=value(g.sourceDefinition,entry.getKey());
            require(raw==null||literal(raw).equals(entry.getValue()),"nonstandard source mode");
        }
        require(keys(g.filterDefinition).equals(Set.of("filenameRegex")),"custom filter/cache policy");
        require(keys(g.poller).equals(Set.of("trigger","maxMessagesPerPoll"))&&literal(value(g.poller,"maxMessagesPerPoll")).equals("1"),"custom poller policy");
        require(keys(g.triggerDefinition).equals(Set.of("fixedDelayValue","fixedRateValue","timeUnit","initialDelayValue")),"custom trigger");
        for(String key:List.of("fixedDelayValue","timeUnit","initialDelayValue"))require(literal(value(g.triggerDefinition,key)).isEmpty(),"nonstandard trigger mode/delay/unit");
    }

    private static void reconfigure(Object live,Graph g,Graph after,String oldRegex,String newRegex,long oldRate,long newRate) throws Exception {
        Object oldRegexRaw=value(g.filterDefinition,"filenameRegex"),oldRateRaw=value(g.triggerDefinition,"fixedRateValue");
        Method clear=live.getClass().getMethod("clearMetadataCache");
        Pattern pattern=Pattern.compile(newRegex); // Reject invalid edits before stopping anything.
        Object factory=SpringReflection.getExistingSingleton(live,g.adapter);
        exact(factory,ADAPTER);
        Object adapter=field(factory,ADAPTER,"adapter");
        exact(adapter,"org.springframework.integration.endpoint.SourcePollingChannelAdapter");
        Object source=field(factory,ADAPTER,"source");exact(source,FILE+"FileReadingMessageSource");
        require(field(adapter,"org.springframework.integration.endpoint.SourcePollingChannelAdapter","source")==source,"source identity changed");
        Object scanner=call(source,"getScanner");exact(scanner,FILE+"DefaultDirectoryScanner");
        Object filter=field(scanner,FILE+"DefaultDirectoryScanner","filter");exact(filter,FILE+"filters.CompositeFileListFilter");
        require(filter==call(live,"getBean",g.filter),"filter product identity changed");
        Set<?> members=(Set<?>)field(filter,FILE+"filters.CompositeFileListFilter","fileFilters");
        require(members.size()==3,"custom composite filter");
        Object regex=null;Set<String> types=new HashSet<>();
        for(Object member:members) {types.add(member.getClass().getName());if(member.getClass().getName().equals(FILE+"filters.RegexPatternFileListFilter"))regex=member;}
        require(types.equals(Set.of(FILE+"filters.IgnoreHiddenFileListFilter",FILE+"filters.AcceptOnceFileListFilter",FILE+"filters.RegexPatternFileListFilter")),"custom filter members");
        Pattern previous=(Pattern)field(regex,FILE+"filters.AbstractRegexPatternFileListFilter","pattern");
        require(previous.flags()==Pattern.compile(oldRegex).flags()&&previous.pattern().equals(oldRegex),"live regex differs from XML baseline");
        Object metadata=field(factory,ADAPTER,"pollerMetadata");exact(metadata,"org.springframework.integration.scheduling.PollerMetadata");
        Object trigger=call(metadata,"getTrigger");exact(trigger,PERIODIC);
        require(trigger==field(adapter,"org.springframework.integration.endpoint.AbstractPollingEndpoint","trigger"),"live trigger identity changed");
        require(Boolean.TRUE.equals(call(trigger,"isFixedRate"))&&Duration.ofMillis(oldRate).equals(call(trigger,"getPeriodDuration")),"live rate differs from XML baseline");
        Object requested=trigger.getClass().getConstructor(Duration.class).newInstance(Duration.ofMillis(newRate));call(requested,"setFixedRate",true);
        boolean running=Boolean.TRUE.equals(call(adapter,"isRunning"));
        try {
            call(adapter,"stop");call(regex,"setPattern",pattern);
            call(adapter,"setTrigger",requested);call(metadata,"setTrigger",requested);
            if(running)call(adapter,"start");
            set(g.filterDefinition,"filenameRegex",value(after.filterDefinition,"filenameRegex"));
            set(g.triggerDefinition,"fixedRateValue",value(after.triggerDefinition,"fixedRateValue"));
            clear.invoke(live);
        } catch(Exception failure) {
            try {call(adapter,"stop");call(regex,"setPattern",previous);call(adapter,"setTrigger",trigger);call(metadata,"setTrigger",trigger);if(running)call(adapter,"start");}
            catch(Exception restore) {failure.addSuppressed(restore);}
            try {set(g.filterDefinition,"filenameRegex",oldRegexRaw);set(g.triggerDefinition,"fixedRateValue",oldRateRaw);clear.invoke(live);}
            catch(Exception restore) {failure.addSuppressed(restore);}
            throw failure;
        }
    }
    private static Object normalize(Object definition,Map<String,String> names) throws Exception {
        Object result=copy(definition);
        for(String key:keys(result)) {
            Object raw=value(result,key);
            if(raw!=null&&raw.getClass().getName().equals("org.springframework.beans.factory.config.RuntimeBeanReference")) {
                String name=reference(raw);
                if(names.containsKey(name))set(result,key,raw.getClass().getConstructor(String.class).newInstance(names.get(name)));
            } else if(raw!=null&&SpringReflection.getBeanClassName(raw)!=null)set(result,key,normalize(raw,names));
        }
        return result;
    }
    private static Object definition(Object f,String name,String type) {
        Object d=SpringReflection.getBeanDefinition(f,name);require(d!=null&&type.equals(SpringReflection.getBeanClassName(d)),"nonstandard owned parser graph");return d;
    }
    private static Object value(Object d,String key) throws Exception {
        Object pv=call(call(d,"getPropertyValues"),"getPropertyValue",key);return pv==null?null:call(pv,"getValue");
    }
    private static void set(Object d,String key,Object v) throws Exception {call(call(d,"getPropertyValues"),"add",key,v);}
    private static void remove(Object d,String key) throws Exception {call(call(d,"getPropertyValues"),"removePropertyValue",key);}
    private static Set<String> keys(Object d) throws Exception {
        var result=new HashSet<String>();for(Object pv:(Object[])call(call(d,"getPropertyValues"),"getPropertyValues"))result.add((String)call(pv,"getName"));return result;
    }
    private static String reference(Object raw) throws Exception {
        require(raw!=null&&raw.getClass().getName().equals("org.springframework.beans.factory.config.RuntimeBeanReference")&&!Boolean.TRUE.equals(call(raw,"isToParent")),"nonlocal parser reference");
        return (String)call(raw,"getBeanName");
    }
    private static String literal(Object raw) throws Exception {
        if(raw!=null&&raw.getClass().getName().equals("org.springframework.beans.factory.config.TypedStringValue"))raw=call(raw,"getValue");
        require(raw instanceof String&&!((String)raw).contains("${")&&!((String)raw).contains("#{"),"nonliteral setting");return (String)raw;
    }
    private static long rate(Object d) throws Exception {
        String raw=literal(value(d,"fixedRateValue"));require(raw.matches("[0-9]+"),"nonliteral/invalid fixed rate");long rate=Long.parseLong(raw);require(rate>0,"nonpositive fixed rate");return rate;
    }
    private static Object field(Object target,String owner,String name) throws Exception {
        Field f=Class.forName(owner,false,target.getClass().getClassLoader()).getDeclaredField(name);f.setAccessible(true);return f.get(target);
    }
    private static void exact(Object target,String name) {require(target!=null&&target.getClass().getName().equals(name),"custom/unavailable live "+name);}
    private static boolean owned(Object d,Path xml,Object live) throws Exception {
        String resource=SpringReflection.getResourceDescription(d);if(resource==null)return false;
        Path resolved=null;
        if(resource.startsWith("file [")&&resource.endsWith("]"))resolved=Path.of(resource.substring(6,resource.length()-1));
        else if(resource.startsWith("class path resource [")&&resource.endsWith("]")) {
            String name=resource.substring(21,resource.length()-1);while(name.startsWith("/"))name=name.substring(1);
            var url=((ClassLoader)call(live,"getBeanClassLoader")).getResource(name);
            if(url!=null&&url.getProtocol().equals("file"))resolved=Path.of(url.toURI());
        }
        return resolved!=null&&SpringXmlResources.canonical(resolved).equals(SpringXmlResources.canonical(xml));
    }
    private static void require(boolean condition,String reason) {if(!condition)throw new IllegalArgumentException(reason);}
}
