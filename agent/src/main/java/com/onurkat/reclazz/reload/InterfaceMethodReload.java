/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.reload;

import com.onurkat.reclazz.bootstrap.MethodForge;
import com.onurkat.reclazz.bootstrap.ReflectionBridge;
import com.onurkat.reclazz.transform.InterfaceMethodCalls;
import com.onurkat.reclazz.transform.RawClassSchema;
import java.lang.invoke.*;
import java.lang.reflect.Method;
import java.util.*;
import java.util.function.Predicate;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Keeps the physical interface schema while publishing code for static and private additions. */
final class InterfaceMethodReload implements Opcodes {
    private InterfaceMethodReload() { }
    private record Key(String name,String descriptor,int access) {
        Key { access &= ~ACC_VARARGS; } // Varargs changes invocation adaptation, not the underlying method.
    }
    static final class History {
        private final Map<Key,MutableCallSite> sites=new HashMap<>();
        private Set<Key> active=Set.of();
        boolean hasMembers(){ return !active.isEmpty(); }
    }
    private static final ClassValue<History> HISTORY=new ClassValue<>() {
        @Override protected History computeValue(Class<?> owner){ return new History(); }
    };
    static History history(Class<?> owner){ return HISTORY.get(owner); }

    record Prepared(byte[] definition,Map<Key,MutableCallSite> sites,Map<Key,MethodHandle> targets,List<Method> methods) {
        Map<Method, MethodHandle> invocationTargets() {
            var handles = new HashMap<Method, MethodHandle>();
            for (Method method : methods) {
                Key key = new Key(method.getName(), Type.getMethodDescriptor(method), method.getModifiers());
                MethodHandle handle = sites.get(key).dynamicInvoker();
                if (method.isVarArgs()) handle = handle.asVarargsCollector(handle.type().lastParameterType());
                handles.put(method, handle);
            }
            return Map.copyOf(handles);
        }
        void publish(Class<?> owner,History history) {
            for (var entry:targets.entrySet()) sites.get(entry.getKey()).setTarget(entry.getValue());
            if (!targets.isEmpty()) MutableCallSite.syncAll(targets.keySet().stream().map(sites::get).toArray(MutableCallSite[]::new));
            history.sites.putAll(sites);
            history.active=methods.stream().map(m -> new Key(m.getName(),Type.getMethodDescriptor(m),m.getModifiers()))
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            ReflectionBridge.replaceClassState(owner,methods,List.of(),List.of());
        }
    }

    private static void checkFunctionalInterfaces(Class<?> owner, ClassNode node, List<MethodNode> added)
            throws ClassNotFoundException {
        if (added.isEmpty()) return;
        Set<String> logical = new HashSet<>();
        for (MethodNode method : added) logical.add(method.name + method.desc);
        for (MethodNode method : node.methods) {
            for (var instruction : method.instructions) {
                if (!(instruction instanceof InvokeDynamicInsnNode site)
                        || !site.bsm.getOwner().equals("java/lang/invoke/LambdaMetafactory")
                        || site.bsmArgs.length < 3 || !(site.bsmArgs[1] instanceof Handle impl)
                        || !node.name.equals(impl.getOwner()) || !logical.contains(impl.getName() + impl.getDesc()))
                    continue;
                Class<?> sam = Class.forName(Type.getReturnType(site.desc).getClassName(), false, owner.getClassLoader());
                if (!java.lang.reflect.Modifier.isPublic(sam.getModifiers()))
                    throw new IllegalArgumentException("non-public functional interface " + sam.getName()
                            + " refers to an added interface method; restart to apply");
                boolean serializable = site.bsm.getName().equals("altMetafactory") && site.bsmArgs.length > 3
                        && site.bsmArgs[3] instanceof Integer flags && (flags & LambdaMetafactory.FLAG_SERIALIZABLE) != 0;
                if (serializable || java.io.Serializable.class.isAssignableFrom(sam))
                    throw new IllegalArgumentException("serializable functional interface " + sam.getName()
                            + " refers to an added interface method; restart to apply");
            }
        }
    }

    private static String metadataSignature(String signature) {
        if (signature == null) return null;
        var declaration = new org.objectweb.asm.signature.SignatureWriter() {
            @Override public org.objectweb.asm.signature.SignatureVisitor visitInterface() {
                return new org.objectweb.asm.signature.SignatureVisitor(ASM9) {};
            }
        };
        new org.objectweb.asm.signature.SignatureReader(signature).accept(declaration);
        return declaration.toString();
    }

    static Prepared prepare(Class<?> owner, MethodHandles.Lookup lookup, byte[] incoming, RawClassSchema physical,
                            Predicate<String> watched, History history) throws ReflectiveOperationException {
        ClassNode node=new ClassNode();
        new ClassReader(incoming).accept(node,0);
        if (!watched.test(node.name))
            throw new IllegalArgumentException("the interface is outside the watched roots; restart to apply member changes");
        if ((node.access & ACC_ANNOTATION)!=0) throw new IllegalArgumentException("annotation interface member changes require a restart");
        var physicalMembers=new HashSet<>(physical.methods());
        var added=new ArrayList<MethodNode>();
        var privateInstance=new HashSet<String>();
        for (MethodNode m:node.methods) {
            if (physicalMembers.remove(new RawClassSchema.Member(m.name,m.desc,m.access&0xffff))) continue;
            added.add(m);
        }
        if (!physicalMembers.isEmpty()) throw new IllegalArgumentException("an original interface method was removed or changed in signature");
        for (MethodNode m : added) {
            if (m.name.startsWith("<") || (m.access & (ACC_ABSTRACT | ACC_NATIVE)) != 0
                    || (m.access & (ACC_PRIVATE | ACC_STATIC)) == 0) {
                throw new IllegalArgumentException("an interface method or field was added, removed or changed in signature; only static and private method additions are supported");
            }
            if ((m.access & ACC_STATIC) == 0) privateInstance.add(m.name + m.desc);
        }
        checkFunctionalInterfaces(owner, node, added);
        node.methods.removeAll(added);
        for (MethodNode m:node.methods) InterfaceMethodCalls.rewrite(m,node.name,watched,privateInstance);
        var original=new ClassWriter(0);node.accept(original);
        byte[] definition=original.toByteArray();
        if (!physical.equals(RawClassSchema.read(definition))) throw new IllegalArgumentException(
                "an interface method or field was added, removed or changed in signature; changes to the original schema require a restart");

        var sites=new HashMap<>(history.sites);
        var targets=new HashMap<Key,MethodHandle>();
        var methods=new ArrayList<Method>();
        if (!added.isEmpty()) {
            if (lookup==null || !lookup.hasFullPrivilegeAccess() || lookup.lookupClass()!=owner || !MethodForge.isAvailable())
                throw new IllegalArgumentException("interface additions require a prepared lookup in the defining module and reflection support");
            var holder=new ClassWriter(0);
            holder.visit(node.version,ACC_FINAL|ACC_SUPER|ACC_SYNTHETIC,node.name+"$$ReclazzInterfaceBody",null,"java/lang/Object",null);
            var carrier=new ClassWriter(0);
            // This interface carries method annotations and generic parameters. It is not a
            // subtype of the owner's parents, which may be sealed to the real owner.
            carrier.visit(node.version,ACC_PUBLIC|ACC_INTERFACE|ACC_ABSTRACT,node.name+"$$ReclazzInterfaceMetadata",
                    metadataSignature(node.signature),"java/lang/Object",null);
            for (int i=0;i<added.size();i++) {
                MethodNode m=added.get(i);
                var copy=carrier.visitMethod(m.access,m.name,m.desc,m.signature,m.exceptions.toArray(String[]::new));
                var metadata=new MethodNode(ASM9,m.access,m.name,m.desc,m.signature,m.exceptions.toArray(String[]::new));
                m.accept(metadata);
                metadata.instructions.clear(); metadata.tryCatchBlocks.clear(); metadata.localVariables=null;
                metadata.visibleLocalVariableAnnotations=null;metadata.invisibleLocalVariableAnnotations=null;
                metadata.instructions.add(new InsnNode(ACONST_NULL));metadata.instructions.add(new InsnNode(ATHROW));
                metadata.maxStack=1;metadata.accept(copy);
                InterfaceMethodCalls.rewrite(m,node.name,watched,privateInstance);
                for (var insn:m.instructions.toArray()) {
                    if (insn instanceof MethodInsnNode c && c.getOpcode()==INVOKESPECIAL && c.itf) {
                        if (!node.name.equals(c.owner)) throw new IllegalArgumentException("a direct superinterface call from an added method requires a restart");
                        c.setOpcode(INVOKEINTERFACE);
                    }
                }
                String desc=(m.access&ACC_STATIC)!=0?m.desc:"(L"+node.name+";"+m.desc.substring(1);
                var body=holder.visitMethod(ACC_STATIC,"body"+i,desc,null,null);
                // An instance method's receiver becomes local zero. Its argument annotations and
                // generic signature belong on the logical interface method, not on this static body.
                m.parameters=null;m.visibleParameterAnnotations=null;m.invisibleParameterAnnotations=null;
                m.visibleAnnotableParameterCount=0;m.invisibleAnnotableParameterCount=0;
                m.accept(body);
            }
            holder.visitEnd();carrier.visitEnd();
            var bodyLookup=lookup.defineHiddenClass(holder.toByteArray(),false,MethodHandles.Lookup.ClassOption.NESTMATE);
            Class<?> metadata=lookup.defineHiddenClass(carrier.toByteArray(),false,MethodHandles.Lookup.ClassOption.NESTMATE).lookupClass();
            MethodHandle initialize=MethodHandles.lookup().findVirtual(MethodHandles.Lookup.class,"ensureInitialized",
                    MethodType.methodType(Class.class,Class.class)).bindTo(lookup).bindTo(owner).asType(MethodType.methodType(void.class));
            for (int i=0;i<added.size();i++) {
                MethodNode m=added.get(i);
                MethodType declaration=MethodType.fromMethodDescriptorString(m.desc,owner.getClassLoader());
                boolean statik=(m.access&ACC_STATIC)!=0;
                MethodType type=statik?declaration:declaration.insertParameterTypes(0,owner);
                MethodHandle target=bodyLookup.findStatic(bodyLookup.lookupClass(),"body"+i,type);
                if (statik) target=MethodHandles.foldArguments(target,initialize);
                else {
                    MethodHandle check=MethodHandles.lookup().findStatic(Objects.class,"requireNonNull",MethodType.methodType(Object.class,Object.class));
                    target=MethodHandles.filterArguments(target,0,check.asType(MethodType.methodType(owner,owner)));
                }
                var key=new Key(m.name,m.desc,m.access&0xffff);
                targets.put(key,target);
                MutableCallSite site=sites.computeIfAbsent(key,k->new MutableCallSite(type));
                MethodHandle visible=site.dynamicInvoker();
                Method source=Arrays.stream(metadata.getDeclaredMethods()).filter(candidate->candidate.getName().equals(m.name)
                        && Type.getMethodDescriptor(candidate).equals(m.desc)).findFirst().orElseThrow();
                Method method=MethodForge.interfaceMethod(lookup, source, visible, m.signature);
                if (method==null) throw new IllegalStateException("interface method reflection could not be prepared");
                methods.add(method);
            }
        }
        for (Key previous:history.active) {
            if (targets.containsKey(previous)) continue;
            MethodType type=sites.get(previous).type();
            MethodHandle missing=MethodHandles.throwException(type.returnType(),NoSuchMethodError.class)
                    .bindTo(new NoSuchMethodError(owner.getName()+"."+previous.name()+previous.descriptor()));
            targets.put(previous,MethodHandles.dropArguments(missing,0,type.parameterList()));
        }
        return new Prepared(definition,sites,targets,List.copyOf(methods));
    }
}
