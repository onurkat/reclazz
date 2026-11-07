/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package org.apache.solr.client.solrj.request.schema;
import com.onurkat.reclazz.hybris.SolrNativeFixture;
public class SchemaRequest {
    public static class UniqueKey {
        public SolrNativeFixture.Schema process(SolrNativeFixture.Client client, String core) {
            return new SolrNativeFixture.Schema(client.fixture.key);
        }
    }
}
