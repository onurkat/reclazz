/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.e2e;

import com.onurkat.reclazz.e2e.harness.WatchedApp;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;

class InheritedAddedFieldReloadTest {
    @TempDir Path tmp;

    @ParameterizedTest @ValueSource(booleans={false,true})
    void addedParentFieldsResolveThroughChildAndThirdClassWithPhysicalShadowing(boolean shadow) throws Exception {
        String declaration=shadow?"public int b=17;":"";
        String holder="package app;public class Holder extends Parent {"+declaration;
        try(var app=WatchedApp.in(tmp).agentArgs("startupDelaySec=1,debounceMs=100,verbose=true")
                .with("Parent","package app;public class Parent {public int version(){return 0;}}")
                .with("Holder",holder+"public String value(){return \"child0\";}}")
                .with("Reader","package app;public class Reader {public String read(Holder h){return \"reader0\";}}")
                .with("App","""
                    package app;public class App {
                        public static void main(String[] args)throws Exception {
                            Holder old=new Holder();Reader reader=new Reader();
                            while(true){try{System.out.println("OBS "+old.value()+"|"+reader.read(old)+"|"+reader.read(new Holder()));}
                            catch(Throwable e){System.out.println("ERROR "+e);}Thread.sleep(100);}
                        }
                    }
                    """).start()) {
            app.awaitOrFail("OBS child0|reader0|reader0","all classes are loaded before edits");
            app.awaitOrFail("] Watching ","watcher ready");
            app.rewrite("Parent","package app;public class Parent {public int b=7;public static int S=13;public int version(){return 1;}}");
            app.awaitOrFail("Structural reload: app.Parent","parent adds instance and static fields");
            app.rewrite("Holder",holder+"public String value(){b=8;S=14;return b+\":\"+S;}}");
            app.awaitOrFail("OBS 8:14|reader0|reader0","child-owned bodies write and read inherited fields");
            app.rewrite("Reader","package app;public class Reader {public String read(Holder h){return h.b+\":\"+Holder.S;}}");
            app.awaitOrFail("OBS 8:14|8:14|"+(shadow?"17":"7")+":14",
                    "third class uses Holder-typed reference; physical child field shadows added parent field");
        }
    }
}
