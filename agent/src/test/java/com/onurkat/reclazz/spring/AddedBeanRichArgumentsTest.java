/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.bootstrap.LookupCapture;
import com.onurkat.reclazz.platform.PlatformContext;
import com.onurkat.reclazz.ui.RestartLedger;
import org.junit.jupiter.api.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.springframework.beans.factory.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.context.annotation.*;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class AddedBeanRichArgumentsTest {
    public record Wire(String name, int order) implements org.springframework.core.Ordered {
        public int getOrder() { return order; }
    }
    public interface Port<T> { T value(); }
    public interface CustomProvider extends ObjectProvider<Wire> { }
    public static class Text implements Port<String> { public String value() { return "text"; } }
    public static class NumberPort implements Port<Integer> { public Integer value() { return 42; } }
    public static class Box implements AutoCloseable {
        final Object value; int closed;
        Box(Object value) { this.value=value; }
        public void close() { closed++; }
    }
    public record Graph(List<Wire> list, Set<Wire> set, Collection<Wire> collection,
                        Map<String,Wire> map, Wire[] array) { }
    @Configuration(proxyBeanMethods=false)
    public static class Config {
        int calls;
        public Graph graph(List<Wire> list, Set<Wire> set, Collection<Wire> collection, Map<String,Wire> map, Wire[] array) {
            return new Graph(list,set,collection,map,array);
        }
        public Box selected(@Qualifier("group") List<Wire> wires) { return new Box(wires); }
        public Box generic(List<Port<String>> ports) { return new Box(ports); }
        public Box optional(Optional<Wire> value) { return new Box(value); }
        public Box provider(ObjectProvider<Wire> value) { return new Box(value); }
        public Box factory(ObjectFactory<Wire> value) { return new Box(value); }
        public Box values(@Value("true") boolean a, @Value("7") byte b, @Value("Z") char c,
                          @Value("8") short d, @Value("9") int e, @Value("10") long f,
                          @Value("1.25") float g, @Value("2.5") double h, @Value("#{null}") String nil) {
            calls++; return new Box(List.of(a,b,c,d,e,f,g,h,nil==null));
        }
        public Box invalid(@Value("${count:bad}") int value) { calls++; return new Box(value); }
        public Box wide(@Value("2") long first, @Value("3") double second, Wire blue) { return new Box(blue); }
        public static Box wideStatic(@Value("2") long first, @Value("3") double second, Wire blue) { return new Box(blue); }
        public Box required(List<Wire> wires) { return new Box(wires); }
        public static Box staticRich(List<Wire> list, Wire[] array, Optional<Wire> optional, ObjectFactory<Wire> factory) {
            return new Box(List.of(list,Arrays.asList(array),optional,factory));
        }
        public Box primitive(int value) { return new Box(value); }
        public Box primitiveArray(int[] value) { return new Box(value); }
        public Box matrix(Wire[][] value) { return new Box(value); }
        public Box concreteCollection(ArrayList<Wire> value) { return new Box(value); }
        public Box customProvider(CustomProvider value) { return new Box(value); }
        public Box raw(List value) { return new Box(value); }
        public Box wrongMap(Map<Integer,Wire> value) { return new Box(value); }
        public Box wildcard(List<? extends Wire> value) { return new Box(value); }
        public <T> Box variable(T value) { return new Box(value); }
        public List<Wire> genericReturn(Wire value) { return List.of(value); }
    }
    @Configuration(proxyBeanMethods=false)
    static class Native {
        @Bean Graph nativeGraph(List<Wire> list, Set<Wire> set, Collection<Wire> collection, Map<String,Wire> map, Wire[] array) {
            return new Graph(list,set,collection,map,array);
        }
    }
    @AfterEach void clear() { RestartLedger.clear(); }

    @Test void containersMatchNativeFactoryOrderingAndEmptyFallbacks() throws Exception {
        for (boolean populated : List.of(false,true)) {
            try (var scope = new Scope(context -> {
                if (populated) {
                    context.registerBean("blue",Wire.class,()->new Wire("blue",20));
                    context.registerBean("red",Wire.class,()->new Wire("red",10));
                }
                context.register(Native.class);
            })) {
                assertTrue(scope.reload(bytes("graph")),RestartLedger.digest().toString());
                Graph actual=scope.context.getBean("graph",Graph.class);
                Graph nativeValue=scope.context.getBean("nativeGraph",Graph.class);
                assertEquals(nativeValue.list(),actual.list()); assertEquals(nativeValue.set(),actual.set());
                assertEquals(nativeValue.collection().getClass(),actual.collection().getClass());
                assertEquals(new ArrayList<>(nativeValue.collection()),new ArrayList<>(actual.collection()));
                assertEquals(nativeValue.map(),actual.map());
                assertArrayEquals(nativeValue.array(),actual.array());
                assertEquals(populated ? List.of("red","blue") : List.of(),actual.list().stream().map(Wire::name).toList());
            }
        }
    }

    @Test void genericElementTypesAndQualifierFilterCandidates() throws Exception {
        try (var scope = new Scope()) {
            scope.context.registerBean("text",Text.class); scope.context.registerBean("number",NumberPort.class);
            scope.context.registerBean("red",Wire.class,()->new Wire("red",10));
            scope.context.registerBean("blue",Wire.class,()->new Wire("blue",20), definition ->
                    ((org.springframework.beans.factory.support.AbstractBeanDefinition) definition).addQualifier(
                            new org.springframework.beans.factory.support.AutowireCandidateQualifier(Qualifier.class,"group")));
            assertTrue(scope.reload(bytes("generic","selected")),RestartLedger.digest().toString());
            assertEquals(List.of(scope.context.getBean("text")),scope.box("generic").value);
            assertEquals(List.of(scope.context.getBean("blue")),scope.box("selected").value);
        }
    }

    @Test void optionalAbsencePresenceAndAmbiguityUseSpring() throws Exception {
        try (var scope = new Scope()) {
            assertTrue(scope.reload(bytes("optional"))); assertEquals(Optional.empty(),scope.box("optional").value);
            Wire wire=new Wire("one",1); scope.context.getBeanFactory().registerSingleton("wire",wire);
            assertTrue(scope.reload(bytes("optional"))); assertEquals(Optional.of(wire),scope.box("optional").value);
            scope.context.registerBean("other",Wire.class,()->new Wire("other",2));
            assertFalse(scope.reload(bytes("optional"))); assertFalse(scope.context.containsBeanDefinition("optional"));
            assertTrue(RestartLedger.digest().stream().anyMatch(s->s.contains("NoUniqueBeanDefinitionException")));
        }
    }

    @Test void nativeProvidersRemainLazyAndLookUpTheCurrentTarget() throws Exception {
        try (var scope = new Scope()) {
            var made=new AtomicInteger();
            scope.context.registerBean("wire",Wire.class,()->new Wire("wire"+made.incrementAndGet(),1),d->d.setLazyInit(true));
            assertTrue(scope.reload(bytes("provider","factory")),RestartLedger.digest().toString());
            assertEquals(0,made.get());
            ObjectProvider<?> provider=(ObjectProvider<?>) scope.box("provider").value;
            ObjectFactory<?> factory=(ObjectFactory<?>) scope.box("factory").value;
            Object first=provider.getObject(); assertSame(first,factory.getObject()); assertEquals(1,made.get());
            scope.context.getDefaultListableBeanFactory().destroySingleton("wire");
            Object second=provider.getObject(); assertNotSame(first,second); assertSame(second,factory.getObject());
            scope.context.removeBeanDefinition("wire"); assertNull(provider.getIfAvailable());
            assertThrows(NoSuchBeanDefinitionException.class,factory::getObject);
        }
    }

    @Test void providerDoesNotPutHiddenMetadataInTheGlobalNameDiscoverer() throws Exception {
        try (var scope = new Scope()) {
            scope.context.getDefaultListableBeanFactory().setParameterNameDiscoverer(new org.springframework.core.ParameterNameDiscoverer() {
                public String[] getParameterNames(java.lang.reflect.Method method) {
                    assertFalse(method.getDeclaringClass().isHidden()); return null;
                }
                public String[] getParameterNames(java.lang.reflect.Constructor<?> constructor) { return null; }
            });
            scope.context.registerBean("first",Wire.class,()->new Wire("first",1));
            scope.context.registerBean("second",Wire.class,()->new Wire("second",2));
            assertTrue(scope.reload(bytes("provider")),RestartLedger.digest().toString());
            ObjectProvider<?> provider=(ObjectProvider<?>) scope.box("provider").value;
            assertNull(provider.getIfUnique()); assertEquals(2,provider.stream().count());
            assertThrows(NoUniqueBeanDefinitionException.class,provider::getObject);
            assertEquals(List.of("first","second"),provider.orderedStream().map(v->((Wire)v).name()).toList());
        }
    }

    @Test void everyPrimitiveAndAnExplicitNullReachTheFactoryBody() throws Exception {
        try (var scope = new Scope()) {
            assertTrue(scope.reload(bytes("values")),RestartLedger.digest().toString());
            assertEquals(List.of(true,(byte)7,'Z',(short)8,9,10L,1.25F,2.5D,true),scope.box("values").value);
            assertEquals(1,scope.context.getBean(Config.class).calls);
        }
    }

    @Test void invalidValueDoesNotCallBodyAndCanRecoverOnAnotherSave() throws Exception {
        try (var scope = new Scope()) {
            assertFalse(scope.reload(bytes("invalid"))); assertEquals(0,scope.context.getBean(Config.class).calls);
            assertFalse(scope.context.containsBeanDefinition("invalid"));
            scope.context.getEnvironment().getPropertySources().addFirst(
                    new org.springframework.core.env.MapPropertySource("values",Map.of("count","12")));
            assertTrue(scope.reload(bytes("invalid")),RestartLedger.digest().toString());
            assertEquals(12,scope.box("invalid").value); assertEquals(1,scope.context.getBean(Config.class).calls);
        }
    }

    @Test void debugNamesAfterWideSlotsWorkForInstanceAndStaticFactories() throws Exception {
        try (var scope = new Scope()) {
            scope.context.registerBean("blue",Wire.class,()->new Wire("blue",1));
            scope.context.registerBean("red",Wire.class,()->new Wire("red",2));
            ClassNode source=read(bytes("wide","wideStatic"));
            source.methods.stream().filter(m->m.name.startsWith("wide")).forEach(m->m.parameters=null);
            assertTrue(scope.reload(write(source)),RestartLedger.digest().toString());
            assertSame(scope.context.getBean("blue"),scope.box("wide").value);
            assertSame(scope.context.getBean("blue"),scope.box("wideStatic").value);
        }
    }

    @Test void staticFactoriesResolveGenericContainersAndProvidersToo() throws Exception {
        try (var scope = new Scope()) {
            scope.context.registerBean("blue",Wire.class,()->new Wire("blue",1),d->d.setPrimary(true));
            scope.context.registerBean("red",Wire.class,()->new Wire("red",2));
            assertTrue(scope.reload(bytes("staticRich")),RestartLedger.digest().toString());
            List<?> arguments=(List<?>) scope.box("staticRich").value;
            Object blue=scope.context.getBean("blue"),red=scope.context.getBean("red");
            assertEquals(List.of(blue,red),arguments.get(0));
            assertEquals(List.of(blue,red),arguments.get(1));
            assertEquals(Optional.of(blue),arguments.get(2));
            assertSame(blue,((ObjectFactory<?>)arguments.get(3)).getObject());
        }
    }

    @Test void eagerContainerDependenciesKeepDestructionEdgesForCachedTargets() throws Exception {
        try (var scope = new Scope()) {
            scope.context.registerBean("wire",Wire.class,()->new Wire("wire",1)); scope.context.getBean("wire");
            assertTrue(scope.reload(bytes("required"))); Box old=scope.box("required");
            assertTrue(Arrays.asList(scope.context.getBeanFactory().getDependentBeans("wire")).contains("required"));
            scope.context.getDefaultListableBeanFactory().destroySingleton("wire"); assertEquals(1,old.closed);
            Box current=scope.box("required"); assertNotSame(old,current);
            assertEquals(List.of(scope.context.getBean("wire")),current.value);
        }
    }

    @Test void unsupportedGenericShapesStayNamedAndUnregistered() throws Exception {
        try (var scope = new Scope()) {
            for(String name:List.of("primitive","primitiveArray","matrix","concreteCollection","customProvider",
                    "raw","wrongMap","wildcard","variable","genericReturn")) {
                assertFalse(scope.reload(bytes(name)),name); assertFalse(scope.context.containsBeanDefinition(name),name);
            }
        }
    }

    private static final class Scope implements AutoCloseable {
        final AnnotationConfigApplicationContext context=new AnnotationConfigApplicationContext();
        final SpringAddedBeanReloader reloader;
        Scope() throws Exception {
            this(context -> { });
        }
        Scope(java.util.function.Consumer<AnnotationConfigApplicationContext> setup) throws Exception {
            LookupCapture.store(Config.class,MethodHandles.privateLookupIn(Config.class,MethodHandles.lookup()));
            context.registerBean("config",Config.class); setup.accept(context); context.refresh();
            PlatformContext platform=(PlatformContext) Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{PlatformContext.class},
                    (p,m,a)->m.getName().equals("getAllApplicationContexts") ? List.of(context) : null);
            reloader=new SpringAddedBeanReloader(platform);
        }
        Box box(String name) { return context.getBean(name,Box.class); }
        boolean reload(byte[] bytes) {
            Set<String> added=new HashSet<>(); for(var method:read(bytes).methods) added.add(method.name+":"+method.desc);
            return reloader.reloadBeanMethods(Config.class,added,bytes);
        }
        public void close() { context.close(); }
    }
    private static byte[] bytes(String... names) throws Exception {
        try(var in=Config.class.getResourceAsStream("/"+Config.class.getName().replace('.','/')+".class")) {
            ClassNode source=read(Objects.requireNonNull(in).readAllBytes());
            for(var method:source.methods) if(Arrays.asList(names).contains(method.name))
                method.visibleAnnotations=new ArrayList<>(List.of(new AnnotationNode(AddedBeanAdapter.BEAN)));
            return write(source);
        }
    }
    private static ClassNode read(byte[] bytes) { ClassNode source=new ClassNode(); new ClassReader(bytes).accept(source,0); return source; }
    private static byte[] write(ClassNode source) { ClassWriter writer=new ClassWriter(0); source.accept(writer); return writer.toByteArray(); }
}
