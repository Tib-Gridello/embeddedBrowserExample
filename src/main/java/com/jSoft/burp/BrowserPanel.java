package com.jSoft.burp;

import burp.api.montoya.MontoyaApi;
import me.friwi.jcefmaven.CefAppBuilder;
import me.friwi.jcefmaven.CefInitializationException;
import me.friwi.jcefmaven.UnsupportedPlatformException;
import org.cef.CefApp;
import org.cef.CefClient;
import org.cef.browser.CefBrowser;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Component;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

///////////////////////////////////////////////////////////////////////////////
// CLASS BrowserPanel
///////////////////////////////////////////////////////////////////////////////
class BrowserPanel extends JPanel{
//---------------------------------------------------------------------------
public BrowserPanel(final MontoyaApi api){
  super(new BorderLayout());
  api.extension().registerUnloadingHandler(this::unload);

  this.add(new JLabel("Starting browser..."), BorderLayout.CENTER);

  _initExecSvc = Executors.newSingleThreadExecutor(_TF);
  _initTask    = _initExecSvc.submit(this::_initJcefAndAttachBrowser);
}//end ctor()

//---------------------------------------------------------------------------
public void unload(){
  if(!_unloading.compareAndExchange(false, true)) return;

  if(_initTask != null){
    _initTask.cancel(true);
  }

  if(_initExecSvc != null){
    _initExecSvc.shutdownNow();
    try{ _initExecSvc.awaitTermination(1, TimeUnit.SECONDS); }
    catch(InterruptedException ignored){}
  }

  _disposeJcef();
}//end unload()

//////////////
// PRIVATE
//////////////
private static final String        START_URL = "https://www.duckduckgo.com";
private static final ThreadFactory _TF = new ThreadFactory(){
  @Override
  public Thread newThread(Runnable r){
    return Thread.ofPlatform().
      daemon().
      name("jcef-init", 1).
      unstarted(r);
  }
};//end _TF

private final AtomicBoolean   _unloading = new AtomicBoolean(false);
private final ExecutorService _initExecSvc;
private final Future<?>       _initTask;

private volatile CefApp     _cefApp;
private volatile CefClient  _cefClient;
private volatile CefBrowser _cefBrowser;

//-----------------------------------------------------------------------------
private void _initJcefAndAttachBrowser(){
  if(_unloading.get()) return;

  final CefAppBuilder builder = new CefAppBuilder();
  builder.addJcefArgs("--disable-gpu-vsync"); //minimize jitter in some setups
  builder.getCefSettings().windowless_rendering_enabled = false; // use AWT component

  try{
    _cefApp = builder.build();
  }
  catch(final IOException | UnsupportedPlatformException | InterruptedException | CefInitializationException e){
    SwingUtilities.invokeLater(() -> {
      this.removeAll();
      this.add(new JScrollPane(new JTextArea("Failed to initialize embedded browser:\n\n" + e)), BorderLayout.CENTER);
      this.revalidate();
      this.repaint();
    });
  }
  catch(final UnsatisfiedLinkError e){
    SwingUtilities.invokeLater(() -> {
      this.removeAll();
      this.add(new JScrollPane(new JTextArea("Browser can only be initialized once per Burp JVM session:\n\n" + e)), BorderLayout.CENTER);
      this.revalidate();
      this.repaint();
    });
  }

  _cefClient = _cefApp.createClient();
  _cefBrowser = _cefClient.createBrowser(START_URL, false, false);
  final Component browserUi = _cefBrowser.getUIComponent();

  SwingUtilities.invokeLater(() -> {
    this.removeAll();
    this.add(browserUi, BorderLayout.CENTER);
    this.revalidate();
    this.repaint();
  });
}//end _initJcefAndAttachBrowser()

//-----------------------------------------------------------------------------
private void _disposeJcef(){
  if(_cefBrowser != null){
    _cefBrowser.close(true);
    _cefBrowser = null;
  }

  if(_cefClient != null){
    _cefClient.dispose();
    _cefClient = null;
  }

  if(_cefApp != null){
    _cefApp.dispose();
    _cefApp = null;
  }
}//end _disposeJcef()
}
///////////////////////////////////////////////////////////////////////////////
// END CLASS BrowserPanel
///////////////////////////////////////////////////////////////////////////////