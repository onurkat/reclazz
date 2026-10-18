package com.example;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.core.SpringVersion;

/** SDK-derived native Spring graph; no SAP tenant/bootstrap or private application code. */
public class ConstructorOwnershipApp {
    public static class Observer {
        public Owner owner;
        Observer(Owner owner) { this.owner = owner; }
    }

    public static void main(String[] args) throws Exception {
        String mode = args[0], shape = args[1];
        var parent = new AnnotationConfigApplicationContext();
        parent.register(Helper.class);
        boolean childShape = shape.equals("parent");
        if (!childShape) parent.register(Owner.class);
        parent.refresh();
        var ownerContext = parent;
        if (childShape) {
            ownerContext = new AnnotationConfigApplicationContext();
            ownerContext.setParent(parent);
            ownerContext.register(Owner.class);
            ownerContext.refresh();
        }
        var sourceFactory = parent.getDefaultListableBeanFactory();
        var ownerFactory = ownerContext.getDefaultListableBeanFactory();
        // Only startup creates/looks up Owner. HTTP observation cannot create/heal/reload it.
        var observer = new Observer(ownerContext.getBean(Owner.class));
        ownerFactory.registerSingleton("heldObserver", observer);
        Object stable = new Object();
        sourceFactory.registerSingleton("unrelated", stable);
        final var child = ownerContext;
        Runnable close = () -> { if (child != parent) child.close(); parent.close(); };
        if (mode.equals("native") || mode.equals("replay")) {
            try {
                if (mode.equals("replay")) {
                    ownerFactory.destroySingleton("owner");
                    // Explicit native cache control, not a watcher acceptance observation.
                    observer.owner = ownerContext.getBean(Owner.class);
                }
                System.out.println("SNAP=" + snapshot(observer, sourceFactory, ownerFactory, stable));
            } finally { close.run(); }
            return;
        }
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String nonce = exchange.getRequestURI().getRawQuery();
            String body = "nonce=" + nonce + ";" + snapshot(observer, sourceFactory, ownerFactory, stable);
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        Runtime.getRuntime().addShutdownHook(new Thread(() -> { server.stop(0); close.run(); }));
        server.start();
        System.out.println("CONSTRUCTOR_PORT=" + server.getAddress().getPort());
    }

    static String snapshot(Observer observer, DefaultListableBeanFactory source,
                           DefaultListableBeanFactory owner, Object stable) {
        Helper currentHelper = (Helper) source.getSingleton("helper");
        Owner currentOwner = (Owner) owner.getSingleton("owner");
        boolean forward = Arrays.asList(owner.getDependentBeans("helper")).contains("owner");
        boolean reverse = Arrays.asList(owner.getDependenciesForBean("owner")).contains("helper");
        return "pid=" + ProcessHandle.current().pid() + ";spring=" + SpringVersion.getVersion()
                + ";factoryJar=" + new java.io.File(DefaultListableBeanFactory.class.getProtectionDomain()
                    .getCodeSource().getLocation().getPath()).getName()
                + ";heldCaptured=" + observer.owner.captured
                + ";currentCaptured=" + (currentOwner == null ? -1 : currentOwner.captured)
                + ";helperSeed=" + (currentHelper == null ? -1 : currentHelper.seed)
                + ";helperIdentity=" + System.identityHashCode(currentHelper)
                + ";ownerIdentity=" + System.identityHashCode(currentOwner)
                + ";heldIsCurrent=" + (observer.owner == currentOwner)
                + ";heldHelperIsCurrent=" + (observer.owner.helper == currentHelper)
                + ";forward=" + forward + ";reverse=" + reverse
                + ";unrelated=" + (source.getSingleton("unrelated") == stable)
                + ";ownerMarker=" + observer.owner.marker();
    }
}
