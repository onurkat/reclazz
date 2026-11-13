/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.platform;

import java.lang.reflect.*;
import java.net.URL;
import java.nio.file.*;
import java.util.*;
import org.w3c.dom.*;
import javax.xml.parsers.DocumentBuilderFactory;

/** Native namespace component origins; no application bean is created by observation. */
public final class SpringSecurityXmlResources {
    private SpringSecurityXmlResources() { }
    public record Snapshot(Set<String> beans, String chain, String scope, String content) { }
    private static final Map<Object, Map<Path, Set<String>>> pending = new WeakHashMap<>();
    private static final Map<Object, Map<Path, Snapshot>> loaded = new WeakHashMap<>();

    public static synchronized void begin(Object reader, Object resource) {
        try { pending.computeIfAbsent(reader, x -> new HashMap<>()).put(path(resource), new LinkedHashSet<>()); }
        catch (Throwable ignored) { }
    }
    public static synchronized void component(Object reader, Object resource, Object component) {
        try {
            if (!"security:http".equals(call(component,"getName"))) return;
            Set<String> names = pending.computeIfAbsent(reader,x -> new HashMap<>())
                    .computeIfAbsent(path(resource),x -> new LinkedHashSet<>());
            for (Object child : (Object[])call(component,"getNestedComponents")) {
                try { names.add((String)call(child,"getBeanName")); }
                catch (NoSuchMethodException notBean) { }
            }
        } catch (Throwable ignored) { }
    }
    public static synchronized void complete(Object reader, Object resource) {
        try {
            Path path=path(resource); Map<Path,Set<String>> staged=pending.get(reader);
            if (staged==null) return;
            Set<String> names=staged.remove(path); if(names==null||names.isEmpty())return;
            Object registry=call(reader,"getRegistry"); String chain=null;
            for(String name:names) {
                Object bd=call(registry,"getBeanDefinition",name);
                if("org.springframework.security.web.DefaultSecurityFilterChain".equals(call(bd,"getBeanClassName"))) {
                    if(chain!=null)return; // one owned http per file; never guess a pairing
                    chain=name;
                }
            }
            if(chain==null)return;
            Snapshot snapshot=new Snapshot(Set.copyOf(names),chain,fingerprint(path,true),fingerprint(path,false));
            loaded.computeIfAbsent(registry,x->new HashMap<>()).put(path,snapshot);
        } catch(Throwable ignored) { }
    }
    public static synchronized Snapshot snapshot(Object registry,Path path) {
        return loaded.getOrDefault(registry,Map.of()).get(SpringXmlResources.canonical(path));
    }
    private static Path path(Object resource)throws Exception {
        URL url=(URL)call(resource,"getURL");
        if(!"file".equals(url.getProtocol()))throw new IllegalArgumentException("not local XML");
        return SpringXmlResources.canonical(Path.of(url.toURI()));
    }
    /** Scope guard only. Native component events, never this DOM, establish ownership. */
    private static String fingerprint(Path path,boolean maskAccess)throws Exception {
        var factory=DocumentBuilderFactory.newInstance();factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities",false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd",false);
        factory.setXIncludeAware(false);factory.setExpandEntityReferences(false);
        var doc=factory.newDocumentBuilder().parse(path.toFile());
        StringBuilder out=new StringBuilder();append(doc.getDocumentElement(),out,maskAccess,0);
        return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(out.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
    private static void append(Element e,StringBuilder out,boolean mask,int depth) {
        if(depth>32||out.length()>1048576)throw new IllegalArgumentException("security XML bound");
        out.append('<').append(e.getNamespaceURI()).append(':').append(e.getLocalName());
        List<String> attrs=new ArrayList<>();
        for(int i=0;i<e.getAttributes().getLength();i++) {
            Node a=e.getAttributes().item(i);
            if(a.getNodeName().startsWith("xmlns"))continue;
            boolean access=mask&&"http://www.springframework.org/schema/security".equals(e.getNamespaceURI())
                    &&"intercept-url".equals(e.getLocalName())&&"access".equals(a.getNodeName());
            attrs.add(a.getNodeName()+"="+(access?"<access>":a.getNodeValue()));
        }
        Collections.sort(attrs);for(String a:attrs)out.append(a.length()).append(':').append(a);
        out.append('>');
        for(Node n=e.getFirstChild();n!=null;n=n.getNextSibling())
            if(n instanceof Element child)append(child,out,mask,depth+1);
            else if(n.getNodeType()==Node.TEXT_NODE&&!n.getTextContent().isBlank())out.append(n.getTextContent());
        out.append("</>");
    }
    private static Object call(Object target,String method,Object...args)throws ReflectiveOperationException {
        for(Method m:target.getClass().getMethods())if(m.getName().equals(method)&&m.getParameterCount()==args.length)
            return m.invoke(target,args);
        throw new NoSuchMethodException(method);
    }
}
