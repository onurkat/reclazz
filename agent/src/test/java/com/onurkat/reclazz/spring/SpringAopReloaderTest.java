/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.platform.PlatformContext;
import com.onurkat.reclazz.ui.RestartLedger;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;
import org.springframework.aop.Advisor;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.aspectj.annotation.AnnotationAwareAspectJAutoProxyCreator;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.annotation.Order;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SpringAopReloaderTest {
    public interface Service { String first(); String second(); String fail(); }
    public static class Counter implements Service {
        int calls;
        public String first() { return "first:" + ++calls; }
        public String second() { return "second:" + ++calls; }
        public String fail() { throw new IllegalStateException("target failure"); }
    }
    public static class Raw { public String first() { return "raw"; } }
    @Aspect @Order(20)
    public static class Edited {
        @Around("execution(* first(..))")
        public Object advice(ProceedingJoinPoint call) throws Throwable {
            return "edit[" + call.proceed() + "]";
        }
    }
    @Aspect @Order(10)
    public static class Unrelated {
        @Around("execution(* *..Counter.*(..))")
        public Object advice(ProceedingJoinPoint call) throws Throwable {
            return "other[" + call.proceed() + "]";
        }
    }

    @AfterEach void reset() throws Exception { redefine(null); RestartLedger.clear(); }

    @Test void warmedJdkProxyAndInjectedReferenceFollowPointcutAndKeepTargetState() throws Exception {
        exercise(false);
    }

    @Test void warmedCglibProxyAndInjectedReferenceFollowPointcutAndKeepTargetState() throws Exception {
        exercise(true);
    }

    private void exercise(boolean cglib) throws Exception {
        try (var context = context(cglib, false)) {
            Service injected = context.getBean("service", Service.class);
            Advised proxy = (Advised) injected;
            Object target = proxy.getTargetSource().getTarget();
            assertEquals("edit[first:1]", injected.first());
            assertEquals("second:2", injected.second()); // warm the negative method cache too
            redefine("execution(* second(..))");
            assertTrue(reloader(context).reloadAopProxies(Edited.class));
            assertSame(injected, context.getBean("service"));
            assertSame(target, proxy.getTargetSource().getTarget());
            assertEquals("first:3", injected.first());
            assertEquals("edit[second:4]", injected.second());
            int count = proxy.getAdvisors().length;
            for (int i = 0; i < 3; i++) {
                reloader(context).reloadAopProxies(Edited.class);
                assertEquals("edit[second:" + (5 + i) + "]", injected.second());
                assertEquals(count, proxy.getAdvisors().length);
            }
            assertEquals("target failure", assertThrows(IllegalStateException.class, injected::fail).getMessage());
        }
    }

    @Test void anAlreadyProxiedBeanGainsTheAspectWithSpringOrderingAndKeepsOtherAdvice() throws Exception {
        redefine("execution(* noSuchMethod(..))");
        try (var context = context(false, true)) {
            Service injected = context.getBean("service", Service.class);
            Advised proxy = (Advised) injected;
            Advisor other = proxy.getAdvisors()[1];
            assertEquals("other[first:1]", injected.first());
            redefine("execution(* first(..))");
            reloader(context).reloadAopProxies(Edited.class);
            assertEquals("other[edit[first:2]]", injected.first());
            assertTrue(List.of(proxy.getAdvisors()).contains(other), "unrelated advisor identity must survive");
            redefine("execution(* noSuchMethod(..))");
            reloader(context).reloadAopProxies(Edited.class);
            assertEquals("other[first:3]", injected.first());
        }
    }

    @Test void beanNamePointcutsAreResolvedInEveryContext() throws Exception {
        try (var one = context(false, false); var two = context(true, false)) {
            Service oneService = one.getBean("service", Service.class);
            Service twoService = two.getBean("service", Service.class);
            Service untouched = one.getBean("otherService", Service.class);
            redefine("bean(service) && execution(* second(..))");
            reloader(one, two).reloadAopProxies(Edited.class);
            assertEquals("edit[second:1]", oneService.second());
            assertEquals("edit[second:1]", twoService.second());
            assertEquals("first:1", untouched.first());
            assertEquals("second:2", untouched.second());
        }
    }

    @Test void rawNewlyMatchedBeanIsNamedAndLazyAndPrototypeBeansAreNotCreated() throws Exception {
        redefine("execution(* second(..))");
        try (var context = context(false, false)) {
            Raw raw = context.getBean("raw", Raw.class);
            redefine("execution(* first(..))");
            RestartLedger.clear();
            reloader(context).reloadAopProxies(Edited.class);
            assertSame(raw, context.getBean("raw"));
            assertEquals("raw", raw.first());
            assertTrue(RestartLedger.digest().stream().anyMatch(s -> s.contains("raw") && s.contains("unproxied")),
                    RestartLedger.digest().toString());
            assertFalse(context.getBeanFactory().containsSingleton("lazy"));
            assertFalse(context.getBeanFactory().containsSingleton("prototype"));
        }
    }

    @Test void frozenProxyIsUntouchedAndNamed() throws Exception {
        try (var context = context(false, false)) {
            Service injected = context.getBean("service", Service.class);
            // The actual AdvisedSupport lives in the JDK invocation handler.
            var field = Proxy.getInvocationHandler(injected).getClass().getDeclaredField("advised");
            field.setAccessible(true);
            ((org.springframework.aop.framework.AdvisedSupport) field.get(Proxy.getInvocationHandler(injected))).setFrozen(true);
            Advisor[] before = ((Advised) injected).getAdvisors();
            redefine("execution(* second(..))");
            RestartLedger.clear();
            reloader(context).reloadAopProxies(Edited.class);
            assertArrayEquals(before, ((Advised) injected).getAdvisors());
            assertEquals("edit[first:1]", injected.first());
            assertTrue(RestartLedger.digest().stream().anyMatch(s -> s.contains("service") && s.contains("frozen")));
        }
    }

    @Test void invalidPointcutLeavesLivingChainsIntactAndCorrectedSaveRecovers() throws Exception {
        try (var context = context(false, false)) {
            Service injected = context.getBean("service", Service.class);
            Advisor[] before = ((Advised) injected).getAdvisors();
            redefine("this is not a pointcut");
            reloader(context).reloadAopProxies(Edited.class);
            assertArrayEquals(before, ((Advised) injected).getAdvisors());
            assertEquals("edit[first:1]", injected.first());
            redefine("execution(* second(..))");
            reloader(context).reloadAopProxies(Edited.class);
            assertEquals("edit[second:2]", injected.second());
        }
    }

    @Test void plainAdvisorProxyGainsJoinPointSupportWithoutLosingItsAdvisor() throws Exception {
        redefine("execution(* noSuchMethod(..))");
        try (var context = new GenericApplicationContext()) {
            context.registerBean("autoProxyCreator", AnnotationAwareAspectJAutoProxyCreator.class);
            context.registerBean("edited", Edited.class);
            var pointcut = new org.springframework.aop.support.StaticMethodMatcherPointcut() {
                @Override public boolean matches(java.lang.reflect.Method method, Class<?> type) {
                    return type == Counter.class;
                }
            };
            var plain = new org.springframework.aop.support.DefaultPointcutAdvisor(pointcut,
                    (org.aopalliance.intercept.MethodInterceptor) call -> "plain[" + call.proceed() + "]");
            plain.setOrder(5);
            context.registerBean("plain", Advisor.class, () -> plain);
            context.registerBean("service", Counter.class);
            context.refresh();
            Service old = context.getBean("service", Service.class);
            assertEquals("plain[first:1]", old.first());
            assertArrayEquals(new Advisor[]{plain}, ((Advised) old).getAdvisors());
            redefine("execution(* first(..))");
            reloader(context).reloadAopProxies(Edited.class);
            assertEquals("plain[edit[first:2]]", old.first());
            assertSame(plain, ((Advised) old).getAdvisors()[1]);
        }
    }

    @Test void nestedAndDynamicProxyTargetsAreNamedWithoutFetchingDynamicTargets() throws Exception {
        try (var context = context(false, false)) {
            Service inner = context.getBean("service", Service.class);
            var nested = new org.springframework.aop.framework.ProxyFactory(inner);
            Object nestedProxy = nested.getProxy();
            var dynamic = new org.springframework.aop.framework.ProxyFactory();
            dynamic.setInterfaces(Service.class);
            dynamic.setTargetSource(new org.springframework.aop.TargetSource() {
                public Class<?> getTargetClass() { return Counter.class; }
                public boolean isStatic() { return false; }
                public Object getTarget() { throw new AssertionError("dynamic target fetched"); }
                public void releaseTarget(Object target) { throw new AssertionError("dynamic target released"); }
            });
            Object dynamicProxy = dynamic.getProxy();
            context.getBeanFactory().registerSingleton("nested", nestedProxy);
            context.getBeanFactory().registerSingleton("dynamic", dynamicProxy);
            redefine("execution(* second(..))");
            RestartLedger.clear();
            reloader(context).reloadAopProxies(Edited.class);
            assertEquals(0, ((Advised) nestedProxy).getAdvisors().length);
            assertEquals(0, ((Advised) dynamicProxy).getAdvisors().length);
            assertTrue(RestartLedger.digest().stream().anyMatch(s -> s.contains("nested proxy")));
            assertTrue(RestartLedger.digest().stream().anyMatch(s -> s.contains("dynamic or custom")));
            assertEquals("edit[second:1]", inner.second());
        }
    }

    public static class CustomCreator extends AnnotationAwareAspectJAutoProxyCreator { }

    @Test void customCreatorIsExplicitlyRefusedAndExistingAdviceRemains() throws Exception {
        try (var context = new GenericApplicationContext()) {
            context.registerBean("autoProxyCreator", CustomCreator.class);
            context.registerBean("edited", Edited.class);
            context.registerBean("service", Counter.class);
            context.refresh();
            Service old = context.getBean("service", Service.class);
            Advisor[] before = ((Advised) old).getAdvisors();
            redefine("execution(* second(..))");
            RestartLedger.clear();
            reloader(context).reloadAopProxies(Edited.class);
            assertArrayEquals(before, ((Advised) old).getAdvisors());
            assertEquals("edit[first:1]", old.first());
            assertTrue(RestartLedger.digest().stream().anyMatch(s -> s.contains("custom auto-proxy creator")));
        }
    }

    @Test void childProxyUsesTheAspectDeclaredInItsParentContext() throws Exception {
        try (var parent = new GenericApplicationContext()) {
            parent.registerBean("edited", Edited.class);
            parent.refresh();
            try (var child = new GenericApplicationContext(parent)) {
                child.registerBean("autoProxyCreator", AnnotationAwareAspectJAutoProxyCreator.class);
                child.registerBean("service", Counter.class);
                child.refresh();
                Service old = child.getBean("service", Service.class);
                assertEquals("edit[first:1]", old.first());
                redefine("execution(* second(..))");
                reloader(parent, child).reloadAopProxies(Edited.class);
                assertEquals("first:2", old.first());
                assertEquals("edit[second:3]", old.second());
            }
        }
    }

    @Test void contextWithoutAopAndOrdinaryClassAreNoOps() {
        try (var context = new GenericApplicationContext()) {
            context.refresh();
            assertFalse(reloader(context).reloadAopProxies(Edited.class));
            assertFalse(reloader(context).reloadAopProxies(Counter.class));
        }
    }

    private static GenericApplicationContext context(boolean cglib, boolean other) {
        var context = new GenericApplicationContext();
        context.registerBean("autoProxyCreator", AnnotationAwareAspectJAutoProxyCreator.class, () -> {
            var creator = new AnnotationAwareAspectJAutoProxyCreator();
            creator.setProxyTargetClass(cglib);
            return creator;
        });
        context.registerBean("edited", Edited.class);
        if (other) context.registerBean("unrelated", Unrelated.class);
        context.registerBean("service", Counter.class);
        context.registerBean("otherService", Counter.class);
        context.registerBean("raw", Raw.class);
        context.registerBean("lazy", Raw.class, () -> { throw new AssertionError("lazy instantiated"); }, bd -> bd.setLazyInit(true));
        context.registerBean("prototype", Raw.class, () -> { throw new AssertionError("prototype instantiated"); }, bd -> bd.setScope("prototype"));
        context.refresh();
        return context;
    }

    private static SpringAopReloader reloader(GenericApplicationContext... contexts) {
        var platform = (PlatformContext) Proxy.newProxyInstance(PlatformContext.class.getClassLoader(),
                new Class<?>[]{PlatformContext.class}, (p, method, args) -> switch (method.getName()) {
                    case "getAllApplicationContexts" -> List.of(contexts);
                    case "getApplicationContext" -> contexts[0];
                    default -> null;
                });
        return new SpringAopReloader(platform);
    }

    private static void redefine(String pointcut) throws Exception {
        var instrumentation = ByteBuddyAgent.install();
        // Transform after JaCoCo so its injected members remain in the class.
        var transformer = new java.lang.instrument.ClassFileTransformer() {
            @Override public byte[] transform(ClassLoader loader, String name, Class<?> type,
                    java.security.ProtectionDomain domain, byte[] bytes) {
                if (type != Edited.class) return null;
                var node = new ClassNode();
                new ClassReader(bytes).accept(node, 0);
                for (var method : node.methods) {
                    if (method.visibleAnnotations != null) for (var annotation : method.visibleAnnotations) {
                        if (annotation.desc.equals("Lorg/aspectj/lang/annotation/Around;"))
                            annotation.values = new ArrayList<>(List.of("value",
                                    pointcut == null ? "execution(* first(..))" : pointcut));
                    }
                }
                var writer = new ClassWriter(0);
                node.accept(writer);
                return writer.toByteArray();
            }
        };
        instrumentation.addTransformer(transformer, true);
        try { instrumentation.retransformClasses(Edited.class); }
        finally { instrumentation.removeTransformer(transformer); }
        org.springframework.util.ReflectionUtils.clearCache();
        org.springframework.core.annotation.AnnotationUtils.clearCache();
    }
}
