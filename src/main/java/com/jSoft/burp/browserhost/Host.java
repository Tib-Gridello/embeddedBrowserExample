package com.jSoft.burp.browserhost;

import me.friwi.jcefmaven.CefAppBuilder;
import me.friwi.jcefmaven.CefInitializationException;
import me.friwi.jcefmaven.UnsupportedPlatformException;
import org.cef.CefApp;
import org.cef.CefClient;
import org.cef.browser.CefBrowser;
import org.cef.handler.CefDisplayHandlerAdapter;
import org.cef.handler.CefLoadHandlerAdapter;
import org.cef.handler.CefRequestHandlerAdapter;
import org.cef.handler.CefLifeSpanHandlerAdapter;
import org.cef.handler.CefJSDialogHandlerAdapter;
// Optional handlers vary by JCEF build; keep a conservative set to ensure compatibility
import org.cef.browser.CefFrame;

import javax.swing.SwingUtilities;
import java.awt.Component;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Lives in Burp's system classloader (via Java agent or classpath) and manages
 * a JCEF browser. Exposes only JDK types across classloader boundaries.
 */
public final class Host {
  private static volatile CefApp app;
  private static volatile CefClient client;
  private static volatile CefBrowser browser;
  private static volatile Component browserUi;
  private static volatile String    homeUrl = "https://www.google.com";

  private static final AtomicReference<Consumer<String>> onAddressChange = new AtomicReference<>();
  private static final AtomicReference<Consumer<String>> onTitleChange   = new AtomicReference<>();
  private static final AtomicReference<Consumer<String>> onEventLog      = new AtomicReference<>();
  private static final AtomicReference<Consumer<Boolean>> onLoading      = new AtomicReference<>();
  private static final AtomicReference<Consumer<Boolean>> onCanBack      = new AtomicReference<>();
  private static final AtomicReference<Consumer<Boolean>> onCanFwd       = new AtomicReference<>();
  private static volatile Pattern allowPattern; // null = allow all

  private Host(){}

  public static synchronized Component getOrCreateBrowserComponent(String startUrl) throws Throwable{
    if(browserUi != null) return browserUi;

    final CefAppBuilder builder = new CefAppBuilder();
    builder.addJcefArgs("--disable-gpu-vsync");
    builder.getCefSettings().windowless_rendering_enabled = false;

    try{
      app = builder.build();
    }catch(IOException | UnsupportedPlatformException | InterruptedException | CefInitializationException e){
      throw e;
    }

    client = app.createClient();
    browser = client.createBrowser(startUrl != null ? startUrl : "about:blank", false, false);
    browserUi = browser.getUIComponent();

    // Handlers
    client.addDisplayHandler(new CefDisplayHandlerAdapter(){
      @Override public void onAddressChange(CefBrowser b, CefFrame f, String url){
        Consumer<String> c = onAddressChange.get();
        if(c != null) c.accept(url);
      }
      @Override public void onTitleChange(CefBrowser b, String title){
        Consumer<String> c = onTitleChange.get();
        if(c != null) c.accept(title);
      }
    });
    client.addLoadHandler(new CefLoadHandlerAdapter(){
      @Override public void onLoadingStateChange(CefBrowser b, boolean isLoading, boolean canGoBack, boolean canGoForward){
        Consumer<Boolean> l = onLoading.get();
        if(l != null) l.accept(isLoading);
        Consumer<Boolean> cb = onCanBack.get();
        if(cb != null) cb.accept(canGoBack);
        Consumer<Boolean> cf = onCanFwd.get();
        if(cf != null) cf.accept(canGoForward);
      }
    });

    // Request/navigation policy
    client.addRequestHandler(new CefRequestHandlerAdapter(){
      @Override public boolean onBeforeBrowse(CefBrowser b, CefFrame f, org.cef.network.CefRequest req, boolean user_gesture, boolean is_redirect){
        String url = req != null ? req.getURL() : null;
        boolean ok = isAllowed(url);
        Consumer<String> log = onEventLog.get();
        if(log != null){
          log.accept("onBeforeBrowse url=" + url + " allowed=" + ok);
        }
        if(!ok){
          // Show a simple error page instead of navigating
          showBlocked(url);
          return true; // cancel original navigation
        }
        return false;
      }
    });

    // Other handlers omitted for compatibility; add incrementally if needed


    return browserUi;
  }

  public static void navigate(String url){
    if(browser != null && url != null){
      browser.loadURL(url);
    }
  }

  public static void reload(){
    if(browser != null){ browser.reload(); }
  }

  public static void goBack(){
    if(browser != null && browser.canGoBack()) browser.goBack();
  }

  public static void goForward(){
    if(browser != null && browser.canGoForward()) browser.goForward();
  }

  public static void stop(){
    if(browser != null) browser.stopLoad();
  }

  // DevTools support is version-dependent; not implemented for this JCEF version.

  public static void setOnAddressChange(Consumer<String> c){ onAddressChange.set(c); }
  public static void setOnTitleChange(Consumer<String> c){ onTitleChange.set(c); }
  public static void setOnEventLog(Consumer<String> c){ onEventLog.set(c); }
  public static void setOnLoading(Consumer<Boolean> c){ onLoading.set(c); }
  public static void setOnCanGoBack(Consumer<Boolean> c){ onCanBack.set(c); }
  public static void setOnCanGoForward(Consumer<Boolean> c){ onCanFwd.set(c); }

  public static void setUrlAllowRegex(String regex){
    if(regex == null || regex.isEmpty()){
      allowPattern = null;
    }else{
      allowPattern = Pattern.compile(regex);
    }
  }

  public static void setHomeUrl(String url){
    if(url != null && !url.isBlank()) homeUrl = url;
  }

  public static void navigateHome(){
    navigate(homeUrl);
  }

  public static synchronized void dispose(){
    // Only dispose if you know no other components use it. Typically keep alive.
    try{ if(browser != null){ browser.close(true); } }catch(Throwable ignored){}
    browser = null; browserUi = null;
    try{ if(client != null){ client.dispose(); } }catch(Throwable ignored){}
    client = null;
    try{ if(app != null){ app.dispose(); } }catch(Throwable ignored){}
    app = null;
  }

  public static String diagnostics(){
    StringBuilder sb = new StringBuilder();
    ClassLoader hostCl = Host.class.getClassLoader();
    sb.append("Host CL: ").append(hostCl).append('\n');
    if(app != null){
      sb.append("CefApp: ").append(app.getClass().getName())
        .append(" @").append(System.identityHashCode(app))
        .append(" CL=").append(app.getClass().getClassLoader()).append('\n');
    } else {
      sb.append("CefApp: null\n");
    }
    if(client != null){
      sb.append("CefClient: ").append(client.getClass().getName())
        .append(" @").append(System.identityHashCode(client))
        .append(" CL=").append(client.getClass().getClassLoader()).append('\n');
    } else {
      sb.append("CefClient: null\n");
    }
    if(browser != null){
      sb.append("CefBrowser: ").append(browser.getClass().getName())
        .append(" @").append(System.identityHashCode(browser))
        .append(" CL=").append(browser.getClass().getClassLoader()).append('\n');
    } else {
      sb.append("CefBrowser: null\n");
    }
    if(browserUi != null){
      sb.append("BrowserUI: ").append(browserUi.getClass().getName())
        .append(" @").append(System.identityHashCode(browserUi))
        .append(" CL=").append(browserUi.getClass().getClassLoader()).append('\n');
    } else {
      sb.append("BrowserUI: null\n");
    }
    return sb.toString();
  }

  //-----------------------------------------------------------------------------
  private static boolean isAllowed(String url){
    if(url == null) return false;
    // Always allow internal/about/data schemes so we can render error pages
    if(url.startsWith("about:") || url.startsWith("data:")) return true;
    Pattern p = allowPattern;
    if(p == null) return true;
    return p.matcher(url).matches();
  }

  private static void showBlocked(String attempted){
    if(browser == null) return;
    String safe = attempted == null ? "" : attempted.replace("<", "&lt;").replace(">", "&gt;");
    String html = "<!doctype html><html><head><meta charset='utf-8'><title>Navigation Blocked</title>" +
      "<style>body{font-family:system-ui,Segoe UI,Roboto,Arial,sans-serif;margin:2rem;color:#333} .card{border:1px solid #ddd;border-radius:8px;padding:1.5rem;max-width:860px} .bad{color:#b00020} code{background:#f6f8fa;padding:2px 4px;border-radius:4px} </style>"+
      "</head><body><div class='card'><h2 class='bad'>Navigation blocked</h2>"+
      "<p>This extension currently allows only <code>https://google.com</code> (testing mode).</p>"+
      "<p>Attempted URL:</p><pre><code>"+ safe +"</code></pre>"+
      "</div></body></html>";
    String url = "data:text/html;charset=utf-8," + encodeForDataUrl(html);
    try{
      browser.loadURL(url);
    }catch(Throwable ignored){}
  }

  private static String encodeForDataUrl(String s){
    StringBuilder out = new StringBuilder(s.length()*2);
    for(char c : s.toCharArray()){
      if(c <= 0x20 || c >= 0x7f || c=='%' || c=='#' || c=='?' || c=='&'){
        out.append('%');
        out.append(String.format("%02X", (int)c));
      }else if(c==' '){
        out.append("%20");
      }else{
        out.append(c);
      }
    }
    return out.toString();
  }
}
