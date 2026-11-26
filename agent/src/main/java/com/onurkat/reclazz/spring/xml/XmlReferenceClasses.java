/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.spring.xml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;

/** A resource probe avoids poisoning SAP's negative class lookup cache before javac finishes. */
final class XmlReferenceClasses {
    private XmlReferenceClasses() { }
    private static final String BEANS = "http://www.springframework.org/schema/beans";
    private static final Set<String> PRIMITIVES = Set.of("boolean", "byte", "char", "double", "float", "int", "long", "short", "void");
    private static final Pattern BINARY_NAME = Pattern.compile(
            "\\p{javaJavaIdentifierStart}\\p{javaJavaIdentifierPart}*(?:\\.\\p{javaJavaIdentifierStart}\\p{javaJavaIdentifierPart}*)*");

    record Preflight(Set<String> missing, org.w3c.dom.Document resolvedDocument) { }
    static Preflight inspect(Path file, ClassLoader loader, Object factory) {
        var missing = new LinkedHashSet<String>();
        try (var input = Files.newInputStream(file)) {
            var parser = DocumentBuilderFactory.newInstance();
            parser.setNamespaceAware(true);
            parser.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            parser.setFeature("http://xml.org/sax/features/external-general-entities", false);
            parser.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            parser.setXIncludeAware(false);
            parser.setExpandEntityReferences(false);
            var document = parser.newDocumentBuilder().parse(input);
            boolean resolvedClass = false;
            var types = new LinkedHashSet<String>();
            var refs = new LinkedHashSet<String>();
            var declared = new LinkedHashSet<String>();
            var elements = document.getElementsByTagNameNS(BEANS, "*");
            for (int i = 0; i < elements.getLength(); i++) {
                var node = (Element) elements.item(i);
                switch (node.getLocalName()) {
                    case "bean" -> {
                        declared.add(node.getAttribute("id"));
                        String raw = node.getAttribute("class");
                        String literal = literalName(raw, factory);
                        if (literal != null) {
                            types.add(literal);
                            if (!literal.equals(raw)) {
                                node.setAttribute("class", literal);
                                resolvedClass = true;
                            }
                        }
                    }
                    case "ref", "idref" -> refs.add(node.getAttribute("bean"));
                    case "property", "constructor-arg" -> refs.add(node.getAttribute("ref"));
                    case "entry" -> {
                        refs.add(node.getAttribute("key-ref"));
                        refs.add(node.getAttribute("value-ref"));
                    }
                }
            }
            refs.remove("");
            refs.removeAll(declared);
            for (String ref : refs) {
                Object definition = SpringReflection.getBeanDefinition(factory, ref);
                if (definition == null) continue;
                String literal = literalName(SpringReflection.getBeanClassName(definition), factory);
                if (literal != null) types.add(literal);
            }
            for (String type : types) {
                if (!PRIMITIVES.contains(type) && loader.getResource(type.replace('.', '/') + ".class") == null)
                    missing.add(type);
            }
            return new Preflight(missing, resolvedClass ? document : null);
        } catch (Exception unavailable) {
            // Spring owns the diagnosis for XML outside the supported shape.
        }
        return new Preflight(missing, null);
    }

    private static String literalName(String type, Object factory) {
        if (type == null || type.isBlank()) return null;
        if (type.contains("${") || type.contains("#{")) {
            try {
                type = (String) factory.getClass().getMethod("resolveEmbeddedValue", String.class).invoke(factory, type);
            } catch (ReflectiveOperationException unavailable) {
                return null;
            }
        }
        return type != null && BINARY_NAME.matcher(type.trim()).matches() ? type.trim() : null;
    }
}
