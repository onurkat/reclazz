package com.onurkat.reclazz.spring;

import com.onurkat.reclazz.platform.ApplicationContextHolder;
import com.onurkat.reclazz.platform.SpringXmlResources;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.io.FileSystemResource;
import static org.junit.jupiter.api.Assertions.*;

class SpringComponentScanOwnersTest {
    @TempDir Path tmp;
    @BeforeEach void before() {ApplicationContextHolder.clear();}
    @AfterEach void after() {ApplicationContextHolder.clear();}
    static String xml(String scan) {return "<beans xmlns='http://www.springframework.org/schema/beans' xmlns:c='http://www.springframework.org/schema/context'>"+scan+"</beans>";}
    private Path file(String name,String scan) throws Exception {var p=tmp.resolve(name);Files.writeString(p,xml(scan));return p;}
    static void capture(GenericApplicationContext context,Path path) {
        // Unit seam for the successful-reader hook; no startup scanning of the future class.
        SpringXmlResources.record(new XmlBeanDefinitionReader(context),new FileSystemResource(path));ApplicationContextHolder.register(context);
    }
    @Test void actualOwnerIdentityAndPackageBoundary() throws Exception {
        var p=file("a.xml","<c:component-scan base-package='com.example.target'/>");
        try(var own=new GenericApplicationContext();var other=new GenericApplicationContext()) {
            own.refresh();other.refresh();capture(own,p);ApplicationContextHolder.register(other);
            assertEquals(List.of(own),SpringComponentScanOwners.find("com.example.target.New",List.of(other,own)));
            assertEquals(List.of(own),SpringComponentScanOwners.find("com.example.target.child.New",List.of(other,own)));
            assertTrue(SpringComponentScanOwners.find("com.example.targetOther.New",List.of(other,own)).isEmpty());
        }
    }
    @Test void anUnloadedFileOrClosedOwnerConfersNoOwnership() throws Exception {
        var p=file("b.xml","<c:component-scan base-package='com.example'/>");
        var own=new GenericApplicationContext();own.refresh();ApplicationContextHolder.register(own);
        assertTrue(SpringComponentScanOwners.find("com.example.New",List.of(own)).isEmpty());capture(own,p);own.close();
        assertTrue(SpringComponentScanOwners.find("com.example.New",List.of(own)).isEmpty());
    }
    @Test void filteredCustomAndUnresolvedScansDoNotConferOwnership() throws Exception {
        int i=0;
        for(String scan:List.of("<c:component-scan base-package='com.example' use-default-filters='false'/>","<c:component-scan base-package='com.example'><c:exclude-filter type='regex' expression='.*'/></c:component-scan>","<c:component-scan base-package='${owned.package}'/>","<c:component-scan base-package='com.example' name-generator='example.Custom'/>","<beans profile='disabled'><c:component-scan base-package='com.example'/></beans>")) {
            assertFalse(SpringComponentScanOwners.matches(file("bad"+(i++)+".xml",scan),"com.example.New"));
        }
    }
    @Test void enclosingBeanDefaultsDoNotMasqueradeAsDefaultScanning() throws Exception {
        int i=0;
        for(String attribute:List.of("default-lazy-init","default-init-method","default-destroy-method","default-autowire","default-autowire-candidates","default-merge")) {
            var p=file("defaults"+(i++)+".xml","<beans "+attribute+"='custom'><c:component-scan base-package='com.example'/></beans>");
            assertFalse(SpringComponentScanOwners.matches(p,"com.example.New"));
        }
    }
    @Test void aFileEditNotReadSuccessfullyCannotGrantNewOwnership() throws Exception {
        var p=file("edit.xml","<c:component-scan base-package='com.example.before'/>");
        try(var own=new GenericApplicationContext()) {
            own.refresh();capture(own,p);
            Files.writeString(p,xml("<c:component-scan base-package='com.example.after'/>"));
            assertTrue(SpringComponentScanOwners.find("com.example.after.New",List.of(own)).isEmpty());
            capture(own,p);assertEquals(List.of(own),SpringComponentScanOwners.find("com.example.after.New",List.of(own)));
        }
    }
    @Test void repeatedResourcesDeduplicateTheSameOwnerByIdentity() throws Exception {
        try(var own=new GenericApplicationContext()) {
            own.refresh();capture(own,file("c.xml","<c:component-scan base-package='com.example'/>"));capture(own,file("d.xml","<c:component-scan base-package='com.example'/>"));
            assertEquals(List.of(own),SpringComponentScanOwners.find("com.example.New",List.of(own,own)));
        }
    }
    @Test void externalDoctypeIsRefusedBeforeResolution() throws Exception {
        var p=tmp.resolve("doctype.xml");Files.writeString(p,"<!DOCTYPE beans SYSTEM 'https://example.invalid/no-request'><beans xmlns:c='http://www.springframework.org/schema/context'><c:component-scan base-package='com.example'/></beans>");
        assertFalse(SpringComponentScanOwners.matches(p,"com.example.New"));
    }
}
