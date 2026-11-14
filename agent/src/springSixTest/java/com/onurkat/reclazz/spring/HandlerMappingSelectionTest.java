/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.ui.StatusReporter;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.GenericWebApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

class HandlerMappingSelectionTest {
    public static class CustomMapping extends RequestMappingHandlerMapping { }
    @RestController public static class AddedController {
        @GetMapping("/selection-added") public String added() { return "selected"; }
    }

    private static GenericWebApplicationContext context(Class<?>... mappings) {
        var context = new GenericWebApplicationContext(new MockServletContext());
        for (int i = 0; i < mappings.length; i++)
            context.registerBeanDefinition("mapping" + i, new RootBeanDefinition(mappings[i]));
        return context;
    }
    private static boolean reload(GenericWebApplicationContext context) {
        context.getBeanFactory().registerSingleton("addedController", new AddedController());
        return new SpringMvcReloader(null).reloadMappingsInOwner(context, AddedController.class);
    }
    private static void served(GenericWebApplicationContext context) throws Exception {
        var response = MockMvcBuilders.webAppContextSetup(context).build()
                .perform(get("/selection-added")).andReturn().getResponse();
        assertEquals(200, response.getStatus());
        assertEquals("selected", response.getContentAsString());
    }
    @Test void uniqueCustomSubclassStillUsed() throws Exception {
        try (var context = context(CustomMapping.class)) {
            context.refresh();
            assertTrue(reload(context));
            served(context);
        }
    }
    @Test void actuatorLikeSecondMappingPicksExactBase() throws Exception {
        try (var context = context(RequestMappingHandlerMapping.class, CustomMapping.class)) {
            context.refresh();
            var other = context.getBean("mapping1", RequestMappingHandlerMapping.class);
            assertTrue(other.getHandlerMethods().isEmpty());
            assertTrue(reload(context));
            served(context);
            assertTrue(other.getHandlerMethods().isEmpty(), "the secondary registry must stay untouched");
        }
    }
    @Test void twoExactMappingsRefusedWithNames() {
        try (var context = context(RequestMappingHandlerMapping.class, RequestMappingHandlerMapping.class)) {
            context.refresh();
            var messages = new ArrayList<String>();
            StatusReporter.StatusListener listener = (level, message) -> messages.add(message);
            StatusReporter.addListener(listener);
            try { assertFalse(reload(context)); }
            finally { StatusReporter.removeListener(listener); }
            String diagnostic = String.join("\n", messages);
            assertTrue(diagnostic.contains("mapping0"), diagnostic);
            assertTrue(diagnostic.contains("mapping1"), diagnostic);
            assertTrue(diagnostic.contains(RequestMappingHandlerMapping.class.getName()), diagnostic);
            for (var mapping : context.getBeansOfType(RequestMappingHandlerMapping.class).values())
                assertTrue(mapping.getHandlerMethods().isEmpty());
        }
    }
    @Test void lazySecondMappingNotInitialised() throws Exception {
        var created = new AtomicInteger();
        try (var context = context(RequestMappingHandlerMapping.class)) {
            var lazy = new RootBeanDefinition(RequestMappingHandlerMapping.class);
            lazy.setLazyInit(true);
            lazy.setInstanceSupplier(() -> { created.incrementAndGet(); return new RequestMappingHandlerMapping(); });
            context.registerBeanDefinition("lazyMapping", lazy);
            context.refresh();
            assertEquals(0, created.get());
            assertTrue(reload(context));
            assertEquals(0, created.get(), "selection must not create lazy infrastructure");
            assertFalse(context.getBeanFactory().containsSingleton("lazyMapping"));
            // MockMvc's own native initialization may enumerate mappings; check selection first.
            assertFalse(context.getBean("mapping0", RequestMappingHandlerMapping.class).getHandlerMethods().isEmpty());
        }
    }
    @Test void missingLocalMappingStillSearchesParent() throws Exception {
        try (var parent = context(CustomMapping.class); var child = context()) {
            parent.refresh(); child.setParent(parent); child.refresh();
            var finder = SpringMvcReloader.class.getDeclaredMethod("findHandlerMapping", Object.class);
            finder.setAccessible(true);
            assertSame(parent.getBean("mapping0"), finder.invoke(new SpringMvcReloader(null), child));
        }
    }
}
