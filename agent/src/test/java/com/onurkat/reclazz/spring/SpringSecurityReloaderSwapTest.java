/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The matching that decides which chains inside a live FilterChainProxy are
 * replaced by the rebuilt beans.
 *
 * <p>The swap cannot pair old with new by identity: by the time it runs, the
 * bean-refresh cascade has already churned the chain beans, so any instance
 * captured "before" was never the one the proxy holds (measured on Boot 3.3).
 * Staleness is decided against the container instead: an element that is a
 * current bean stays, an element with no filters is a {@code WebSecurity
 * .ignoring()} chain built inline and stays, and the rest are startup-era
 * chains replaced in order. When the stale entries and the rebuilt beans do
 * not line up one to one, nothing is touched, because guessing which security
 * rules go where is the one thing this must never do. These tests drive
 * {@code swapChains} through fakes shaped like the real classes: a proxy with
 * a {@code filterChains} list, chains answering {@code getFilters()}.
 */
class SpringSecurityReloaderSwapTest {

    /** Shaped like FilterChainProxy: the list field the finder looks for. */
    static class ProxyShape {
        @SuppressWarnings("unused")
        private List<Object> filterChains;
        ProxyShape(List<Object> chains) { this.filterChains = chains; }
        List<Object> chains() { return filterChains; }
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

    @Test
    void aStaleChainIsReplacedByTheRebuiltBean() {
        Chain stale = new Chain(3);
        Chain rebuilt = new Chain(3);
        ProxyShape proxy = new ProxyShape(new ArrayList<>(List.of(stale)));

        assertEquals(1, SpringSecurityReloader.swapChains(proxy, List.of(rebuilt)));
        assertSame(rebuilt, proxy.chains().get(0),
                "the proxy must now serve the chain built from the reloaded configuration");
    }

    @Test
    void anIgnoringChainStaysWhereItIs() {
        Chain ignoring = new Chain(0);              // ignoring() builds a chain with no filters
        Chain stale = new Chain(3);
        Chain rebuilt = new Chain(3);
        ProxyShape proxy = new ProxyShape(new ArrayList<>(List.of(ignoring, stale)));

        assertEquals(1, SpringSecurityReloader.swapChains(proxy, List.of(rebuilt)));
        assertSame(ignoring, proxy.chains().get(0), "an inline ignoring() chain is not a bean and is not ours to move");
        assertSame(rebuilt, proxy.chains().get(1));
    }

    @Test
    void anAlreadyCurrentListIsLeftAlone() {
        Chain current = new Chain(3);
        ProxyShape proxy = new ProxyShape(new ArrayList<>(List.of(current)));

        assertEquals(0, SpringSecurityReloader.swapChains(proxy, List.of(current)),
                "the proxy already serves the current bean; a swap would be a lie in the count");
        assertSame(current, proxy.chains().get(0));
    }

    @Test
    void aCountMismatchTouchesNothing() {
        Chain staleOne = new Chain(3);
        Chain staleTwo = new Chain(4);
        Chain rebuiltOnlyOne = new Chain(3);
        ProxyShape proxy = new ProxyShape(new ArrayList<>(List.of(staleOne, staleTwo)));

        assertEquals(0, SpringSecurityReloader.swapChains(proxy, List.of(rebuiltOnlyOne)),
                "two stale chains and one rebuilt bean cannot be paired without guessing");
        assertSame(staleOne, proxy.chains().get(0));
        assertSame(staleTwo, proxy.chains().get(1));
    }

    @Test
    void multipleStaleChainsAreReplacedInOrder() {
        Chain staleA = new Chain(2);
        Chain staleB = new Chain(5);
        Chain newA = new Chain(2);
        Chain newB = new Chain(5);
        ProxyShape proxy = new ProxyShape(new ArrayList<>(List.of(staleA, staleB)));

        assertEquals(2, SpringSecurityReloader.swapChains(proxy, List.of(newA, newB)));
        assertSame(newA, proxy.chains().get(0), "order is the pairing, so it must be preserved");
        assertSame(newB, proxy.chains().get(1));
    }

    @Test
    void anUnmodifiableListIsReplacedThroughTheFieldInstead() {
        Chain stale = new Chain(3);
        Chain rebuilt = new Chain(3);
        ProxyShape proxy = new ProxyShape(List.of(stale));   // List.of refuses set()

        assertEquals(1, SpringSecurityReloader.swapChains(proxy, List.of(rebuilt)));
        assertSame(rebuilt, proxy.chains().get(0),
                "an immutable captured list must not stop the swap; the field takes a fresh list");
    }

    @Test
    void aProxyWithoutTheListIsAQuietZero() {
        assertEquals(0, SpringSecurityReloader.swapChains(new Object(), List.of(new Chain(1))),
                "an unrecognisable proxy shape degrades to no swap, never to an error");
    }
}
