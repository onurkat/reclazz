/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.e2e;

import com.onurkat.reclazz.e2e.harness.StatusTestClient;
import com.onurkat.reclazz.e2e.harness.WatchedApp;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.net.Socket;
import java.io.*;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;
class AutoCompileToggleReloadTest {
    @TempDir Path dir;
    static String service(String value){return "package app;public class Service{public static String value(){return \""+value+"\";}}";}
    @Test void offToOnCompilesSavedJava()throws Exception{exercise(false);}
    @Test void onToOffReloadsCompiledClassOnly()throws Exception{exercise(true);}
    void exercise(boolean disable)throws Exception {
        Path port=dir.resolve("port");var app=WatchedApp.in(dir).mavenLayout().agentArgs("startupDelaySec=1,debounceMs=100,verbose=true,autoCompile=false,portFile="+port)
                .with("Service",service("one")).with("App","package app;public class App{public static void main(String[]args)throws Exception{String old=\"\";while(true){String current=Service.value();if(!current.equals(old)){old=current;System.out.println(\"VALUE=\"+current);}Thread.sleep(30);}}}").start();
        try(app){app.awaitOrFail("VALUE=one","baseline");app.awaitWatcherReady();
            assertTrue(command(port,"CONFIG SET on base=0 autoCompile=true","CONFIG on ").startsWith("CONFIG on OK rev=1 "));
            Files.writeString(dir.resolve("src/main/java/app/Service.java"),service("two"));app.awaitOrFail("VALUE=two","enabling must compile and reload the source save");
            if(disable){assertTrue(command(port,"CONFIG SET off base=1 autoCompile=false","CONFIG off ").startsWith("CONFIG off OK rev=2 "));
                byte[] admitted=Files.readAllBytes(app.classesDir().resolve("app/Service.class"));Files.writeString(dir.resolve("src/main/java/app/Service.java"),service("three"));
                command(port,"SCAN request=offscan","SCAN_RESULT offscan ");Thread.sleep(2300);
                assertArrayEquals(admitted,Files.readAllBytes(app.classesDir().resolve("app/Service.class")),"disabled source save must not invoke the internal compiler");assertFalse(app.output().stream().anyMatch(line->line.equals("VALUE=three")));
                app.rewrite("Service",service("three"));app.awaitOrFail("VALUE=three","disabled mode must still reload an externally compiled class");
            }
        }finally{String output=System.getenv("RECLAZZ_R17_TEST_OUTPUT");if(output!=null){Path out=Path.of(output);Files.createDirectories(out);Files.write(out.resolve(disable?"disable.log":"enable.log"),app.output());}}
    }
    static String command(Path port,String command,String prefix)throws Exception {
        try(var socket=StatusTestClient.connect(port)){socket.setSoTimeout(15000);var reader=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.UTF_8));
            socket.getOutputStream().write((command+"\n").getBytes(StandardCharsets.UTF_8));socket.getOutputStream().flush();String line;
            while((line=reader.readLine())!=null){String message=new ObjectMapper().readTree(line).path("message").asText();if(message.startsWith(prefix))return message;}
        }throw new AssertionError("No correlated reply for "+command);
    }
}
