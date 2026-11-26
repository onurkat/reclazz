/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.spring.xml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;

/** SAP's native loader remembers failed class lookups. Check a new ref's class resource before its first lookup. */
final class XmlReferenceClasses {
    private XmlReferenceClasses() { }
    private static final String BEANS = "http://www.springframework.org/schema/beans";

    static Set<String> missing(Path file, ClassLoader loader, Object factory) {
        var missing = new LinkedHashSet<String>();
        try (var input = Files.newInputStream(file)) {
            var builder = DocumentBuilderFactory.newInstance();
            builder.setNamespaceAware(true);
            builder.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            builder.setFeature("http://xml.org/sax/features/external-general-entities", false);
            builder.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            builder.setXIncludeAware(false);
            builder.setExpandEntityReferences(false);
            var document = builder.newDocumentBuilder().parse(input);
            Set<String> refs = new HashSet<>();
            var properties = document.getElementsByTagNameNS(BEANS, "property");
            for (int i = 0; i < properties.getLength(); i++) {
                var ref = ((Element) properties.item(i)).getAttribute("ref");
                if (!ref.isEmpty()) refs.add(ref);
            }
            var beans = document.getElementsByTagNameNS(BEANS, "bean");
            for (int i = 0; i < beans.getLength(); i++) {
                var bean = (Element) beans.item(i);
                String name = bean.getAttribute("id"), type = bean.getAttribute("class");
                if (!refs.contains(name) || type.isEmpty() || SpringReflection.getBeanDefinition(factory, name) != null) continue;
                if (loader.getResource(type.replace('.', '/') + ".class") == null) missing.add(type);
            }
        } catch (Exception unavailable) {
            // Spring owns the parsing and error report for XML outside this narrow shape.
        }
        return missing;
    }
}
