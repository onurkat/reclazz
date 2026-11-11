package com.onurkat.reclazz.hybris.interceptor;

import de.hybris.platform.servicelayer.interceptor.*;
import de.hybris.platform.servicelayer.interceptor.impl.*;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.beans.factory.config.RuntimeBeanReference;
import java.util.*;

/** Real SAP registry/mapping + Spring; only database/type/session services are isolated. */
public class InterceptorRegistrySdkProbe {
    public static class Registry extends DefaultInterceptorRegistry {
        boolean failOnce;
        @Override public void registerInterceptor(InterceptorMapping mapping) {
            if (failOnce) { failOnce = false; throw new IllegalStateException("injected registry failure"); }
            super.registerInterceptor(mapping);
        }
        @Override protected void init() {}
        @Override protected boolean isValidTypeCode(String type) { return true; }
        @Override protected List<String> getAssignableTypes(String type) { return List.of(type.toLowerCase(Locale.ROOT)); }
    }
    public static class Policy extends InterceptorExecutionPolicy {
        @Override public <T extends Interceptor> Collection<T> getEnabledInterceptors(InterceptorExecutionContext<T> c) {
            return c.getAvailableInterceptors();
        }
    }
    public static class Target implements ValidateInterceptor<Object> {
        static int sequence;
        final int generation = ++sequence;
        @Override public void onValidate(Object model, InterceptorContext context) {}
    }
    public static class Other implements ValidateInterceptor<Object> {
        @Override public void onValidate(Object model, InterceptorContext context) {}
    }
    public static void main(String[] args) throws Exception {
        applicabilityCases();
        loaderAndTenantBoundary();
        mixedOwnerFactory();
        recreatedRegistry();
        try (var context = new GenericApplicationContext()) {
            context.registerBeanDefinition("target", new RootBeanDefinition(Target.class));
            context.registerBeanDefinition("other", new RootBeanDefinition(Other.class));
            mapping(context, "mapping", "target", 7);
            mapping(context, "unrelated", "other", 12);
            Registry registry = new Registry();
            registry.setApplicationContext(context);
            registry.setInterceptorExecutionPolicy(new Policy());
            context.getBeanFactory().registerSingleton("interceptorRegistry", registry);
            context.refresh();
            InterceptorMapping original = context.getBean("mapping", InterceptorMapping.class);
            InterceptorMapping unrelated = context.getBean("unrelated", InterceptorMapping.class);
            registry.registerInterceptor(original);
            registry.registerInterceptor(unrelated);
            Target old = context.getBean("target", Target.class);
            check(registry.getValidateInterceptors("Product").contains(old), "old registry must be warm");
            var reloader = new InterceptorReloader();
            for (int i = 0; i < 3; i++) {
                var refresh = reloader.prepareInContext(Target.class.getName(), context, context.getClassLoader());
                context.getDefaultListableBeanFactory().destroySingleton("target"); // also destroys dependent mapping
                Target next = context.getBean("target", Target.class);
                if (!Boolean.getBoolean("probe.disableRefresh")) check(refresh.complete(), "refresh failed");
                Collection<ValidateInterceptor> registered = registry.getValidateInterceptors("Product");
                check(registered.size() == 2, "duplicate/lost interceptor: " + registered);
                check(registered.stream().filter(t -> t == next).count() == 1, "new target must be registered exactly once");
                check(!registered.contains(old), "stale target survived");
                check(registered.contains(context.getBean("other")), "unrelated target lost");
                check(context.getBean("mapping", InterceptorMapping.class).getOrder() == 7, "order lost");
                check(!refresh.complete(), "completion must be one-shot");
                old = next;
            }
            var failedRefresh = reloader.prepareInContext(Target.class.getName(), context, context.getClassLoader());
            Target beforeFailure = context.getBean("target", Target.class);
            context.getDefaultListableBeanFactory().destroySingleton("target");
            registry.failOnce = true;
            check(!failedRefresh.complete(), "registry failure reported success");
            var restored = registry.getValidateInterceptors("Product");
            check(restored.size() == 2 && restored.contains(beforeFailure), "old registry mapping was not restored");
            var retry = reloader.prepareInContext(Target.class.getName(), context, context.getClassLoader());
            check(retry.complete(), "failed mapping must be retryable");
            check(registry.getValidateInterceptors("Product").size() == 2, "retry duplicated mappings");
            check(registry.getValidateInterceptors("Product").contains(context.getBean("target")), "retry kept old target");
            check(!reloader.prepareInContext(String.class.getName(), context, context.getClassLoader()).complete(),
                    "missing mapping reported success");
            System.out.println("PASS: real SAP registry, 3 refreshes, exact-once targets, order, unrelated mapping, missing mapping, failure rollback");
        }
    }
    static void loaderAndTenantBoundary() throws Exception {
        java.net.URL fixture = java.nio.file.Path.of(System.getProperty("probe.tenantClasses")).toUri().toURL();
        try (var loader = new java.net.URLClassLoader(new java.net.URL[]{fixture}, Target.class.getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (!name.equals("de.hybris.platform.core.Registry")) return super.loadClass(name, resolve);
                synchronized (getClassLoadingLock(name)) {
                    Class<?> loaded = findLoadedClass(name);
                    if (loaded == null) loaded = findClass(name);
                    if (resolve) resolveClass(loaded);
                    return loaded;
                }
            }
        }; var c = new GenericApplicationContext()) {
            c.setClassLoader(loader);
            c.registerBeanDefinition("target", new RootBeanDefinition(Target.class));
            mapping(c, "mapping", "target", 7);
            Registry registry = new Registry();
            registry.setApplicationContext(c);
            registry.setInterceptorExecutionPolicy(new Policy());
            c.getBeanFactory().registerSingleton("interceptorRegistry", registry);
            c.refresh();
            registry.registerInterceptor(c.getBean("mapping", InterceptorMapping.class));
            registry.getValidateInterceptors("Product");
            Class<?> tenant = loader.loadClass("de.hybris.platform.core.Registry");
            tenant.getField("context").set(null, c);
            var reloader = new InterceptorReloader();
            tenant.getField("failActivation").set(null, true);
            check(!reloader.prepare(Target.class.getName(), List.of(c)).complete(), "failed tenant reported success");
            tenant.getField("failActivation").set(null, false);
            var refresh = reloader.prepare(Target.class.getName(), List.of(new Object(), c));
            c.getDefaultListableBeanFactory().destroySingleton("target");
            Target next = c.getBean("target", Target.class);
            check(refresh.complete(), "context loader / tenant activation failed");
            check(registry.getValidateInterceptors("Product").contains(next), "loader path left stale registry");
            check((Integer) tenant.getField("activations").get(null) == 2, "tenant bridge was not used");
            System.out.println("PASS: context loader and tenant fault/retry boundary over real SAP interceptor registry");
        }
    }

    static void recreatedRegistry() {
        try (var c = new GenericApplicationContext()) {
            c.registerBeanDefinition("target", new RootBeanDefinition(Target.class));
            mapping(c, "mapping", "target", 7);
            var definition = new RootBeanDefinition(Registry.class);
            var mappings = new org.springframework.beans.factory.support.ManagedList<Object>();
            mappings.add(new RuntimeBeanReference("mapping"));
            definition.getPropertyValues().add("interceptorMappings", mappings);
            definition.getPropertyValues().add("applicationContext", c);
            definition.getPropertyValues().add("interceptorExecutionPolicy", new Policy());
            c.registerBeanDefinition("interceptorRegistry", definition);
            c.refresh();
            Registry original = c.getBean("interceptorRegistry", Registry.class);
            original.getValidateInterceptors("Product");
            var refresh = new InterceptorReloader().prepareInContext(Target.class.getName(), c, c.getClassLoader());
            c.getDefaultListableBeanFactory().destroySingleton("target");
            Target next = c.getBean("target", Target.class);
            Registry current = c.getBean("interceptorRegistry", Registry.class);
            check(current != original, "Spring must recreate the dependent registry in this fixture");
            check(refresh.complete(), "new registry must be recognized");
            check(current.getValidateInterceptors("Product").size() == 1, "recreated registry duplicate");
            check(current.getValidateInterceptors("Product").contains(next), "recreated registry stale");
            System.out.println("PASS: Spring-dependent registry recreation");
        }
    }


    public static final class Session {
        public void activate() { }
    }
    public static final class Sessions {
        final Session initial = new Session();
        int created, closed;
        public boolean hasCurrentSession() { return true; }
        public Session getCurrentSession() { return initial; }
        public Session getRawSession(Session session) { return session; }
        public Session createNewSession() { created++; return new Session(); }
        public void closeSession(Session session) { closed++; }
    }
    static com.onurkat.reclazz.ext.PremiumModule shippedModule() {
        var platform = (com.onurkat.reclazz.platform.PlatformContext) java.lang.reflect.Proxy.newProxyInstance(
            Target.class.getClassLoader(), new Class<?>[]{com.onurkat.reclazz.platform.PlatformContext.class},
            (p,m,a) -> { if (m.getName().equals("getPlatformId")) return com.onurkat.reclazz.platform.PlatformContext.Platform.HYBRIS;
                throw new UnsupportedOperationException(m.getName()); });
        return new com.onurkat.reclazz.hybris.SapPremiumModuleFactory().create(new com.onurkat.reclazz.ext.PremiumContext() {
            public com.onurkat.reclazz.platform.PlatformContext platform() { return platform; }
            public com.onurkat.reclazz.config.AgentConfig config() { return com.onurkat.reclazz.config.AgentConfig.parse(null); }
            public com.onurkat.reclazz.platform.PropertyFileSnapshots propertySnapshots() { return new com.onurkat.reclazz.platform.PropertyFileSnapshots(); }
            public java.lang.instrument.Instrumentation instrumentation() { return null; }
            public int applyLoggerLevels(Map<String,String> values, Collection<String> keys) { throw new AssertionError(); }
            public boolean requestRunningSystemUpdate() { throw new AssertionError(); }
        });
    }
    static void mixedOwnerFactory() throws Exception {
        java.net.URL fixture = java.nio.file.Path.of(System.getProperty("probe.tenantClasses")).toUri().toURL();
        try(var loader=new java.net.URLClassLoader(new java.net.URL[]{fixture},Target.class.getClassLoader()) {
            @Override protected Class<?> loadClass(String name,boolean resolve) throws ClassNotFoundException {
                if(!name.startsWith("de.hybris.platform.core.Registry")) return super.loadClass(name,resolve);
                synchronized(getClassLoadingLock(name)) {
                    Class<?> c=findLoadedClass(name); if(c==null)c=findClass(name); if(resolve)resolveClass(c); return c;
                }
            }
        }; var owner=new GenericApplicationContext(); var hidden=new GenericApplicationContext()) {
            owner.setClassLoader(loader); owner.getBeanFactory().setBeanClassLoader(loader);
            hidden.setClassLoader(new ClassLoader(Target.class.getClassLoader()) {
                @Override protected Class<?> loadClass(String name,boolean resolve) throws ClassNotFoundException {
                    if(name.equals(Target.class.getName())) throw new ClassNotFoundException(name);
                    return super.loadClass(name,resolve);
                }
            }); hidden.refresh();
            owner.registerBeanDefinition("target",new RootBeanDefinition(Target.class));
            mapping(owner,"mapping","target",7);
            Registry registry=new Registry(); registry.setApplicationContext(owner);registry.setInterceptorExecutionPolicy(new Policy());
            Sessions sessions=new Sessions();owner.getBeanFactory().registerSingleton("sessionService",sessions);
            owner.getBeanFactory().registerSingleton("interceptorRegistry",registry);owner.refresh();
            loader.loadClass("de.hybris.platform.core.Registry").getField("context").set(null,owner);
            registry.registerInterceptor(owner.getBean("mapping",InterceptorMapping.class));registry.getValidateInterceptors("Product");
            var module=shippedModule();var messages=new ArrayList<String>();
            com.onurkat.reclazz.ui.StatusReporter.StatusListener listener=(level,text)->{if(level.equals("WARN"))messages.add(text);};
            com.onurkat.reclazz.ui.StatusReporter.addListener(listener);
            try {
                for(boolean reverse:List.of(false,true)) {
                    messages.clear();com.onurkat.reclazz.ui.RestartLedger.clear();
                    Target old=owner.getBean("target",Target.class);
                    var pending=module.prepareInterceptor(Target.class.getName(),reverse?List.of(owner,hidden):List.of(hidden,owner));
                    owner.getDefaultListableBeanFactory().destroySingleton("target");Target next=owner.getBean("target",Target.class);
                    check(pending.complete(),"mixed owner factory reported false");
                    var targets=registry.getValidateInterceptors("Product");
                    check(targets.size()==1 && targets.contains(next) && !targets.contains(old),"factory lost exact new target");
                    check(messages.isEmpty() && com.onurkat.reclazz.ui.RestartLedger.size()==0,"invisible owner produced restart concern");
                }
                messages.clear();com.onurkat.reclazz.ui.RestartLedger.clear();
                Target old=owner.getBean("target",Target.class);
                var pending=module.prepareInterceptor(Target.class.getName(),List.of(hidden,owner));
                owner.getDefaultListableBeanFactory().destroySingleton("target");registry.failOnce=true;
                check(!pending.complete(),"owning registration failure reported success");
                check(!messages.isEmpty() && com.onurkat.reclazz.ui.RestartLedger.size()>0,"owning failure hidden");
                check(registry.getValidateInterceptors("Product").size()==1 && registry.getValidateInterceptors("Product").contains(old),"failure did not restore old mapping");
                check(sessions.created==6 && sessions.closed==6,"factory did not restore scoped sessions");
            } finally {com.onurkat.reclazz.ui.StatusReporter.removeListener(listener);com.onurkat.reclazz.ui.RestartLedger.clear();}
            System.out.println("PASS: shipped factory mixed-owner both orders, exact targets, quiet nonowners, owning failure and rollback");
        }
    }


    /** Uses SAP's real storage; only type availability and faults are controlled. */
    public static class SelectiveRegistry extends Registry {
        final Set<String> absent = new HashSet<>();
        int registrations, removals, failRegistrations;
        boolean queryFailure;
        @Override protected boolean isValidTypeCode(String type) { return !absent.contains(type); }
        @Override protected List<String> getAssignableTypes(String type) {
            if (queryFailure) throw new IllegalStateException("type lookup unavailable");
            if (absent.contains(type)) throw new de.hybris.platform.jalo.JaloItemNotFoundException("missing " + type, 0);
            return super.getAssignableTypes(type);
        }
        @Override public void registerInterceptor(InterceptorMapping mapping) {
            registrations++;
            if (failRegistrations > 0) {
                int left = failRegistrations--;
                throw new IllegalStateException(left == 2 ? "registration refused" : "restoration refused");
            }
            super.registerInterceptor(mapping);
        }
        @Override public void unregisterInterceptor(InterceptorMapping mapping) { removals++; super.unregisterInterceptor(mapping); }
        void resetCounts() { registrations=0; removals=0; }
    }
    static GenericApplicationContext selective(SelectiveRegistry registry, boolean absentMapping) {
        var c=new GenericApplicationContext();
        c.registerBeanDefinition("target",new RootBeanDefinition(Target.class));
        c.registerBeanDefinition("other",new RootBeanDefinition(Other.class));
        mapping(c,"mapping","target",7);mapping(c,"unrelated","other",12);
        if(absentMapping)c.getBeanDefinition("mapping").getPropertyValues().add("typeCode","MissingProbe");
        registry.setApplicationContext(c);registry.setInterceptorExecutionPolicy(new Policy());
        c.getBeanFactory().registerSingleton("interceptorRegistry",registry);
        c.getBeanFactory().registerSingleton("sessionService",new Sessions());c.refresh();
        registry.registerInterceptor(c.getBean("mapping",InterceptorMapping.class));
        registry.registerInterceptor(c.getBean("unrelated",InterceptorMapping.class));
        registry.resetCounts();return c;
    }
    static java.net.URLClassLoader tenantLoader() throws Exception {
        var url=java.nio.file.Path.of(System.getProperty("probe.tenantClasses")).toUri().toURL();
        return new java.net.URLClassLoader(new java.net.URL[]{url},Target.class.getClassLoader()) {
            @Override protected Class<?> loadClass(String name,boolean resolve) throws ClassNotFoundException {
                if(!name.startsWith("de.hybris.platform.core.Registry"))return super.loadClass(name,resolve);
                synchronized(getClassLoadingLock(name)) {
                    var c=findLoadedClass(name);if(c==null)c=findClass(name);if(resolve)resolveClass(c);return c;
                }
            }
        };
    }
    static void bindTenant(GenericApplicationContext c,ClassLoader loader) throws Exception {
        c.setClassLoader(loader);c.getBeanFactory().setBeanClassLoader(loader);
        loader.loadClass("de.hybris.platform.core.Registry").getField("context").set(null,c);
    }
    static void applicabilityCases() throws Exception {
        var failed=new ArrayList<String>();int passed=0;
        for(String name:List.of("absentTypeOwnerDoesNotMutateOrWarn","mixedTypeOwnersBothOrders",
                "mixedApplicableAndAbsentMappings","preflightUnexpectedFailureStaysLoud",
                "typeDisappearsAfterCaptureStaysLoud","inconsistentRegistriesStayLoud",
                "realRegistrationAndRollbackCauses")) {
            var messages=new ArrayList<String>();
            com.onurkat.reclazz.ui.StatusReporter.StatusListener listener=(level,text)->messages.add(level+":"+text);
            com.onurkat.reclazz.ui.StatusReporter.addListener(listener);
            com.onurkat.reclazz.ui.RestartLedger.clear();
            try { applicabilityCase(name,messages);passed++;System.out.println("PASS applicability: "+name); }
            catch(AssertionError e){failed.add(name+": "+e.getMessage());System.out.println("FAIL applicability: "+name+": "+e.getMessage());}
            finally {com.onurkat.reclazz.ui.StatusReporter.removeListener(listener);com.onurkat.reclazz.ui.RestartLedger.clear();}
        }
        System.out.println("Applicability cases: "+passed+"/7 passed");
        check(failed.isEmpty(),failed.toString());
    }
    static String ledger() {return String.join("\n",com.onurkat.reclazz.ui.RestartLedger.digest());}
    static void applicabilityCase(String name,List<String> messages) throws Exception {
        if(name.equals("mixedTypeOwnersBothOrders")) {
            var missing=new SelectiveRegistry();missing.absent.add("MissingProbe");var present=new SelectiveRegistry();
            try(var absent=selective(missing,true);var owner=selective(present,false);
                    var la=tenantLoader();var lb=tenantLoader()) {
                bindTenant(absent,la);bindTenant(owner,lb);var module=shippedModule();
                for(boolean reverse:List.of(false,true)) {
                    var refresh=module.prepareInterceptor(Target.class.getName(),reverse?List.of(owner,absent):List.of(absent,owner));
                    Object old=owner.getBean("target");owner.getDefaultListableBeanFactory().destroySingleton("target");
                    Object next=owner.getBean("target");check(refresh.complete(),"mixed-owner refresh failed");
                    var targets=present.getValidateInterceptors("Product");
                    check(targets.size()==2 && targets.stream().filter(t->t==next).count()==1 && !targets.contains(old)
                            && targets.contains(owner.getBean("other")),"eligible targets wrong");
                    check(owner.getBean("mapping",InterceptorMapping.class).getOrder()==7,"mapping order changed");
                    check(missing.registrations==0 && missing.removals==0,"absent owner mutated");
                }
                check(com.onurkat.reclazz.ui.RestartLedger.size()==0,"false mixed-owner concern");
                check(messages.stream().noneMatch(m->m.startsWith("WARN:")),"false warning");
                check(messages.stream().filter(m->m.startsWith("INFO:")&&m.contains("skipped")).count()==1,"skip not once per owner");
            }return;
        }
        var registry=new SelectiveRegistry();boolean missing=name.equals("absentTypeOwnerDoesNotMutateOrWarn");
        if(missing)registry.absent.add("MissingProbe");
        try(var c=selective(registry,missing)) {
            var reloader=new InterceptorReloader();Object mapping=c.getBean("mapping"),old=c.getBean("target");
            if(name.equals("mixedApplicableAndAbsentMappings")) {
                mapping(c,"absentMapping","target",9);
                c.getBeanDefinition("absentMapping").getPropertyValues().add("typeCode","MissingProbe");
                registry.absent.add("MissingProbe");c.getBean("absentMapping");
            }
            if(name.equals("preflightUnexpectedFailureStaysLoud"))registry.queryFailure=true;
            if(name.equals("inconsistentRegistriesStayLoud")) {
                var other=new SelectiveRegistry();other.absent.add("Product");other.setApplicationContext(c);other.setInterceptorExecutionPolicy(new Policy());
                var service=new de.hybris.platform.servicelayer.internal.model.impl.DefaultModelService(){
                    @Override public de.hybris.platform.servicelayer.interceptor.InterceptorRegistry lookupInterceptorRegistry(){return other;}
                };
                service.getInterceptorRegistry();c.getBeanFactory().registerSingleton("modelService",service);
            }
            var refresh=reloader.prepareInContext(Target.class.getName(),c,c.getClassLoader());
            switch(name) {
                case "absentTypeOwnerDoesNotMutateOrWarn" -> {
                    check(refresh.complete(),"absent owner must be a no-op");check(!refresh.complete(),"no-op not one-shot");
                    check(reloader.prepareInContext(Target.class.getName(),c,c.getClassLoader()).complete(),"repeat no-op failed");
                    check(registry.registrations==0 && registry.removals==0,"absent mapping mutated");
                    check(c.getBean("mapping")==mapping && c.getBean("target")==old,"absent Spring identity changed");
                    check(com.onurkat.reclazz.ui.RestartLedger.size()==0,"absent type in ledger");
                    check(messages.stream().noneMatch(m->m.startsWith("WARN:")),"absent type warned");
                    check(messages.stream().filter(m->m.startsWith("INFO:")&&m.contains("skipped")).count()==1,"skip info not once");
                }
                case "mixedApplicableAndAbsentMappings" -> {
                    c.getDefaultListableBeanFactory().destroySingleton("target");Object next=c.getBean("target");
                    check(refresh.complete(),"mixed mappings failed");
                    check(registry.registrations==1 && registry.removals==1,"absent mapping mutated");
                    check(registry.getValidateInterceptors("Product").stream().filter(t->t==next).count()==1,"new target not exact");
                    check(com.onurkat.reclazz.ui.RestartLedger.size()==0,"mixed mappings false concern");
                }
                case "preflightUnexpectedFailureStaysLoud","inconsistentRegistriesStayLoud" -> {
                    check(!refresh.complete(),"preflight failure ignored");
                    check(registry.registrations==0 && registry.removals==0,"mutated before eligibility");
                    check(ledger().contains("IllegalStateException"),"preflight cause missing from ledger");
                    if(registry.queryFailure)check(ledger().contains("type lookup unavailable"),"lookup message missing");
                }
                case "typeDisappearsAfterCaptureStaysLoud" -> {
                    registry.absent.add("Product");c.getDefaultListableBeanFactory().destroySingleton("target");
                    check(!refresh.complete(),"post-capture missing type swallowed");
                    check(registry.removals==2 && registry.registrations==2,"rollback not attempted");
                    check(ledger().contains("JaloItemNotFoundException") && ledger().contains("missing Product"),"missing-type cause lost");
                }
                case "realRegistrationAndRollbackCauses" -> {
                    c.getDefaultListableBeanFactory().destroySingleton("target");registry.failRegistrations=2;
                    check(!refresh.complete(),"registration failure ignored");
                    check(ledger().contains("registration refused") && ledger().contains("restoration refused")
                            && ledger().contains("IllegalStateException"),"both causes must survive rollback failure");
                    check(messages.stream().anyMatch(m->m.contains("Interceptor re-registration failed")&&m.contains("registration refused")),"warning cause missing");
                }
                default -> throw new AssertionError(name);
            }
        }
    }

    static void mapping(GenericApplicationContext c, String name, String target, int order) {
        var definition = new RootBeanDefinition(InterceptorMapping.class);
        definition.getPropertyValues().add("typeCode", "Product");
        definition.getPropertyValues().add("interceptor", new RuntimeBeanReference(target));
        definition.getPropertyValues().add("order", order);
        c.registerBeanDefinition(name, definition);
    }
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
