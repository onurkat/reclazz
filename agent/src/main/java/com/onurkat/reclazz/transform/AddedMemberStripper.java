package com.onurkat.reclazz.transform;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.Set;

/**
 * Removes members a reload added, leaving the rest of the new class as it is.
 *
 * The point is to produce something the JVM will accept redefining. A class
 * that gained a field or a method cannot be redefined, the JVM rejects any
 * schema change, so the companion engine keeps the new members outside the
 * loaded class and dispatches to them. But that left the loaded class holding
 * its *original* constructor bodies, because constructors cannot be moved to a
 * companion: their super() chains have to run on the real class.
 *
 * The consequence was quiet and wrong. Add a field with an initialiser, and an
 * object created after the reload still got the default value, because the
 * constructor that ran was the one compiled before the field existed. Not just
 * pre-existing instances, which is a documented and unavoidable limit, but new
 * ones, which is not.
 *
 * Stripping the added members gives a class whose shape matches what is loaded
 * and whose method bodies are the new ones. The registered transformer then
 * rewrites access to the added members onto the companion store during the
 * redefine, exactly as it does at load time, so the new constructor's
 * assignment to a new field lands where reads of that field will look for it.
 */
public final class AddedMemberStripper {

    private AddedMemberStripper() {
    }

    /**
     * @param addedFields  keys as {@code name:descriptor}
     * @param addedMethods keys as {@code name:descriptor}
     */
    public static byte[] strip(byte[] newBytecode, Set<String> addedFields, Set<String> addedMethods) {
        if ((addedFields == null || addedFields.isEmpty())
                && (addedMethods == null || addedMethods.isEmpty())) {
            return newBytecode;
        }

        ClassReader reader = new ClassReader(newBytecode);
        ClassWriter writer = new ClassWriter(0);
        reader.accept(new Stripper(writer, addedFields, addedMethods), 0);
        return writer.toByteArray();
    }

    private static final class Stripper extends ClassVisitor {
        private final Set<String> fields;
        private final Set<String> methods;

        Stripper(ClassVisitor cv, Set<String> fields, Set<String> methods) {
            super(Opcodes.ASM9, cv);
            this.fields = fields == null ? Set.of() : fields;
            this.methods = methods == null ? Set.of() : methods;
        }

        @Override
        public FieldVisitor visitField(int access, String name, String descriptor,
                                       String signature, Object value) {
            if (fields.contains(name + ":" + descriptor)) {
                return null;
            }
            return super.visitField(access, name, descriptor, signature, value);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                         String signature, String[] exceptions) {
            // Constructors are the reason this class exists: they must survive
            // so their new bodies reach the loaded class. Only members the
            // reload introduced are dropped.
            if (!"<init>".equals(name) && !"<clinit>".equals(name)
                    && methods.contains(name + ":" + descriptor)) {
                return null;
            }
            return super.visitMethod(access, name, descriptor, signature, exceptions);
        }
    }
}
