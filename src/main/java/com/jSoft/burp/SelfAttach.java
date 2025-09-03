package com.jSoft.burp;

import com.jSoft.burp.agent.Agent;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.URISyntaxException;
import java.security.CodeSource;

// Uses JDK attach API to self-attach and load the agent in the running JVM.
// Falls back silently if jdk.attach is not present or attach is disallowed.
final class SelfAttach {
  private static volatile boolean attempted;

  static synchronized void ensureAgentInstalled(){
    if(attempted) return;
    attempted = true;

    if(Agent.isInstalled()) return;

    // Try dynamic attach via jdk.attach
    try{
      final Class<?> vmClass = Class.forName("com.sun.tools.attach.VirtualMachine");
      final String pid = getPid();
      if(pid == null) return;
      final Object vm = vmClass.getMethod("attach", String.class).invoke(null, pid);
      try{
        final File jar = getThisJar();
        if(jar != null){
          vmClass.getMethod("loadAgent", String.class).invoke(vm, jar.getAbsolutePath());
        }
      }finally{
        try{ vmClass.getMethod("detach").invoke(vm); }catch(Throwable ignored){}
      }
    }catch(Throwable ignored){
      // ignore: user can also launch Burp with -javaagent:... for guaranteed install
    }
  }

  private static String getPid(){
    try{
      final String name = ManagementFactory.getRuntimeMXBean().getName();
      final int idx = name.indexOf('@');
      return (idx > 0) ? name.substring(0, idx) : null;
    }catch(Throwable t){
      return null;
    }
  }

  private static File getThisJar(){
    try{
      CodeSource cs = SelfAttach.class.getProtectionDomain().getCodeSource();
      if(cs == null) return null;
      return new File(cs.getLocation().toURI());
    }catch(URISyntaxException e){
      return null;
    }
  }
}

