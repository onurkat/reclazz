/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.reload;

import com.onurkat.reclazz.config.AgentConfig;
import com.onurkat.reclazz.ui.RestartLedger;
import com.onurkat.reclazz.transform.*;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.io.*;
import java.lang.instrument.ClassDefinition;
import java.lang.reflect.Modifier;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class StaticInitializerResetTest extends TransformTestBase {
    private static final String NAME = "com.example.StaticTable";
    private static final String INTERNAL = NAME.replace('.', '/');
    private static final String OLD = "public static final Map<String,String> CODES = Map.of(\"key\", \"old\");";
    private static final String NEW = OLD.replace("old", "new");
    private static byte[] bytes(String body) {
        // The real transformer always installs <clinit> for its lookup field.
        // Keep one in this native-only component harness across constant transitions too.
        return compile(new SourceFile(NAME, "package com.example; import java.util.*; import java.math.*; public class StaticTable {public static Object KEEP=null;" + body + "}")).get(INTERNAL);
    }
    private static class Loader extends ClassLoader {
        Loader() { super(StaticInitializerResetTest.class.getClassLoader()); }
        Class<?> define(byte[] bytes) { return defineClass(NAME,bytes,0,bytes.length); }
    }
    private record Owner(Class<?> type, Loader loader, byte[] original, boolean enabled) {
        Object value(String field) throws Exception { return type.getField(field).get(null); }
        void set(String field,Object value) throws Exception { type.getField(field).set(null,value); }
        void reload(byte[] next) throws Exception {
            ByteBuddyAgent.install().redefineClasses(new ClassDefinition(type, StaticFieldPreparation.stableSchema(loader,INTERNAL,next)));
            StaticInitializerReset.afterReload(type,next,enabled);
        }
        void reload(String next) throws Exception { reload(bytes(next)); }
    }
    private static Owner owner(String body, boolean enabled) throws Exception {
        byte[] raw=bytes(body);Loader loader=new Loader();
        byte[] prepared=StaticFieldPreparation.firstDefinition(raw,enabled);
        StaticFieldPreparation.recordDefinition(loader,INTERNAL,raw,enabled);
        Class<?> type=loader.define(prepared);Class.forName(NAME,true,loader);
        return new Owner(type,loader,raw,enabled);
    }
    private static String capture(Throwing action) throws Exception {
        PrintStream saved=System.out;ByteArrayOutputStream out=new ByteArrayOutputStream();
        try { System.setOut(new PrintStream(out));action.run(); } finally { System.setOut(saved); }
        return out.toString(java.nio.charset.StandardCharsets.UTF_8);
    }
    @FunctionalInterface private interface Throwing { void run() throws Exception; }

    @Test void optionOffKeepsFinalAndValue() throws Exception {
        assertFalse(AgentConfig.parse(null).isResetStatics());assertTrue(AgentConfig.parse("resetStatics=true").isResetStatics());
        byte[] raw=bytes(OLD);assertSame(raw,StaticFieldPreparation.firstDefinition(raw,false));
        var o=owner(OLD,false);assertTrue(Modifier.isFinal(o.type.getField("CODES").getModifiers()));
        String log=capture(()->o.reload(NEW));assertEquals(Map.of("key","old"),o.value("CODES"));
        assertTrue(log.contains("resetStatics=true"),log);
    }
    @Test void definalizationIsStableAcrossRedefinition() throws Exception {
        var o=owner(OLD,true);assertFalse(Modifier.isFinal(o.type.getField("CODES").getModifiers()));
        o.reload(NEW);assertEquals(Map.of("key","new"),o.value("CODES"));
        o.reload(OLD.replace("old","third"));assertEquals(Map.of("key","third"),o.value("CODES"));
    }
    @Test void definalizationSkipsConstantsEnumsInterfacesSyntheticAndSerialVersionUid() {
        ClassNode n=new ClassNode();new ClassReader(bytes(OLD+"public static final int CONSTANT=7; public static final long serialVersionUID=Long.valueOf(2);")).accept(n,0);
        n.fields.add(new FieldNode(Opcodes.ACC_STATIC|Opcodes.ACC_FINAL|Opcodes.ACC_SYNTHETIC,"synthetic","Ljava/lang/Object;",null,null));
        var w=new ClassWriter(0);n.accept(w);byte[] raw=w.toByteArray();
        var prepared=node(StaticFieldPreparation.firstDefinition(raw,true));
        assertFalse(isFinal(prepared,"CODES"));
        for(String f:List.of("CONSTANT","serialVersionUID","synthetic"))assertTrue(isFinal(prepared,f));
        for(int flag:List.of(Opcodes.ACC_INTERFACE,Opcodes.ACC_ENUM)) {
            n.access|=flag;w=new ClassWriter(0);n.accept(w);
            assertTrue(isFinal(node(StaticFieldPreparation.firstDefinition(w.toByteArray(),true)),"CODES"));
        }
    }
    private static ClassNode node(byte[] bytes) { var n=new ClassNode();new ClassReader(bytes).accept(n,0);return n; }
    private static boolean isFinal(ClassNode n,String name) { return n.fields.stream().filter(f->f.name.equals(name)).anyMatch(f->(f.access&Opcodes.ACC_FINAL)!=0); }

    @Test void unchangedInitializerPreservesRuntimeValue() throws Exception {
        var o=owner(OLD+"public static int version(){return 0;}",true);
        Map<String,String> runtime=new HashMap<>(Map.of("runtime","kept"));o.set("CODES",runtime);
        o.reload(OLD+"public static int version(){return 1;}");assertSame(runtime,o.value("CODES"));
        o.set("CODES",null);o.reload(OLD+"public static int version(){return 2;}");assertNull(o.value("CODES"));
        o.reload(NEW+"public static int version(){return 3;}");assertEquals(Map.of("key","new"),o.value("CODES"));
    }
    @Test void changedNonFinalInitializerIsReset() throws Exception {
        var o=owner("public static volatile int COUNT=7;",true);o.reload("public static volatile int COUNT=9;");
        assertEquals(9,o.value("COUNT"));assertTrue(Modifier.isVolatile(o.type.getField("COUNT").getModifiers()));
    }
    @Test void otherFieldEditDoesNotResetThisField() throws Exception {
        var o=owner(OLD+"public static int COUNT=7;",true);o.set("CODES",null);
        o.reload(OLD+"public static int COUNT=9;");assertNull(o.value("CODES"));assertEquals(9,o.value("COUNT"));
    }
    @Test void debugOnlyChangeDoesNotReset() throws Exception {
        var o=owner(OLD,true);o.set("CODES",null);
        ClassNode n=node(o.original);for(var m:n.methods)for(var i:m.instructions)if(i instanceof LineNumberNode l)l.line+=100;
        ClassWriter w=new ClassWriter(0);n.accept(w);o.reload(w.toByteArray());assertNull(o.value("CODES"));
    }
    @Test void staticBlockStraightLineEqualsDeclaration() throws Exception {
        var o=owner(OLD,true);
        o.reload("public static final Map<String,String> CODES; static { CODES=Map.of(\"key\",\"new\"); }");
        assertEquals(Map.of("key","new"),o.value("CODES"));
    }
    @Test void candidateThrowKeepsOldValueAndBaseline() throws Exception {
        var o=owner(OLD,true);Object old=o.value("CODES");
        String log=capture(()->o.reload("public static final Map<String,String> CODES=Map.of(\"key\",\"one\",\"key\",\"two\");"));
        assertSame(old,o.value("CODES"));assertTrue(log.contains("evaluation-failed"),log);
        o.set("CODES",null);o.reload(OLD);assertNull(o.value("CODES"),"failed candidate must not replace old baseline");
        o.reload(NEW);assertEquals(Map.of("key","new"),o.value("CODES"));
    }
    @Test void missingBaselineRefuses() throws Exception {
        Loader loader=new Loader();Class<?> type=loader.define(bytes(OLD.replace("final ","")));Class.forName(NAME,true,loader);
        String log=capture(()->StaticInitializerReset.afterReload(type,bytes(NEW.replace("final ","")),true));
        assertEquals(Map.of("key","old"),type.getField("CODES").get(null));assertTrue(log.contains("missing-baseline"),log);
    }
    @Test void sameNameDifferentLoaderBaselinesDoNotMix() throws Exception {
        var first=owner(OLD,true);var second=owner(NEW,true);
        first.set("CODES",null);first.reload(OLD);assertNull(first.value("CODES"));
        second.reload(OLD);assertEquals(Map.of("key","old"),second.value("CODES"));
        assertNull(first.value("CODES"));
    }
    @Test void equalButDistinctLoadersDoNotSharePhysicalMasks() throws Exception {
        class EqualLoader extends Loader {
            @Override public boolean equals(Object other) { return other instanceof EqualLoader; }
            @Override public int hashCode() { return 1; }
        }
        Loader first=new EqualLoader(), second=new EqualLoader();
        byte[] dynamic=bytes("public static final int COUNT=Integer.parseInt(\"7\");");
        byte[] constant=bytes("public static final int COUNT=7;");
        StaticFieldPreparation.recordDefinition(first,INTERNAL,dynamic,true);
        StaticFieldPreparation.recordDefinition(second,INTERNAL,constant,true);
        assertFalse(isFinal(node(StaticFieldPreparation.stableSchema(first,INTERNAL,constant)),"COUNT"));
        assertTrue(isFinal(node(StaticFieldPreparation.stableSchema(second,INTERNAL,constant)),"COUNT"));
    }
    @Test void definalizationPreservesOriginalSerializationUid() throws Exception {
        ClassNode source=node(bytes(OLD));source.interfaces.add("java/io/Serializable");
        ClassWriter writer=new ClassWriter(0);source.accept(writer);byte[] raw=writer.toByteArray();
        Loader originalLoader=new Loader();Class<?> original=originalLoader.define(raw);
        long expected=ObjectStreamClass.lookup(original).getSerialVersionUID();
        Loader preparedLoader=new Loader();TransformContext context=new TransformContext();context.addWatched(INTERNAL);
        byte[] transformed=new ReclazzTransformer(context,AgentConfig.parse("resetStatics=true"))
                .transform(preparedLoader,INTERNAL,null,null,raw);
        Class<?> prepared=preparedLoader.define(transformed);
        assertEquals(expected,ObjectStreamClass.lookup(prepared).getSerialVersionUID());
    }
    @ParameterizedTest @ValueSource(strings={
        "Map.of(\"key\", System.getProperty(\"user.name\"))",
        "Map.of(\"key\", \"x\".toUpperCase())",
        "new HashMap<>()",
        "Map.of(\"key\", java.time.DayOfWeek.MONDAY.name())",
        "Map.of(\"key\", StaticTable.OTHER)",
        "System.currentTimeMillis()>0 ? Map.of(\"key\",\"a\") : Map.of(\"key\",\"b\")",
        "new java.util.function.Supplier<Map<String,String>>() { public Map<String,String> get(){return Map.of();}}.get()",
        "((java.util.function.Supplier<Map<String,String>>) () -> Map.of()).get()"
    }) void refusals(String expression) throws Exception {
        String extra="public static String OTHER=\"external\";";
        var o=owner(OLD+extra,true);Object old=o.value("CODES");
        // Purity admission is a component check: lambda/anonymous-class source
        // also adds members, independently of whether evaluating it is safe.
        String log=capture(()->StaticInitializerReset.afterReload(o.type,
                bytes("public static final Map<String,String> CODES="+expression+";"+extra),true));
        assertSame(old,o.value("CODES"));assertTrue(log.contains("initializer changed, not reset:"),log);
    }
    @ParameterizedTest @ValueSource(strings={
        "for(int i=0;i<2;i++) CODES=Map.of(\"key\",\"new\");",
        "try { CODES=Map.of(\"key\",\"new\"); } catch(RuntimeException e) { CODES=Map.of(); }",
        "CODES=Map.of(\"key\",\"one\"); CODES=Map.of(\"key\",\"two\");"
    }) void refusesLoopHandlerAndMultipleWrites(String block) throws Exception {
        var o=owner(OLD.replace("final ",""),true);Object old=o.value("CODES");
        String log=capture(()->o.reload("public static Map<String,String> CODES;static {"+block+"}"));
        assertSame(old,o.value("CODES"));assertTrue(log.contains("not reset: non-isolated-write"),log);
    }
    @Test void lateAttachStaticFinalRefuses() throws Exception {
        Loader loader=new Loader();byte[] raw=bytes(OLD);Class<?> type=loader.define(raw);Class.forName(NAME,true,loader);
        String log=capture(()->StaticInitializerReset.afterReload(type,bytes(NEW),true));
        assertTrue(Modifier.isFinal(type.getField("CODES").getModifiers()));assertTrue(log.contains("missing-baseline"),log);
    }
    @Test void nonConstantToConstantKeepsSchemaAndRefusesField() throws Exception {
        var o=owner("public static final int COUNT=Integer.parseInt(\"7\");",true);
        String log=capture(()->o.reload("public static final int COUNT=9;"));
        assertEquals(7,o.value("COUNT"));assertFalse(Modifier.isFinal(o.type.getField("COUNT").getModifiers()));
        assertTrue(log.contains("became-constant"),log);
    }
    @Test void constantToNonConstantKeepsFinalAndRefusesField() throws Exception {
        var o=owner("public static final int COUNT=7;",true);
        String log=capture(()->o.reload("public static final int COUNT=Integer.parseInt(\"9\");"));
        assertEquals(7,o.value("COUNT"));assertTrue(Modifier.isFinal(o.type.getField("COUNT").getModifiers()));
        assertTrue(log.contains("not-prepared"),log);
    }
    @Test void mixedOwnerConstantTransitionWithMapAndMethodEdit() throws Exception {
        var o=owner(OLD+"public static final int COUNT=Integer.parseInt(\"7\");public static int version(){return 0;}",true);
        o.reload(NEW+"public static final int COUNT=9;public static int version(){return 1;}");
        assertEquals(7,o.value("COUNT"));assertEquals(Map.of("key","new"),o.value("CODES"));
        assertEquals(1,o.type.getMethod("version").invoke(null));
    }
    @Test void frozenMaskSurvivesRetransformAndSplicedPayload() throws Exception {
        byte[] raw=bytes(OLD+"public static final int COUNT=Integer.parseInt(\"7\");");
        Loader loader=new Loader();var context=new TransformContext();context.addWatched(INTERNAL);
        var transformer=new ReclazzTransformer(context,AgentConfig.parse("resetStatics=true"));
        byte[] first=transformer.transform(loader,INTERNAL,null,null,raw);assertNotNull(first);
        Class<?> type=loader.define(first);
        byte[] next=bytes(NEW+"public static final int COUNT=9;");
        byte[] retransformed=transformer.transform(loader,INTERNAL,type,null,next);
        assertFalse(isFinal(node(retransformed),"COUNT"));
        byte[] spliced=transformer.doTransform(INTERNAL,next,loader);
        assertFalse(isFinal(node(spliced),"COUNT"));
        var altered=node(spliced);
        altered.fields.stream().filter(f->f.name.equals("COUNT")).forEach(f->f.access|=Opcodes.ACC_FINAL);
        var writer=new ClassWriter(0);altered.accept(writer);spliced=writer.toByteArray();
        byte[] bypass=transformer.transform(loader,INTERNAL,type,null,spliced);
        assertFalse(isFinal(node(bypass==null?spliced:bypass),"COUNT"));
        ByteBuddyAgent.install().redefineClasses(new ClassDefinition(type,retransformed));
    }
    @ParameterizedTest @ValueSource(strings={
        "List.of(1,2,3)","Set.of(1,2,3)","Map.ofEntries(Map.entry(\"a\",1),Map.entry(\"b\",2))",
        "List.of(0,1,2,3,4,5,6,7,8,9,10)","Set.of(0,1,2,3,4,5,6,7,8,9,10)",
        "new BigDecimal(\"1.25\")","new BigInteger(\"123\")","Boolean.valueOf(true)",
        "Character.valueOf('x')","Byte.valueOf((byte)7)","Short.valueOf((short)7)","Long.valueOf(7L)","Float.valueOf(1.5f)","Double.valueOf(1.5d)"
    }) void admitsOnlyPureLiteralTrees(String expression) throws Exception {
        var o=owner("public static Object VALUE=\"old\";",true);
        String log=capture(()->o.reload("public static Object VALUE="+expression+";"));
        assertNotEquals("old",o.value("VALUE"),log);assertTrue(log.contains("Reset static "+NAME+".VALUE (initializer changed)"),log);
    }
    @Test void nullFactoryFailurePreservesOldValue() throws Exception {
        var o=owner(OLD,true);Object old=o.value("CODES");
        String log=capture(()->o.reload("public static final Map<String,String> CODES=Map.of(\"key\",null);"));
        assertSame(old,o.value("CODES"));assertTrue(log.contains("evaluation-failed"),log);
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void unchangedNonIsolatedInitializerAndMethodEditIsSilent(boolean enabled) throws Exception {
        for (String block : List.of(
                "for(int i=0;i<2;i++) CODES=Map.of(\"key\",\"old\");",
                "try {CODES=Map.of(\"key\",\"old\");} catch(RuntimeException e) {CODES=Map.of();}",
                "Map<String,String> local=Map.of(\"key\",\"old\");CODES=local;")) {
            String fields="public static Map<String,String> CODES;static {"+block+"}";
            var o=owner(fields+"public static int version(){return 0;}",enabled);Object old=o.value("CODES");
            RestartLedger.clear();
            String log=capture(()->{
                o.reload(fields+"public static int version(){return 1;}");
                o.reload(fields+"public static int version(){return 2;}");
            });
            assertSame(old,o.value("CODES"));assertEquals(2,o.type.getMethod("version").invoke(null));
            assertTrue(log.isBlank(),log);assertEquals(0,RestartLedger.size());
        }
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void nonIsolatedWriteNeedsChangedClinitAndOptInForAdvice(boolean enabled) throws Exception {
        String fields="public static Map<String,String> CODES;static {for(int i=0;i<2;i++) CODES=Map.of(\"key\",\"old\");}";
        var o=owner(fields+"public static int version(){return 0;}",enabled);Object old=o.value("CODES");
        String next=fields.replace("old","new");RestartLedger.clear();
        String changed=capture(()->o.reload(next+"public static int version(){return 1;}"));
        assertSame(old,o.value("CODES"));
        if(enabled) assertTrue(changed.contains("not reset: non-isolated-write"),changed);
        else assertTrue(changed.isBlank(),changed);
        assertEquals(enabled?1:0,RestartLedger.size());var ledger=RestartLedger.digest();
        String unchanged=capture(()->o.reload(next+"public static int version(){return 2;}"));
        assertTrue(unchanged.isBlank(),unchanged);assertEquals(ledger,RestartLedger.digest());
    }
    private static Owner unpreparedOwner(boolean enabled) throws Exception {
        byte[] raw=bytes(OLD+"public static String LOG=\"unchanged\";public static int version(){return 0;}");
        Loader loader=new Loader();Class<?> type=loader.define(raw);Class.forName(NAME,true,loader);
        return new Owner(type,loader,raw,enabled);
    }
    @Test void attachModeMethodEditIsSilentWhenOptionOff() throws Exception {
        var o=unpreparedOwner(false);Object old=o.value("CODES");RestartLedger.clear();
        String log=capture(()->{
            o.reload(OLD+"public static String LOG=\"unchanged\";public static int version(){return 1;}");
            o.reload(OLD+"public static String LOG=\"unchanged\";public static int version(){return 2;}");
        });
        assertSame(old,o.value("CODES"));assertEquals(2,o.type.getMethod("version").invoke(null));
        assertTrue(log.isBlank(),log);assertEquals(0,RestartLedger.size());
    }
    @Test void attachModeMissingBaselineIsReportedOncePerOwnerWithOptIn() throws Exception {
        var o=unpreparedOwner(true);Object old=o.value("CODES");RestartLedger.clear();
        String log=capture(()->{
            o.reload(OLD+"public static String LOG=\"unchanged\";public static int version(){return 1;}");
            o.reload(OLD+"public static String LOG=\"unchanged\";public static int version(){return 2;}");
        });
        assertSame(old,o.value("CODES"));assertEquals(1,log.lines().filter(s->s.contains("missing-baseline")).count(),log);
        assertFalse(log.contains("restart"),log);assertEquals(0,RestartLedger.size());
    }
    @Test void optionOffConstantChangeIsSilent() throws Exception {
        var o=owner("public static final int COUNT=7;",false);RestartLedger.clear();
        String log=capture(()->o.reload("public static final int COUNT=9;"));
        assertEquals(7,o.value("COUNT"));assertTrue(log.isBlank(),log);assertEquals(0,RestartLedger.size());
    }
    @ParameterizedTest @ValueSource(strings={
        "Map.of(\"key\", StaticTable.OTHER)",
        "Map.of(\"key\", System.getProperty(\"user.name\"))",
        "Map.of(\"key\", \"x\".toUpperCase())",
        "System.currentTimeMillis()>0 ? Map.of(\"key\",\"a\") : Map.of(\"key\",\"b\")",
        "new HashMap<>()"
    }) void optionOffRefusedShapeIsSilent(String expression) throws Exception {
        String extra="public static String OTHER=\"unchanged\";";
        var o=owner(OLD+extra,false);Object old=o.value("CODES");RestartLedger.clear();
        String log=capture(()->o.reload("public static final Map<String,String> CODES="+expression+";"+extra));
        assertSame(old,o.value("CODES"));assertTrue(log.isBlank(),log);assertEquals(0,RestartLedger.size());
    }
    @Test void optionOffOriginalConstantCannotBeMadeResettableByEnablingOption() throws Exception {
        var o=owner("public static final int COUNT=7;",false);RestartLedger.clear();
        String log=capture(()->o.reload("public static final int COUNT;static{COUNT=9;}"));
        assertEquals(7,o.value("COUNT"));assertTrue(log.isBlank(),log);assertEquals(0,RestartLedger.size());
    }
    @Test void optionOffAdmittedChangeHasOnePointerAcrossUnchangedSaves() throws Exception {
        var o=owner(OLD+"public static int version(){return 0;}",false);RestartLedger.clear();
        String changed=capture(()->o.reload(NEW+"public static int version(){return 1;}"));
        assertEquals(1,changed.lines().filter(s->s.contains("resetStatics=true")).count(),changed);
        assertEquals(1,RestartLedger.size());var ledger=RestartLedger.digest();
        String unchanged=capture(()->o.reload(NEW+"public static int version(){return 2;}"));
        assertTrue(unchanged.isBlank(),unchanged);assertEquals(ledger,RestartLedger.digest());
        assertEquals(Map.of("key","old"),o.value("CODES"));
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void nonIsolatedDebugLabelsAndFramesDoNotCountAsClinitChanges(boolean enabled) throws Exception {
        String fields="public static Map<String,String> CODES;static {try{CODES=Map.of(\"key\",\"old\");}catch(RuntimeException e){CODES=Map.of();}}";
        var o=owner(fields,enabled);Object old=o.value("CODES");ClassNode cls=node(o.original);
        for (var method:cls.methods) if(method.name.equals("<clinit>")) {
            for(var insn:method.instructions.toArray()) if(insn instanceof LineNumberNode line) line.line+=100;
            method.instructions.insert(new LabelNode());
        }
        ClassWriter writer=new ClassWriter(0);cls.accept(writer);RestartLedger.clear();
        String log=capture(()->o.reload(writer.toByteArray()));
        assertSame(old,o.value("CODES"));assertTrue(log.isBlank(),log);assertEquals(0,RestartLedger.size());
    }
    @Test void nonIsolatedFieldNotWrittenByCurrentClinitGetsNoAdvice() throws Exception {
        var o=owner("public static int COUNT=7;public static Map<String,String> CODES;static {for(int i=0;i<2;i++)CODES=Map.of(\"key\",\"old\");}",true);
        Object old=o.value("CODES");RestartLedger.clear();
        String log=capture(()->o.reload("public static int COUNT=9;public static Map<String,String> CODES;"));
        assertEquals(9,o.value("COUNT"));assertSame(old,o.value("CODES"));
        assertFalse(log.contains(".CODES"),log);assertEquals(0,RestartLedger.size());
    }
    @Test void reintroducedLogicalFieldStaysOnFieldStorePath() throws Exception {
        var o=owner(OLD,true);o.set("CODES",null);
        byte[] next=bytes(NEW);
        StaticInitializerReset.afterReload(o.type,next,true,Set.of("CODES:Ljava/util/Map;"));
        assertNull(o.value("CODES"),"the old physical slot must not be reclaimed on re-add");
        StaticInitializerReset.afterReload(o.type,next,true);
        assertNull(o.value("CODES"),"later saves must also leave that logical added field alone");
    }
}
