package com.onurkat.reclazz.hybris.impex;

import com.onurkat.reclazz.config.AgentConfig;
import com.onurkat.reclazz.ext.*;
import com.onurkat.reclazz.hybris.SapPremiumModuleFactory;
import com.onurkat.reclazz.platform.*;
import com.onurkat.reclazz.ui.RestartLedger;
import com.onurkat.reclazz.ui.StatusReporter;
import com.onurkat.reclazz.watcher.ChangeEvent;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ImpexDeletedEventTest {
    @TempDir Path root;
    private final List<String> messages = new ArrayList<>();
    private final StatusReporter.StatusListener listener = (level, text) -> messages.add(level + ":" + text);
    @BeforeEach void start() { RestartLedger.clear(); StatusReporter.addListener(listener); }
    @AfterEach void stop() { StatusReporter.removeListener(listener); RestartLedger.clear(); }
    private int dispatch(Path path, ChangeEvent.Type type) throws Exception {
        var config=AgentConfig.parse("autoImpex=true");
        var platform=new HybrisPlatformContext(root,config);
        PremiumContext context=new PremiumContext() {
            public PlatformContext platform(){return platform;}
            public AgentConfig config(){return config;}
            public PropertyFileSnapshots propertySnapshots(){return new PropertyFileSnapshots();}
            public java.lang.instrument.Instrumentation instrumentation(){return null;}
            public int applyLoggerLevels(Map<String,String> values,Collection<String> keys){throw new AssertionError();}
            public boolean requestRunningSystemUpdate(){throw new AssertionError();}
        };
        Class<?> assembly=Arrays.stream(SapPremiumModuleFactory.class.getDeclaredClasses()).filter(c->c.getSimpleName().equals("Assembly")).findFirst().orElseThrow();
        var constructor=assembly.getDeclaredConstructor(PremiumContext.class);constructor.setAccessible(true);
        var method=assembly.getDeclaredMethod("handleImpexChange",ChangeEvent.class,ImpexAutoImporter.class);method.setAccessible(true);
        int[] calls={0};
        var importer=new ImpexAutoImporter(){@Override public void importFile(Path file,HybrisContext owner){assertEquals(path,file);calls[0]++;}};
        method.invoke(constructor.newInstance(context),new ChangeEvent(path,type,"example","resources"),importer);
        return calls[0];
    }
    @Test void deletedImpexEventDoesNotWarnOrImport() throws Exception {
        Path path=Files.writeString(root.resolve("owned.impex"),"# replacement exists");
        assertEquals(0,dispatch(path,ChangeEvent.Type.DELETED));
        assertTrue(messages.isEmpty(),messages.toString());assertEquals(0,RestartLedger.size());
    }
    @Test void vanishedModifiedPathDoesNotWarnOrImport() throws Exception {
        assertEquals(0,dispatch(root.resolve("gone.impex"),ChangeEvent.Type.MODIFIED));
        assertTrue(messages.isEmpty(),messages.toString());assertEquals(0,RestartLedger.size());
    }
    @Test void modifiedImpexStillImports() throws Exception {
        Path path=Files.writeString(root.resolve("owned.impex"),"# existing");
        assertEquals(1,dispatch(path,ChangeEvent.Type.MODIFIED));
        assertTrue(messages.stream().anyMatch(m->m.equals("INFO:ImpEx file changed: owned.impex")));
        assertEquals(0,RestartLedger.size());
    }
    @Test void missingPathFromOtherCallerStillWarns() {
        new ImpexAutoImporter().importFile(root.resolve("missing.impex"),null);
        assertEquals(1,messages.size());assertTrue(messages.get(0).startsWith("WARN:ImpEx file not found:"));assertEquals(0,RestartLedger.size());
    }
}
