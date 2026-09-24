// Copyright 2026 Onur Kat
// SPDX-License-Identifier: Apache-2.0
import java.lang.instrument.ClassDefinition;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.Files;
import java.nio.file.Path;

/** A prewired research experiment, not a Reclazz transformer or feature. */
public class Probe {
    public static void main(String[] args) throws Throwable {
        result("runtime", System.getProperty("java.runtime.version"));
        result("vendor", System.getProperty("java.vendor"));
        result("vm", System.getProperty("java.vm.name"));
        result("os", System.getProperty("os.name"));
        result("arch", System.getProperty("os.arch"));
        require(ProbeAgent.inst != null && ProbeAgent.inst.isRedefineClassesSupported(), "redefinition unavailable");
        require(ProbeAgent.inst.isModifiableClass(C.class), "C must be modifiable");
        C original = new C();
        Object keptReference = original;
        Class<?> keptClass = original.getClass();
        ClassLoader keptLoader = keptClass.getClassLoader();
        require(original.value().equals("v1:A:7"), "baseline");
        result("baseline", original.value());

        // A disabled/misconfigured agent must not masquerade as a hierarchy-specific refusal.
        ProbeAgent.inst.redefineClasses(new ClassDefinition(C.class, Files.readAllBytes(Path.of(args[0]))));
        require(original.value().equals("v1:A:7"), "identical-byte redefine changed behavior");
        result("controlRedefine", "accepted");
        boolean rejected = false;
        try {
            ProbeAgent.inst.redefineClasses(new ClassDefinition(C.class, Files.readAllBytes(Path.of(args[1]))));
            result("directRedefine", "accepted");
        } catch (UnsupportedOperationException error) {
            result("directRedefine", "rejected");
            result("directDetail", error.getMessage());
            require(error.getMessage() != null && error.getMessage().contains("superclass or interfaces"),
                    "unexpected refusal; do not classify it as hierarchy evidence");
            rejected = true;
        }
        require(rejected, "this runtime differs from the recorded refusal; inspect instead of claiming a universal limit");
        require(C.class.getSuperclass() == A.class, "loaded hierarchy changed");
        require(original.value().equals("v1:A:7"), "failed redefine changed live behavior");

        Class<?> hidden = MethodHandles.lookup()
                .defineHiddenClass(Files.readAllBytes(Path.of(args[2])), true).lookupClass();
        Object delegate = hidden.getDeclaredConstructor().newInstance();
        require(hidden.isHidden() && hidden.getSuperclass() == B.class, "expected hidden B delegate");
        Router.target = MethodHandles.lookup()
                .findVirtual(hidden, "apply", MethodType.methodType(String.class, C.class)).bindTo(delegate);
        require(original.value().equals("v2:B:7"), "delegated new-base behavior with original state");
        require(original == keptReference && original.getClass() == keptClass, "original object/class changed");
        require(original.getClass().getClassLoader() == keptLoader, "original loader changed");
        require(hidden.getClassLoader() == keptLoader, "hidden delegate used a different loader");
        require(delegate != keptReference, "delegate is a separate receiver, not the original object");
        require(C.class.getSuperclass() == A.class, "delegation changed native superclass");
        require(!(keptReference instanceof B) && !B.class.isInstance(original), "native instanceof changed");
        boolean castRejected = false;
        try { B.class.cast(keptReference); }
        catch (ClassCastException expected) { castRejected = true; }
        require(castRejected, "native cast unexpectedly accepted original as B");
        require(original.who().equals("A"), "unrouted inherited call changed");
        result("delegatedValue", original.value());
        result("sameObject", original == keptReference);
        result("sameClass", original.getClass() == keptClass);
        result("sameOriginalLoader", original.getClass().getClassLoader() == keptLoader);
        result("sameDelegateLoader", hidden.getClassLoader() == keptLoader);
        result("hiddenDelegate", hidden.isHidden());
        result("separateReceiver", delegate != keptReference);
        result("nativeSuperclass", C.class.getSuperclass().getName());
        result("instanceofB", keptReference instanceof B);
        result("castBRejected", castRejected);
        result("inheritedCall", original.who());
        original.state = 11;
        require(original.value().equals("v2:B:11"), "delegate must read the original, not a copied state value");
        result("afterOriginalStateChange", original.value());
        result("passed", true);
    }

    static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    static void result(String name, Object value) {
        System.out.println("RESULT " + name + "=" + value);
    }
}
