package com.jSoft.burp;

import javax.swing.SwingUtilities;
import java.awt.Component;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.function.Consumer;

/**
 * Minimal reflective bridge to a host-side helper that runs in Burp's
 * classloader and manages the built-in JCEF browser. This allows rendering
 * and control without loading JCEF classes in the extension classloader.
 *
 * Expected host class contract (to be provided on Burp's main classpath or via agent):
 *   package com.jSoft.burp.browserhost;
 *   public final class Host {
 *     public static Component getOrCreateBrowserComponent(String startUrl);
 *     public static void navigate(String url);
 *     public static void dispose();
 *   }
 */
final class BrowserHostBridge {
  private static final String HOST_CLASS = "com.jSoft.burp.browserhost.Host";

  private static volatile Class<?> hostClass;
  private static volatile Method   mGetOrCreate;
  private static volatile Method   mNavigate;
  private static volatile Method   mDispose;
  private static volatile Method   mReload;
  private static volatile Method   mSetAddr;
  private static volatile Method   mSetTitle;
  private static volatile Method   mSetConsole;
  private static volatile Method   mDiagnostics;
  private static volatile Method   mSetEventLog;
  private static volatile Method   mSetUrlAllowRegex;
  private static volatile Method   mSetLoading;
  private static volatile Method   mSetCanBack;
  private static volatile Method   mSetCanFwd;
  private static volatile Method   mGoBack;
  private static volatile Method   mGoForward;
  private static volatile Method   mStop;
  private static volatile Method   mSetHomeUrl;
  private static volatile Method   mHome;

  private BrowserHostBridge(){}

  static boolean isAvailable(){
    try{
      ensureLoaded();
      return hostClass != null && mGetOrCreate != null;
    }catch(Throwable ignored){
      return false;
    }
  }

  static Component getOrCreateBrowserComponent(String startUrl) throws Throwable{
    ensureLoaded();
    if(mGetOrCreate == null){
      throw new IllegalStateException("Browser host not available");
    }
    Object comp = mGetOrCreate.invoke(null, startUrl);
    if(!(comp instanceof Component)){
      throw new IllegalStateException("Host returned non-Component: " + (comp == null ? "null" : comp.getClass()));
    }
    return (Component)comp;
  }

  static void navigate(String url){
    try{
      ensureLoaded();
      if(mNavigate != null){
        mNavigate.invoke(null, url);
      }
    }catch(Throwable ignored){
      // best-effort only; fall back silently
    }
  }

  static void reload(){
    try{
      ensureLoaded();
      if(mReload != null){
        mReload.invoke(null);
      }
    }catch(Throwable ignored){}
  }

  // DevTools hook is optional; Host may not implement it depending on JCEF version.

  static void setOnAddressChange(Consumer<String> c){
    try{
      ensureLoaded();
      if(mSetAddr != null){
        mSetAddr.invoke(null, c);
      }
    }catch(Throwable ignored){}
  }

  static void setOnTitleChange(Consumer<String> c){
    try{
      ensureLoaded();
      if(mSetTitle != null){
        mSetTitle.invoke(null, c);
      }
    }catch(Throwable ignored){}
  }

  static void setOnConsoleMessage(Consumer<String> c){
    try{
      ensureLoaded();
      if(mSetConsole != null){
        mSetConsole.invoke(null, c);
      }
    }catch(Throwable ignored){}
  }

  static String diagnostics(){
    try{
      ensureLoaded();
      if(mDiagnostics != null){
        Object o = mDiagnostics.invoke(null);
        return (o == null) ? "" : String.valueOf(o);
      }
    }catch(Throwable ignored){}
    return "";
  }

  static void setOnEventLog(Consumer<String> c){
    try{
      ensureLoaded();
      if(mSetEventLog != null){
        mSetEventLog.invoke(null, c);
      }
    }catch(Throwable ignored){}
  }

  static void setUrlAllowRegex(String regex){
    try{
      ensureLoaded();
      if(mSetUrlAllowRegex != null){
        mSetUrlAllowRegex.invoke(null, regex);
      }
    }catch(Throwable ignored){}
  }

  static void setOnLoading(Consumer<Boolean> c){
    try{
      ensureLoaded();
      if(mSetLoading != null){
        mSetLoading.invoke(null, c);
      }
    }catch(Throwable ignored){}
  }

  static void setOnCanGoBack(Consumer<Boolean> c){
    try{
      ensureLoaded();
      if(mSetCanBack != null){
        mSetCanBack.invoke(null, c);
      }
    }catch(Throwable ignored){}
  }

  static void setOnCanGoForward(Consumer<Boolean> c){
    try{
      ensureLoaded();
      if(mSetCanFwd != null){
        mSetCanFwd.invoke(null, c);
      }
    }catch(Throwable ignored){}
  }

  static void goBack(){
    try{ ensureLoaded(); if(mGoBack != null) mGoBack.invoke(null); }catch(Throwable ignored){}
  }
  static void goForward(){
    try{ ensureLoaded(); if(mGoForward != null) mGoForward.invoke(null); }catch(Throwable ignored){}
  }
  static void stop(){
    try{ ensureLoaded(); if(mStop != null) mStop.invoke(null); }catch(Throwable ignored){}
  }
  static void setHomeUrl(String url){
    try{ ensureLoaded(); if(mSetHomeUrl != null) mSetHomeUrl.invoke(null, url); }catch(Throwable ignored){}
  }
  static void home(){
    try{ ensureLoaded(); if(mHome != null) mHome.invoke(null); }catch(Throwable ignored){}
  }

  static void dispose(){
    try{
      ensureLoaded();
      if(mDispose != null){
        // dispose must run on EDT if it manipulates Swing
        Runnable r = () -> {
          try{
            mDispose.invoke(null);
          }catch(IllegalAccessException | InvocationTargetException ignored){}
        };
        if(SwingUtilities.isEventDispatchThread()) r.run();
        else SwingUtilities.invokeLater(r);
      }
    }catch(Throwable ignored){
      // ignore
    }
  }

  private static void ensureLoaded() throws ClassNotFoundException, NoSuchMethodException{
    if(hostClass != null) return;
    synchronized(BrowserHostBridge.class){
      if(hostClass != null) return;
      ClassLoader sys = ClassLoader.getSystemClassLoader();
      hostClass = Class.forName(HOST_CLASS, false, sys);
      // Required method: Component getOrCreateBrowserComponent(String)
      mGetOrCreate = hostClass.getMethod("getOrCreateBrowserComponent", String.class);
      // Optional helpers:
      try{ mNavigate  = hostClass.getMethod("navigate", String.class); }catch(NoSuchMethodException ignored){}
      try{ mDispose   = hostClass.getMethod("dispose"); }catch(NoSuchMethodException ignored){}
      try{ mReload    = hostClass.getMethod("reload"); }catch(NoSuchMethodException ignored){}
      // no devtools method by default
      try{ mSetAddr   = hostClass.getMethod("setOnAddressChange", Consumer.class); }catch(NoSuchMethodException ignored){}
      try{ mSetTitle  = hostClass.getMethod("setOnTitleChange", Consumer.class); }catch(NoSuchMethodException ignored){}
      try{ mSetConsole= hostClass.getMethod("setOnConsoleMessage", Consumer.class); }catch(NoSuchMethodException ignored){}
      try{ mDiagnostics= hostClass.getMethod("diagnostics"); }catch(NoSuchMethodException ignored){}
      try{ mSetEventLog = hostClass.getMethod("setOnEventLog", Consumer.class); }catch(NoSuchMethodException ignored){}
      try{ mSetUrlAllowRegex = hostClass.getMethod("setUrlAllowRegex", String.class); }catch(NoSuchMethodException ignored){}
      try{ mSetLoading = hostClass.getMethod("setOnLoading", Consumer.class); }catch(NoSuchMethodException ignored){}
      try{ mSetCanBack = hostClass.getMethod("setOnCanGoBack", Consumer.class); }catch(NoSuchMethodException ignored){}
      try{ mSetCanFwd  = hostClass.getMethod("setOnCanGoForward", Consumer.class); }catch(NoSuchMethodException ignored){}
      try{ mGoBack     = hostClass.getMethod("goBack"); }catch(NoSuchMethodException ignored){}
      try{ mGoForward  = hostClass.getMethod("goForward"); }catch(NoSuchMethodException ignored){}
      try{ mStop       = hostClass.getMethod("stop"); }catch(NoSuchMethodException ignored){}
      try{ mSetHomeUrl = hostClass.getMethod("setHomeUrl", String.class); }catch(NoSuchMethodException ignored){}
      try{ mHome       = hostClass.getMethod("navigateHome"); }catch(NoSuchMethodException ignored){}
    }
  }
}
