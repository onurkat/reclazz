/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.bootstrap;

import com.onurkat.reclazz.transform.TransformTestBase;
import org.junit.jupiter.api.Test;
import java.lang.invoke.MethodHandles;
import static org.junit.jupiter.api.Assertions.*;

class FieldDeclarationResolutionTest extends TransformTestBase {
    @Test void descriptorInterfaceOrderShadowingAndAccessRemainDistinct() throws Throwable {
        var bytes=compile(
                new SourceFile("sample.Parent","package sample;public class Parent {public static int shared=19;public int value=7;public long sameName=23;private int secret=29;}"),
                new SourceFile("sample.Trait","package sample;public interface Trait {int shared=31;}"),
                new SourceFile("sample.Child","package sample;public class Child extends Parent implements Trait {public int value=17;public String sameName=\"child\";}"));
        var loader=sharedLoader(bytes);Class<?> child=loader.load("sample.Child");Object value=child.getConstructor().newInstance();
        var lookup=MethodHandles.lookup();
        assertEquals(31,ProtectedCallResolver.resolveField(lookup,child,"shared",int.class,true,false).invoke());
        assertEquals(17,ProtectedCallResolver.resolveField(lookup,child,"value",int.class,false,false).invoke(value));
        assertEquals(23L,ProtectedCallResolver.resolveField(lookup,child,"sameName",long.class,false,false).invoke(value));
        assertThrows(IllegalAccessException.class,()->ProtectedCallResolver.resolveField(lookup,child,"secret",int.class,false,false));
        assertThrows(IncompatibleClassChangeError.class,()->ProtectedCallResolver.resolveField(lookup,child,"value",int.class,true,false));
    }
}
