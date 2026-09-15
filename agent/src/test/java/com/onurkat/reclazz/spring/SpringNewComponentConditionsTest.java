/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.platform.PlatformContext;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.stereotype.Component;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Also inherited on the isolated Spring 6 classpath. Fixtures must be top-level. */
public class SpringNewComponentConditionsTest {
    static int created;

    @Test
    void profilesMatchNativeStartupIncludingExpressionsDefaultsAndComposedAnnotations() throws Exception {
        for (String profile : List.of("", "prod", "test")) {
            for (Class<?> type : List.of(ConditionalProfileComponent.class,
                    ConditionalDefaultComponent.class, ConditionalComposedComponent.class)) {
                boolean expected;
                created = 0;
                try (var nativeContext = new AnnotationConfigApplicationContext()) {
                    if (!profile.isEmpty()) nativeContext.getEnvironment().setActiveProfiles(profile);
                    nativeContext.register(type);
                    nativeContext.refresh();
                    expected = !nativeContext.getBeansOfType(type).isEmpty();
                    assertEquals(expected ? 1 : 0, created);
                }
                created = 0;
                try (var context = new AnnotationConfigApplicationContext()) {
                    if (!profile.isEmpty()) context.getEnvironment().setActiveProfiles(profile);
                    context.refresh();
                    var before = Set.of(context.getBeanDefinitionNames());
                    assertEquals(expected ? SpringNewBeanRegistrar.Outcome.REGISTERED
                                    : SpringNewBeanRegistrar.Outcome.FILTERED,
                            register(context, type), type + " profile=" + profile);
                    assertEquals(expected ? 1 : 0, created);
                    assertEquals(expected, !context.getBeansOfType(type).isEmpty());
                    if (!expected) assertEquals(before, Set.of(context.getBeanDefinitionNames()));
                }
            }
        }
    }

    @Test
    void customConditionsReadTheLiveEnvironmentRegistryFactoryResourcesAndCapturedLoader() throws Exception {
        for (boolean enabled : List.of(false, true)) {
            created = 0;
            try (var context = new AnnotationConfigApplicationContext()) {
                context.getEnvironment().getPropertySources().addFirst(
                        new MapPropertySource("fixture", Map.of("feature.enabled", Boolean.toString(enabled))));
                context.addProtocolResolver((location, loader) -> location.equals("fixture:enabled")
                        ? new ByteArrayResource(new byte[]{1}) : null);
                context.registerBean("marker", String.class, () -> "marker");
                context.refresh();
                // The context's fallback loader can differ from the factory's captured loader.
                ClassLoader captured = new ClassLoader(context.getClassLoader()) { };
                context.getBeanFactory().setBeanClassLoader(captured);
                ContextCondition.factory = context.getBeanFactory();
                ContextCondition.environment = context.getEnvironment();
                ContextCondition.loader = captured;
                var before = Set.of(context.getBeanDefinitionNames());
                assertEquals(enabled ? SpringNewBeanRegistrar.Outcome.REGISTERED
                        : SpringNewBeanRegistrar.Outcome.FILTERED, register(context, ConditionalContextComponent.class));
                assertEquals(enabled ? 1 : 0, created);
                if (!enabled) assertEquals(before, Set.of(context.getBeanDefinitionNames()));
                assertEquals("marker", context.getBean("marker"));
            }
        }
    }

    @Test
    void bothConfigurationPhasesCanVetoRegistrationLikeNativeStartup() throws Exception {
        for (Class<?> type : List.of(ConditionalParseComponent.class, ConditionalRegisterComponent.class)) {
            created = 0;
            try (var nativeContext = new AnnotationConfigApplicationContext(type)) {
                assertTrue(nativeContext.getBeansOfType(type).isEmpty());
                assertEquals(0, created);
            }
            try (var context = new AnnotationConfigApplicationContext()) {
                context.refresh();
                var before = Set.of(context.getBeanDefinitionNames());
                assertEquals(SpringNewBeanRegistrar.Outcome.FILTERED, register(context, type));
                assertEquals(before, Set.of(context.getBeanDefinitionNames()));
                assertEquals(0, created);
            }
        }
    }

    @Test
    void nativeConditionOrderingShortCircuitsBeforeTheThrowingCondition() throws Exception {
        ThrowCondition.calls = 0;
        try (var nativeContext = new AnnotationConfigApplicationContext(ConditionalOrderedComponent.class)) {
            assertTrue(nativeContext.getBeansOfType(ConditionalOrderedComponent.class).isEmpty());
            assertEquals(0, ThrowCondition.calls);
        }
        try (var context = new AnnotationConfigApplicationContext()) {
            context.refresh();
            assertEquals(SpringNewBeanRegistrar.Outcome.FILTERED, register(context, ConditionalOrderedComponent.class));
            assertEquals(0, ThrowCondition.calls);
            assertTrue(context.getBeansOfType(ConditionalOrderedComponent.class).isEmpty());
        }
    }

    @Test
    void conditionErrorsDeclineWithoutConstructingOrRegisteringTheComponent() throws Exception {
        ThrowCondition.calls = 0;
        created = 0;
        try (var context = new AnnotationConfigApplicationContext()) {
            context.refresh();
            var before = Set.of(context.getBeanDefinitionNames());
            assertEquals(SpringNewBeanRegistrar.Outcome.CONDITION_FAILED, register(context, ConditionalThrowComponent.class));
            assertEquals(1, ThrowCondition.calls, "the condition must actually have run");
            assertEquals(before, Set.of(context.getBeanDefinitionNames()));
            assertEquals(0, created);
        }
    }

    @Test
    void anExistingDefinitionIsNotReplacedOrReconditioned() throws Exception {
        ThrowCondition.calls = 0;
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean("conditionalThrowComponent", String.class, () -> "original");
            context.refresh();
            assertEquals(SpringNewBeanRegistrar.Outcome.DECLINED, register(context, ConditionalThrowComponent.class));
            assertEquals(0, ThrowCondition.calls);
            assertEquals("original", context.getBean("conditionalThrowComponent"));
        }
    }

    @Test
    void conditionEvaluationDoesNotInstallAnnotationInfrastructureInAGenericContext() throws Exception {
        for (boolean enabled : List.of(false, true)) {
            try (var context = new GenericApplicationContext()) {
                context.getEnvironment().setActiveProfiles(enabled ? "prod" : "test");
                context.refresh();
                assertEquals(0, context.getBeanDefinitionCount());
                assertEquals(enabled ? SpringNewBeanRegistrar.Outcome.REGISTERED
                        : SpringNewBeanRegistrar.Outcome.FILTERED, register(context, ConditionalProfileComponent.class));
                assertEquals(enabled ? Set.of("conditionalProfileComponent") : Set.of(),
                        Set.of(context.getBeanDefinitionNames()));
            }
        }
    }

    static SpringNewBeanRegistrar.Outcome register(GenericApplicationContext context, Class<?> type)
            throws Exception {
        PlatformContext platform = (PlatformContext) Proxy.newProxyInstance(
                PlatformContext.class.getClassLoader(), new Class[]{PlatformContext.class},
                (p, m, a) -> m.getName().equals("getAllApplicationContexts") ? List.of(context) : null);
        try (var in = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
            return new SpringNewBeanRegistrar(platform, new SpringMvcReloader(platform))
                    .registerIfComponent(type.getName(), java.util.Objects.requireNonNull(in).readAllBytes());
        }
    }

    public static class ContextCondition implements Condition {
        static Object factory, environment;
        static ClassLoader loader;
        @Override public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            assertSame(factory, context.getBeanFactory());
            assertSame(factory, context.getRegistry());
            assertSame(environment, context.getEnvironment());
            assertSame(loader, context.getClassLoader());
            assertTrue(context.getRegistry().containsBeanDefinition("marker"));
            assertTrue(context.getResourceLoader().getResource("fixture:enabled").exists());
            assertTrue(metadata.isAnnotated(Component.class.getName()));
            return Boolean.parseBoolean(context.getEnvironment().getProperty("feature.enabled"));
        }
    }

    public static class ParseVeto implements ConfigurationCondition {
        @Override public ConfigurationPhase getConfigurationPhase() { return ConfigurationPhase.PARSE_CONFIGURATION; }
        @Override public boolean matches(ConditionContext c, AnnotatedTypeMetadata m) { return false; }
    }
    public static class RegisterVeto implements ConfigurationCondition {
        @Override public ConfigurationPhase getConfigurationPhase() { return ConfigurationPhase.REGISTER_BEAN; }
        @Override public boolean matches(ConditionContext c, AnnotatedTypeMetadata m) { return false; }
    }
    public static class EarlyVeto implements Condition, Ordered {
        @Override public int getOrder() { return Ordered.HIGHEST_PRECEDENCE; }
        @Override public boolean matches(ConditionContext c, AnnotatedTypeMetadata m) { return false; }
    }
    public static class ThrowCondition implements Condition {
        static int calls;
        @Override public boolean matches(ConditionContext c, AnnotatedTypeMetadata m) {
            calls++;
            throw new IllegalStateException("fixture condition failure");
        }
    }
}

@Component @Profile("prod & !test")
class ConditionalProfileComponent {
    ConditionalProfileComponent() { SpringNewComponentConditionsTest.created++; }
}
@Component @Profile("default")
class ConditionalDefaultComponent {
    ConditionalDefaultComponent() { SpringNewComponentConditionsTest.created++; }
}
@Component @Profile({"prod", "test"}) @Retention(RetentionPolicy.RUNTIME)
@interface ConditionalStereotype { }
@ConditionalStereotype
class ConditionalComposedComponent {
    ConditionalComposedComponent() { SpringNewComponentConditionsTest.created++; }
}
@Component @Conditional(SpringNewComponentConditionsTest.ContextCondition.class)
class ConditionalContextComponent {
    ConditionalContextComponent() { SpringNewComponentConditionsTest.created++; }
}
@Component @Conditional(SpringNewComponentConditionsTest.ParseVeto.class)
class ConditionalParseComponent {
    ConditionalParseComponent() { SpringNewComponentConditionsTest.created++; }
}
@Component @Conditional(SpringNewComponentConditionsTest.RegisterVeto.class)
class ConditionalRegisterComponent {
    ConditionalRegisterComponent() { SpringNewComponentConditionsTest.created++; }
}
@Component @Conditional({SpringNewComponentConditionsTest.ThrowCondition.class, SpringNewComponentConditionsTest.EarlyVeto.class})
class ConditionalOrderedComponent { }
@Component @Conditional(SpringNewComponentConditionsTest.ThrowCondition.class)
class ConditionalThrowComponent {
    ConditionalThrowComponent() { SpringNewComponentConditionsTest.created++; }
}
