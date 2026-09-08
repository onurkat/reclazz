/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.util.Reflect;

import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.Set;

/** The arithmetic-only subset of @Value SpEL that property reload can evaluate. */
final class PropertyValueExpression {
    private static final String AST = "org.springframework.expression.spel.ast.";
    private static final String PARSER = "org.springframework.expression.spel.standard.SpelExpressionParser";
    private static final String RESOLVER = "org.springframework.context.expression.StandardBeanExpressionResolver";
    private static final int MAX_LENGTH = 2048;
    private static final int MAX_NODES = 256;
    private static final int MAX_DEPTH = 32;
    private static final Set<String> NODES = Set.of(
            "IntLiteral", "LongLiteral", "FloatLiteral", "RealLiteral", "StringLiteral", "BooleanLiteral", "NullLiteral",
            "OpPlus", "OpMinus", "OpMultiply", "OpDivide", "OpModulus",
            "OpEQ", "OpNE", "OpLT", "OpLE", "OpGT", "OpGE", "OpAnd", "OpOr", "OperatorNot", "Ternary", "Elvis");
    private static final Set<Class<?>> SCALARS = Set.of(
            String.class, Boolean.class, Character.class, Byte.class, Short.class,
            Integer.class, Long.class, Float.class, Double.class);

    private PropertyValueExpression() { }

    /** An unsupported declaration is uncheckable, not a bad property value. */
    static final class Unsupported extends Exception {
        Unsupported(String message) { super("@Value expression needs a restart: " + message); }
    }

    static boolean isExpression(String value) { return value.contains("#{"); }

    static Object evaluate(Object context, SpringPropertyRebinder.ValueTarget target, Object resolved) throws Exception {
        if (target.unsupportedReason() != null) throw new Unsupported(target.unsupportedReason());
        // A constructor placeholder may itself resolve to SpEL. Spring will
        // evaluate that at recreation too, so check it before any destruction.
        if (!isExpression(target.expression()) && !(target.field() == null
                && resolved instanceof String text && isExpression(text))) return resolved;
        if (target.field() != null && (Modifier.isStatic(target.field().getModifiers())
                || Modifier.isFinal(target.field().getModifiers())))
            throw new Unsupported("only writable instance fields are supported");
        if (!target.type().isPrimitive() && !SCALARS.contains(target.type()))
            throw new Unsupported("only primitive, boxed primitive and String values are supported");
        if (!(resolved instanceof String text)) throw new Unsupported("placeholder resolution did not return text");
        if (text.length() > MAX_LENGTH) throw new Unsupported("expression exceeds " + MAX_LENGTH + " characters");
        if (!text.startsWith("#{") || !text.endsWith("}") || text.indexOf("#{", 2) >= 0)
            throw new Unsupported("only a single whole #{...} expression is supported");

        Object factory = SpringBeans.getBeanFactory(context);
        Object resolver = PropertyChangeCheck.call(factory, "getBeanExpressionResolver");
        if (resolver == null || !resolver.getClass().getName().equals(RESOLVER)
                || !"#{".equals(Reflect.readField(resolver, "expressionPrefix"))
                || !"}".equals(Reflect.readField(resolver, "expressionSuffix")))
            throw new Unsupported("custom or unavailable bean expression resolver");
        Object parser = Reflect.readField(resolver, "expressionParser");
        if (parser == null || !parser.getClass().getName().equals(PARSER))
            throw new Unsupported("custom or unavailable expression parser");

        // Parse only. Never call the bean resolver's evaluate: its context
        // exposes application beans, methods, types, and constructors.
        Object expression = PropertyChangeCheck.call(parser, "parseRaw", text.substring(2, text.length() - 1));
        Object root = PropertyChangeCheck.call(expression, "getAST");
        record Node(Object value, int depth) { }
        var pending = new ArrayDeque<Node>();
        pending.add(new Node(root, 1));
        int count = 0;
        while (!pending.isEmpty()) {
            Node node = pending.removeFirst();
            if (++count > MAX_NODES || node.depth() > MAX_DEPTH)
                throw new Unsupported("expression exceeds the node/depth limit");
            String type = node.value().getClass().getName();
            if (!type.startsWith(AST) || !NODES.contains(type.substring(AST.length())))
                throw new Unsupported("operation " + node.value().getClass().getSimpleName() + " is unsupported");
            int children = (Integer) PropertyChangeCheck.call(node.value(), "getChildCount");
            // Check every branch, including one evaluation would short-circuit.
            for (int i = 0; i < children; i++)
                pending.addLast(new Node(node.value().getClass().getMethod("getChild", int.class)
                        .invoke(node.value(), i), node.depth() + 1));
        }

        ClassLoader loader = parser.getClass().getClassLoader();
        Class<?> simple = Class.forName("org.springframework.expression.spel.support.SimpleEvaluationContext", true, loader);
        Object builder = simple.getMethod("forReadOnlyDataBinding").invoke(null);
        // StandardBeanExpressionResolver uses the factory's ConversionService
        // inside expressions as well as during final field conversion.
        Object conversion = PropertyChangeCheck.call(factory, "getConversionService");
        if (conversion != null) PropertyChangeCheck.call(builder, "withConversionService", conversion);
        Object evaluationContext = PropertyChangeCheck.call(builder, "build");
        Class<?> evaluationType = Class.forName("org.springframework.expression.EvaluationContext", false, loader);
        Object value = expression.getClass().getMethod("getValue", evaluationType).invoke(expression, evaluationContext);
        if ((value instanceof Double d && !Double.isFinite(d))
                || (value instanceof Float f && !Float.isFinite(f)))
            throw new IllegalArgumentException("@Value expression produced a non-finite number");
        if (value instanceof String string && string.length() > MAX_LENGTH)
            throw new Unsupported("expression result exceeds " + MAX_LENGTH + " characters");
        return value;
    }
}
