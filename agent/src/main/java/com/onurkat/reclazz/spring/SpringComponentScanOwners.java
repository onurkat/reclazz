/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.platform.SpringXmlResources;
import com.onurkat.reclazz.ui.StatusReporter;
import java.nio.file.*;
import java.util.*;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;

/** Only actual loaded, plain XML scans confer a component's live context ownership. */
final class SpringComponentScanOwners {
    private SpringComponentScanOwners() { }
    static List<Object> find(String className,List<Object> contexts) {
        var result=new ArrayList<Object>();
        for(Path file:SpringXmlResources.loadedFiles()) {
            var owners=SpringXmlResources.owners(file,contexts);
            if(owners.isEmpty())continue;
            byte[] contents,digest;
            try {contents=Files.readAllBytes(file);digest=java.security.MessageDigest.getInstance("SHA-256").digest(contents);}catch(Exception unavailable) {continue;}
            if(!matches(file,contents,className))continue;
            for(Object owner:owners)if(SpringXmlResources.matchesLoadedContent(file,owner,digest)&&result.stream().noneMatch(x->x==owner))result.add(owner);
        }
        return result;
    }
    static boolean matches(Path file,String className) {
        try {return matches(file,Files.readAllBytes(file),className);}catch(Exception unavailable) {return false;}
    }
    private static boolean matches(Path file,byte[] contents,String className) {
        try {
            var factory=DocumentBuilderFactory.newInstance();factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities",false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd",false);
            factory.setXIncludeAware(false);factory.setExpandEntityReferences(false);
            try(var input=new java.io.ByteArrayInputStream(contents)) {
                var document=factory.newDocumentBuilder().parse(input);
                var scans=document.getElementsByTagNameNS("http://www.springframework.org/schema/context","component-scan");
                for(int i=0;i<scans.getLength();i++) {
                    Element scan=(Element)scans.item(i);boolean plain=true;
                    for(int a=0;a<scan.getAttributes().getLength();a++) {
                        var attribute=scan.getAttributes().item(a);
                        if(!attribute.getNodeName().equals("base-package")&&!attribute.getNodeName().startsWith("xmlns"))plain=false;
                    }
                    for(var child=scan.getFirstChild();child!=null;child=child.getNextSibling())if(child instanceof Element)plain=false;
                    String packages=scan.getAttribute("base-package");
                    if(!plain||packages.contains("$")||packages.contains("#")||packages.contains("*")) {
                        StatusReporter.detail("New controller scan-owner selection skipped unsupported component-scan in "+file.getFileName());continue;
                    }
                    // Enclosing profiles and bean defaults change scan admission/definitions; do not guess them.
                    for(var parent=scan.getParentNode();parent instanceof Element e;parent=parent.getParentNode()) {
                        if(e.hasAttribute("profile"))plain=false;
                        for(String attribute:List.of("default-lazy-init","default-init-method","default-destroy-method","default-autowire","default-autowire-candidates","default-merge"))if(e.hasAttribute(attribute))plain=false;
                    }
                    if(!plain) {StatusReporter.detail("New controller scan-owner selection skipped XML bean defaults/profile in "+file.getFileName());continue;}
                    for(String pkg:packages.split("[,;\\s]+")) {
                        if(pkg.matches("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*")&&className.startsWith(pkg+"."))return true;
                    }
                }
            }
        } catch(Exception failure) {StatusReporter.detail("New controller scan-owner selection unavailable for "+file.getFileName()+": "+failure.getClass().getSimpleName());}
        return false;
    }
}
