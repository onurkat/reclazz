/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.transform;
import java.lang.ref.*;import java.util.*;import java.util.function.Predicate;
import org.objectweb.asm.*;import org.objectweb.asm.tree.ClassNode;
/** Bounded initial record linkage, without class definition or initialization. */
public final class RecordCohortCandidates {
 private RecordCohortCandidates(){}
 static final int MAX_LINKS=2,MAX_RESOURCES=256;
 // A positive distance is an upper bound from an actual discovered path. A negative
 // decision is eligibility for that root only, never proof about another root's walk.
 private record Decision(int distance,boolean defined){}
 private record Node(String name,ClassNode source,int depth,Node parent){}
 private static final Object LOCK=new Object();private static final ReferenceQueue<ClassLoader> collected=new ReferenceQueue<>();
 private static final Map<Key,Map<String,Decision>> loaders=new HashMap<>();
 private static final class Key extends WeakReference<ClassLoader>{final int hash;Key(ClassLoader loader){super(loader,collected);hash=System.identityHashCode(loader);}public int hashCode(){return hash;}public boolean equals(Object o){return this==o||o instanceof Key k&&get()!=null&&get()==k.get();}}
 private static Map<String,Decision> entries(ClassLoader loader){Reference<? extends ClassLoader> dead;while((dead=collected.poll())!=null)loaders.remove(dead);return loaders.computeIfAbsent(new Key(loader),k->new HashMap<>());}
 private static Decision known(ClassLoader loader,String name){if(loader==null)return null;synchronized(LOCK){return entries(loader).get(name);}}
 public static boolean retain(ClassLoader loader,String name){Decision d=known(loader,name);return d!=null&&d.defined&&d.distance>=0;}
 static void observe(ClassLoader loader,ClassNode source,Predicate<String> watched){
  if(loader==null)return;Decision previous=known(loader,source.name);if(previous!=null&&previous.defined)return;
  int distance=scan(loader,source,watched);
  synchronized(LOCK){entries(loader).compute(source.name,(k,v)->v!=null&&v.defined?v:new Decision(distance,true));}
 }
 public static boolean candidate(ClassLoader loader,String name,Predicate<String> watched){
  if(!watched.test(name))return false;Decision d=known(loader,name);if(d!=null)return d.distance>=0;
  if(loader==null)return true;
  int distance;
  try(var in=loader.getResourceAsStream(name+".class")){
   if(in==null)distance=0;else{var node=new ClassNode();new ClassReader(in).accept(node,ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);distance=scan(loader,node,watched);}
  }catch(java.io.IOException|RuntimeException unavailable){distance=0;}
  final int result=distance;synchronized(LOCK){entries(loader).compute(name,(k,v)->v!=null&&v.defined?v:new Decision(result,false));}
  return distance>=0;
 }
 private static void rememberPath(ClassLoader loader,Node last,int tailDistance){
  synchronized(LOCK){var values=entries(loader);int distance=tailDistance;
   for(Node node=last;node!=null;node=node.parent,distance++){
    Decision previous=values.get(node.name);
    // Never upgrade an already defined, unprepared class using later resource bytes.
    if(previous!=null&&(previous.defined&&previous.distance<0||previous.distance>=0&&previous.distance<=distance))continue;
    values.put(node.name,new Decision(distance,previous!=null&&previous.defined));
   }
  }
 }
 private static int scan(ClassLoader loader,ClassNode root,Predicate<String> watched){
  var seen=new HashSet<String>();var pending=new ArrayDeque<Node>();pending.add(new Node(root.name,root,0,null));seen.add(root.name);int resources=0;
  while(!pending.isEmpty()){
   Node current=pending.removeFirst();
   if((current.source.access&Opcodes.ACC_RECORD)!=0){rememberPath(loader,current,0);return current.depth;}
   if(current.depth==MAX_LINKS||resources>=MAX_RESOURCES)continue;
   for(String dot:ClassReferences.typed(current.source,false)){
    if(resources>=MAX_RESOURCES)break; // Already read nodes are still checked; do not grow the frontier.
    String name=dot.replace('.','/');if(!watched.test(name)||!seen.add(name))continue;
    Node path=new Node(name,null,current.depth+1,current);Decision cached=known(loader,name);
    if(cached!=null&&cached.distance>=0&&path.depth+cached.distance<=MAX_LINKS){rememberPath(loader,path,cached.distance);return path.depth+cached.distance;}
    resources++;
    try(var in=loader.getResourceAsStream(name+".class")){
     if(in==null){rememberPath(loader,path,0);return path.depth;}
     var next=new ClassNode();new ClassReader(in).accept(next,ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);pending.addLast(new Node(name,next,path.depth,current));
    }catch(java.io.IOException|RuntimeException unavailable){rememberPath(loader,path,0);return path.depth;}
   }
  }
  return -1;
 }
}
