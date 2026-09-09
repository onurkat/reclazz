/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.reload;

import com.onurkat.reclazz.bootstrap.DispatchTable;
import com.onurkat.reclazz.bootstrap.ExceptionHandlerBridge;
import com.onurkat.reclazz.bootstrap.InjectedNames;
import com.onurkat.reclazz.transform.ExceptionHandlerTransformer;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.springframework.http.*;
import org.springframework.mock.web.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.ExceptionHandlerExceptionResolver;
import javax.servlet.http.HttpServletRequest;
import java.lang.instrument.Instrumentation;
import java.lang.invoke.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AddedExceptionHandlersTest {
    static Instrumentation instrumentation;
    static ExceptionHandlerTransformer transformer;

    @BeforeAll static void hookActualSpring() throws Exception {
        instrumentation = ByteBuddyAgent.install();
        transformer = new ExceptionHandlerTransformer();
        instrumentation.addTransformer(transformer, true);
        instrumentation.retransformClasses(ExceptionHandlerExceptionResolver.class);
    }
    @AfterAll static void restoreSpring() throws Exception {
        instrumentation.removeTransformer(transformer);
        instrumentation.retransformClasses(ExceptionHandlerExceptionResolver.class);
    }
    @AfterEach void clearMetadata() {
        ExceptionHandlerBridge.replace(Owner.class, null, null, null);
    }

    @RestController public static class Owner {
        int calls;
        public String boom() { throw new IllegalArgumentException("bad"); }
        public String impl(IllegalArgumentException failure, HttpServletRequest request) {
            return ++calls + ":" + failure.getMessage() + ":" + request.getRequestURI();
        }
        public String generalImpl(RuntimeException failure) { return "root:" + failure.getMessage(); }
        public ResponseEntity<String> entityImpl(IllegalArgumentException failure) {
            return ResponseEntity.status(423).header("X-Selected", "entity").body("entity:" + failure.getMessage());
        }
        public java.util.concurrent.CompletableFuture<String> asyncImpl(IllegalArgumentException failure) {
            calls++; return java.util.concurrent.CompletableFuture.completedFuture("bad");
        }
    }
    @RestController static class SimpleShape {
        @ExceptionHandler @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
        public String handle(IllegalArgumentException failure, HttpServletRequest request) { return null; }
    }
    @RestController static class EntityShape {
        @ExceptionHandler(IllegalArgumentException.class)
        public ResponseEntity<String> handle(IllegalArgumentException failure) { return null; }
    }
    @RestController static class AmbiguousShape {
        @ExceptionHandler(IllegalArgumentException.class)
        public String handle(IllegalArgumentException failure, HttpServletRequest request) { return null; }
        @ExceptionHandler(IllegalArgumentException.class)
        public String general(RuntimeException failure) { return null; }
    }
    @RestController static class AsyncShape {
        @ExceptionHandler(IllegalArgumentException.class)
        public java.util.concurrent.CompletableFuture<String> handle(IllegalArgumentException failure) { return null; }
    }

    @Test void realSpringInfersTheExceptionResolvesRequestAndUsesStatusAndBodyAnnotations() throws Throwable {
        var resolver = resolver();
        assertTrue(ExceptionHandlerBridge.isHooked(ExceptionHandlerExceptionResolver.class));
        publish(shape(SimpleShape.class), Map.of("handle", "impl"));
        var owner = new Owner();
        var response = resolve(resolver, owner, new IllegalArgumentException("bad"));
        assertEquals(422, response.getStatus());
        assertEquals("1:bad:/boom", response.getContentAsString());
        assertEquals(1, owner.calls);
        assertEquals(Owner.class, Owner.class.getMethod("boom").getDeclaringClass());
        assertThrows(NoSuchMethodException.class, () -> Owner.class.getMethod("handle", IllegalArgumentException.class, HttpServletRequest.class));
    }

    @Test void causeMatchingUsesSpringsResolverAndExposesTheActualCauseArgument() throws Throwable {
        var resolver = resolver();
        publish(shape(SimpleShape.class), Map.of("handle", "impl"));
        assertEquals("1:cause:/boom", resolve(resolver, new Owner(),
                new RuntimeException("wrapper", new IllegalArgumentException("cause"))).getContentAsString());
    }

    @Test void concreteGenericResponseEntityKeepsStatusHeadersAndBody() throws Throwable {
        var resolver = resolver();
        publish(shape(EntityShape.class), Map.of("handle", "entityImpl"));
        var response = resolve(resolver, new Owner(), new IllegalArgumentException("bad"));
        assertEquals(423, response.getStatus());
        assertEquals("entity", response.getHeader("X-Selected"));
        assertEquals("entity:bad", response.getContentAsString());
        assertEquals("org.springframework.http.ResponseEntity<java.lang.String>",
                ExceptionHandlerBridge.metadataClass(Owner.class).getDeclaredMethods()[0].getGenericReturnType().getTypeName());
    }

    @Test void removalAndRestorationAreVisibleWhenSpringRebuildsItsCache() throws Throwable {
        var resolver = resolver();
        publish(shape(SimpleShape.class), Map.of("handle", "impl"));
        assertEquals(422, resolve(resolver, new Owner(), new IllegalArgumentException()).getStatus());
        publish(shape(Owner.class), Map.of());
        assertSame(Owner.class, ExceptionHandlerBridge.metadataClass(Owner.class));
        assertNull(resolver().resolveException(new MockHttpServletRequest(), new MockHttpServletResponse(),
                new HandlerMethod(new Owner(), Owner.class.getMethod("boom")), new IllegalArgumentException()));
        publish(shape(SimpleShape.class), Map.of("handle", "impl"));
        assertEquals(422, resolve(resolver(), new Owner(), new IllegalArgumentException()).getStatus());
    }

    @Test void proxyReceiverIsNotUnwrappedAndNoBodyRunsWithoutItsAdvice() throws Throwable {
        var resolver = resolver();
        publish(shape(SimpleShape.class), Map.of("handle", "impl"));
        var owner = new Owner();
        var factory = new org.springframework.aop.framework.ProxyFactory(owner);
        factory.setProxyTargetClass(true);
        Object proxy = factory.getProxy();
        var method = ExceptionHandlerBridge.metadataClass(Owner.class).getDeclaredMethods()[0];
        assertThrows(IllegalStateException.class, () -> ExceptionHandlerBridge.receiver(proxy, method));
        assertEquals(0, owner.calls);
    }

    @Test void ambiguousMappingIsRejectedBySpringAndClearsTheOldAdapter() throws Throwable {
        resolver();
        publish(shape(SimpleShape.class), Map.of("handle", "impl"));
        var failure = assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> publish(shape(AmbiguousShape.class), Map.of("handle", "impl", "general", "generalImpl")));
        assertInstanceOf(IllegalStateException.class, failure.getCause());
        assertTrue(failure.getCause().getMessage().contains("Ambiguous"));
        assertSame(Owner.class, ExceptionHandlerBridge.metadataClass(Owner.class));
    }

    @Test void asynchronousAndExtraAdviceMetadataAreExplicitlyRefused() throws Throwable {
        resolver();
        var async = assertThrows(IllegalStateException.class, () -> publish(shape(AsyncShape.class), Map.of("handle", "asyncImpl")));
        assertTrue(async.getMessage().contains("asynchronous"));
        var node = shape(SimpleShape.class);
        node.methods.stream().filter(m -> m.name.equals("handle")).findFirst().orElseThrow().visibleAnnotations
                .add(new AnnotationNode("Lorg/springframework/transaction/annotation/Transactional;"));
        assertTrue(assertThrows(IllegalStateException.class, () -> publish(node, Map.of("handle", "impl")))
                .getMessage().contains("unsupported handler annotation"));
    }

    @Test void classTypeVariablesAndMethodTypeVariablesAreRefused() throws Throwable {
        resolver();
        var node = shape(SimpleShape.class);
        node.signature = "<T:Ljava/lang/Object;>Ljava/lang/Object;";
        assertThrows(IllegalStateException.class, () -> publish(node, Map.of("handle", "impl")));
        var methodNode = shape(SimpleShape.class);
        methodNode.methods.stream().filter(m -> m.name.equals("handle")).findFirst().orElseThrow().signature =
                "<T:Ljava/lang/Object;>(Ljava/lang/IllegalArgumentException;Ljavax/servlet/http/HttpServletRequest;)Ljava/lang/String;";
        assertThrows(IllegalStateException.class, () -> publish(methodNode, Map.of("handle", "impl")));
        methodNode.methods.stream().filter(m -> m.name.equals("handle")).findFirst().orElseThrow().signature =
                "(Ljava/lang/IllegalArgumentException;Ljavax/servlet/http/HttpServletRequest;)TT;";
        assertThrows(IllegalStateException.class, () -> publish(methodNode, Map.of("handle", "impl")));
    }

    @Test void ordinaryMetadataAndReceiversArePassedThrough() throws Throwable {
        var owner = new Owner();
        assertSame(Owner.class, ExceptionHandlerBridge.metadataClass(Owner.class));
        assertSame(owner, ExceptionHandlerBridge.receiver(owner, Owner.class.getMethod("boom")));
    }

    @Test void classResponseStatusIsAvailableToTheActualReturnValuePipeline() throws Throwable {
        var resolver = resolver();
        var node = shape(SimpleShape.class);
        var method = node.methods.stream().filter(m -> m.name.equals("handle")).findFirst().orElseThrow();
        method.visibleAnnotations.removeIf(a -> a.desc.equals(Type.getDescriptor(ResponseStatus.class)));
        var status = new AnnotationNode(Type.getDescriptor(ResponseStatus.class));
        status.values = new ArrayList<>(List.of("value", new String[]{Type.getDescriptor(HttpStatus.class), "CONFLICT"}));
        node.visibleAnnotations.add(status);
        publish(node, Map.of("handle", "impl"));
        assertEquals(409, resolve(resolver, new Owner(), new IllegalArgumentException("bad")).getStatus());
    }

    @Test void staticAndInterfaceMetadataAreRefusedBeforePublishing() throws Throwable {
        resolver();
        var node = shape(SimpleShape.class);
        node.methods.stream().filter(m -> m.name.equals("handle")).findFirst().orElseThrow().access |= Opcodes.ACC_STATIC;
        assertThrows(IllegalStateException.class, () -> publish(node, Map.of("handle", "impl")));
        var withInterface = shape(SimpleShape.class);
        withInterface.interfaces.add("java/lang/Runnable");
        assertThrows(IllegalStateException.class, () -> publish(withInterface, Map.of("handle", "impl")));
        assertSame(Owner.class, ExceptionHandlerBridge.metadataClass(Owner.class));
    }

    private static ExceptionHandlerExceptionResolver resolver() {
        var resolver = new ExceptionHandlerExceptionResolver(); resolver.afterPropertiesSet(); return resolver;
    }
    private static MockHttpServletResponse resolve(ExceptionHandlerExceptionResolver resolver, Owner owner, Exception failure) throws Exception {
        var response = new MockHttpServletResponse();
        assertNotNull(resolver.resolveException(new MockHttpServletRequest("GET", "/boom"), response,
                new HandlerMethod(owner, Owner.class.getMethod("boom")), failure));
        return response;
    }
    private static ClassNode shape(Class<?> type) throws Exception {
        var node = new ClassNode();
        try (var input = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
            new ClassReader(input.readAllBytes()).accept(node, 0);
        }
        node.name = Type.getInternalName(Owner.class);
        return node;
    }
    private static void publish(ClassNode node, Map<String, String> bodies) throws Throwable {
        var targets = new LinkedHashMap<String, MethodHandle>();
        var lookup = MethodHandles.privateLookupIn(Owner.class, MethodHandles.lookup());
        for (var m : node.methods) if (bodies.containsKey(m.name)) {
            var type = MethodType.fromMethodDescriptorString(m.desc, Owner.class.getClassLoader());
            targets.put(InjectedNames.siteKey(m.name, InjectedNames.descHash(m.desc)), lookup.findVirtual(Owner.class, bodies.get(m.name), type));
        }
        DispatchTable.getOrCreate(Owner.class).retarget(targets);
        var writer = new ClassWriter(0); node.accept(writer);
        AddedExceptionHandlers.publish(Owner.class, writer.toByteArray(), lookup, targets);
    }
}
