/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.agent;

import com.onurkat.reclazz.reload.SwitchMapRefresh;
import com.onurkat.reclazz.watcher.ChangeEvent;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassWriter;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PreparationDoesNotLoadTest {
    @TempDir Path root;
    @ParameterizedTest @ValueSource(booleans={false,true})
    void firstSavedClassRemainsUndefinedUntilAdmission(boolean compiled)throws Exception {
        String name="app.Fresh";ClassWriter writer=new ClassWriter(0);
        writer.visit(61,1,name.replace('.','/'),null,"java/lang/Object",null);writer.visitEnd();byte[] bytes=writer.toByteArray();
        class AppLoader extends ClassLoader {
            int attempts;Class<?> defined;
            AppLoader(){super(PreparationDoesNotLoadTest.class.getClassLoader());}
            @Override protected Class<?> loadClass(String requested,boolean resolve)throws ClassNotFoundException {
                if(!requested.equals(name))return super.loadClass(requested,resolve);
                attempts++;if(defined==null)defined=defineClass(requested,bytes,0,bytes.length);return defined;
            }
        }
        var loader=new AppLoader();var thread=Thread.currentThread();var previousLoader=thread.getContextClassLoader();
        var field=ReclazzAgent.class.getDeclaredField("instrumentation");field.setAccessible(true);var previous=field.get(null);
        Instrumentation census=(Instrumentation)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Instrumentation.class},
                (proxy,method,args)->{if(method.getName().equals("getAllLoadedClasses"))return new Class<?>[]{Object.class};throw new AssertionError("Unexpected instrumentation call: "+method.getName());});
        try {
            field.set(null,census);thread.setContextClassLoader(loader);
            if(compiled){
                var method=ReclazzAgent.class.getDeclaredMethod("prepareCompiledSwitchMaps",Map.class,Map.class);method.setAccessible(true);
                @SuppressWarnings("unchecked") var accepted=(Map<String,byte[]>)method.invoke(null,Map.of(name,bytes),Map.of(name,"test"));
                assertTrue(accepted.containsKey(name));
            }else{
                Path path=Files.createDirectories(root.resolve("app")).resolve("Fresh.class");Files.write(path,bytes);
                var method=ReclazzAgent.class.getDeclaredMethod("prepareSwitchMaps",List.class);method.setAccessible(true);
                var plan=(ReloadQueue.SavePlan)method.invoke(null,List.of(new ChangeEvent(path,ChangeEvent.Type.CREATED,"app","classes").withBytes(bytes)));
                assertEquals(1,plan.apply().size());assertTrue(plan.refused().isEmpty());
            }
            assertEquals(0,loader.attempts,"preparation must not ask the app loader to resolve a first-time save");
            assertNull(loader.defined,"only admission may define/instrument this class");
        }finally{SwitchMapRefresh.end();thread.setContextClassLoader(previousLoader);field.set(null,previous);}
    }
}
