/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.reload;

import com.onurkat.reclazz.bootstrap.DispatchTable;
import com.onurkat.reclazz.bootstrap.MvcBindingBridge;
import com.onurkat.reclazz.bootstrap.InjectedNames;
import com.onurkat.reclazz.transform.MvcBindingTransformer;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.ui.Model;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;
import javax.servlet.http.HttpServletRequest;
import java.lang.instrument.Instrumentation;
import java.lang.invoke.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

class AddedMvcBindingMethodsTest {
    static Instrumentation instrumentation;
    static MvcBindingTransformer transformer;
    @BeforeAll static void hookActualSpring() throws Exception {
        instrumentation = ByteBuddyAgent.install(); transformer = new MvcBindingTransformer();
        instrumentation.addTransformer(transformer, true);
        instrumentation.retransformClasses(RequestMappingHandlerAdapter.class);
    }
    @AfterAll static void restoreSpring() throws Exception {
        instrumentation.removeTransformer(transformer);
        instrumentation.retransformClasses(RequestMappingHandlerAdapter.class);
    }
    @AfterEach void clearMetadata() { MvcBindingBridge.replace(Owner.class, null, null, null); }

    @RestController public static class Owner {
        int calls;
        private final String prefix = new String("owner");
        @GetMapping("/value") public String value(@RequestParam("amount") Integer amount, Model model) {
            return amount + ":" + model.getAttribute("tag") + ":" + model.getAttribute("base");
        }
        @GetMapping("/plain") public String plain(@RequestParam("other") Integer amount, Model model) { return value(amount, model); }
        @ModelAttribute("base") public String base() { return "seed"; }
        public void bindImpl(WebDataBinder binder) {
            binder.registerCustomEditor(Integer.class, new org.springframework.beans.propertyeditors.CustomNumberEditor(Integer.class, false) {
                @Override public void setAsText(String text) { setValue(Integer.valueOf(text) * 2); }
            });
        }
        public String tagImpl(String base, HttpServletRequest request) { return prefix + (++calls) + ":" + base + ":" + request.getRequestURI(); }
        public String alternate(String base, HttpServletRequest request) { return "updated"; }
        public void voidImpl(Model model) { calls++; model.addAttribute("tag", prefix); }
        public List<String> listImpl() { calls++; return List.of(prefix, "list"); }
        public java.util.concurrent.CompletableFuture<String> asyncImpl() { calls++; return java.util.concurrent.CompletableFuture.completedFuture(prefix); }
        public String invalidBinderImpl(WebDataBinder binder) { calls++; return "invalid"; }
    }
    @RestController static class Shape {
        @ModelAttribute("base") public String base() { return null; }
        @InitBinder("amount") private void bind(WebDataBinder binder) { }
        @ModelAttribute("tag") private String tag(@ModelAttribute("base") String base, HttpServletRequest request) { return null; }
    }
    @RestController static class VoidShape {
        @ModelAttribute public void added(Model model) { }
        @ModelAttribute("base") public String base() { return null; }
    }
    @RestController static class ListShape {
        @ModelAttribute("tag") public List<String> added() { return null; }
    }
    @RestController static class AsyncShape {
        @ModelAttribute("tag") public java.util.concurrent.CompletableFuture<String> added() { return null; }
    }
    @RestController static class InvalidBinderShape {
        @InitBinder public String added(WebDataBinder binder) { return null; }
    }
    private static final Map<String,String> BODIES = Map.of("base", "base", "bind", "bindImpl", "tag", "tagImpl");

    @Test void realSpringUsesNamedBinderPrivateModelRequestArgumentAndExistingModel() throws Throwable {
        var owner = new Owner(); var mvc = mvc(owner);
        assertTrue(MvcBindingBridge.isHooked(RequestMappingHandlerAdapter.class));
        publish(shape(Shape.class), BODIES);
        assertEquals("14:owner1:seed:/value:seed", response(mvc, "/value", "amount"));
        assertEquals("7:owner2:seed:/plain:seed", response(mvc, "/plain", "other"));
        assertEquals(2, owner.calls);
        assertThrows(NoSuchMethodException.class, () -> Owner.class.getDeclaredMethod("bind", WebDataBinder.class));
    }
    @Test void voidModelMethodWritesIntoSpringsActualModel() throws Throwable {
        var owner = new Owner(); var mvc = mvc(owner);
        publish(shape(VoidShape.class), Map.of("added", "voidImpl", "base", "base"));
        assertEquals("7:owner:seed", response(mvc, "/value", "amount")); assertEquals(1, owner.calls);
    }
    @Test void concreteGenericReturnMetadataAndModelValueArePreserved() throws Throwable {
        var owner = new Owner(); var mvc = mvc(owner);
        publish(shape(ListShape.class), Map.of("added", "listImpl"));
        assertEquals("7:[owner, list]:null", response(mvc, "/value", "amount"));
        var method = MvcBindingBridge.metadataClass(Owner.class).getDeclaredMethod("added");
        assertEquals("java.util.List<java.lang.String>", method.getGenericReturnType().getTypeName());
        assertEquals(1, owner.calls);
    }
    @Test void cachedMetadataFollowsDispatchRetargetWithoutRepublishing() throws Throwable {
        var mvc = mvc(new Owner()); publish(shape(Shape.class), BODIES);
        assertEquals("14:owner1:seed:/value:seed", response(mvc, "/value", "amount"));
        Class<?> schema = MvcBindingBridge.metadataClass(Owner.class);
        var type = MethodType.methodType(String.class, String.class, HttpServletRequest.class);
        DispatchTable.getOrCreate(Owner.class).retarget(Map.of(InjectedNames.siteKey("tag", InjectedNames.descHash(type.toMethodDescriptorString())),
                MethodHandles.lookup().findVirtual(Owner.class, "alternate", type)));
        assertEquals("14:updated:seed", response(mvc, "/value", "amount"));
        assertSame(schema, MvcBindingBridge.metadataClass(Owner.class));
    }
    @Test void removingAndRestoringMethodsChangesTheNextFreshSpringScan() throws Throwable {
        mvc(new Owner()); publish(shape(Shape.class), BODIES);
        assertEquals("14:owner1:seed:/value:seed", response(mvc(new Owner()), "/value", "amount"));
        publish(shape(Owner.class), Map.of());
        assertSame(Owner.class, MvcBindingBridge.metadataClass(Owner.class));
        assertEquals("7:null:seed", response(mvc(new Owner()), "/value", "amount"));
        publish(shape(Shape.class), BODIES);
        assertEquals("14:owner1:seed:/value:seed", response(mvc(new Owner()), "/value", "amount"));
    }
    @Test void proxyReceiverIsRefusedBeforeAnyCallbackBodyRuns() throws Throwable {
        var owner = new Owner(); mvc(owner); publish(shape(Shape.class), BODIES);
        var proxyFactory = new org.springframework.aop.framework.ProxyFactory(owner); proxyFactory.setProxyTargetClass(true);
        Object proxy = proxyFactory.getProxy();
        var method = MvcBindingBridge.metadataClass(Owner.class).getDeclaredMethod("bind", WebDataBinder.class);
        assertThrows(IllegalStateException.class, () -> MvcBindingBridge.receiver(proxy, method));
        assertEquals(0, owner.calls);
    }
    @Test void typeVariablesInterfacesAndSessionMetadataAreRefused() throws Throwable {
        mvc(new Owner());
        var generic = shape(Shape.class); generic.signature = "<T:Ljava/lang/Object;>Ljava/lang/Object;";
        assertThrows(IllegalStateException.class, () -> publish(generic, BODIES));
        var iface = shape(Shape.class); iface.interfaces.add("java/lang/Runnable");
        assertThrows(IllegalStateException.class, () -> publish(iface, BODIES));
        var methodGeneric = shape(Shape.class);
        methodGeneric.methods.stream().filter(m -> m.name.equals("tag")).findFirst().orElseThrow().signature =
                "(Ljava/lang/String;Ljavax/servlet/http/HttpServletRequest;)TT;";
        assertThrows(IllegalStateException.class, () -> publish(methodGeneric, BODIES));
        var session = shape(Shape.class); session.visibleAnnotations.add(new AnnotationNode(Type.getDescriptor(SessionAttributes.class)));
        assertThrows(IllegalStateException.class, () -> publish(session, BODIES));
        assertSame(Owner.class, MvcBindingBridge.metadataClass(Owner.class));
    }
    @Test void invalidBinderReturnAndAsyncModelAreRefusedBeforeInvocation() throws Throwable {
        mvc(new Owner());
        assertTrue(assertThrows(IllegalStateException.class, () -> publish(shape(InvalidBinderShape.class), Map.of("added", "invalidBinderImpl")))
                .getMessage().contains("must return void"));
        assertTrue(assertThrows(IllegalStateException.class, () -> publish(shape(AsyncShape.class), Map.of("added", "asyncImpl")))
                .getMessage().contains("asynchronous"));
    }
    @Test void extraAdviceAndStaticCallbacksCannotBypassTheirSemantics() throws Throwable {
        mvc(new Owner()); publish(shape(Shape.class), BODIES);
        var extra = shape(Shape.class);
        extra.methods.stream().filter(m -> m.name.equals("bind")).findFirst().orElseThrow().visibleAnnotations
                .add(new AnnotationNode("Lorg/springframework/transaction/annotation/Transactional;"));
        assertThrows(IllegalStateException.class, () -> publish(extra, BODIES));
        assertSame(Owner.class, MvcBindingBridge.metadataClass(Owner.class));
        var stat = shape(Shape.class); stat.methods.stream().filter(m -> m.name.equals("bind")).findFirst().orElseThrow().access |= Opcodes.ACC_STATIC;
        assertThrows(IllegalStateException.class, () -> publish(stat, BODIES));
    }
    @Test void requestMappedModelAttributesAreNotInvokedAsModelInitializers() throws Throwable {
        var mvc = mvc(new Owner());
        var node = shape(Shape.class);
        var endpoint = new MethodNode(Opcodes.ACC_PUBLIC, "endpoint", "()Ljava/lang/String;", null, null);
        endpoint.visibleAnnotations = new ArrayList<>(List.of(new AnnotationNode(Type.getDescriptor(ModelAttribute.class)),
                new AnnotationNode(Type.getDescriptor(GetMapping.class))));
        node.methods.add(endpoint);
        publish(node, BODIES);
        assertThrows(NoSuchMethodException.class, () -> MvcBindingBridge.metadataClass(Owner.class).getDeclaredMethod("endpoint"));
        assertEquals("14:owner1:seed:/value:seed", response(mvc, "/value", "amount"));
    }

    @Test void ordinaryMetadataAndReceiversPassThrough() throws Throwable {
        var owner = new Owner();
        assertSame(Owner.class, MvcBindingBridge.metadataClass(Owner.class));
        assertSame(owner, MvcBindingBridge.receiver(owner, Owner.class.getDeclaredMethod("base")));
    }
    @Test void incompatibleFrameworkBytecodeIsNotPartiallyHooked() throws Exception {
        byte[] bytes;
        try (var input = RequestMappingHandlerAdapter.class.getResourceAsStream("/" + MvcBindingTransformer.TARGET + ".class")) { bytes = input.readAllBytes(); }
        var node = new ClassNode(); new ClassReader(bytes).accept(node, 0);
        node.methods.removeIf(m -> m.name.equals("createInitBinderMethod"));
        var writer = new ClassWriter(0); node.accept(writer);
        assertNull(transformer.transform(Owner.class.getClassLoader(), MvcBindingTransformer.TARGET, null, null, writer.toByteArray()));
        assertNull(transformer.transform(Owner.class.getClassLoader(), "unrelated/Type", null, null, bytes));
    }
    private static MockMvc mvc(Owner owner) { return MockMvcBuilders.standaloneSetup(owner).build(); }
    private static String response(MockMvc mvc, String path, String param) throws Exception {
        var response = mvc.perform(get(path).param(param, "7")).andReturn().getResponse();
        assertEquals(200, response.getStatus(), response.getContentAsString()); return response.getContentAsString();
    }
    private static ClassNode shape(Class<?> type) throws Exception {
        var node = new ClassNode();
        try (var input = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
            new ClassReader(input.readAllBytes()).accept(node, 0);
        }
        node.name = Type.getInternalName(Owner.class); return node;
    }
    private static void publish(ClassNode node, Map<String,String> bodies) throws Throwable {
        var targets = new LinkedHashMap<String,MethodHandle>(); var lookup = MethodHandles.lookup();
        for (var m : node.methods) if (bodies.containsKey(m.name)) {
            var type = MethodType.fromMethodDescriptorString(m.desc, Owner.class.getClassLoader());
            targets.put(InjectedNames.siteKey(m.name, InjectedNames.descHash(m.desc)), lookup.findVirtual(Owner.class, bodies.get(m.name), type));
        }
        DispatchTable.getOrCreate(Owner.class).retarget(targets);
        var writer = new ClassWriter(0); node.accept(writer);
        AddedMvcBindingMethods.publish(Owner.class, writer.toByteArray(), lookup, targets);
    }
}
