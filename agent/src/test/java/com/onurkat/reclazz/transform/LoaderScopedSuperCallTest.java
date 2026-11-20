/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.transform;
import com.onurkat.reclazz.config.AgentConfig;
import com.onurkat.reclazz.platform.NoopPlatformContext;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class LoaderScopedSuperCallTest extends TransformTestBase {
    static final java.util.concurrent.atomic.AtomicInteger IDS=new java.util.concurrent.atomic.AtomicInteger();
    void witness(boolean census)throws Exception {
        String parent="ScopedParent"+IDS.incrementAndGet(),child="ScopedChild"+IDS.incrementAndGet();
        var raw=compile(new SourceFile(parent,"public class "+parent+" {public String value(){return \"parent\";}}"),new SourceFile(child,"public class "+child+" extends "+parent+" {public String value(){return \"child:\"+super.value();}}"));
        var context=new TransformContext();context.addWatched(parent);context.addWatched(child);var config=AgentConfig.parse(null);var transformer=new ReclazzTransformer(context,config);
        class Loader extends ClassLoader {
            final boolean instrument;Loader(boolean instrument){super(LoaderScopedSuperCallTest.class.getClassLoader());this.instrument=instrument;}
            @Override protected Class<?> findClass(String name)throws ClassNotFoundException {byte[] bytes=raw.get(name);if(bytes==null)throw new ClassNotFoundException(name);
                if(instrument)try{bytes=transformer.transform(this,name,null,null,bytes);}catch(Exception error){throw new ClassNotFoundException(name,error);}
                return defineClass(name,bytes,0,bytes.length);}
            @Override public InputStream getResourceAsStream(String path){byte[] bytes=raw.get(path.replace(".class",""));return bytes==null?super.getResourceAsStream(path):new ByteArrayInputStream(bytes);}
        }
        var live=new Loader(true);Class<?> good=live.loadClass(parent);good.getConstructor().newInstance();var foreign=new Loader(false);Class<?> bad=foreign.loadClass(parent);
        if(census){var view=new NoopPlatformContext();context.bindRuntime(config,view);var old=config.runtime();assertTrue(config.compareAndSetRuntime(old,new AgentConfig.Runtime(old.revision()+1,old.options(),old.scope(),old.stats(),context.stageInventory(view,new Class<?>[]{good,bad}))));}
        else context.markRaw(foreign,parent);
        Class<?> type=live.loadClass(child);Object held=type.getConstructor().newInstance();assertEquals("child:parent",type.getMethod("value").invoke(held));
    }
    @Test void foreignRawDefinitionDoesNotSuppressOwnParentRewrite()throws Exception{witness(false);}
    @Test void foreignRawCensusDoesNotSuppressOwnParentRewrite()throws Exception{witness(true);}
    void ownRawParentWitness(boolean census)throws Exception {
        String parent="OwnRawParent"+IDS.incrementAndGet(),child="OwnRawChild"+IDS.incrementAndGet();
        var raw=compile(new SourceFile(parent,"public class "+parent+" {public String value(){return \"own-raw\";}}"),new SourceFile(child,"public class "+child+" extends "+parent+" {public String value(){return \"child:\"+super.value();}}"));
        byte[] foreignBytes=compile(new SourceFile(parent,"public class "+parent+" {public String value(){return \"foreign\";}}")).get(parent);
        var context=new TransformContext();context.addWatched(parent);context.addWatched(child);
        var config=AgentConfig.parse(null);var transformer=new ReclazzTransformer(context,config);
        class Loader extends ClassLoader {
            final boolean foreign;Loader(boolean foreign){super(LoaderScopedSuperCallTest.class.getClassLoader());this.foreign=foreign;}
            @Override protected Class<?> findClass(String name)throws ClassNotFoundException {
                byte[] bytes=foreign&&name.equals(parent)?foreignBytes:raw.get(name);if(bytes==null)throw new ClassNotFoundException(name);
                if(foreign||name.equals(child))try{bytes=transformer.transform(this,name,null,null,bytes);}catch(Exception error){throw new ClassNotFoundException(name,error);}
                return defineClass(name,bytes,0,bytes.length);
            }
            @Override public InputStream getResourceAsStream(String path){byte[] bytes=raw.get(path.replace(".class",""));return bytes==null?super.getResourceAsStream(path):new ByteArrayInputStream(bytes);}
        }
        var own=new Loader(false);Class<?> ownParent=own.loadClass(parent);
        var foreign=new Loader(true);Class<?> foreignParent=foreign.loadClass(parent);
        assertEquals("foreign",foreignParent.getMethod("value").invoke(foreignParent.getConstructor().newInstance()));
        assertFalse(context.isTransformed(ownParent));assertTrue(context.isTransformed(foreignParent));
        if(census){var view=new NoopPlatformContext();context.bindRuntime(config,view);var old=config.runtime();assertTrue(config.compareAndSetRuntime(old,new AgentConfig.Runtime(old.revision()+1,old.options(),old.scope(),old.stats(),context.stageInventory(view,new Class<?>[]{ownParent,foreignParent}))));}
        else context.markRaw(own,parent);
        // The name-only query sees the foreign transformed definition, so it cannot
        // decide whether this subclass's actual parent has a renamed method copy.
        assertFalse(context.isLoadedUninstrumented(parent));assertTrue(context.isLoadedUninstrumented(own,parent));
        Class<?> type=own.loadClass(child);assertSame(ownParent,type.getSuperclass());assertSame(own,type.getClassLoader());
        Object held=type.getConstructor().newInstance();assertEquals("child:own-raw",type.getMethod("value").invoke(held));
    }
    @Test void ownRawParentStillRunsWhenForeignDuplicateIsTransformed()throws Exception{ownRawParentWitness(false);}
    @Test void ownRawCensusParentStillRunsWhenForeignDuplicateIsTransformed()throws Exception{ownRawParentWitness(true);}

}
