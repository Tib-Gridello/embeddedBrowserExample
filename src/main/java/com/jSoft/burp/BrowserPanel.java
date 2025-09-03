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
import javax.swing.JTextField;
import javax.swing.JButton;
import javax.swing.JToolBar;
import javax.swing.UIManager;
import javax.swing.JComponent;
import javax.swing.AbstractAction;
import javax.swing.KeyStroke;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
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
  _api = api;
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
private static final String        START_URL = "https://www.google.com";
private static final String        UI_KEY_BROWSER_COMPONENT = "com.jSoft.burp.jcef.component";
private static final ThreadFactory _TF = new ThreadFactory(){
  @Override
  public Thread newThread(Runnable r){
    return Thread.ofPlatform().
      daemon().
      name("jcef-init", 1).
      unstarted(r);
  }
};//end _TF

private final MontoyaApi     _api;
private final AtomicBoolean   _unloading = new AtomicBoolean(false);
private final ExecutorService _initExecSvc;
private final Future<?>       _initTask;

private volatile CefApp     _cefApp;
private volatile CefClient  _cefClient;
private volatile CefBrowser _cefBrowser;
private volatile Component  _browserUi;
private volatile boolean    _usingHost;

//-----------------------------------------------------------------------------
private void _initJcefAndAttachBrowser(){
  if(_unloading.get()) return;

  // First preference: if a host-side Burp JCEF bridge is available, use it.
  try{
    // Attempt to ensure our agent is installed so Host is visible to system loader
    SelfAttach.ensureAgentInstalled();
    // Give the agent a brief moment; then check availability
    boolean hostReady = BrowserHostBridge.isAvailable();
    if(!hostReady){
      for(int i=0;i<10 && !hostReady;i++){
        try{ Thread.sleep(100); }catch(InterruptedException ignored){}
        hostReady = BrowserHostBridge.isAvailable();
      }
    }
    if(hostReady){
      _browserUi = BrowserHostBridge.getOrCreateBrowserComponent(START_URL);
      if(_api != null){
        _api.logging().logToOutput("[Embedded Browser] Using Burp's built-in JCEF via host bridge.");
      }
      // Sample debug listeners
      BrowserHostBridge.setOnAddressChange(url -> {
        if(_api != null) _api.logging().logToOutput("[Browser] Location: " + url);
      });
      BrowserHostBridge.setOnEventLog(ev -> {
        if(_api != null) _api.logging().logToOutput("[Event] " + ev);
      });
      // Allow only hosts under google.com (any scheme, any subdomain)
      BrowserHostBridge.setUrlAllowRegex("^[a-zA-Z][a-zA-Z0-9+.-]*://([^.*/]+\\.)*google\\.com(?::\\d+)?(/.*)?$");
      _usingHost = true;
      if(_api != null){
        final String diag = BrowserHostBridge.diagnostics();
        if(!diag.isEmpty()){
          _api.logging().logToOutput("[Host Diagnostics]\n" + diag);
        }
      }
      SwingUtilities.invokeLater(() -> {
        this.removeAll();
        if(_usingHost){
          this.add(_buildToolbar(), BorderLayout.NORTH);
        }
        this.add(_browserUi, BorderLayout.CENTER);
        this.revalidate();
        this.repaint();
      });
      return;
    }
  }catch(final Throwable t){
    // If anything fails, log and fall back to local JCEF initialization
    if(_api != null){
      _api.logging().logToError("[Embedded Browser] Host bridge failed: " + t);
    }
  }

  // Reuse existing JCEF component across extension reloads to avoid
  // reloading native libraries in a new classloader.
  final Object existing = UIManager.getDefaults().get(UI_KEY_BROWSER_COMPONENT);
  if(existing instanceof Component){
    _browserUi = (Component)existing;
    if(_api != null){
      _api.logging().logToOutput("[Embedded Browser] Reusing existing JCEF component from previous load.");
    }
    SwingUtilities.invokeLater(() -> {
      final Container parent = _browserUi.getParent();
      if(parent != null){
        parent.remove(_browserUi);
        parent.revalidate();
        parent.repaint();
      }
      this.removeAll();
      this.add(_browserUi, BorderLayout.CENTER);
      this.revalidate();
      this.repaint();
    });
    return;
  }

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
    if(_api != null){
      _api.logging().logToError("[Embedded Browser] Initialization failed: " + e);
    }
    return;
  }
  catch(final UnsatisfiedLinkError e){
    // If natives are already loaded in another classloader, try reusing a
    // previously created component; otherwise inform the user.
    final Object comp = UIManager.getDefaults().get(UI_KEY_BROWSER_COMPONENT);
    if(comp instanceof Component){
      _browserUi = (Component)comp;
      if(_api != null){
        _api.logging().logToOutput("[Embedded Browser] Native libs already loaded; reusing existing JCEF component.");
      }
      SwingUtilities.invokeLater(() -> {
        final Container parent = _browserUi.getParent();
        if(parent != null){
          parent.remove(_browserUi);
          parent.revalidate();
          parent.repaint();
        }
        this.removeAll();
        this.add(_browserUi, BorderLayout.CENTER);
        this.revalidate();
        this.repaint();
      });
    }else{
      SwingUtilities.invokeLater(() -> {
        this.removeAll();
        this.add(new JScrollPane(new JTextArea("Browser can only be initialized once per Burp JVM session:\n\n" + e)), BorderLayout.CENTER);
        this.revalidate();
        this.repaint();
      });
      if(_api != null){
        _api.logging().logToError("[Embedded Browser] Native libs already loaded in another classloader; no existing component to reuse.");
      }
    }
    return;
  }

  _cefClient = _cefApp.createClient();
  _cefBrowser = _cefClient.createBrowser(START_URL, false, false);
  _browserUi = _cefBrowser.getUIComponent();
  UIManager.getDefaults().put(UI_KEY_BROWSER_COMPONENT, _browserUi);

  SwingUtilities.invokeLater(() -> {
    this.removeAll();
    this.add(_browserUi, BorderLayout.CENTER);
    this.revalidate();
    this.repaint();
  });
}//end _initJcefAndAttachBrowser()

//-----------------------------------------------------------------------------
private void _disposeJcef(){
  // Keep JCEF alive across extension reloads for reuse; only clear references
  // here. Burp JVM cannot unload native libraries per classloader anyway.
  // If someone removed the shared UI component from UIManager, fall back to
  // disposing in the future if necessary.
  if(_cefBrowser != null){
    // intentionally not closing to allow reuse across reloads
    _cefBrowser = null;
  }

  if(_cefClient != null){
    // intentionally not disposing to allow reuse across reloads
    _cefClient = null;
  }

  if(_cefApp != null){
    // intentionally not disposing to allow reuse across reloads
    _cefApp = null;
  }
}//end _disposeJcef()

//-----------------------------------------------------------------------------
private JToolBar _buildToolbar(){
  final JToolBar bar = new JToolBar();
  bar.setFloatable(false);

  final JButton back = new JButton("\u25C0"); // ◀
  final JButton fwd  = new JButton("\u25B6"); // ▶
  final JButton stop = new JButton("\u23F9"); // ⏹
  final JButton reload = new JButton("\u21BB"); // ↻
  final JButton home = new JButton("\u2302"); // ⌂
  for(JButton b : new JButton[]{back,fwd,stop,reload,home}){
    b.setFocusable(false);
    b.setBorderPainted(false);
    b.setOpaque(false);
  }
  back.setToolTipText("Back (Alt+Left)");
  fwd.setToolTipText("Forward (Alt+Right)");
  stop.setToolTipText("Stop (Esc)");
  reload.setToolTipText("Reload (Ctrl+R)");
  home.setToolTipText("Home (Alt+Home)");

  final JTextField url = new JTextField(60);
  url.setToolTipText("Enter URL and press Enter (Ctrl+L to focus)");

  // Actions
  back.addActionListener(e -> BrowserHostBridge.goBack());
  fwd.addActionListener(e -> BrowserHostBridge.goForward());
  stop.addActionListener(e -> BrowserHostBridge.stop());
  reload.addActionListener(e -> BrowserHostBridge.reload());
  home.addActionListener(e -> BrowserHostBridge.home());
  url.addActionListener(e -> BrowserHostBridge.navigate(url.getText().trim()));

  // Reflect host nav state into UI
  BrowserHostBridge.setOnCanGoBack(enabled -> SwingUtilities.invokeLater(() -> back.setEnabled(enabled != null && enabled)));
  BrowserHostBridge.setOnCanGoForward(enabled -> SwingUtilities.invokeLater(() -> fwd.setEnabled(enabled != null && enabled)));
  BrowserHostBridge.setOnLoading(loading -> SwingUtilities.invokeLater(() -> {
    boolean l = loading != null && loading;
    stop.setEnabled(l);
  }));
  BrowserHostBridge.setOnAddressChange(current -> SwingUtilities.invokeLater(() -> {
    if(current == null) return;
    // Avoid replacing the URL bar with data: URIs for internal error pages
    if(current.startsWith("data:")) return;
    if(!current.equals(url.getText())){
      url.setText(current);
    }
  }));

  // Keyboard shortcuts on the toolbar panel
  // Ctrl+L -> focus URL, Alt+Left/Right -> back/forward, Ctrl+R -> reload, Esc -> stop, Alt+Home -> home
  bar.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("control L"), "focusUrl");
  bar.getActionMap().put("focusUrl", new AbstractAction(){ public void actionPerformed(java.awt.event.ActionEvent e){ url.requestFocusInWindow(); url.selectAll(); }});
  bar.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("alt LEFT"), "back");
  bar.getActionMap().put("back", new AbstractAction(){ public void actionPerformed(java.awt.event.ActionEvent e){ back.doClick(); }});
  bar.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("alt RIGHT"), "forward");
  bar.getActionMap().put("forward", new AbstractAction(){ public void actionPerformed(java.awt.event.ActionEvent e){ fwd.doClick(); }});
  bar.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("control R"), "reload");
  bar.getActionMap().put("reload", new AbstractAction(){ public void actionPerformed(java.awt.event.ActionEvent e){ reload.doClick(); }});
  bar.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("ESCAPE"), "stop");
  bar.getActionMap().put("stop", new AbstractAction(){ public void actionPerformed(java.awt.event.ActionEvent e){ stop.doClick(); }});
  bar.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("alt HOME"), "home");
  bar.getActionMap().put("home", new AbstractAction(){ public void actionPerformed(java.awt.event.ActionEvent e){ home.doClick(); }});

  // Layout
  bar.add(back);
  bar.add(fwd);
  bar.add(reload);
  bar.add(stop);
  bar.add(home);
  bar.addSeparator();
  bar.add(url);
  return bar;
}
}
///////////////////////////////////////////////////////////////////////////////
// END CLASS BrowserPanel
///////////////////////////////////////////////////////////////////////////////
