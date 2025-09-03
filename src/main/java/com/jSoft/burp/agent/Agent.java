package com.jSoft.burp.agent;

import java.io.File;
import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.net.URISyntaxException;
import java.security.CodeSource;
import java.util.jar.JarFile;

public final class Agent {
  private static volatile boolean installed;

  public static void premain(String agentArgs, Instrumentation inst){
    install(inst);
  }

  public static void agentmain(String agentArgs, Instrumentation inst){
    install(inst);
  }

  private static synchronized void install(Instrumentation inst){
    if(installed) return;
    try{
      File self = getThisJar();
      if(self != null && self.isFile()){
        inst.appendToSystemClassLoaderSearch(new JarFile(self));
        installed = true;
      }
    }catch(IOException ignored){
    }
  }

  public static boolean isInstalled(){
    return installed;
  }

  private static File getThisJar(){
    try{
      CodeSource cs = Agent.class.getProtectionDomain().getCodeSource();
      if(cs == null) return null;
      return new File(cs.getLocation().toURI());
    }catch(URISyntaxException e){
      return null;
    }
  }
}

