/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package org.apache.solr.client.solrj;
public class SolrQuery extends com.onurkat.reclazz.hybris.SolrNativeFixture.Query {
    public enum ORDER { asc, desc }
    public SolrQuery(String text) { super(text); }
}
