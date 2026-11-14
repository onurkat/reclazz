/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.reload;

import com.fasterxml.jackson.annotation.*;
import com.onurkat.reclazz.bootstrap.*;
import com.onurkat.reclazz.platform.ApplicationContextHolder;
import com.onurkat.reclazz.transform.JacksonAccessorTransformer;
import java.lang.invoke.*;
import java.lang.reflect.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.objectweb.asm.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Real Jackson3 isolated from Jackson2, plus the two namespaces coexisting. */
class JacksonThreeAddedGetterTest {
    public static class Owner { public String getName() { return "original"; } public static MethodHandles.Lookup lookup() { return MethodHandles.lookup(); } }
    static class NonPublicOwner { public String getName() { return "original"; } public static MethodHandles.Lookup lookup() { return MethodHandles.lookup(); } }
    public static class Added { public String getAdded() { return null; } }
    public static class Renamed { @JsonProperty("renamed") public String getAdded() { return null; } }
    public static class Ignored { @JsonIgnore public String getAdded() { return null; } }
    public static class Private { @JsonProperty("private_name") private String named() { return null; } }
    public static class Generic { public List<Map<String, String>> getNestedValues() { return null; } }
    public static class Throwing { public String getFailure() { return null; } }
    public static class Empty { }
    public enum Choice { FIRST, SECOND }
    private List<Object> previous;
    private List<Object> registry() throws Exception {
        var f=JacksonMapperRegistry.class.getDeclaredField("mappers"); f.setAccessible(true);
        @SuppressWarnings("unchecked") var entries=(List<Object>)f.get(null); return entries;
    }
    @BeforeEach void isolate() throws Exception {
        try { Class.forName("tools.jackson.databind.json.JsonMapper"); }
        catch (ClassNotFoundException absent) { assumeTrue(false,"Jackson3 belongs to the Seven graph"); }
        previous=new ArrayList<>(registry()); registry().clear(); ApplicationContextHolder.clear();
    }
    @AfterEach void restore() throws Exception {
        if(previous!=null) { registry().clear(); registry().addAll(previous); }
        ApplicationContextHolder.clear();
    }
    private static byte[] bytes(Class<?> type) throws Exception {
        try(var in=type.getResourceAsStream("/"+type.getName().replace('.','/')+".class")) { return Objects.requireNonNull(in).readAllBytes(); }
    }
    private static final class Loader extends ClassLoader {
        final boolean pure;
        Loader(boolean pure) { super(JacksonThreeAddedGetterTest.class.getClassLoader()); this.pure=pure; }
        @Override protected Class<?> loadClass(String name,boolean resolve) throws ClassNotFoundException {
            synchronized(getClassLoadingLock(name)) {
                if(pure && name.startsWith("com.fasterxml.jackson.databind.")) throw new ClassNotFoundException(name);
                if(!name.startsWith("tools.jackson.databind.") && !name.equals(Owner.class.getName()) && !name.equals(NonPublicOwner.class.getName())) return super.loadClass(name,resolve);
                Class<?> type=findLoadedClass(name);
                if(type==null) {
                    String internal=name.replace('.','/');
                    try(var in=getParent().getResourceAsStream(internal+".class")) {
                        byte[] original=Objects.requireNonNull(in).readAllBytes();
                        byte[] transformed=new JacksonAccessorTransformer().transform(this,internal,null,null,original);
                        byte[] value=transformed==null?original:transformed;
                        type=defineClass(name,value,0,value.length);
                    } catch(Exception failure) { throw new ClassNotFoundException(name,failure); }
                }
                if(resolve)resolveClass(type);return type;
            }
        }
        Class<?> countingMapper() {
            var w=new ClassWriter(ClassWriter.COMPUTE_MAXS);
            w.visit(Opcodes.V17,Opcodes.ACC_PUBLIC,"com/example/CountingJsonMapper",null,"tools/jackson/databind/json/JsonMapper",null);
            w.visitField(Opcodes.ACC_PUBLIC,"calls","I",null,null).visitEnd();
            var c=w.visitMethod(Opcodes.ACC_PUBLIC,"<init>","()V",null,null);c.visitCode();c.visitVarInsn(Opcodes.ALOAD,0);c.visitMethodInsn(Opcodes.INVOKESPECIAL,"tools/jackson/databind/json/JsonMapper","<init>","()V",false);c.visitInsn(Opcodes.RETURN);c.visitMaxs(0,0);c.visitEnd();
            var m=w.visitMethod(Opcodes.ACC_PUBLIC,"clearCaches","()V",null,null);m.visitCode();m.visitVarInsn(Opcodes.ALOAD,0);m.visitInsn(Opcodes.DUP);m.visitFieldInsn(Opcodes.GETFIELD,"com/example/CountingJsonMapper","calls","I");m.visitInsn(Opcodes.ICONST_1);m.visitInsn(Opcodes.IADD);m.visitFieldInsn(Opcodes.PUTFIELD,"com/example/CountingJsonMapper","calls","I");m.visitVarInsn(Opcodes.ALOAD,0);m.visitMethodInsn(Opcodes.INVOKESPECIAL,"tools/jackson/databind/json/JsonMapper","clearCaches","()V",false);m.visitInsn(Opcodes.RETURN);m.visitMaxs(0,0);m.visitEnd();w.visitEnd();
            byte[] value=w.toByteArray();return defineClass("com.example.CountingJsonMapper",value,0,value.length);
        }
    }
    private static final class Fixture implements AutoCloseable {
        final Loader loader; final Class<?> owner; final Object bean;
        final IllegalStateException getterFailure=new IllegalStateException("getter-failed");
        Fixture(boolean pure) throws Exception { this(pure,Owner.class); }
        Fixture(boolean pure,Class<?> ownerType) throws Exception {
            loader=new Loader(pure);owner=loader.loadClass(ownerType.getName());
            var ctor=owner.getDeclaredConstructor();ctor.setAccessible(true);bean=ctor.newInstance();
        }
        Object builder() throws Exception {return loader.loadClass("tools.jackson.databind.json.JsonMapper").getMethod("builder").invoke(null);}
        Object mapper() throws Exception {Object b=builder();return b.getClass().getMethod("build").invoke(b);}
        void publish(Class<?> shape,Map<String,Object> values) throws Exception {
            var targets=new LinkedHashMap<String,MethodHandle>();
            for(Method method:shape.getDeclaredMethods()) {
                MethodHandle target;
                if(method.getName().equals("getFailure")) target=MethodHandles.dropArguments(MethodHandles.throwException(String.class,IllegalStateException.class).bindTo(getterFailure),0,owner);
                else target=MethodHandles.dropArguments(MethodHandles.constant(method.getReturnType(),values.get(method.getName())),0,owner);
                targets.put(InjectedNames.siteKey(method.getName(),InjectedNames.descHash(org.objectweb.asm.Type.getMethodDescriptor(method))),target);
            }
            var lookupMethod=owner.getMethod("lookup");lookupMethod.setAccessible(true);
            var lookup=(MethodHandles.Lookup)lookupMethod.invoke(null);DispatchTable.retargetAll(owner,lookup,targets);
            Set<String> covered=JacksonAddedGetters.publish(owner,bytes(shape),lookup,targets);
            if(shape!=Empty.class) assertFalse(covered.isEmpty(),"real Jackson3 metadata must be published");
        }
        String json(Object mapper) throws Exception {return (String)mapper.getClass().getMethod("writeValueAsString",Object.class).invoke(mapper,bean);}
        void flush(Object mapper) throws Exception {var method=JacksonEnumCaches.class.getDeclaredMethod("flushMapper",Object.class);method.setAccessible(true);assertEquals(true,method.invoke(null,mapper));}
        @Override public void close() {JacksonBridge.replace(owner,null,Map.of());}
    }
    private static Map<?,?> parsed(String json) throws Exception {return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json,Map.class);}
    private static Object read(Object target,String field) throws Exception {
        for(Class<?> c=target.getClass();c!=null;c=c.getSuperclass())try{var f=c.getDeclaredField(field);f.setAccessible(true);return f.get(target);}catch(NoSuchFieldException next){}
        throw new NoSuchFieldException(field);
    }
    @Test void additionRenameIgnoreRemovalRestoreAndPlainReflection() throws Exception {
        for(boolean pure:new boolean[]{true,false})try(var f=new Fixture(pure)) {
            if(pure)assertThrows(ClassNotFoundException.class,()->f.loader.loadClass("com.fasterxml.jackson.databind.ObjectMapper"));
            else assertNotNull(f.loader.loadClass("com.fasterxml.jackson.databind.ObjectMapper"));
            Object mapper=f.mapper();assertEquals(Map.of("name","original"),parsed(f.json(mapper)));
            for(Class<?> shape:List.of(Added.class,Renamed.class,Ignored.class,Empty.class,Added.class)) {
                f.publish(shape,Map.of("getAdded","live"));f.flush(mapper);var result=parsed(f.json(mapper));
                if(shape==Added.class)assertEquals("live",result.get("added"));
                else if(shape==Renamed.class) {assertEquals("live",result.get("renamed"));assertFalse(result.containsKey("added"));}
                else assertEquals(Map.of("name","original"),result);
                assertThrows(NoSuchMethodException.class,()->f.owner.getDeclaredMethod("getAdded"));
            }
        }
    }
    @Test void retainedWriterUsesNewBodiesWhileFreshWriterGetsNewShape() throws Exception {
        for(boolean pure:new boolean[]{true,false})try(var f=new Fixture(pure)) {
            f.publish(Added.class,Map.of("getAdded","one"));Object mapper=f.mapper();Object writer=mapper.getClass().getMethod("writerFor",Class.class).invoke(mapper,f.owner);
            assertEquals("one",parsed(f.json(writer)).get("added"));Object prefetch=read(writer,"_prefetch");
            f.publish(Added.class,Map.of("getAdded","two"));f.flush(mapper);
            assertEquals("two",parsed(f.json(writer)).get("added"));
            f.publish(Renamed.class,Map.of("getAdded","three"));f.flush(mapper);
            assertSame(prefetch,read(writer,"_prefetch"));assertTrue(parsed(f.json(writer)).containsKey("added"));
            Object fresh=mapper.getClass().getMethod("writerFor",Class.class).invoke(mapper,f.owner);
            assertEquals("three",parsed(f.json(fresh)).get("renamed"));
        }
    }
    @Test void snakeNamingAndGenericNestedCollectionUseNativePolicy() throws Exception {
        for(boolean pure:new boolean[]{true,false})try(var f=new Fixture(pure)) {
            f.publish(Generic.class,Map.of("getNestedValues",List.of(Map.of("value","nested"))));
            Object b=f.builder();Class<?> naming=f.loader.loadClass("tools.jackson.databind.PropertyNamingStrategy");Object snake=f.loader.loadClass("tools.jackson.databind.PropertyNamingStrategies").getField("SNAKE_CASE").get(null);
            b.getClass().getMethod("propertyNamingStrategy",naming).invoke(b,snake);Object mapper=b.getClass().getMethod("build").invoke(b);
            assertEquals(List.of(Map.of("value","nested")),parsed(f.json(mapper)).get("nested_values"));
        }
    }
    @Test void privateAccessRespectsDisabledOverride() throws Exception {
        for(boolean pure:new boolean[]{true,false})try(var f=new Fixture(pure)) {
            f.publish(Private.class,Map.of("named","private"));assertEquals("private",parsed(f.json(f.mapper())).get("private_name"));
            Object b=f.builder();Class<?> feature=f.loader.loadClass("tools.jackson.databind.MapperFeature");Object list=Array.newInstance(feature,1);Array.set(list,0,feature.getField("CAN_OVERRIDE_ACCESS_MODIFIERS").get(null));
            b.getClass().getMethod("disable",list.getClass()).invoke(b,list);Object restricted=b.getClass().getMethod("build").invoke(b);
            assertThrows(InvocationTargetException.class,()->f.json(restricted));
        }
    }
    @Test void getterExceptionRetainsRootCauseAndPath() throws Exception {
        for(boolean pure:new boolean[]{true,false})try(var f=new Fixture(pure)) {
            f.publish(Throwing.class,Map.of());Object mapper=f.mapper();var error=assertThrows(InvocationTargetException.class,()->f.json(mapper));assertTrue(error.getCause().getMessage().contains("failure"),error.toString());
            Throwable root=error;while(root.getCause()!=null)root=root.getCause();assertInstanceOf(IllegalStateException.class,root);assertEquals("getter-failed",root.getMessage());
        }
    }
    private static MethodHandle unreflect(MethodHandles.Lookup lookup,Method method) throws Throwable {
        try { return (MethodHandle)JacksonBridge.class.getMethod("unreflect",MethodHandles.Lookup.class,Method.class).invoke(null,lookup,method); }
        catch(InvocationTargetException failure) { throw failure.getCause(); }
    }
    private static Method metadata(Fixture f,String name) {
        return Arrays.stream(JacksonBridge.getDeclaredMethods(f.owner)).filter(m->m.getName().equals(name)).findFirst().orElseThrow();
    }
    @Test void unreflectOfOriginalMemberIsNative() throws Throwable {
        Method publicMethod=Owner.class.getMethod("getName");
        var lookup=MethodHandles.lookup();var nativeHandle=lookup.unreflect(publicMethod);var routed=unreflect(lookup,publicMethod);
        assertEquals(nativeHandle.type(),routed.type());assertEquals(nativeHandle.invoke(new Owner()),routed.invoke(new Owner()));
        Method inaccessible=Private.class.getDeclaredMethod("named");
        var restricted=MethodHandles.publicLookup();
        assertThrows(IllegalAccessException.class,()->restricted.unreflect(inaccessible));
        assertThrows(IllegalAccessException.class,()->unreflect(restricted,inaccessible));
    }
    @Test void addedGetterRestrictedLookupRefused() throws Exception {
        for(boolean pure:new boolean[]{true,false})try(var f=new Fixture(pure)) {
            f.publish(Added.class,Map.of("getAdded","value"));Method m=metadata(f,"getAdded");
            var restricted=MethodHandles.lookup().dropLookupMode(MethodHandles.Lookup.PUBLIC);
            assertThrows(IllegalAccessException.class,()->restricted.unreflect(m));
            assertThrows(IllegalAccessException.class,()->unreflect(restricted,m));
        }
    }
    @Test void addedGetterNonPublicOwnerOverrideDisabledRefused() throws Exception {
        for(boolean pure:new boolean[]{true,false})try(var f=new Fixture(pure,NonPublicOwner.class)) {
            f.publish(Added.class,Map.of("getAdded","value"));Method m=metadata(f,"getAdded");
            assertTrue(Modifier.isPublic(m.getDeclaringClass().getModifiers()),"carrier visibility differs from real owner");
            assertFalse(Modifier.isPublic(f.owner.getModifiers()));
            assertThrows(IllegalAccessException.class,()->unreflect(MethodHandles.lookup(),m));
        }
    }
    @Test void createdHandleKeepsAccessAfterOverrideCleared() throws Throwable {
        for(boolean pure:new boolean[]{true,false})try(var f=new Fixture(pure)) {
            f.publish(Private.class,Map.of("named","one"));Method m=metadata(f,"named");m.setAccessible(true);
            MethodHandle handle=unreflect(MethodHandles.publicLookup(),m);m.setAccessible(false);
            assertEquals(MethodType.methodType(String.class,f.owner),handle.type());assertEquals("one",handle.invoke(f.bean));
            f.publish(Private.class,Map.of("named","two"));assertEquals("two",handle.invoke(f.bean));
            assertThrows(IllegalAccessException.class,()->unreflect(MethodHandles.publicLookup(),m));
        }
    }
    @Test void addedGetterHandleThrowsGetterExceptionUnwrapped() throws Throwable {
        for(boolean pure:new boolean[]{true,false})try(var f=new Fixture(pure)) {
            f.publish(Throwing.class,Map.of());Method m=metadata(f,"getFailure");m.setAccessible(true);
            MethodHandle handle=unreflect(MethodHandles.lookup(),m);
            var error=assertThrows(IllegalStateException.class,()->handle.invoke(f.bean));assertSame(f.getterFailure,error);assertEquals("getter-failed",error.getMessage());assertNull(error.getCause());
            assertThrows(ClassCastException.class,()->handle.invoke(new Object()));
            assertThrows(NullPointerException.class,()->handle.invoke((Object)null));
        }
    }
    public static class MapperContext {
        final Object mapper;public MapperContext(Object mapper){this.mapper=mapper;}
        public Map<String,Object> getBeansOfType(Class<?> type){return type.isInstance(mapper)?Map.of("a",mapper,"b",mapper):Map.of();}
    }
    @Test void realCachesClearAndMapperDiscoveryDeduplicatesIdentity() throws Exception {
        for(boolean pure:new boolean[]{true,false})try(var f=new Fixture(pure)) {
            Object mapper=f.loader.countingMapper().getConstructor().newInstance();f.json(mapper);
            mapper.getClass().getMethod("readValue",String.class,Class.class).invoke(mapper,"\"FIRST\"",Choice.class);
            Object reader=mapper.getClass().getMethod("readerFor",Class.class).invoke(mapper,Choice.class);Object held=read(reader,"_rootDeserializer");assertNotNull(held);
            assertTrue(((Map<?,?>)read(mapper,"_rootDeserializers")).size()>0);
            ApplicationContextHolder.register(new MapperContext(mapper));
            assertEquals(1,JacksonMapperRegistry.live().stream().filter(x->x==mapper).count(),"delegated constructor tracking must deduplicate");
            JacksonEnumCaches.flush();assertEquals(1,mapper.getClass().getField("calls").get(mapper),"registry/context/aliases flush one identity once");
            assertTrue(((Map<?,?>)read(mapper,"_rootDeserializers")).isEmpty());
            Object ser=read(read(mapper,"_serializationContexts"),"_cache");Object deser=read(read(mapper,"_deserializationContexts"),"_cache");
            assertEquals(0,ser.getClass().getMethod("size").invoke(ser));assertEquals(0,deser.getClass().getMethod("cachedDeserializersCount").invoke(deser));
            assertNull(((java.util.concurrent.atomic.AtomicReference<?>)read(ser,"_readOnlyMap")).get());assertSame(held,read(reader,"_rootDeserializer"));
            Object b=mapper.getClass().getMethod("rebuild").invoke(mapper);Object rebuilt=b.getClass().getMethod("build").invoke(b);assertNotSame(mapper,rebuilt);
            assertEquals(1,JacksonMapperRegistry.live().stream().filter(x->x==rebuilt).count());
            assertEquals(Map.of("name","original"),parsed(f.json(mapper)));ApplicationContextHolder.clear();
        }
    }
}
