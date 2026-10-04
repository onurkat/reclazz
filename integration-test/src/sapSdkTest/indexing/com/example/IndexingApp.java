/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.example;

import de.hybris.platform.solrfacetsearch.indexer.impl.DefaultSolrInputDocument;
import org.apache.solr.common.SolrInputDocument;
import org.springframework.core.SpringVersion;
import java.util.*;

/** An isolated document-building operation and immutable snapshot store, not a Solr server. */
public class IndexingApp {
    public static void main(String[] args) throws Exception {
        var provider = new ProbeProvider();
        var resolver = new ProbeResolver();
        var type = new ProbeTypeResolver();
        var ordinary = new Ordinary();
        Map<String, String> stored = new HashMap<>();
        System.out.println("INDEX_READY=" + ProcessHandle.current().pid() + ";" + SpringVersion.getVersion());
        try (var input = new Scanner(System.in)) {
            while (input.hasNextLine()) {
                String[] command = input.nextLine().split(" ");
                String operation = command[1], id = command[2];
                if (operation.equals("INDEX")) {
                    var document = new DefaultSolrInputDocument(new SolrInputDocument(), null, null, null);
                    for (var field : provider.getFieldValues(null, null, id))
                        document.addField(field.getFieldName(), field.getValue());
                    resolver.resolve(document, null, List.of(), null);
                    type.resolve(document, null, id);
                    stored.put(id, "provider=" + document.getFieldValue("provider")
                            + ";resolver=" + document.getFieldValue("resolver")
                            + ";type=" + document.getFieldValue("type"));
                }
                String result = operation.equals("ORDINARY") ? ordinary.value() : stored.get(id);
                System.out.println("INDEX_RESULT " + command[0] + " " + result);
            }
        }
    }
}
