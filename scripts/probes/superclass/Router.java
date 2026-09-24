// Copyright 2026 Onur Kat
// SPDX-License-Identifier: Apache-2.0
import java.lang.invoke.*;
public class Router {
 public static volatile MethodHandle target;
 static { try { target = MethodHandles.lookup().findStatic(Router.class,"original",MethodType.methodType(String.class,C.class)); }
 catch(Exception e) { throw new ExceptionInInitializerError(e); } }
 public static String original(C self) { return "v1:"+self.who()+":"+self.state; }
}
