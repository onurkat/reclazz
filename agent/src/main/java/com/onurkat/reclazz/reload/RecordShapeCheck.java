/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.reload;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.ClassNode;
import java.util.*;

/** Native record components cannot be changed by ordinary JVM redefinition. */
public final class RecordShapeCheck {
    private RecordShapeCheck() { }
    public record Shape(boolean record, List<String> components) {
        public Shape { components=List.copyOf(components); }
    }
    public static Shape incoming(byte[] bytes) {
        var node=new ClassNode();new ClassReader(bytes).accept(node,ClassReader.SKIP_CODE|ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);
        return new Shape((node.access&Opcodes.ACC_RECORD)!=0,node.recordComponents==null?List.of():node.recordComponents.stream().map(c->c.name+":"+c.descriptor).toList());
    }
    public static Shape live(Class<?> type) {
        return new Shape(type.isRecord(),type.isRecord()?Arrays.stream(type.getRecordComponents()).map(c->c.getName()+":"+Type.getDescriptor(c.getType())).toList():List.of());
    }
    public static boolean changed(Class<?> type,byte[] bytes) { return !live(type).equals(incoming(bytes)); }
}
