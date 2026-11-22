/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.bootstrap;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
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
    // A read-only reflection hook must not initialize a record's slot while the JDK is
    // still building that record's physical roots. Only the engine's slot() creates it.
    private static final class Holder { volatile Slot slot; }
    private static final ClassValue<Holder> slots=new ClassValue<>() {
        @Override protected Holder computeValue(Class<?> owner) { return new Holder(); }
    };
    // Caller-owned storage: a shared parent record must not retain a disposable child caller.
    private static final ClassValue<Map<Slot,Map<String,CallerSite>>> callers=new ClassValue<>() {
        @Override protected Map<Slot,Map<String,CallerSite>> computeValue(Class<?> owner){return new IdentityHashMap<>();}
    };
    private RecordSchema() { }
    private static void trusted(Class<?> caller){LookupCapture.requireTrusted(caller,"accessing record publication state");}

    public record Component(String name,Class<?> type,Method accessor,RecordComponent metadata) {
        public Component(String name,Class<?> type,Method accessor){this(name,type,accessor,null);}
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
                          Map<String,MethodHandle> initializerTargets,Map<String,MethodHandle> objectMethods,RecordHeader.View header) {
        public Members(List<Component> components,List<Method> methods,List<Field> fields,List<Constructor<?>> constructors,
                Map<String,MethodHandle> constructorTargets,Map<String,MethodHandle> initializerTargets,Map<String,MethodHandle> objectMethods) {
            this(components,methods,fields,constructors,constructorTargets,initializerTargets,objectMethods,null);
        }
        public Members {
            components=List.copyOf(components);methods=List.copyOf(methods);fields=List.copyOf(fields);
            constructors=List.copyOf(constructors);constructorTargets=Map.copyOf(constructorTargets);
            initializerTargets=Map.copyOf(initializerTargets);objectMethods=Map.copyOf(objectMethods);
            if(components.stream().map(Component::name).distinct().count()!=components.size())
                throw new IllegalArgumentException("Duplicate record component");
        }
    }
    public static Slot slot(Class<?> record) {
        trusted(WALKER.getCallerClass());Objects.requireNonNull(record);
        if(!record.isRecord())throw new IllegalArgumentException("Not a record: "+record.getName());
        Holder holder=slots.get(record);Slot found=holder.slot;if(found!=null)return found;
        synchronized(holder) {
            if(holder.slot==null) {
                List<Component> components=new ArrayList<>();
                for(var component:ReflectionBridge.physicalRecordComponents(record))
                    components.add(new Component(component.getName(),component.getType(),component.getAccessor(),component));
                holder.slot=new Slot(record,new Members(components,List.of(),List.of(),List.of(),Map.of(),Map.of(),Map.of()));
            }
            return holder.slot;
        }
    }
    static State virtualState(Class<?> owner) {
        if(!owner.isRecord())return null;
        Slot slot=slots.get(owner).slot;if(slot==null)return null;
        State state=slot.active.get();return state.virtual?state:null;
    }
    static Members virtualMembers(Class<?> owner) {
        State state=virtualState(owner);return state==null?null:state.members;
    }
    static ReflectionBridge.Members reflectionMembers(Class<?> owner) {
        State state=virtualState(owner);return state==null?null:state.reflectionView;
    }
    static RecordComponent[] reflectionComponents(Class<?> owner) {
        State state=virtualState(owner);return state==null?null:RecordReflection.copy(state.componentView);
    }
    public static CallerSite callerSite(Class<?> caller,Slot slot,String key,MethodHandle beforeInstall) {
        trusted(WALKER.getCallerClass());Objects.requireNonNull(caller);Objects.requireNonNull(slot);
        Objects.requireNonNull(key);Objects.requireNonNull(beforeInstall);
        return callerSiteInternal(caller,slot,key,beforeInstall);
    }
    static CallerSite callerSiteInternal(Class<?> caller,Slot slot,String key,MethodHandle beforeInstall) {
        Map<Slot,Map<String,CallerSite>> owned=callers.get(caller);
        synchronized(owned) {
            var sites=owned.computeIfAbsent(slot,ignored->new HashMap<>());
            CallerSite site=sites.computeIfAbsent(key,ignored->new CallerSite(slot,beforeInstall));
            if(!site.type.equals(beforeInstall.type()))throw new IllegalArgumentException("Caller site type changed: "+key);
            return site;
        }
    }
    static void validateRecordMember(Class<?> owner,java.lang.reflect.Executable metadata,MethodHandle target) {
        if(!owner.isRecord())return;
        validateCarrier(owner,metadata.getDeclaringClass());
        validateTargetOwner(owner,target);
    }
    static void validateCarrier(Class<?> owner,Class<?> carrier) {
        if(owner.isRecord()&&carrier!=owner&&!(carrier.isHidden()&&carrier.getClassLoader()==owner.getClassLoader()
                &&carrier.getNestHost()==owner.getNestHost()))
            throw new IllegalArgumentException("Record metadata belongs to another owner");
    }
    static void validateTargetOwner(Class<?> owner,MethodHandle target) {
        // reflectAs re-enters Class.getMethod/getConstructor and therefore sees logical roots,
        // which may intentionally hide this physical initializer (or an injected v0 method).
        // revealDirect inspects the VM member without weakening directness or type provenance.
        java.lang.invoke.MethodHandleInfo member;
        try {member=MethodHandles.lookup().revealDirect(target);}
        catch(IllegalArgumentException inaccessible) {
            try {
                MethodHandles.Lookup lookup=LookupCapture.recordTargetLookup(owner);
                if(lookup==null)lookup=MethodHandles.privateLookupIn(owner,MethodHandles.lookup());
                member=lookup.revealDirect(target);
            }catch(IllegalAccessException|IllegalArgumentException unavailable) {
                throw new IllegalArgumentException("Record target must be direct and unadapted",unavailable);
            }
        }
        Class<?> declaring=member.getDeclaringClass();
        boolean sameRecord=declaring==owner || declaring.isHidden()
                && declaring.getClassLoader()==owner.getClassLoader() && declaring.getNestHost()==owner.getNestHost();
        boolean bootstrap=declaring.getClassLoader()==null || declaring==RecordSchema.class || declaring==MemberForge.class;
        if(!sameRecord&&!bootstrap)throw new IllegalArgumentException("Record target belongs to another owner: "+declaring.getName());
        MethodType declared=member.getMethodType();
        switch(member.getReferenceKind()) {
            case java.lang.invoke.MethodHandleInfo.REF_invokeStatic -> { }
            case java.lang.invoke.MethodHandleInfo.REF_invokeVirtual,java.lang.invoke.MethodHandleInfo.REF_invokeInterface,
                    java.lang.invoke.MethodHandleInfo.REF_invokeSpecial -> declared=declared.insertParameterTypes(0,declaring);
            case java.lang.invoke.MethodHandleInfo.REF_newInvokeSpecial -> declared=declared.changeReturnType(declaring);
            default -> throw new IllegalArgumentException("Record target must be an executable member");
        }
        if(!declared.equals(target.type()))throw new IllegalArgumentException("Record target type was adapted");
    }

    public static final class State {
        private final long sequence;
        private final Members members;
        private final boolean virtual;
        private final ReflectionBridge.Members reflectionView;
        private final List<RecordReflection.Template> componentView;
        private final RecordObjects.Bodies objectBodies;
        private final java.util.concurrent.ConcurrentHashMap<String,MethodHandle> constructorFactories=new java.util.concurrent.ConcurrentHashMap<>();
        private volatile boolean committed,aborted;
        private State(Class<?> owner,long sequence,Members members,boolean virtual){
            this.sequence=sequence;this.members=members;this.virtual=virtual;
            reflectionView=virtual?new ReflectionBridge.Members(members.methods(),members.fields(),members.constructors()):null;
            componentView=virtual?RecordReflection.prepare(owner,members.components()):List.of();
            if(virtual)RecordStorage.prepare(owner);
            objectBodies=virtual?RecordObjects.prepare(owner,members):null;
        }
        public long sequence(){return sequence;}
        public Members members(){trusted(WALKER.getCallerClass());return members;}
        Members membersInternal(){return members;}
        RecordObjects.Bodies objectsInternal(){return objectBodies;}
        RecordHeader.View headerInternal(){return members.header();}
        MethodHandle constructorFactory(Class<?> owner,String descriptor) {
            return constructorFactories.computeIfAbsent(descriptor,key->MemberForge.recordConstructorFactory(owner,key,this));
        }
    }
    public static final class Slot {
        private final Class<?> owner;
        private final List<Component> physical;
        private final AtomicReference<State> active;
        private Slot(Class<?> owner,Members original) {
            this.owner=owner;physical=original.components();State initial=new State(owner,0,original,false);
            initial.committed=true;active=new AtomicReference<>(initial);
        }
        public State current(){trusted(WALKER.getCallerClass());return active.get();}
        State currentInternal(){return active.get();}
        public List<Component> physicalComponents(){trusted(WALKER.getCallerClass());return physical;}
        public Preparation prepare(Members members) {
            trusted(WALKER.getCallerClass());State previous=active.get();return prepare(members,previous.virtual,previous);
        }
        /** Full logical roots become visible only with this preparation's successful CAS. */
        public Preparation prepareVirtual(Members members) {
            trusted(WALKER.getCallerClass());return prepare(members,true,active.get());
        }
        private Preparation prepare(Members members,boolean virtual,State current) {
            // Fallible annotation/generic parsers can initialize application enum classes.
            // They run outside the publication monitor; a concurrent commit makes this
            // exact captured previous State lose its CAS rather than rebasing it silently.
            Objects.requireNonNull(members);
            if(members.header()!=null&&members.header().owner!=owner)throw new IllegalArgumentException("Foreign record class header");
            for(Component c:members.components())if(c.accessor().getDeclaringClass()!=owner)
                throw new IllegalArgumentException("Record accessor belongs to another class");
            for(Method m:members.methods())if(m.getDeclaringClass()!=owner)throw new IllegalArgumentException("Foreign method root");
            for(Field f:members.fields())if(f.getDeclaringClass()!=owner)throw new IllegalArgumentException("Foreign field root");
            for(Constructor<?> c:members.constructors())if(c.getDeclaringClass()!=owner)throw new IllegalArgumentException("Foreign constructor root");
            for(var handles:List.of(members.constructorTargets(),members.initializerTargets(),members.objectMethods()))
                for(MethodHandle target:handles.values())validateTargetOwner(owner,target);
            return new Preparation(this,current,new State(owner,current.sequence+1,members,virtual));
        }
        private synchronized boolean activate(Preparation preparation) {
            if(preparation.next.aborted||!active.compareAndSet(preparation.previous,preparation.next))return false;
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
            trusted(WALKER.getCallerClass());stageTarget(site,target);
        }
        public synchronized void stageDispatch(Class<?> caller,Map<String,MethodHandle> targets) {
            trusted(WALKER.getCallerClass());if(finished)throw new IllegalStateException("Record preparation is closed");
            DispatchTable.getOrCreate(Objects.requireNonNull(caller)).stageRecord(this,slot,Map.copyOf(targets));
        }
        public synchronized void stageCallerMembers(Class<?> caller,RecordCaller.View members) {
            trusted(WALKER.getCallerClass());if(finished)throw new IllegalStateException("Record preparation is closed");
            RecordCaller.stage(this,slot,Objects.requireNonNull(caller),Objects.requireNonNull(members));
        }
        public synchronized void stageFieldInitializers(Class<?> caller,Map<String,MethodHandle> rawTargets) {
            trusted(WALKER.getCallerClass());if(finished)throw new IllegalStateException("Record preparation is closed");
            FieldStore.stageRecordInitialisers(this,slot,Objects.requireNonNull(caller),Map.copyOf(rawTargets));
        }
        State attempt(){return next;}
        void stageTarget(CallerSite site,MethodHandle target) {
            if(finished)throw new IllegalStateException("Record preparation is closed");
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
        private record History(MethodHandle baseline,List<Entry> entries) { }
        private volatile History history;
        private final MethodHandle dispatcher;
        private CallerSite(Slot slot,MethodHandle beforeInstall) {
            this.slot=slot;this.type=beforeInstall.type();history=new History(beforeInstall,List.of());
            MethodHandle invocation=MethodHandles.foldArguments(MethodHandles.exactInvoker(type),SELECT.bindTo(this));
            dispatcher=beforeInstall.isVarargsCollector()?invocation.asVarargsCollector(type.parameterType(type.parameterCount()-1)):invocation;
        }
        public MethodHandle dispatcher(){trusted(WALKER.getCallerClass());return dispatcher;}
        MethodHandle dispatcherInternal(){return dispatcher;}
        private static boolean valid(Entry entry,State current) {
            return entry.attempt.committed || entry.attempt==current;
        }
        private synchronized void stage(State attempt,MethodHandle target) {
            History previous=history;State current=slot.active.get();long latest=-1;
            for(Entry entry:previous.entries)if(valid(entry,current)&&entry.attempt.sequence<=current.sequence)
                latest=Math.max(latest,entry.attempt.sequence);
            var retained=new ArrayList<Entry>();
            for(Entry entry:previous.entries)if(entry.attempt!=attempt&&!entry.attempt.aborted
                    &&(!entry.attempt.committed||entry.attempt.sequence>=latest))retained.add(entry);
            retained.add(new Entry(attempt,target));history=new History(previous.baseline,List.copyOf(retained));
        }
        private synchronized void discard(State attempt) {
            History previous=history;
            history=new History(previous.baseline,previous.entries.stream().filter(entry->entry.attempt!=attempt).toList());
        }
        record Selected(MethodHandle target,boolean baseline) { }
        Selected selected() {
            while(true) {
                State current=slot.active.get();History snapshot=history;
                MethodHandle target=snapshot.baseline;long latest=-1;
                for(Entry entry:snapshot.entries)if(valid(entry,current)&&entry.attempt.sequence<=current.sequence
                        &&entry.attempt.sequence>latest){latest=entry.attempt.sequence;target=entry.target;}
                // Retaining an immutable history makes pruning safe. If its pruning epoch was
                // newer than our State, retry instead of falling back to a pre-install target.
                if(slot.active.get()==current)return new Selected(target,latest<0);
            }
        }
        synchronized boolean hasPending() {
            State current=slot.active.get();
            return history.entries.stream().anyMatch(entry->!entry.attempt.aborted&&!valid(entry,current));
        }
        Object publicationLock(){return slot;}
        void cancelPending() {
            synchronized(slot) {synchronized(this) {
                State current=slot.active.get();History previous=history;
                for(Entry entry:previous.entries)if(!valid(entry,current))entry.attempt.aborted=true;
                history=new History(previous.baseline,previous.entries.stream().filter(entry->!entry.attempt.aborted).toList());
            }}
        }
        synchronized void rebase(MethodHandle baseline) {
            if(hasPending())throw new IllegalStateException("Caller has another pending record preparation");
            if(!type.equals(baseline.type()))throw new IllegalArgumentException("Caller baseline type changed");
            history=new History(baseline,List.of());
        }
        synchronized void installBaseline(MethodHandle baseline) {
            if(!type.equals(baseline.type()))throw new IllegalArgumentException("Caller baseline type changed");
            history=new History(baseline,history.entries);
        }
        private MethodHandle select() {return selected().target;}

    }
}
