/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package de.hybris.platform.processengine.definition;

import org.springframework.core.io.Resource;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.xml.parsers.DocumentBuilderFactory;
import org.xml.sax.InputSource;
import java.io.ByteArrayInputStream;

/** Synthetic parser fixture; native schema and advice are checked in the SDK lane. */
public class XMLProcessDefinitionsReader {
    public int parses;
    public Runnable afterParse;
    public ProcessDefinition from(Resource resource) throws Exception {
        parses++;
        byte[] bytes = resource.getInputStream().readAllBytes();
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        var builder = factory.newDocumentBuilder();
        builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler() {
            @Override public void fatalError(org.xml.sax.SAXParseException e) throws org.xml.sax.SAXException { throw e; }
        });
        var root = builder.parse(new ByteArrayInputStream(bytes)).getDocumentElement();
        if (!root.getTagName().equals("process")) throw new IllegalArgumentException("Expected process");
        var definition = new ProcessDefinition(root.getAttribute("name"),
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)), root.getAttribute("start"));
        Runnable callback = afterParse; afterParse = null;
        if (callback != null) callback.run();
        return definition;
    }
}
