package com.onurkat.reclazz.hybris.backoffice;

import com.onurkat.reclazz.config.AgentConfig;
import com.onurkat.reclazz.ext.*;
import com.onurkat.reclazz.hybris.ProcessTenantFixture;
import com.onurkat.reclazz.hybris.SapPremiumModuleFactory;
import com.onurkat.reclazz.platform.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.support.GenericApplicationContext;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BackofficeConfigFactoryTest {
    private void apply(GenericApplicationContext context,AgentConfig config) {
        PlatformContext platform=(PlatformContext)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{PlatformContext.class},(p,m,a)->switch(m.getName()){
            case "getPlatformId" -> PlatformContext.Platform.GENERIC;
            case "getAllApplicationContexts" -> List.of(context);
            default -> throw new UnsupportedOperationException(m.getName());
        });
        PremiumModule module=new SapPremiumModuleFactory().create(new PremiumContext(){
            public PlatformContext platform(){return platform;}
            public AgentConfig config(){return config;}
            public PropertyFileSnapshots propertySnapshots(){return new PropertyFileSnapshots();}
            public java.lang.instrument.Instrumentation instrumentation(){return null;}
            public int applyLoggerLevels(Map<String,String> content,Collection<String> keys){throw new AssertionError();}
            public boolean requestRunningSystemUpdate(){throw new AssertionError();}
        });
        module.changeHandlers().get(Capability.BACKOFFICE_RELOAD).handle(new PlatformChangeHandler.Change(Capability.BACKOFFICE_RELOAD,Path.of("example-backoffice-config.xml"),"MODIFIED","example","resources"));
    }
    @Test void realFactoryUsesOwnerTenantSessionAndRestoresCallerOnSuccessAndFailure(@TempDir Path dir) throws Exception {
        ProcessTenantFixture.reset();
        try(var loader=BackofficeConfigReloaderTest.fixture(dir);var ctx=new GenericApplicationContext()) {
            Object service=BackofficeConfigReloaderTest.make(loader,"Service");service.getClass().getField("checkTenant").setBoolean(service,true);
            ctx.getBeanFactory().registerSingleton("cockpitConfigurationService",service);ctx.refresh();
            var owner=ProcessTenantFixture.attach(ctx);ctx.setClassLoader(loader);
            Object caller=new Object();ProcessTenantFixture.CURRENT.set(caller);
            var oldSession=new ProcessTenantFixture.Session(owner);oldSession.activate();
            ClassLoader oldLoader=Thread.currentThread().getContextClassLoader();
            var messages=BackofficeConfigReloaderTest.capture(()->apply(ctx,AgentConfig.parse(null)));
            assertEquals(1,BackofficeConfigReloaderTest.count(service,"defaults"));assertTrue(messages.stream().anyMatch(m->m.startsWith("OK:")));
            assertSame(caller,ProcessTenantFixture.CURRENT.get());assertSame(oldSession,ProcessTenantFixture.SESSIONS.get().get(owner));assertSame(oldLoader,Thread.currentThread().getContextClassLoader());
            service.getClass().getField("fail").setBoolean(service,true);
            messages=BackofficeConfigReloaderTest.capture(()->apply(ctx,AgentConfig.parse(null)));
            assertTrue(messages.stream().anyMatch(m->m.startsWith("WARN:")&&m.contains("native reset failed")));assertTrue(messages.stream().noneMatch(m->m.startsWith("OK:")||m.contains("No running backoffice")));
            assertSame(caller,ProcessTenantFixture.CURRENT.get());assertSame(oldSession,ProcessTenantFixture.SESSIONS.get().get(owner));assertSame(oldLoader,Thread.currentThread().getContextClassLoader());
            var sessions=(ProcessTenantFixture.Sessions)ctx.getBean("sessionService");assertEquals(2,sessions.created);assertEquals(2,sessions.closed);
            messages=BackofficeConfigReloaderTest.capture(()->apply(ctx,AgentConfig.parse("backofficeConfigReset=false")));
            assertTrue(messages.stream().anyMatch(m->m.startsWith("WARN:")&&m.contains("not applied")));assertEquals(2,sessions.created);assertEquals(1,BackofficeConfigReloaderTest.count(service,"defaults"));
        } finally {ProcessTenantFixture.reset();}
    }
    @Test void noCockpitHasTheAbsenceMessage() {
        try(var ctx=new GenericApplicationContext()) {
            ctx.refresh();var messages=BackofficeConfigReloaderTest.capture(()->apply(ctx,AgentConfig.parse(null)));
            assertTrue(messages.stream().anyMatch(m->m.contains("No running backoffice")));assertTrue(messages.stream().noneMatch(m->m.startsWith("OK:")));
        }
    }
}
