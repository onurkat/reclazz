/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring.xml;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ModernXmlAliasReferencesTest extends XmlAliasReferencesTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path temporary;

    @Test void computedExpressionReportsTheAliasOnTheRealSavePath() throws Exception {
        var fixture = new XmlAliasDiagnosticsTest(); fixture.tmp = temporary; fixture.reset();
        try { fixture.computedExpressionRetainsItsWarmChoiceAndReportsTheAliasWhileColdChoosesTheTarget(); }
        finally { fixture.close(); }
    }

    static class ResourceHolder { @jakarta.annotation.Resource Object x; }
    static class InheritedResource extends ResourceHolder {}
    @Override @Test void inheritedResourceImplicitNameIsSensitive() {
        var f = new DefaultListableBeanFactory(); f.registerSingleton("consumer", new InheritedResource());
        assertThrows(IllegalStateException.class, () -> XmlAliasReferences.check(f, "x"));
    }
}
