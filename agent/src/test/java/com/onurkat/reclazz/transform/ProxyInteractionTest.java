package com.onurkat.reclazz.transform;

import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Spring AOP and other framework proxies wrap our trampolined classes.
 * Verify dispatch still works.
 */
class ProxyInteractionTest extends TransformTestBase {

    @Test
    void jdkDynamicProxyOverInterface() throws Exception {
        Map<String, byte[]> classes = compileAndTransform(
                new SourceFile("Greeter",
                        "public interface Greeter { String greet(String name); }"),
                new SourceFile("RealGreeter",
                        "public class RealGreeter implements Greeter {\n" +
                        "    public String greet(String name) { return \"hi \" + name; }\n" +
                        "}")
        );
        SharedLoader sl = sharedLoader(classes);
        Class<?> greeterCls = sl.load("Greeter");
        Class<?> realCls = sl.load("RealGreeter");
        Object real = realCls.getDeclaredConstructor().newInstance();

        Object proxy = Proxy.newProxyInstance(
                greeterCls.getClassLoader(),
                new Class<?>[]{greeterCls},
                (InvocationHandler) (p, method, args) -> {
                    Object inner = method.invoke(real, args);
                    return "[wrapped] " + inner;
                }
        );

        Method greetOnIface = greeterCls.getDeclaredMethod("greet", String.class);
        Object result = greetOnIface.invoke(proxy, "world");
        assertEquals("[wrapped] hi world", result);
    }

    @Test
    void manualSubclassDelegatingToTrampolinedParent() throws Exception {
        // Simulate CGLIB-style class proxying: subclass the trampolined class
        // and call super.method() from the override. The super call must
        // bypass the trampoline (otherwise infinite loop).
        Map<String, byte[]> classes = compileAndTransform(
                new SourceFile("Service",
                        "public class Service {\n" +
                        "    public String process(String input) { return \"raw:\" + input; }\n" +
                        "}"),
                new SourceFile("ServiceProxy",
                        "public class ServiceProxy extends Service {\n" +
                        "    @Override public String process(String input) {\n" +
                        "        return \"[intercepted] \" + super.process(input);\n" +
                        "    }\n" +
                        "    public static String run() { return new ServiceProxy().process(\"hello\"); }\n" +
                        "}")
        );
        Class<?> cls = defineAndLoad(classes, "ServiceProxy");
        assertEquals("[intercepted] raw:hello", invokeStatic(cls, "run"));
    }

    @Test
    void deeplyNestedSuperCalls() {
        // Level 3 inheritance, each level wrapping super
        Map<String, byte[]> classes = compileAndTransform(
                new SourceFile("L1",
                        "public class L1 {\n" +
                        "    public String label() { return \"L1\"; }\n" +
                        "}"),
                new SourceFile("L2",
                        "public class L2 extends L1 {\n" +
                        "    @Override public String label() { return super.label() + \">L2\"; }\n" +
                        "}"),
                new SourceFile("L3",
                        "public class L3 extends L2 {\n" +
                        "    @Override public String label() { return super.label() + \">L3\"; }\n" +
                        "    public static String run() { return new L3().label(); }\n" +
                        "}")
        );
        assertEquals("L1>L2>L3", invokeStatic(defineAndLoad(classes, "L3"), "run"));
    }
}
