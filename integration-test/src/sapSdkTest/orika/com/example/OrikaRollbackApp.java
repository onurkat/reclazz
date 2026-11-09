package com.example;

import ma.glasnost.orika.impl.DefaultMapperFactory;

/** Synthetic native mapper, kept warm across class edits by the SDK script. */
public class OrikaRollbackApp {
    public static class Source {
        public String getCode() { return "code"; }
        public String getExtra() { return "v2"; }
        public String getOther() { return "other"; }
    }
    public static void main(String[] args) throws Exception {
        var mapper = new DefaultMapperFactory.Builder().build().getMapperFacade();
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/", exchange -> {
            int status = 200; String body;
            try { body = mapper.map(new Source(), OrikaRollbackDto.class).describe(); }
            catch (Throwable failure) { status = 500; body = failure.toString(); failure.printStackTrace(); }
            byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status,bytes.length);
            try (var stream = exchange.getResponseBody()) { stream.write(bytes); }
        });
        server.start(); System.out.println("ORIKA_PORT="+server.getAddress().getPort());
    }
}
