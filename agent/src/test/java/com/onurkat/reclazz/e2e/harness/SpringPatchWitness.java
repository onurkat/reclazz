/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.e2e.harness;

import java.util.List;

public final class SpringPatchWitness {
    private SpringPatchWitness() { }

    private static final List<String> REQUIRED = List.of("org.springframework.core.SpringVersion",
            "org.springframework.beans.factory.BeanFactory", "org.springframework.context.ApplicationContext",
            "org.springframework.aop.TargetSource");
    private static final String ORM = "org.springframework.orm.jpa.support.PersistenceAnnotationBeanPostProcessor";

    public static void verify() throws Exception {
        String patch = System.getProperty("reclazz.springPatch");
        if (patch == null) return;
        ClassLoader loader = SpringPatchWitness.class.getClassLoader();
        for (String name : REQUIRED) check(Class.forName(name, false, loader), patch);
        try { check(Class.forName(ORM, false, loader), patch); }
        catch (ClassNotFoundException absent) { /* Optional on the XML graph. */ }
        if (!patch.equals(org.springframework.core.SpringVersion.getVersion()))
            throw new AssertionError("SpringVersion does not report " + patch);
    }

    private static void check(Class<?> type, String patch) {
        String selected = type.getPackage().getImplementationVersion();
        if (!patch.equals(selected)) throw new AssertionError(type.getName() + " selected " + selected + ", expected " + patch);
    }

    public static String instrumentMain(String source) {
        String patch = System.getProperty("reclazz.springPatch");
        if (patch == null) return source;
        if (!patch.matches("[0-9]+[.][0-9]+[.][0-9]+")) throw new IllegalArgumentException(patch);
        String main = "public static void main(String[] args) throws Exception {";
        if (!source.contains(main)) throw new AssertionError("No fixture main to verify");
        StringBuilder code = new StringBuilder(main);
        for (String name : REQUIRED) code.append("if (!\"").append(patch).append("\".equals(")
                .append(name).append(".class.getPackage().getImplementationVersion())) throw new AssertionError(\"")
                .append(name).append(" runtime version\");");
        code.append("if (!\"").append(patch).append("\".equals(org.springframework.core.SpringVersion.getVersion())) throw new AssertionError(\"SpringVersion\");");
        code.append("try {Class<?> orm=Class.forName(\"").append(ORM)
                .append("\",false,App.class.getClassLoader());if(!\"").append(patch)
                .append("\".equals(orm.getPackage().getImplementationVersion()))throw new AssertionError(\"ORM runtime version\");} catch(ClassNotFoundException absent){}");
        return source.replace(main,code);
    }
}
