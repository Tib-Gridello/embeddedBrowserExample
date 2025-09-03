Embedded Browser for Burp Suite
===============================

Overview
--------

This extension embeds a Chromium (JCEF) browser in a Burp Suite tab. It provides full control of the browser (navigation, request policy, events) when the included Java agent is used to expose a small host shim in Burp’s main classloader. A display‑only fallback is available if the agent is not active.

Key Features
------------

- Host‑mode browser with handlers: Request policy, navigation control, and event callbacks.
- Clean toolbar: Back, Forward, Reload, Stop, Home, and a URL bar.
- Keyboard shortcuts: Ctrl+L, Alt+Left/Right, Ctrl+R, Esc, Alt+Home.
- Allowlist demo: Only hosts under `google.com` are allowed by default; blocked navigations render a friendly error page.
- Safe fallback: If the agent is not active, a local JCEF instance is used and its UI component is reused across extension reloads.

How It Works
------------

- Burp loads each extension in its own classloader. JCEF (Chromium) types must be used from the same classloader that loaded the native libraries. Swing/AWT `Component` can be shared, but `CefApp/CefClient/CefBrowser` cannot.
- The bundled Java agent appends a host shim into the system classloader at Burp startup. The host creates and owns JCEF, exposing only JDK types (e.g., `java.awt.Component`, `String`, `Consumer<T>`) so the extension can control it via reflection.
- If the agent isn’t active, the extension falls back to its own JCEF and caches the UI `Component` for visual reuse (no handler control).

Requirements
------------

- Burp Suite Community or Professional
- Java 21+

Install & Run
-------------

1) Start Burp with the agent enabled (recommended):

- Linux/macOS:
  - `java -javaagent:/absolute/path/to/embeddedBrowserExample-1.0.0-SNAPSHOT.jar -jar /absolute/path/to/burpsuite_community.jar`

2) Load the same JAR as a Burp extension:

- Extender → Extensions → Add → Type: Java → Select `embeddedBrowserExample-1.0.0-SNAPSHOT.jar`

Expected Output (host mode)
---------------------------

- `[Embedded Browser] Using Burp's built-in JCEF via host bridge.`
- Toolbar is visible at the top of the tab.

If you see instead
-------------------

- `[Embedded Browser] Reusing existing JCEF component from previous load.`
- You are in fallback mode (agent not active). Restart Burp with `-javaagent` and reload the extension.

Usage
-----

- URL bar: Type an address and press Enter.
- Shortcuts: Ctrl+L (focus URL), Alt+Left/Right (back/forward), Ctrl+R (reload), Esc (stop), Alt+Home (home).
- Allowlist behavior: Only `*.google.com` is allowed by default. Other hosts are blocked with an inline error page showing the attempted URL, and an event is logged in Extender output.
- Start page: `https://www.google.com`.

Configuration Hooks
-------------------

- Allowlist regex (set by the panel on startup):
  - `^[a-zA-Z][a-zA-Z0-9+.-]*://([^.*/]+\.)*google\.com(?::\d+)?(/.*)?$`
- Home URL: defaults to `https://www.google.com`, can be changed in host API (`setHomeUrl`).

Project Layout
--------------

- `src/main/java/com/jSoft/burp/Extension.java`: Registers the suite tab.
- `src/main/java/com/jSoft/burp/BrowserPanel.java`: UI/toolbar, host vs fallback selection.
- `src/main/java/com/jSoft/burp/BrowserHostBridge.java`: Reflection bridge into the host shim.
- `src/main/java/com/jSoft/burp/browserhost/Host.java`: System‑classloader JCEF owner and handlers.
- `src/main/java/com/jSoft/burp/agent/Agent.java`: Java agent (premain/agentmain) that exposes the host shim.

Build
-----

- `mvn -DskipTests package`
- Output: `target/embeddedBrowserExample-1.0.0-SNAPSHOT.jar` (contains both extension and agent)

Troubleshooting
---------------

- No toolbar / fallback message: Start Burp with `-javaagent:<absolute path to jar>` and reload the extension.
- Native already loaded: Restart Burp and ensure host mode is used; only one JCEF can load per JVM.
- URL bar shows `data:`: The panel ignores internal `data:` pages so the bar remains clean.

Notes
-----

- This project does not steal or reparent Burp’s own Browser tab. It runs its own JCEF instance in the system classloader for stability and handler control.
