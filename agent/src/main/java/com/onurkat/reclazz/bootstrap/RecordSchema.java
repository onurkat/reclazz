/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.bootstrap;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** Dormant record publication substrate. Bootstrap code: JDK dependencies only. */
public final class RecordSchema {
    private static final StackWalker WALKER=StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
    private static final ClassValue<Slot> slots=new ClassValue<>() {
        @Override protected Slot computeValue(Class<?> owner) {
            if(!owner.isRecord())throw new IllegalArgumentException("Not a record: "+owner.getName());
            List<Component> components=new ArrayList<>();
            for(var component:ReflectionBridge.physicalRecordComponents(owner))
                components.add(new Component(component.getName(),component.getType(),component.getAccessor()));
            return new Slot(owner,new Members(components,List.of(),List.of(),List.of(),Map.of(),Map.of(),Map.of()));
        }
    };
    // Caller-owned storage: a shared parent record must not retain a disposable child caller.
    private static final ClassValue<Map<Slot,Map<String,CallerSite>>> callers=new ClassValue<>() {
        @Override protected Map<Slot,Map<String,CallerSite>> computeValue(Class<?> owner){return new IdentityHashMap<>();}
    };
    private RecordSchema() { }
    private static void trusted(Class<?> caller){LookupCapture.requireTrusted(caller,"accessing record publication state");}

    public record Component(String name,Class<?> type,Method accessor) {
        public Component {
            Objects.requireNonNull(name);Objects.requireNonNull(type);Objects.requireNonNull(accessor);
            if(!accessor.getName().equals(name) || accessor.getParameterCount()!=0 || accessor.getReturnType()!=type)
                throw new IllegalArgumentException("Record accessor type: "+name);
        }
    }
    /**
     * Record-owned members only; caller members and targets belong in caller-owned histories.
     * Handle maps accept only direct, unadapted record/trusted-nest or bootstrap executable
     * entries. Bound/composed handles are deliberately rejected: their captured data cannot be
     * proved safe by inspecting the handle's apparent signature. Enabling adapters must retain
     * checked direct initializers/factories here, or introduce a provenance-preserving factory
     * with a real lifetime witness; never bypass this ownership boundary for a generic adapter.
     */
    public record Members(List<Component> components,List<Method> methods,List<Field> fields,
                          List<Constructor<?>> constructors,Map<String,MethodHandle> constructorTargets,
                          Map<String,MethodHandle> initializerTargets,Map<String,MethodHandle> objectMethods) {
        public Members {
            components=List.copyOf(components);methods=List.copyOf(methods);fields=List.copyOf(fields);
            constructors=List.copyOf(constructors);constructorTargets=Map.copyOf(constructorTargets);
            initializerTargets=Map.copyOf(initializerTargets);objectMethods=Map.copyOf(objectMethods);
            if(components.stream().map(Component::name).distinct().count()!=components.size())
                throw new IllegalArgumentException("Duplicate record component");
        }
    }
    public static Slot slot(Class<?> record) {
        trusted(WALKER.getCallerClass());return slots.get(Objects.requireNonNull(record));
    }
    public static CallerSite callerSite(Class<?> caller,Slot slot,String key,MethodHandle beforeInstall) {
        trusted(WALKER.getCallerClass());Objects.requireNonNull(caller);Objects.requireNonNull(slot);
        Objects.requireNonNull(key);Objects.requireNonNull(beforeInstall);
        Map<Slot,Map<String,CallerSite>> owned=callers.get(caller);
        synchronized(owned) {
            var sites=owned.computeIfAbsent(slot,ignored->new HashMap<>());
            CallerSite site=sites.computeIfAbsent(key,ignored->new CallerSite(slot,beforeInstall));
            if(!site.type.equals(beforeInstall.type()))throw new IllegalArgumentException("Caller site type changed: "+key);
            return site;
        }
    }
    private static void validateTargetOwner(Class<?> owner,MethodHandle target) {
        final java.lang.reflect.Member member;
        try {member=MethodHandles.reflectAs(java.lang.reflect.Member.class,target);}
        catch(IllegalArgumentException unavailable){throw new IllegalArgumentException("Record target must be direct and unadapted",unavailable);}
        Class<?> declaring=member.getDeclaringClass();
        boolean sameRecord=declaring==owner || declaring.isHidden()
                && declaring.getClassLoader()==owner.getClassLoader() && declaring.getNestHost()==owner.getNestHost();
        boolean bootstrap=declaring.getClassLoader()==null || declaring==RecordSchema.class || declaring==MemberForge.class;
        if(!sameRecord&&!bootstrap)throw new IllegalArgumentException("Record target belongs to another owner: "+declaring.getName());
        MethodType declared;
        if(member instanceof Method method) {
            declared=MethodType.methodType(method.getReturnType(),method.getParameterTypes());
            if(!java.lang.reflect.Modifier.isStatic(method.getModifiers()))declared=declared.insertParameterTypes(0,declaring);
        } else if(member instanceof Constructor<?> constructor) {
            declared=MethodType.methodType(declaring,constructor.getParameterTypes());
        } else throw new IllegalArgumentException("Record target must be an executable member");
        // Even an implementation that cracks a type-adapted direct handle must not let its
        // MethodType retain a foreign loader which the underlying member never referenced.
        if(!declared.equals(target.type()))throw new IllegalArgumentException("Record target type was adapted");
    }

    public static final class State {
        private final long sequence;
        private final Members members;
        private volatile boolean committed,aborted;
        private State(long sequence,Members members){this.sequence=sequence;this.members=members;}
        public long sequence(){return sequence;}
        public Members members(){trusted(WALKER.getCallerClass());return members;}
    }
    public static final class Slot {
        private final Class<?> owner;
        private final List<Component> physical;
        private final AtomicReference<State> active;
        private Slot(Class<?> owner,Members original) {
            this.owner=owner;physical=original.components();State initial=new State(0,original);
            initial.committed=true;active=new AtomicReference<>(initial);
        }
        public State current(){trusted(WALKER.getCallerClass());return active.get();}
        public List<Component> physicalComponents(){trusted(WALKER.getCallerClass());return physical;}
        public synchronized Preparation prepare(Members members) {
            trusted(WALKER.getCallerClass());Objects.requireNonNull(members);
            for(Component c:members.components())if(c.accessor().getDeclaringClass()!=owner)
                throw new IllegalArgumentException("Record accessor belongs to another class");
            for(Method m:members.methods())if(m.getDeclaringClass()!=owner)throw new IllegalArgumentException("Foreign method root");
            for(Field f:members.fields())if(f.getDeclaringClass()!=owner)throw new IllegalArgumentException("Foreign field root");
            for(Constructor<?> c:members.constructors())if(c.getDeclaringClass()!=owner)throw new IllegalArgumentException("Foreign constructor root");
            for(var handles:List.of(members.constructorTargets(),members.initializerTargets(),members.objectMethods()))
                for(MethodHandle target:handles.values())validateTargetOwner(owner,target);
            State current=active.get();return new Preparation(this,current,new State(current.sequence+1,members));
        }
        private synchronized boolean activate(Preparation preparation) {
            if(!active.compareAndSet(preparation.previous,preparation.next))return false;
            // No fallible work, callbacks or next activation between CAS and this publication flag.
            preparation.next.committed=true;return true;
        }
    }
    public static final class Preparation implements AutoCloseable {
        private final Slot slot;
        private final State previous,next;
        private final List<CallerSite> touched=new ArrayList<>();
        private boolean finished;
        private Preparation(Slot slot,State previous,State next){this.slot=slot;this.previous=previous;this.next=next;}
        public synchronized void stage(CallerSite site,MethodHandle target) {
            trusted(WALKER.getCallerClass());if(finished)throw new IllegalStateException("Record preparation is closed");
            Objects.requireNonNull(site);Objects.requireNonNull(target);
            if(site.slot!=slot)throw new IllegalArgumentException("Caller belongs to another record cohort");
            if(!site.type.equals(target.type()))throw new IllegalArgumentException("Caller target type changed");
            // Register cleanup ownership before a site can see the prepared entry.
            if(!touched.contains(site))touched.add(site);
            site.stage(next,target);
        }
        public synchronized boolean commit() {
            trusted(WALKER.getCallerClass());if(finished)throw new IllegalStateException("Record preparation is closed");
            if(!slot.activate(this)){discard();return false;}
            finished=true;touched.clear();return true;
        }
        private void discard() {
            next.aborted=true;finished=true;
            for(CallerSite site:touched)site.discard(next);
            touched.clear();
        }
        @Override public synchronized void close() {
            trusted(WALKER.getCallerClass());if(!finished)discard();
        }
    }
    private record Entry(State attempt,MethodHandle target) { }
    public static final class CallerSite {
        private static final MethodHandle SELECT;
        static {
            try {SELECT=MethodHandles.lookup().findVirtual(CallerSite.class,"select",MethodType.methodType(MethodHandle.class));}
            catch(ReflectiveOperationException failure){throw new ExceptionInInitializerError(failure);}
        }
        private final Slot slot;
        private final MethodType type;
        private final MethodHandle beforeInstall,dispatcher;
        private List<Entry> history=List.of();
        private CallerSite(Slot slot,MethodHandle beforeInstall) {
            this.slot=slot;this.type=beforeInstall.type();this.beforeInstall=beforeInstall;
            MethodHandle invocation=MethodHandles.foldArguments(MethodHandles.exactInvoker(type),SELECT.bindTo(this));
            dispatcher=beforeInstall.isVarargsCollector()?invocation.asVarargsCollector(type.parameterType(type.parameterCount()-1)):invocation;
        }
        public MethodHandle dispatcher(){trusted(WALKER.getCallerClass());return dispatcher;}
        private static boolean valid(Entry entry,State current) {
            return entry.attempt.committed || entry.attempt==current;
        }
        private synchronized void stage(State attempt,MethodHandle target) {
            State current=slot.active.get();long latest=-1;
            for(Entry entry:history)if(valid(entry,current)&&entry.attempt.sequence<=current.sequence)
                latest=Math.max(latest,entry.attempt.sequence);
            var retained=new ArrayList<Entry>();
            for(Entry entry:history)if(entry.attempt!=attempt&&!entry.attempt.aborted
                    &&(!entry.attempt.committed||entry.attempt.sequence>=latest))retained.add(entry);
            retained.add(new Entry(attempt,target));history=List.copyOf(retained);
        }
        private synchronized void discard(State attempt) {
            history=history.stream().filter(entry->entry.attempt!=attempt).toList();
        }
        private MethodHandle select() {
            MethodHandle target=beforeInstall;
            synchronized(this) {
                // Snapshot the State and history under the same site lock as pruning. A reader
                // cannot capture epoch 1, lose its history to epoch 3, then fall back to v0.
                State current=slot.active.get();long latest=-1;
                for(Entry entry:history)if(valid(entry,current)&&entry.attempt.sequence<=current.sequence
                        &&entry.attempt.sequence>latest){latest=entry.attempt.sequence;target=entry.target;}
            }
            return target;
        }
    }
}
