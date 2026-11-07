/* Copyright 2026 Onur Kat; SPDX-License-Identifier: Apache-2.0 */
package com.example;
import com.hybris.cockpitng.util.WidgetControllers;
import org.zkoss.zk.ui.event.*;
import org.zkoss.zul.Div;
import java.io.*;
import java.util.Arrays;
public class SocketBindingApp {
    static Widget old;
    static Div oldView;
    static Widget held;
    static Div heldView;
    static Div bind(Widget target){
        Div view=new Div();
        WidgetControllers.setupWidgetEventListeners(view,target,null,null);
        return view;
    }
    @SuppressWarnings({"rawtypes","unchecked"})
    static String send(Div view,Widget target,String socket){
        target.seen="none";
        try{
            for(EventListener listener:view.getEventListeners("onSocketInput_"+socket))
                listener.onEvent(new Event("onSocketInput_"+socket,view,"sample"));
            return target.seen;
        }catch(Throwable e){
            while(e instanceof java.lang.reflect.InvocationTargetException)e=e.getCause();
            return "ERROR:"+e.getClass().getSimpleName();
        }
    }
    public static void main(String[] args)throws Exception{
        old=new Widget();oldView=bind(old);
        System.out.println("READY baseline="+send(oldView,old,"existing"));System.out.flush();
        BufferedReader input=new BufferedReader(new InputStreamReader(System.in));
        for(String command;(command=input.readLine())!=null;){
            if(command.equals("quit"))return;
            if(command.equals("duplicate")){
                Widget target=new Widget();Div view=bind(target);int before=0,after=0;
                for(Object listener:view.getEventListeners("onSocketInput_existing"))before++;
                WidgetControllers.setupWidgetEventListeners(view,target,null,null);
                for(Object listener:view.getEventListeners("onSocketInput_existing"))after++;
                System.out.println("DUPLICATE before="+before+" after="+after);System.out.flush();continue;
            }

            Widget fresh=new Widget();Div newView=bind(fresh);
            boolean visible=Arrays.stream(Widget.class.getMethods()).anyMatch(m->m.getName().equals("input"));
            boolean physical=Arrays.stream((java.lang.reflect.Method[])Class.forName("com.onurkat.reclazz.bootstrap.ReflectionBridge").getMethod("physicalDeclaredMethods",Class.class).invoke(null,Widget.class)).anyMatch(m->m.getName().equals("input"));
            String beforeHeld=held==null?"not-created":send(heldView,held,"example");
            System.out.println("RESULT visible="+visible+" physical="+physical+" old="+send(oldView,old,"example")+" fresh="+send(newView,fresh,"example")+" held="+beforeHeld+" existing="+send(newView,fresh,"existing"));System.out.flush();
            if(held==null&&visible){held=fresh;heldView=newView;}
        }
    }
}
