package com.onurkat.reclazz.hybris.interceptor;

import com.onurkat.reclazz.platform.*;
import com.onurkat.reclazz.spring.xml.SpringXmlReloader;
import com.onurkat.reclazz.ui.RestartLedger;
import com.onurkat.reclazz.ui.StatusReporter;
import de.hybris.platform.servicelayer.interceptor.impl.InterceptorMapping;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.core.io.FileSystemResource;
import java.nio.file.*;
import java.util.*;

/** Actual XML mutation and native registry; database/type/session services use existing SDK seams. */
public class XmlInterceptorMappingsSdkProbe {
    public static class First extends InterceptorRegistrySdkProbe.Target { }
    public static class Second extends InterceptorRegistrySdkProbe.Other { }
    public static class Unrelated extends InterceptorRegistrySdkProbe.Other { }
    public static class LifecycleMapping extends InterceptorMapping {
        private boolean fail;
        public void setFail(boolean fail) { this.fail=fail; }
        public void ready() { if(fail) throw new IllegalStateException("synthetic init failure"); }
    }
    private static final String HEAD = "<beans xmlns='http://www.springframework.org/schema/beans'>";
    private static final String TARGETS = bean("first", First.class) + bean("second", Second.class)
            + bean("other", Unrelated.class);
    private static String bean(String id, Class<?> type) {
        return "<bean id='"+id+"' class='"+type.getName()+"'/>";
    }
    private static String mapping(String id, String target, String type, boolean rich) {
        return "<bean id='"+id+"' class='"+(rich ? LifecycleMapping.class : InterceptorMapping.class).getName()+"'"
                +(rich ? " init-method='ready'" : "")+">"
                +"<property name='typeCode' value='"+type+"'/><property name='interceptor' ref='"+target+"'/>"
                +"<property name='order' value='7'/></bean>";
    }
    private static final String UNRELATED = mapping("unrelated", "other", "Product", false);
    private static int passed;
    private static int failed;
    public static void main(String[] args) throws Exception {
        run("xmlAddedMappingIsRegistered", () -> {
            try(var f=new Fixture("",false)) {
                f.edit(mapping("mapping","first","Product",false));
                f.target("Product", f.c.getBean("first"), 1); f.unrelated();
            }
        });
        run("xmlRefEditMovesRegistrationToNewTarget", () -> {
            try(var f=new Fixture("first",false)) {
                Object mapping=f.c.getBean("mapping");
                f.edit(mapping("mapping","second","Product",false));
                check(mapping==f.c.getBean("mapping"),"setter edit recreated mapping");
                f.target("Product",f.c.getBean("first"),0); f.target("Product",f.c.getBean("second"),1); f.unrelated();
            }
        });
        run("xmlTypeCodeEditMovesRegistrationToNewType", () -> {
            try(var f=new Fixture("first",false)) {
                Object mapping=f.c.getBean("mapping");
                f.edit(mapping("mapping","first","Title",false));
                check(mapping==f.c.getBean("mapping"),"type edit recreated mapping");
                f.target("Product",f.c.getBean("first"),0); f.target("Title",f.c.getBean("first"),1); f.unrelated();
            }
        });
        if (Boolean.getBoolean("probe.xmlRedOnly")) finish();
        run("configuredGraphXmlMappingRecreationStaysRestartRequired", () -> {
            try(var f=new Fixture("first",true)) {
                Object previous=f.c.getBean("mapping"); RestartLedger.clear();
                List<String> messages=new ArrayList<>();
                StatusReporter.StatusListener listener=(level,message)->messages.add(message);
                StatusReporter.addListener(listener);
                try { f.edit(mapping("mapping","second","Product",true)); }
                finally { StatusReporter.removeListener(listener); }
                check(previous==f.c.getBean("mapping"),"refused recreation changed mapping");
                check(f.registry==f.c.getBean("interceptorRegistry") && f.model.getInterceptorRegistry()==f.registry,"refused recreation changed registry");
                f.target("Product",f.c.getBean("first"),1); f.target("Product",f.c.getBean("second"),0); f.unrelated();
                check(messages.stream().anyMatch(x->x.contains("0 changes applied, 1 require restart")),"no exact refusal summary");
                check(RestartLedger.digest().stream().anyMatch(x->x.contains("configuredMappings")),"no configured mapping restart advice");
            }
        });
        run("repeatEditDoesNotDuplicate", () -> {
            try(var f=new Fixture("first",false)) {
                for(int i=0;i<3;i++) {
                    String target=i%2==0?"second":"first";
                    f.edit(mapping("mapping",target,"Product",false));
                    f.target("Product",f.c.getBean(target),1);
                    f.target("Product",f.c.getBean(target.equals("first")?"second":"first"),0);
                    f.unrelated();
                    var hook=new XmlInterceptorMappings();
                    var pending=hook.before(f.c,"mapping",null);
                    pending.complete(f.c.getBean("mapping"),true);
                    pending.complete(f.c.getBean("mapping"),true);
                    check(f.registry.getValidateInterceptors("Product").size()==2,"duplicate mapping");
                }
            }
        });
        run("unrelatedRegistrationsPreserved", () -> {
            try(var f=new Fixture("first",false)) {
                f.edit(mapping("mapping","second","Title",false)); f.unrelated();
                check(f.registry.getValidateInterceptors("Product").size()==1,"old bucket must contain only unrelated");
            }
        });
        run("failedPropertyChangeKeepsOriginalRegistration", () -> {
            try(var f=new Fixture("first",false)) {
                f.edit(mapping("mapping","missingTarget","Product",false));
                f.target("Product",f.c.getBean("first"),1); f.unrelated();
                check(((InterceptorMapping)f.c.getBean("mapping")).getInterceptor()==f.c.getBean("first"),"failed ref changed instance");
            }
        });
        run("registrationFailureRestoresAndWarns", () -> {
            try(var f=new Fixture("first",false)) {
                RestartLedger.clear(); List<String> warnings=new ArrayList<>();
                StatusReporter.StatusListener listener=(level,message)->{ if(level.equals("WARN")) warnings.add(message); };
                StatusReporter.addListener(listener);
                try {
                    f.registry.failOnce=true;
                    f.edit(mapping("mapping","second","Product",false));
                    f.target("Product",f.c.getBean("first"),1); f.target("Product",f.c.getBean("second"),0); f.unrelated();
                    check(RestartLedger.digest().stream().anyMatch(x->x.contains("mapping")&&x.contains("registration failed")),"no restart note");
                    check(warnings.stream().anyMatch(x->x.contains("mapping")&&x.contains("registration failed")),"no warning");
                } finally { StatusReporter.removeListener(listener); }
            }
        });
        run("registryUnavailableWarnsAndNotesRestart", () -> {
            try(var f=new Fixture("",false)) {
                RestartLedger.clear(); f.c.getDefaultListableBeanFactory().destroySingleton("modelService"); f.c.getDefaultListableBeanFactory().destroySingleton("nativeRegistry");
                f.edit(mapping("mapping","first","Product",false));
                check(f.c.containsBean("mapping"),"XML bean creation must remain applied");
                check(RestartLedger.digest().stream().anyMatch(x->x.contains("mapping")&&x.contains("registration failed")),"missing registry silently succeeded");
            }
        });
        run("nonMappingBeanUntouched", () -> {
            try(var f=new Fixture("first",false)) {
                Object mapping=f.c.getBean("mapping");
                f.edit(mapping("mapping","first","Product",false)+bean("ordinary",StringBuilder.class));
                check(f.c.getBean("ordinary") instanceof StringBuilder,"ordinary bean not added");
                check(f.c.getBean("mapping")==mapping,"unrelated mapping identity changed");
                f.target("Product",f.c.getBean("first"),1); f.unrelated();
            }
        });
        run("sharedDefinitionPropertyEditAppliesInEveryOwner", () -> multiOwner(true, false));
        run("distinctDefinitionsStillApplyPerOwner", () -> multiOwner(false, false));
        run("bothOwnerOrdersApply", () -> multiOwner(true, true));
        finish();
    }
    private static void multiOwner(boolean shared, boolean reverse) throws Exception {
        try (var a = new Fixture("first", false);
             var b = new Fixture("first", false, shared ? a.c.getBeanFactory().getBeanDefinition("mapping") : null)) {
            for (var owner : reverse ? List.of(b.c, a.c) : List.of(a.c, b.c)) ApplicationContextHolder.register(owner);
            SpringXmlResources.record(new XmlBeanDefinitionReader(b.c), new FileSystemResource(a.xml));
            PlatformContext platform = (PlatformContext) java.lang.reflect.Proxy.newProxyInstance(
                    XmlInterceptorMappingsSdkProbe.class.getClassLoader(), new Class<?>[]{PlatformContext.class},
                    (p,m,v) -> m.getName().equals("getApplicationContext") ? a.c : null);
            var reload = new SpringXmlReloader(platform, paths -> {}, new XmlInterceptorMappings());
            Object aMapping = a.c.getBean("mapping"), bMapping = b.c.getBean("mapping");
            String[][] phases = {{"second", "Product"}, {"second", "Title"}, {"second", "Title"}, {"first", "Product"}};
            for (String[] phase : phases) {
                Files.writeString(a.xml, HEAD + TARGETS + UNRELATED + mapping("mapping", phase[0], phase[1], false) + "</beans>");
                reload.reloadLoaded(a.xml);
                for (var f : List.of(a, b)) {
                    for (String type : List.of("Product", "Title")) for (String target : List.of("first", "second"))
                        f.target(type, f.c.getBean(target), phase[0].equals(target) && phase[1].equals(type) ? 1 : 0);
                    f.unrelated();
                    check(f.model.getInterceptorRegistry() == f.registry && f.c.getBean("interceptorRegistry") == f.registry,
                            "shared edit replaced native registry");
                }
                check(a.c.getBean("mapping") == aMapping && b.c.getBean("mapping") == bMapping,
                        "shared edit replaced mapping identity");
            }
        } finally { ApplicationContextHolder.clear(); }
    }
    private static void finish() {
        System.out.println("XML_SDK passed="+passed+" failed="+failed);
        if(failed>0) throw new AssertionError("XML mapping SDK failures: "+failed);
        if(Boolean.getBoolean("probe.xmlRedOnly")) System.exit(0);
    }
    private static void run(String name, Checked work) throws Exception {
        try {work.run(); passed++; System.out.println("PASS: "+name);}
        catch(AssertionError failure) {failed++;System.out.println("FAIL: "+name+": "+failure.getMessage());}
    }
    @FunctionalInterface interface Checked { void run() throws Exception; }
    private static void check(boolean ok,String reason) {if(!ok) throw new AssertionError(reason);}
    private static final class Fixture implements AutoCloseable {
        final Path dir=Files.createTempDirectory("reclazz-native-xml-");
        final Path xml=dir.resolve("mapping-spring.xml");
        final GenericApplicationContext c=new GenericApplicationContext();
        final InterceptorRegistrySdkProbe.Registry registry;
        final RegistryPreservationSdkProbe.Service model;
        final SpringXmlReloader reloader;
        Fixture(String target,boolean rich) throws Exception { this(target, rich, null); }
        Fixture(String target,boolean rich, Object sharedDefinition) throws Exception {
            Files.writeString(xml,HEAD+TARGETS+UNRELATED+(target.isEmpty()?"":mapping("mapping",target,"Product",rich))+"</beans>");
            var reader=new XmlBeanDefinitionReader(c);reader.setValidating(false);
            var resource=new FileSystemResource(xml);reader.loadBeanDefinitions(resource);SpringXmlResources.record(reader,resource);
            if (sharedDefinition != null) c.registerBeanDefinition("mapping",
                    (org.springframework.beans.factory.config.BeanDefinition) sharedDefinition);
            var definition=new org.springframework.beans.factory.support.RootBeanDefinition(InterceptorRegistrySdkProbe.Registry.class);
            var configured=new org.springframework.beans.factory.support.ManagedList<Object>();
            configured.add(new org.springframework.beans.factory.config.RuntimeBeanReference("unrelated"));
            if(!target.isEmpty()) configured.add(new org.springframework.beans.factory.config.RuntimeBeanReference("mapping"));
            definition.getPropertyValues().add("interceptorMappings",configured);
            definition.getPropertyValues().add("applicationContext",c);
            definition.getPropertyValues().add("interceptorExecutionPolicy",new InterceptorRegistrySdkProbe.Policy());
            c.registerBeanDefinition("nativeRegistry",definition);c.registerAlias("nativeRegistry","interceptorRegistry");c.refresh();
            registry=c.getBean("interceptorRegistry",InterceptorRegistrySdkProbe.Registry.class);
            model=new RegistryPreservationSdkProbe.Service(c); c.getBeanFactory().registerSingleton("modelService",model);
            check(model.getInterceptorRegistry()==registry,"native cache setup");
            registry.getValidateInterceptors("Product");
            PlatformContext platform=(PlatformContext)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{PlatformContext.class},
                    (p,m,a)->switch(m.getName()) {case "getApplicationContext"->c;case "getAllApplicationContexts"->List.of(c);default->null;});
            SpringXmlReloader candidate;
            try {candidate=SpringXmlReloader.class.getConstructor(PlatformContext.class,java.util.function.Consumer.class,XmlBeanLifecycle.class)
                    .newInstance(platform,(java.util.function.Consumer<Set<Path>>)(paths)->{},new XmlInterceptorMappings());}
            catch(NoSuchMethodException baseline) {candidate=new SpringXmlReloader(platform);}
            reloader=candidate;
        }
        void edit(String mapping) throws Exception {Files.writeString(xml,HEAD+TARGETS+UNRELATED+mapping+"</beans>");reloader.reloadLoaded(xml);}
        void target(String type,Object target,int expected) {check(model.getInterceptorRegistry().getValidateInterceptors(type).stream().filter(x->x==target).count()==expected,"wrong target count for "+type+", expected "+expected);}
        void unrelated() {target("Product",c.getBean("other"),1);}
        @Override public void close() throws Exception {c.close();Files.deleteIfExists(xml);Files.deleteIfExists(dir);}
    }
}
