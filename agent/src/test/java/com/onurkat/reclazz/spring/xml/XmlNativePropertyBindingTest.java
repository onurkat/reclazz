/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring.xml;

import com.onurkat.reclazz.platform.ApplicationContextHolder;
import com.onurkat.reclazz.platform.PlatformContext;
import com.onurkat.reclazz.platform.SpringXmlResources;
import com.onurkat.reclazz.ui.RestartLedger;
import com.onurkat.reclazz.ui.StatusReporter;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.io.FileSystemResource;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.config.TypedStringValue;
import org.springframework.beans.SimpleTypeConverter;
import org.springframework.core.MethodParameter;
import org.springframework.core.convert.support.DefaultConversionService;
import java.beans.PropertyEditorSupport;
import java.io.*;
import java.lang.reflect.Proxy;
import java.math.*;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class XmlNativePropertyBindingTest {
    @TempDir Path tmp;
    public enum Mode { OLD, NEXT }
    public static class Settings {
        public Class<?> type; public Mode mode; public char code; public BigDecimal amount; public URI location;
        public List<Class<?>> types; public List<Mode> modes; public Map<String,Mode> mappings;
        public Set<Mode> modeSet; public Map<Mode,String> enumKeys; public BigInteger bigInteger;
        public Integer integerSelection; public String integerWriter;
        public String selection, writer, label; public int number; public boolean flag;
        public Object dependency, value; public InputStream stream; public List<Settings> settingsRefs;
        public void setType(Class<?> v) { type = v; }
        public void setMode(Mode v) { mode = v; }
        public void setCode(char v) { code = v; }
        public void setAmount(BigDecimal v) { amount = v; }
        public void setLocation(URI v) { location = v; }
        public void setTypes(List<Class<?>> v) { types = v; }
        public void setModes(List<Mode> v) { modes = v; }
        public void setMappings(Map<String,Mode> v) { mappings = v; }
        public void setModeSet(Set<Mode> v) { modeSet = v; }
        public void setEnumKeys(Map<Mode,String> v) { enumKeys = v; }
        public void setBigInteger(BigInteger v) { bigInteger = v; }
        public Integer getIntegerSelection() { throw new AssertionError("metadata must not call getter"); }
        public void setIntegerSelection(Integer v) { integerSelection = v; integerWriter = "INTEGER"; }
        public void setIntegerSelection(String v) { integerSelection = Integer.valueOf(v); integerWriter = "STRING"; }
        public String getSelection() { throw new AssertionError("metadata must not call getter"); }
        public void setSelection(Object v) { selection = v.toString(); writer = "OBJECT"; }
        public void setSelection(String v) { selection = v; writer = "STRING"; }
        public void setLabel(String v) { if ("throw".equals(v)) throw new IllegalArgumentException("rejected"); label = v; }
        public void setNumber(int v) { number = v; }
        public void setFlag(boolean v) { flag = v; }
        public void setDependency(Object v) { dependency = v; }
        public void setValue(Object v) { value = v; }
        public void setStream(InputStream v) { stream = v; }
        public void setSettingsRefs(List<Settings> v) { settingsRefs = v; }
    }
    public static class BroadChild extends Settings { @Override public void setSelection(Object v) { super.setSelection(v); } }
    public static class Base<T> extends Settings { public List<T> generic; public T item; public void setGeneric(List<T> v) { generic = v; } public void setItem(T v) { item = v; } }
    public static class Derived extends Base<Mode> { }
    public static class PrivateSettings extends Settings { public int privateNumber; private void setPrivateNumber(int v) { privateNumber = v; } }
    public static class CompanionOnlySettings extends Settings { public String virtual; }
    public static class AmbiguousPrivateSettings extends Settings {
        private void setHidden(Integer v) { throw new AssertionError("ambiguous setter called"); }
        private void setHidden(String v) { throw new AssertionError("ambiguous setter called"); }
    }
    public interface View { void setType(Class<?> v); }
    public static class CodeEditor extends PropertyEditorSupport { public void setAsText(String v) { setValue(v.charAt(0)); } }
    public static class StreamEditor extends PropertyEditorSupport {
        static int calls; public void setAsText(String v) { calls++; setValue(new ByteArrayInputStream(new byte[]{1})); }
    }
    public static class UnknownConverter extends SimpleTypeConverter {
        int calls;
        public <T> T convertIfNecessary(Object v, Class<T> type, MethodParameter p) { calls++; return super.convertIfNecessary(v,type,p); }
    }
    @BeforeEach void before() { ApplicationContextHolder.clear(); RestartLedger.clear(); StreamEditor.calls = 0; }
    @AfterEach void after() { ApplicationContextHolder.clear(); RestartLedger.clear(); }

    @ParameterizedTest @ValueSource(strings={"type","mode","code","amount","location","types","modes","mappings","selection","integerSelection","bigInteger","modeSet","enumKeys"})
    void matchesNativeFreshForScalarGenericAndOverloadedProperties(String property) throws Exception {
        try (var live = new Scope(Settings.class, property(property, false)); var fresh = new Scope(Settings.class, property(property,true))) {
            Object held = live.bean, raw = live.raw();
            live.reload(property(property,true), "after");
            live.quiet(); assertSame(held, live.context.getBean("target")); assertSame(raw,live.raw());
            assertEquals(snapshot(fresh.bean, property), snapshot(live.bean, property));
            assertEquals("after", live.other.label);
        }
    }
    @Test void inheritedNarrowSetterWinsOverDeclaredBroadSetterWithoutCallingGetter() throws Exception {
        try(var live=new Scope(BroadChild.class,literal("selection","before"));var fresh=new Scope(BroadChild.class,literal("selection","new"))) {
            live.reload(literal("selection","new"),"after");live.quiet();
            assertEquals(snapshot(fresh.bean,"selection"),snapshot(live.bean,"selection"));assertEquals("STRING",live.bean.writer);
        }
    }
    @Test void inheritedGenericParameterUsesActualContainingClass() throws Exception {
        try (var live = new Scope(Derived.class, list("generic", "OLD", "")); var fresh = new Scope(Derived.class,list("generic","NEXT",""))) {
            live.reload(list("generic","NEXT",""),"after"); live.quiet();
            assertEquals(fresh.bean.getClass(),live.bean.getClass());
            assertEquals(Mode.NEXT, ((Derived)live.bean).generic.get(0));
        }
    }
    @Test void inheritedScalarTypeVariableUsesResolvedTargetRatherThanObjectErasure() throws Exception {
        try(var live=new Scope(Derived.class,literal("item","OLD"));var fresh=new Scope(Derived.class,literal("item","NEXT"))) {
            live.reload(literal("item","NEXT"),"after");live.quiet();
            assertSame(((Derived)fresh.bean).item,((Derived)live.bean).item);assertSame(Mode.NEXT,((Derived)live.bean).item);
        }
    }
    @Test void erasedGenericSetterCannotAcceptAnIncompatibleWholeBeanReference() throws Exception {
        try(var live=new Scope(Derived.class,literal("item","OLD"))) {
            live.context.getBeanFactory().registerSingleton("wrong","wrong");
            live.reload("<property name='item' ref='wrong'/>","after");live.warning();
            assertSame(Mode.OLD,((Derived)live.bean).item);
            assertEquals("OLD",((TypedStringValue)live.raw().getPropertyValues().get("item")).getValue());
        }
    }
    @Test void explicitTypedValuesAndNullRetainNativeSemantics() throws Exception {
        try (var live = new Scope(Settings.class, property("type", false))) {
            live.reload("<property name='type'><value type='java.lang.Class'>java.lang.Integer</value></property>","one");live.quiet();assertSame(Integer.class,live.bean.type);
            live.reload("<property name='type'><null/></property>","two");live.quiet();assertNull(live.bean.type);
            live.reload("<property name='type'><null/></property><property name='mode'><value type='"+Mode.class.getName()+"'>NEXT</value></property>","three");live.quiet();assertSame(Mode.NEXT,live.bean.mode);
        }
    }
    @ParameterizedTest @ValueSource(strings={"type","mode","code","amount","location","number","flag"})
    void invalidConversionPreservesBeanAndRawMergedValue(String property) throws Exception {
        String initial = property.equals("number")?literal(property,"1"):property.equals("flag")?literal(property,"true"):property(property,false);
        try (var live = new Scope(Settings.class,initial)) {
            Object before = snapshot(live.bean,property), raw = live.raw(), merged = live.merged();
            Object oldValue = ((AbstractBeanDefinition)raw).getPropertyValues().get(property);
            String invalid = property.equals("location")?"not a valid URI":property.equals("code")?"TOO_LONG":"not_valid";
            live.reload(literal(property,invalid),"after");live.warning();
            assertEquals(before,snapshot(live.bean,property));assertSame(raw,live.raw());assertSame(merged,live.merged());
            assertSame(oldValue,live.raw().getPropertyValues().get(property));assertEquals("after",live.other.label);
        }
    }
    @Test void throwingSetterDoesNotCommitAndNextCorrectionAndRevertWork() throws Exception {
        try (var live = new Scope(Settings.class,literal("label","before"))) {
            Object raw=live.raw(),merged=live.merged();
            live.reload(literal("label","throw"),"after");live.warning();assertEquals("before",live.bean.label);
            assertSame(raw,live.raw());assertSame(merged,live.merged());assertEquals("before",((TypedStringValue)live.raw().getPropertyValues().get("label")).getValue());
            for(String value:List.of("new","before","again")) {live.reload(literal("label",value),value);live.quiet();assertEquals(value,live.bean.label);}
        }
    }
    @Test void registeredReferenceIdentityIsPreserved() throws Exception {
        try(var live=new Scope(Settings.class,"<property name='dependency' ref='a'/>")) {
            Object b=live.context.getBean("b");live.reload("<property name='dependency' ref='b'/>","after");live.quiet();assertSame(b,live.bean.dependency);
        }
    }
    @Test void admittedCustomEditorAndConversionServiceMatchNativeFactoryBehavior() throws Exception {
        try(var live=new Scope(Settings.class,literal("code","A"))) {
            live.context.getBeanFactory().registerCustomEditor(char.class,CodeEditor.class);
            live.reload(literal("code","BEGIN"),"one");live.quiet();assertEquals('B',live.bean.code);
            var service=new DefaultConversionService();service.addConverter(String.class,URI.class, v->URI.create("urn:custom:"+v));
            live.context.getBeanFactory().setConversionService(service);
            live.reload(literal("code","BEGIN")+literal("location","NEXT"),"two");live.quiet();assertEquals(URI.create("urn:custom:NEXT"),live.bean.location);
        }
    }
    @Test void unknownTypeConverterIsNotInvokedByTheNewBindingRoute() throws Exception {
        try(var live=new Scope(Settings.class,literal("number","1"))) {
            var converter=new UnknownConverter();live.context.getBeanFactory().setTypeConverter(converter);
            live.reload(literal("number","2"),"before");live.warning();assertEquals(1,live.bean.number);assertEquals(0,converter.calls);
        }
    }
    @Test void resourceTargetAndExplicitResourceValueRefuseBeforeValueResolution() throws Exception {
        try(var live=new Scope(Settings.class,"")) {
            live.context.getBeanFactory().registerCustomEditor(InputStream.class,StreamEditor.class);
            live.reload(literal("stream","resource"),"before");live.warning();assertNull(live.bean.stream);assertEquals(0,StreamEditor.calls);
            live.reload("<property name='value'><value type='java.io.InputStream'>resource</value></property>","before");live.warning();assertNull(live.bean.value);assertEquals(0,StreamEditor.calls);
        }
    }
    @Test void primitiveAndScalarArrayExplicitTypesKeepExistingResolution() throws Exception {
        try(var live=new Scope(Settings.class,literal("number","1"))) {
            live.reload("<property name='number'><value type='int'>2</value></property>","after");live.quiet();assertEquals(2,live.bean.number);
        }
        try(var live=new Scope(Settings.class,"")) {
            live.reload("<property name='value'><value type='java.lang.String[]'>a,b</value></property>","after");live.quiet();
            assertArrayEquals(new String[]{"a","b"},(String[])live.bean.value);
        }
    }
    @Test void genericCustomBeanElementsAndWholeCustomCollectionReferencesKeepIdentity() throws Exception {
        try(var live=new Scope(Settings.class,"")) {
            live.reload("<property name='settingsRefs'><list><ref bean='other'/></list></property>","after");live.quiet();
            assertSame(live.other,live.bean.settingsRefs.get(0));
            var list=new ArrayList<Settings>() { @Override public java.util.Iterator<Settings> iterator() { throw new AssertionError("do not iterate referenced carrier"); } };
            list.add(live.other);live.context.getBeanFactory().registerSingleton("list",list);
            live.reload("<property name='settingsRefs' ref='list'/>","again");live.quiet();assertSame(list,live.bean.settingsRefs);
        }
    }
    @Test void typedMapControlUsesNativeEnumValues() throws Exception {
        try(var live=new Scope(Settings.class,property("mappings",false))) {
            live.reload("<property name='mappings'><map value-type='"+Mode.class.getName()+"'><entry key='a' value='NEXT'/></map></property>","after");live.quiet();
            assertSame(Mode.NEXT,live.bean.mappings.get("a"));
        }
    }
    @Test void unambiguousPrivatePhysicalSetterKeepsLegacyPrimitiveSupport() throws Exception {
        try(var live=new Scope(PrivateSettings.class,"")) {
            live.reload(literal("privateNumber","2"),"after");live.quiet();assertEquals(2,((PrivateSettings)live.bean).privateNumber);
        }
    }
    @Test void absentAndAmbiguousPhysicalSettersRequireRestart() throws Exception {
        try(var live=new Scope(CompanionOnlySettings.class,"")) {
            live.reload(literal("virtual","after"),"before");live.warning();
            assertNull(((CompanionOnlySettings)live.bean).virtual);assertNull(live.raw().getPropertyValues().get("virtual"));
        }
        try(var live=new Scope(AmbiguousPrivateSettings.class,"")) {
            live.reload(literal("hidden","2"),"before");live.warning();assertNull(live.raw().getPropertyValues().get("hidden"));
        }
    }
    @Test void proxyConversionAndNestedPathsRefuseWithoutGetterOrTargetUnwrapping() throws Exception {
        try(var live=new Scope(Settings.class,"")) {
            live.context.getDefaultListableBeanFactory().destroySingleton("target");
            int[] calls={0};Object proxy=Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{View.class},(p,m,a)->{calls[0]++;return null;});
            live.context.getBeanFactory().registerSingleton("target",proxy);
            live.reload(literal("type","java.lang.Integer"),"before");live.warning();assertEquals(0,calls[0]);
        }
        try(var live=new Scope(Settings.class,"")) {
            live.reload(literal("value.label","after"),"before");live.warning();assertNull(live.bean.value);
        }
    }
    private static String literal(String name,String value) {return "<property name='"+name+"' value='"+value+"'/>";}
    private static String list(String name,String value,String type) {return "<property name='"+name+"'><list"+type+"><value>"+value+"</value></list></property>";}
    private static String property(String p,boolean next) {
        String v=next?"NEXT":"OLD";
        return switch(p) {
            case "type"->literal(p,next?"java.lang.Integer":"java.lang.String");
            case "mode"->literal(p,v);case "code"->literal(p,next?"B":"A");case "amount"->literal(p,next?"2.5":"1.0");
            case "location"->literal(p,next?"urn:new":"urn:old");
            case "types"->list(p,next?"java.lang.Integer":"java.lang.String","");case "modes"->list(p,v,"");
            case "mappings"->"<property name='mappings'><map><entry key='a' value='"+v+"'/></map></property>";
            case "selection"->literal(p,next?"new":"before");
            case "integerSelection"->literal(p,next?"2":"1");case "bigInteger"->literal(p,next?"12345678901234567890":"1");
            case "modeSet"->"<property name='modeSet'><set><value>"+v+"</value></set></property>";
            case "enumKeys"->"<property name='enumKeys'><map><entry key='"+v+"' value='value'/></map></property>";
            default->throw new IllegalArgumentException(p);
        };
    }
    private static Object snapshot(Settings b,String p) {
        return switch(p) {
            case "type"->b.type==null?null:b.type.getName();case "mode"->b.mode;
            case "code"->b.code;case "amount"->b.amount;case "location"->b.location;
            case "number"->b.number;case "flag"->b.flag;
            case "types"->typed(((List<?>)b.types).get(0));case "modes"->typed(((List<?>)b.modes).get(0));case "mappings"->typed(((Map<?,?>)b.mappings).get("a"));
            case "selection"->b.writer+":"+b.selection;case "integerSelection"->b.integerWriter+":"+b.integerSelection;
            case "bigInteger"->b.bigInteger;case "modeSet"->typed(((Set<?>)b.modeSet).iterator().next());
            case "enumKeys"->typed(((Map<?,?>)b.enumKeys).keySet().iterator().next());default->throw new IllegalArgumentException(p);
        };
    }
    private static String typed(Object v) {return v.getClass().getName()+":"+v.toString();}
    private final class Scope implements AutoCloseable {
        final GenericApplicationContext context=new GenericApplicationContext();final Path xml;
        final Class<? extends Settings> type;final Settings bean,other;final List<String> events=new ArrayList<>();final SpringXmlReloader reloader;
        Scope(Class<? extends Settings> type,String props) throws Exception {
            this.type=type;xml=Files.createTempFile(tmp,"binding-",".xml");Files.writeString(xml,document(props,"before"));
            var reader=new XmlBeanDefinitionReader(context);reader.setValidating(false);var resource=new FileSystemResource(xml);
            reader.loadBeanDefinitions(resource);SpringXmlResources.record(reader,resource);context.refresh();bean=context.getBean("target",Settings.class);other=context.getBean("other",Settings.class);
            PlatformContext platform=(PlatformContext)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{PlatformContext.class},(p,m,a)->{
                if(m.getName().equals("getApplicationContext"))return context;throw new UnsupportedOperationException(m.getName());});reloader=new SpringXmlReloader(platform);
        }
        String document(String props,String value) {return "<beans xmlns='http://www.springframework.org/schema/beans'><bean id='a' class='java.lang.Object'/><bean id='b' class='java.lang.Object'/>"
                +"<bean id='target' class='"+type.getName()+"'>"+props+"</bean><bean id='other' class='"+Settings.class.getName()+"'>"+literal("label",value)+"</bean></beans>";}
        AbstractBeanDefinition raw(){return (AbstractBeanDefinition)context.getBeanFactory().getBeanDefinition("target");}
        Object merged(){return context.getBeanFactory().getMergedBeanDefinition("target");}
        void reload(String props,String value)throws Exception {Files.writeString(xml,document(props,value));events.clear();RestartLedger.clear();
            StatusReporter.StatusListener l=(level,message)->events.add(level+":"+message);StatusReporter.addListener(l);try{reloader.reload(xml);}finally{StatusReporter.removeListener(l);}}
        void quiet(){assertFalse(events.stream().anyMatch(v->v.contains("Restart required")),events.toString());}
        void warning(){assertTrue(events.stream().anyMatch(v->v.contains("Restart required")),events.toString());}
        public void close(){context.close();}
    }
}
