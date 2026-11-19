/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.*;
import java.lang.annotation.*;
import org.springframework.validation.Validator;
import org.springframework.validation.Errors;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.ConfigurationPropertiesBindHandlerAdvisor;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.validation.annotation.Validated;
import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Runs on the isolated Boot 3 and Boot 4 graphs, with actual factory counters. */
public class BootFourPropertyCheckTest {
    public static final AtomicInteger created = new AtomicInteger();
    public static final AtomicInteger closed = new AtomicInteger();
    static final List<String> trace = new ArrayList<>();
    @Target(ElementType.TYPE) @Retention(RetentionPolicy.RUNTIME)
    @Constraint(validatedBy=TraceValidator.class)
    public @interface TraceConstraint {
        String message() default "trace";
        Class<?>[] groups() default {};
        Class<? extends Payload>[] payload() default {};
    }
    public static class TraceValidator implements ConstraintValidator<TraceConstraint,Properties> {
        public boolean isValid(Properties value,ConstraintValidatorContext context) { trace.add("jsr"); return true; }
    }
    @ConfigurationProperties("probe") @Validated @TraceConstraint
    public static class Properties implements Validator {
        @NotBlank private String label;
        public String getLabel() { return label; }
        public void setLabel(String value) { label = value; }
        public boolean supports(Class<?> type) { return type==Properties.class; }
        public void validate(Object target,Errors errors) { trace.add("self"); }
    }
    @Configuration(proxyBeanMethods=false) @EnableConfigurationProperties(Properties.class)
    static class Config {}

    @ConfigurationProperties("probe")
    public static class PlainProperties {
        private String label;
        public String getLabel(){return label;}
        public void setLabel(String value){label=value;}
    }
    @Configuration(proxyBeanMethods=false) @EnableConfigurationProperties(PlainProperties.class)
    static class PlainConfig {}
    static AnnotationConfigApplicationContext context() { return context(Config.class,false,false); }
    static AnnotationConfigApplicationContext context(Class<?> config,boolean custom,boolean hiddenValidation) {
        var c = new AnnotationConfigApplicationContext();
        c.getEnvironment().getPropertySources().addFirst(new MapPropertySource("original", Map.of("probe.label", "before")));
        if(hiddenValidation) c.setClassLoader(new ClassLoader(c.getClassLoader()) {
            @Override protected Class<?> loadClass(String name,boolean resolve) throws ClassNotFoundException {
                if(name.startsWith("jakarta.validation.")) throw new ClassNotFoundException(name);
                return super.loadClass(name,resolve);
            }
        });
        if(custom) c.getBeanFactory().registerSingleton("configurationPropertiesValidator",new Validator() {
            public boolean supports(Class<?> type){return type==Properties.class;}
            public void validate(Object target,Errors errors){trace.add("custom");}
        });
        c.register(config); c.refresh(); return c;
    }
    static PropertyChangeCheck.Result check(AnnotationConfigApplicationContext c, String value) {
        return new PropertyChangeCheck().check(List.of(c), Map.of("probe.label", value));
    }
    @Test void appliedScratchClosesItsActualFactory() throws Exception {
        try (var spy = new FactoryCounter(); var c = context()) {
            spy.reset(); var r=check(c,"after");
            assertEquals(PropertyChangeOutcome.State.APPLIED,r.state(),r.findings().toString());
            assertEquals(1,created.get()); assertEquals(created.get(),closed.get());
            assertEquals("before",c.getBean(Properties.class).getLabel());
            assertEquals("before",c.getEnvironment().getProperty("probe.label"));
        }
    }
    @Test void rejectedScratchClosesItsActualFactory() throws Exception {
        try (var spy = new FactoryCounter(); var c = context()) {
            spy.reset(); var r=check(c,"");
            assertEquals(PropertyChangeOutcome.State.REJECTED,r.state(),r.findings().toString());
            assertEquals(1,created.get()); assertEquals(created.get(),closed.get());
            assertEquals("before",c.getBean(Properties.class).getLabel());
        }
    }
    @Test void unexpectedAdvisorThrowStillClosesFactory() throws Exception {
        try(var spy=new FactoryCounter();var c=context()) {
            c.getBeanFactory().registerSingleton("throwingScratchAdvisor",(ConfigurationPropertiesBindHandlerAdvisor) handler->{throw new IllegalStateException("scratch advisor failure");});
            spy.reset();var r=check(c,"after");
            assertEquals(PropertyChangeOutcome.State.UNCHECKABLE,r.state(),r.findings().toString());
            assertTrue(r.findings().stream().anyMatch(x->x.contains("scratch advisor failure")));
            assertEquals(1,created.get());assertEquals(created.get(),closed.get());
        }
    }
    @Test void unvalidatedBeanCreatesNoScratchFactory() throws Exception {
        try(var spy=new FactoryCounter();var c=context(PlainConfig.class,false,false)) {
            spy.reset();var r=check(c,"after");
            assertEquals(PropertyChangeOutcome.State.APPLIED,r.state(),r.findings().toString());
            assertEquals(0,created.get());assertEquals(0,closed.get());
        }
    }
    @Test void noJsr303InContextClasspathCreatesNoScratchFactory() throws Exception {
        try(var spy=new FactoryCounter();var c=context(Config.class,false,true)) {
            spy.reset();var r=check(c,"after");
            assertEquals(PropertyChangeOutcome.State.APPLIED,r.state(),r.findings().toString());
            assertEquals(0,created.get());assertEquals(0,closed.get());
        }
    }
    @Test void customThenJsrThenNativeSelfValidation() throws Exception {
        try(var spy=new FactoryCounter();var c=context(Config.class,true,false)) {
            spy.reset();trace.clear();var r=check(c,"after");
            assertEquals(PropertyChangeOutcome.State.APPLIED,r.state(),r.findings().toString());
            assertEquals(List.of("custom","jsr","self"),trace);
            assertEquals(1,created.get());assertEquals(created.get(),closed.get());
        }
    }
    static class KnownShape {
        final Validator configurationPropertiesValidator=null;
        final boolean jsr303Present=false;
        private Validator getJsr303Validator(Class<?> type){return null;}
        private List<Validator> getValidators(Bindable<?> target){return List.of();}
    }
    static class UnknownCacheShape {
        final Validator configurationPropertiesValidator=null;
        final boolean jsr303Present=false;
        Validator unknownCache;
        private Validator getJsr303Validator(Class<?> type){return null;}
        private List<Validator> getValidators(Bindable<?> target){return List.of();}
    }
    static class MissingPresenceShape {
        final Validator configurationPropertiesValidator=null;
        private Validator getJsr303Validator(Class<?> type){return null;}
        private List<Validator> getValidators(Bindable<?> target){return List.of();}
    }
    @Test void unknownLifecycleShapesAreRefused() {
        assertTrue(PropertyChangeCheck.perBindValidatorShape(KnownShape.class,Validator.class,Bindable.class));
        assertFalse(PropertyChangeCheck.perBindValidatorShape(UnknownCacheShape.class,Validator.class,Bindable.class));
        assertFalse(PropertyChangeCheck.perBindValidatorShape(MissingPresenceShape.class,Validator.class,Bindable.class));
    }
    /** Count native factory construction and close, not a mock cleanup method. */
    static class FactoryCounter implements AutoCloseable {
        final java.lang.instrument.Instrumentation instrumentation = ByteBuddyAgent.install();
        final Class<?> type=Class.forName("org.hibernate.validator.internal.engine.ValidatorFactoryImpl");
        final ClassFileTransformer transformer = new ClassFileTransformer() {
            @Override public byte[] transform(ClassLoader loader,String name,Class<?> redefining,
                    ProtectionDomain domain,byte[] bytes) {
                if(redefining!=type) return null;
                var writer=new ClassWriter(ClassWriter.COMPUTE_MAXS);
                new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9,writer) {
                    @Override public MethodVisitor visitMethod(int a,String n,String d,String s,String[] e) {
                        return new MethodVisitor(Opcodes.ASM9,super.visitMethod(a,n,d,s,e)) {
                            @Override public void visitInsn(int opcode) {
                                if(opcode==Opcodes.RETURN && (n.equals("<init>") || n.equals("close"))) {
                                    super.visitFieldInsn(Opcodes.GETSTATIC,"com/onurkat/reclazz/spring/BootFourPropertyCheckTest",n.equals("<init>")?"created":"closed","Ljava/util/concurrent/atomic/AtomicInteger;");
                                    super.visitMethodInsn(Opcodes.INVOKEVIRTUAL,"java/util/concurrent/atomic/AtomicInteger","incrementAndGet","()I",false);
                                    super.visitInsn(Opcodes.POP);
                                }
                                super.visitInsn(opcode);
                            }
                        };
                    }
                },0);
                return writer.toByteArray();
            }
        };
        FactoryCounter() throws Exception {
            // Retransform live bytes so existing coverage instrumentation is retained.
            instrumentation.addTransformer(transformer,true);
            try { instrumentation.retransformClasses(type); }
            catch(Exception failure) { instrumentation.removeTransformer(transformer); throw failure; }
        }
        void reset(){created.set(0);closed.set(0);}
        public void close() throws Exception {instrumentation.removeTransformer(transformer); instrumentation.retransformClasses(type);}
    }
}
