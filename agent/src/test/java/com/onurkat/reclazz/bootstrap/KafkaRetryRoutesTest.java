/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.bootstrap;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class KafkaRetryRoutesTest {
    @Test void nativeWritesRemainVisibleDuringLegacyRegistrationAndAfterDetach() {
        Object resolver = new Object();
        var original = new HashMap<Object,Object>();
        var routes = new KafkaRetryRoutes(resolver, original);
        var owned = Map.of("managed", new Object());
        routes.add(owned);
        var before = KafkaRetryRoutes.view(resolver, original);
        Object nativeRoute = new Object();
        KafkaRetryRoutes.nativeWrite(original, Map.of("native", nativeRoute), resolver, null);
        assertFalse(before.containsKey("native"));
        assertSame(nativeRoute, KafkaRetryRoutes.view(resolver, original).get("native"));
        assertThrows(IllegalStateException.class,
                () -> KafkaRetryRoutes.nativeWrite(original, Map.of("managed", new Object()), resolver, null));
        routes.remove(owned, false);
        assertTrue(routes.detachIfUnused());
        assertEquals(Map.of("native", nativeRoute), KafkaRetryRoutes.view(resolver, original));
    }

    @Test void failedClaimsAndStaleRetirementLeaveThePublishedOwnerIntact() {
        var resolver = new Object();
        // Distinct, equal holders must not be interchangeable proof of provenance.
        var first = new String("holder");
        var second = new String("holder");
        var a = Map.of("a", Map.of("source", first));
        var overlapping = Map.of("b", Map.of("source", second));
        var baseline = new HashMap<Object,Object>(a);
        baseline.putAll(overlapping);
        var table = new KafkaRetryRoutes(resolver, baseline);
        assertFalse(table.claimOriginal(a), "two native IDs share a topic");
        assertTrue(table.detachIfUnused());

        baseline.remove("b");
        table = new KafkaRetryRoutes(resolver, baseline);
        var wrong = Map.of("a", Map.of("source", second));
        assertFalse(table.claimOriginal(wrong));
        assertTrue(table.claimOriginal(a));
        var claimed = table;
        assertThrows(IllegalStateException.class, () -> claimed.remove(wrong, true));
        assertSame(first, ((Map<?,?>)KafkaRetryRoutes.view(resolver, baseline).get("a")).get("source"));
        table.remove(a, true);
        assertFalse(KafkaRetryRoutes.view(resolver, baseline).containsKey("a"));
    }

    @Test void rejectedModernNativeWriteDoesNotLeaveAnEmptyListenerId() {
        var resolver = new Object();
        var original = new HashMap<Object,Object>();
        var table = new KafkaRetryRoutes(resolver, original);
        var managed = Map.of("managed", Map.of("existing", new Object()));
        table.add(managed);
        @SuppressWarnings("unchecked")
        var child = (Map<Object,Object>) KafkaRetryRoutes.destinationFor(original, "new", key -> new HashMap<>(), resolver);
        assertThrows(IllegalStateException.class, () -> KafkaRetryRoutes.nativeWriteForId(
                child, Map.of("existing", new Object()), resolver, "new", original));
        assertFalse(original.containsKey("new"));
        assertFalse(KafkaRetryRoutes.view(resolver, original).containsKey("new"));
        KafkaRetryRoutes.nativeWriteForId(child, Map.of("different", new Object()), resolver, "new", original);
        assertSame(child, original.get("new"));
        table.remove(managed, false);
        assertTrue(table.detachIfUnused());
    }

    @Test void retiredStartupRoutesStayHiddenWhileUnrelatedNativeListenerExtendsItsTopicMap() {
        Object resolver = new Object();
        Object a = new Object();
        var startup = Map.of("first", Map.of("topic-a", a));
        var existing = new HashMap<Object,Object>(Map.of("topic-b", new Object()));
        var original = new HashMap<Object,Object>(startup);
        original.put("second", existing);
        var routes = new KafkaRetryRoutes(resolver, original);
        assertTrue(routes.claimOriginal(startup));
        var before = KafkaRetryRoutes.view(resolver, original);
        Object c = new Object();
        KafkaRetryRoutes.nativeWrite(existing, Map.of("topic-c", c), resolver, "second");
        assertFalse(((Map<?,?>) before.get("second")).containsKey("topic-c"));
        assertThrows(IllegalStateException.class,
                () -> KafkaRetryRoutes.nativeWrite(existing, Map.of("topic-a", new Object()), resolver, "second"));
        assertFalse(existing.containsKey("topic-a"));
        routes.remove(startup, true);
        assertFalse(routes.detachIfUnused());
        var after = KafkaRetryRoutes.view(resolver, original);
        assertFalse(after.containsKey("first"));
        assertSame(c, ((Map<?,?>) after.get("second")).get("topic-c"));
    }
}
