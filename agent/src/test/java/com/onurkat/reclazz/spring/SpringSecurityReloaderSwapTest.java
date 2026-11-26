/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The old bean identities are captured before Spring recreates configuration singletons. */
class SpringSecurityReloaderSwapTest {

    /** Shaped like FilterChainProxy: the list field the finder looks for. */
    static class ProxyShape {
        @SuppressWarnings("unused")
        private List<Object> filterChains;
        ProxyShape(List<Object> chains) { this.filterChains = chains; }
        List<Object> chains() { return filterChains; }
        public List<Object> getFilterChains() { return filterChains; }
    }

    /** Shaped like a security filter chain: getFilters is what is duck-typed. */
    public static class Chain {
        private final List<Object> filters;
        Chain(int filterCount) {
            this.filters = new ArrayList<>();
            for (int i = 0; i < filterCount; i++) filters.add(new Object());
        }
        @SuppressWarnings("unused")
        public List<Object> getFilters() { return filters; }
    }

    private static int swap(ProxyShape proxy, List<Object> originals, List<Object> current) throws Exception {
        var before = proxy.chains();
        var next = SpringSecurityReloader.replaceSlots(before, originals, current);
        int changed = 0;
        for (int i = 0; i < next.size(); i++) if (next.get(i) != before.get(i)) changed++;
        var field = ProxyShape.class.getDeclaredField("filterChains");
        field.setAccessible(true);
        var target = new SpringSecurityReloader.ProxyList(proxy, field, before);
        if (changed != 0) SpringSecurityReloader.publishLists(List.of(new SpringSecurityReloader.Edit(target, next)));
        return changed;
    }

    @Test
    void aStaleChainIsReplacedByTheRebuiltBean() throws Exception {
        Chain stale = new Chain(3);
        Chain rebuilt = new Chain(3);
        ProxyShape proxy = new ProxyShape(new ArrayList<>(List.of(stale)));

        assertEquals(1, swap(proxy, List.of(stale), List.of(rebuilt)));
        assertSame(rebuilt, proxy.chains().get(0),
                "the proxy must now serve the chain built from the reloaded configuration");
    }

    @Test
    void anIgnoringChainStaysWhereItIs() throws Exception {
        Chain ignoring = new Chain(0);              // ignoring() builds a chain with no filters
        Chain stale = new Chain(3);
        Chain rebuilt = new Chain(3);
        ProxyShape proxy = new ProxyShape(new ArrayList<>(List.of(ignoring, stale)));

        assertEquals(1, swap(proxy, List.of(stale), List.of(rebuilt)));
        assertSame(ignoring, proxy.chains().get(0), "an inline ignoring() chain is not a bean and is not ours to move");
        assertSame(rebuilt, proxy.chains().get(1));
    }

    @Test
    void anAlreadyCurrentListIsLeftAlone() throws Exception {
        Chain current = new Chain(3);
        ProxyShape proxy = new ProxyShape(new ArrayList<>(List.of(current)));

        assertEquals(0, swap(proxy, List.of(current), List.of(current)),
                "the proxy already serves the current bean; a swap would be a lie in the count");
        assertSame(current, proxy.chains().get(0));
    }

    @Test
    void aCountMismatchTouchesNothing() throws Exception {
        Chain staleOne = new Chain(3);
        Chain staleTwo = new Chain(4);
        Chain rebuiltOnlyOne = new Chain(3);
        ProxyShape proxy = new ProxyShape(new ArrayList<>(List.of(staleOne, staleTwo)));

        assertThrows(IllegalStateException.class, () -> swap(proxy, List.of(staleOne, staleTwo), List.of(rebuiltOnlyOne)));
        assertSame(staleOne, proxy.chains().get(0));
        assertSame(staleTwo, proxy.chains().get(1));
    }

    @Test
    void multipleStaleChainsAreReplacedInOrder() throws Exception {
        Chain staleA = new Chain(2);
        Chain staleB = new Chain(5);
        Chain newA = new Chain(2);
        Chain newB = new Chain(5);
        ProxyShape proxy = new ProxyShape(new ArrayList<>(List.of(staleA, staleB)));

        assertEquals(2, swap(proxy, List.of(staleA, staleB), List.of(newA, newB)));
        assertSame(newA, proxy.chains().get(0), "order is the pairing, so it must be preserved");
        assertSame(newB, proxy.chains().get(1));
    }

    @Test
    void anUnmodifiableListIsReplacedThroughTheFieldInstead() throws Exception {
        Chain stale = new Chain(3);
        Chain rebuilt = new Chain(3);
        ProxyShape proxy = new ProxyShape(List.of(stale));   // List.of refuses set()

        assertEquals(1, swap(proxy, List.of(stale), List.of(rebuilt)));
        assertSame(rebuilt, proxy.chains().get(0),
                "an immutable captured list must not stop the swap; the field takes a fresh list");
    }

    @Test
    void unverifiableProxyRollsBackTheOriginalList() {
        var stale = new Chain(3);
        var rebuilt = new Chain(3);
        var original = List.<Object>of(stale);
        var proxy = new ProxyShape(original) {
            @Override public List<Object> getFilterChains() { return original; }
        };
        assertThrows(IllegalStateException.class, () -> swap(proxy, List.of(stale), List.of(rebuilt)));
        assertSame(original, proxy.chains());
    }

    @Test void nonBeanChainWithFiltersKeepsItsPosition() throws Exception {
        var inline = new Chain(2);
        var old = new Chain(1);
        var replacement = new Chain(1);
        var proxy = new ProxyShape(List.of(old, inline));
        assertEquals(1, swap(proxy, List.of(old), List.of(replacement)));
        assertSame(inline, proxy.chains().get(1));
    }
}
