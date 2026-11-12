// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.ui.jcef;

import com.intellij.ide.plugins.PluginManager;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.extensions.PluginDescriptor;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.registry.Registry;
import com.intellij.ui.jcef.JBCefClient.JSQueryPool;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.browser.CefMessageRouter;
import org.cef.callback.CefQueryCallback;
import org.cef.handler.CefMessageRouterHandler;
import org.cef.handler.CefMessageRouterHandlerAdapter;
import org.cef.misc.CefLog;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.VisibleForTesting;

import java.lang.ref.WeakReference;
import java.net.IDN;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * A JS query callback.
 * <p>
 * The query function is available in every frame of every browser of the {@link JBCefClient}.
 * The {@link FramePolicy} of the query selects the frames whose calls reach the handlers.
 * The other calls fail in JS with the error code {@code -403} and never reach a handler.
 *
 * @author tav
 */
public final class JBCefJSQuery implements JBCefDisposable {
  private static final Logger LOG = Logger.getInstance(JBCefJSQuery.class);
  private static final boolean DEBUG_JS = Boolean.getBoolean("ide.browser.jcef.debug.js");

  /**
   * The registry key that lets every frame of the client call every query, as before {@link FramePolicy}.
   */
  @VisibleForTesting
  static final @NonNls String ANY_FRAME_REGISTRY_KEY = "ide.browser.jcef.jsQuery.anyFrame";

  /**
   * The error code of a call that the frame policy of the query refuses. The failure callback of the JS call gets it.
   */
  public static final int ERR_CODE_FRAME_REFUSED = -403;
  @VisibleForTesting
  static final @NonNls String ERR_MSG_FRAME_REFUSED = "The frame policy of the query refuses this frame";


  private final @NotNull JSQueryFunc myFunc;
  private final @NotNull JBCefClient myJBCefClient;
  private final @NotNull OwnBrowser myOwnBrowser;
  private final @NotNull FramePolicy myFramePolicy;
  private final @NotNull DisposeHelper myDisposeHelper = new DisposeHelper();

  // The key is a FrameHandler or a frame-blind Function.
  private final @NotNull Map<Object, CefMessageRouterHandler> myHandlerMap = Collections.synchronizedMap(new HashMap<>());

  private JBCefJSQuery(@NotNull JBCefBrowserBase browser, @NotNull JBCefJSQuery.JSQueryFunc func, @NotNull FramePolicy framePolicy) {
    myFunc = func;
    myJBCefClient = browser.getJBCefClient();
    myOwnBrowser = new OwnBrowser(browser.getCefBrowser());
    myFramePolicy = framePolicy;
    Disposer.register(browser.getJBCefClient(), this);
  }

  /**
   * @return name of the global function JS must call to send query to Java
   */
  public @NotNull String getFuncName() {
    return myFunc.myFuncName;
  }

  /**
   * Creates a unique JS query that accepts only the calls of the main frame of the browser.
   * <p>
   * This is {@link #create(JBCefBrowserBase, FramePolicy)} with {@link FramePolicy#mainFrame()}.
   * When the main frame can show content that the caller does not own, use {@link FramePolicy#mainFrameOf(String...)}.
   *
   * @param browser the associated CEF browser
   * @see JBCefClient.Properties#JS_QUERY_POOL_SIZE
   */
  public static @NotNull JBCefJSQuery create(@NotNull JBCefBrowserBase browser) {
    return create(browser, FramePolicy.mainFrame());
  }

  /**
   * Creates a unique JS query that accepts only the calls of the frames that the policy allows.
   *
   * @param browser the associated CEF browser. The query refuses the calls of the other browsers of its client.
   * @param framePolicy the frames whose calls reach the handlers
   * @see JBCefClient.Properties#JS_QUERY_POOL_SIZE
   */
  public static @NotNull JBCefJSQuery create(@NotNull JBCefBrowserBase browser, @NotNull FramePolicy framePolicy) {
    if (!browser.isCefBrowserCreateStarted()) {
      return new JBCefJSQuery(browser, new JSQueryFunc(browser.getJBCefClient()), framePolicy);
    }
    JSQueryPool pool = browser.getJBCefClient().getJSQueryPool();
    JSQueryFunc slot;
    if (pool != null && (slot = pool.useFreeSlot()) != null) {
      return new JBCefJSQuery(browser, slot, framePolicy);
    }
    LOG.warn("Set the property JBCefClient.Properties.JS_QUERY_POOL_SIZE to use JBCefJSQuery after the browser has been created",
             new IllegalStateException());
    // this query will produce en error in JS debug console like: "Uncaught TypeError: window.cefQuery_0123456789_1 is not a function"
    return new JBCefJSQuery(browser, new JSQueryFunc(browser.getJBCefClient()), framePolicy);
  }

  /**
   * @deprecated use {@link #create(JBCefBrowserBase)}
   */
  @Deprecated(forRemoval = true)
  public static JBCefJSQuery create(@NotNull JBCefBrowser browser) {
    return create((JBCefBrowserBase)browser);
  }

  /**
   * Returns the query callback to inject into JS code.
   * <p>
   * When the frame policy refuses the call, the callback writes a warning to the JS console.
   *
   * @param queryResult the result (JS variable name, or JS value in single quotes) that will be passed to the Java handler {@link #addHandler(FrameHandler)}
   */
  public @NotNull String inject(@Nullable String queryResult) {
    return inject(queryResult, "function(response) {}",
                  "function(error_code, error_message) { if (error_code === " + ERR_CODE_FRAME_REFUSED + ") " +
                  "console.warn('" + myFunc.myFuncName + ": ' + error_message); }");
  }

  /**
   * Returns the query callback to inject into JS code.
   *
   * @param queryResult the result (JS variable name, or JS value in single quotes) that will be passed to the Java handler {@link #addHandler(FrameHandler)}
   * @param onSuccessCallback JS callback in format: {@code function(response) {}}
   * @param onFailureCallback JS callback in format: {@code function(error_code, error_message) {}}.
   *                          A call that the frame policy refuses gets it too.
   */
  public @NotNull String inject(@Nullable String queryResult, @NotNull String onSuccessCallback, @NotNull String onFailureCallback) {
    checkDisposed();

    if (queryResult != null && queryResult.isEmpty()) queryResult = "''";
    return "window." + myFunc.myFuncName +
           "({request: '' + " + queryResult + "," +
             "onSuccess: " + onSuccessCallback + "," +
             "onFailure: " + onFailureCallback +
           "});";
  }

  /**
   * Adds a handler that gets the browser and the frame of each call that the frame policy allows.
   */
  public void addHandler(@NotNull FrameHandler handler) {
    addHandlerImpl(handler, handler);
  }

  /**
   * Adds a handler that gets only the request of each call that the frame policy allows.
   * <p>
   * Prefer {@link #addHandler(FrameHandler)}: its handler also gets the frame of the call.
   */
  @ApiStatus.Obsolete
  public void addHandler(@NotNull Function<? super String, ? extends Response> handler) {
    addHandlerImpl(handler, (browser, frame, request) -> handler.apply(request));
  }

  private void addHandlerImpl(@NotNull Object key, @NotNull FrameHandler handler) {
    checkDisposed();

    CefMessageRouterHandler cefHandler = createRouterHandler(myFramePolicy, myOwnBrowser, myFunc.myFuncName, key.getClass(), handler);
    myFunc.myRouter.addHandler(cefHandler, false);
    myHandlerMap.put(key, cefHandler);
  }

  @VisibleForTesting
  static @NotNull CefMessageRouterHandler createRouterHandler(@NotNull FramePolicy framePolicy,
                                                              @NotNull OwnBrowser ownBrowser,
                                                              @NotNull String funcName,
                                                              @NotNull Class<?> handlerClass,
                                                              @NotNull FrameHandler handler) {
    RefusalLog refusalLog = new RefusalLog("JBCefJSQuery " + funcName, handlerClass);
    return new CefMessageRouterHandlerAdapter() {
      @Override
      public boolean onQuery(CefBrowser browser,
                             CefFrame frame,
                             long query_id,
                             String request,
                             boolean persistent,
                             CefQueryCallback callback)
      {
        if (DEBUG_JS)
          CefLog.Debug("onQuery: browser=%s, frame=%s, qid=%d, request=%s", browser, frame, query_id, request);
        if (browser == null || frame == null || !framePolicy.allows(ownBrowser, browser, frame, refusalLog)) {
          if (callback != null) callback.failure(ERR_CODE_FRAME_REFUSED, ERR_MSG_FRAME_REFUSED);
          return true;
        }
        Response response = handler.handle(browser, frame, request);
        if (callback != null && response != null) {
          if (response.isSuccess() && response.hasResponse()) {
            callback.success(response.response());
          } else {
            callback.failure(response.errCode(), response.errMsg());
          }
        } else if (callback != null) {
          callback.success("");
        }
        return true;
      }

      @Override
      public void onQueryCanceled(CefBrowser browser, CefFrame frame, long queryId) {
        if (DEBUG_JS)
          CefLog.Debug("onQueryCanceled: browser=%s, frame=%s, qid=%d", browser, frame, queryId);
      }
    };
  }

  /**
   * Removes a handler that {@link #addHandler(FrameHandler)} added.
   */
  public void removeHandler(@NotNull FrameHandler handler) {
    removeHandlerImpl(handler);
  }

  /**
   * Removes a handler that {@link #addHandler(Function)} added.
   */
  @ApiStatus.Obsolete
  public void removeHandler(@NotNull Function<? super String, ? extends Response> function) {
    removeHandlerImpl(function);
  }

  private void removeHandlerImpl(@NotNull Object key) {
    CefMessageRouterHandler cefHandler = myHandlerMap.remove(key);
    if (cefHandler != null) {
      myFunc.myRouter.removeHandler(cefHandler);
    }
  }

  public void clearHandlers() {
    List<Object> keys;
    // Collection.synchronizedMap object is the internal mutex for the collection.
    synchronized (myHandlerMap) {
      keys = new ArrayList<>(myHandlerMap.keySet());
    }
    keys.forEach(this::removeHandlerImpl);
  }

  @Override
  public void dispose() {
    myDisposeHelper.dispose(() -> {
      if (myFunc.myIsSlot) {
        JSQueryPool pool = myJBCefClient.getJSQueryPool();
        if (pool != null) {
          clearHandlers();
          pool.releaseUsedSlot(myFunc);
          return;
        }
      }
      myJBCefClient.getCefClient().removeMessageRouter(myFunc.myRouter);
      myFunc.myRouter.dispose();
      myHandlerMap.clear();
    });
  }

  @Override
  public boolean isDisposed() {
    return myDisposeHelper.isDisposed();
  }

  private void checkDisposed() {
    if (isDisposed()) throw new IllegalStateException("the JS query has been disposed");
  }

  /**
   * A handler that gets the frame of each call.
   */
  @FunctionalInterface
  public interface FrameHandler {
    /**
     * Handles a request that the frame policy of the query allows.
     * <p>
     * The browser and the frame are valid only during this call.
     * In the out-of-process mode of JCEF, the frame is a proxy that stops working when the call returns.
     * Read {@link CefFrame#isMain()} and {@link CefFrame#getURL()} here, and do not keep the frame.
     *
     * @param browser the browser of the query
     * @param frame the frame that called the query function
     * @param request the request of the JS call
     * @return the response, or {@code null} for an empty success response
     */
    @Nullable Response handle(@NotNull CefBrowser browser, @NotNull CefFrame frame, @NotNull String request);
  }

  /**
   * Selects the frames whose calls reach the handlers of a query.
   * <p>
   * Each policy accepts only the calls of the browser that {@link JBCefJSQuery#create(JBCefBrowserBase, FramePolicy)} gets.
   * A popup or a DevTools window is another browser of the client, so a policy refuses the calls of its own frames.
   * <p>
   * The registry key {@code ide.browser.jcef.jsQuery.anyFrame} turns off the checks of all the frame policies: the
   * policies of the queries and of {@link #guard} and {@link #allows}. The other checks of a page stay, for example the
   * checks of the requests of the clipboard bridge. In the split mode, set the key in the frontend and in the backend:
   * the frontend checks the browser, and the backend checks the frame.
   * <p>
   * A handler of a {@link CefMessageRouter} that does not use {@link JBCefJSQuery} can apply a policy with
   * {@link #guard(CefBrowser, CefMessageRouterHandler)} or {@link #allows(CefBrowser, CefBrowser, CefFrame)}.
   * <p>
   * A same-origin subframe can call the function of the main frame through {@code parent}, and CEF reports such a call
   * as a call of the main frame. In the same way, a same-origin popup can call the function of its opener through
   * {@code opener}, and CEF reports such a call as a call of the main frame of the opener, so a policy accepts it.
   * A policy separates the origins, not the frames or the windows of one origin.
   * A script that runs in the main frame passes every policy, so a page that shows untrusted HTML in its main frame
   * needs its own checks.
   */
  public static final class FramePolicy {
    private static final FramePolicy MAIN_FRAME = new FramePolicy(true, null);
    private static final FramePolicy ANY_FRAME = new FramePolicy(false, null);

    private final boolean myMainFrameOnly;
    private final @Nullable Set<String> myOrigins;

    private FramePolicy(boolean mainFrameOnly, @Nullable Set<String> origins) {
      myMainFrameOnly = mainFrameOnly;
      myOrigins = origins;
    }

    /**
     * Accepts the calls of the main frame of the browser.
     * <p>
     * The main frame can navigate to a page that the caller does not own, and that page can call the query too.
     * A link, a script of the page, a redirect or a cross-origin subframe after a user gesture can start such a navigation.
     * For a browser that can navigate or show content of other parties, use {@link #mainFrameOf(String...)}.
     */
    public static @NotNull FramePolicy mainFrame() {
      return MAIN_FRAME;
    }

    /**
     * Accepts the calls of the main frame of the browser when the main frame shows a page of one of the origins.
     * <p>
     * An origin is {@code scheme://host[:port]}, for example {@code https://example.com} or {@code http://localhost:63342}.
     * The default port of the scheme is the same as no port. All {@code file:} URLs have the origin {@code file://},
     * so {@code file://} also accepts the pages of {@link JBCefBrowserBase#loadHTML(String)}.
     * A URL without a host, such as {@code about:blank}, {@code about:srcdoc}, a {@code data:} or a {@code blob:} URL,
     * has no origin and matches no list, also when the document inherits the origin of its parent.
     * <p>
     * An origin covers every page of the origin. For example, {@code http://localhost:63342} is the whole built-in server,
     * project files included, and {@code file://} is every local file.
     * <p>
     * The policy converts an internationalized host name of an origin to its ASCII form, as Chromium shows it.
     * Write an IPv6 address in the short form that Chromium shows, for example {@code http://[::1]:8080}.
     * An origin in another form matches no URL, so the policy refuses its calls.
     * <p>
     * The check reads the URL that the frame shows when the browser process handles the call. A handler that can change
     * the IDE state or read data should also check a one-time token: put the token into the page and require it in the
     * request.
     *
     * @throws IllegalArgumentException if the list is empty, or if an item is not an origin
     */
    public static @NotNull FramePolicy mainFrameOf(@NonNls @NotNull String @NotNull ... origins) {
      if (origins.length == 0) throw new IllegalArgumentException("no origins");
      Set<String> set = new LinkedHashSet<>();
      for (String origin : origins) {
        String normalized = parseOrigin(origin, true);
        if (normalized == null) throw new IllegalArgumentException("not an origin: " + origin);
        set.add(normalized);
      }
      return new FramePolicy(true, Collections.unmodifiableSet(set));
    }

    /**
     * Accepts the calls of all the frames of the browser, cross-origin and sandboxed iframes included.
     * <p>
     * Use it only for a page that needs the calls of its subframes and that accepts the calls of every page they show.
     */
    public static @NotNull FramePolicy anyFrame() {
      return ANY_FRAME;
    }

    /**
     * Returns whether the policy accepts a call of the frame.
     * <p>
     * Call it in {@code CefMessageRouterHandler.onQuery}, because in the out-of-process mode the frame is valid only
     * during that call. The registry key {@code ide.browser.jcef.jsQuery.anyFrame} makes it accept every call.
     * The log shows the first refusals of the calls of each class that calls this method, for a few origins.
     *
     * @param ownBrowser the browser of the page that the handler serves
     * @param browser the browser that {@code onQuery} gets
     * @param frame the frame that {@code onQuery} gets
     */
    public boolean allows(@NotNull CefBrowser ownBrowser, @Nullable CefBrowser browser, @Nullable CefFrame frame) {
      // The caller is the handler, so the log names its plugin.
      Class<?> caller = CALLER_WALKER.getCallerClass();
      return allows(new OwnBrowser(ownBrowser), browser, frame, ourCallerLogs.get(caller));
    }

    /**
     * Returns a handler that passes to {@code handler} only the calls that the policy accepts.
     * <p>
     * The returned handler does not handle a call of another browser, so that the handler of that browser on the same
     * router can take it. It fails a refused call of its own browser with {@link #ERR_CODE_FRAME_REFUSED}, and the call
     * does not reach {@code handler}. Add the returned handler to the router, and remove that handler, not {@code handler}.
     * <p>
     * The returned handler holds {@code ownBrowser} weakly, so that it does not keep the browser. Pass
     * {@link JBCefBrowserBase#getCefBrowser()}, or use {@link #guard(JBCefBrowserBase, CefMessageRouterHandler)}: a
     * {@link CefBrowser} object that nobody else holds, for example one that a callback of CEF gets, stops matching
     * after a garbage collection, and then the handler does not handle any call.
     *
     * @param ownBrowser the browser of the page that the handler serves
     */
    public @NotNull CefMessageRouterHandler guard(@NotNull CefBrowser ownBrowser, @NotNull CefMessageRouterHandler handler) {
      OwnBrowser own = new OwnBrowser(ownBrowser);
      RefusalLog refusalLog = new RefusalLog("CefMessageRouter handler " + handler.getClass().getName(), handler.getClass());
      return new CefMessageRouterHandlerAdapter() {
        @Override
        public boolean onQuery(CefBrowser browser, CefFrame frame, long queryId, String request, boolean persistent, CefQueryCallback callback) {
          if (browser != null && !own.matches(browser) && !Registry.is(ANY_FRAME_REGISTRY_KEY, false)) return false;
          if (!allows(own, browser, frame, refusalLog)) {
            if (callback != null) callback.failure(ERR_CODE_FRAME_REFUSED, ERR_MSG_FRAME_REFUSED);
            return true;
          }
          return handler.onQuery(browser, frame, queryId, request, persistent, callback);
        }

        @Override
        public void onQueryCanceled(CefBrowser browser, CefFrame frame, long queryId) {
          handler.onQueryCanceled(browser, frame, queryId);
        }
      };
    }

    /**
     * Returns a handler that passes to {@code handler} only the calls that the policy accepts, for the browser of the
     * page that the handler serves. See {@link #guard(CefBrowser, CefMessageRouterHandler)}.
     */
    public @NotNull CefMessageRouterHandler guard(@NotNull JBCefBrowserBase ownBrowser, @NotNull CefMessageRouterHandler handler) {
      return guard(ownBrowser.getCefBrowser(), handler);
    }

    boolean allows(@NotNull OwnBrowser ownBrowser, @Nullable CefBrowser browser, @Nullable CefFrame frame, @NotNull RefusalLog refusalLog) {
      if (Registry.is(ANY_FRAME_REGISTRY_KEY, false)) return true;
      if (browser == null || frame == null) return false;

      // In the out-of-process mode, the frame is valid only during this call.
      boolean isMain = frame.isMain();
      String url = frame.getURL();
      String refusal = check(ownBrowser.matches(browser), isMain, url);
      if (refusal == null) return true;

      refusalLog.refused(this, refusal, originOf(url));
      return false;
    }

    /**
     * Returns the reason to refuse a call, or {@code null} to accept it.
     */
    @VisibleForTesting
    @Nullable String check(boolean ownBrowser, boolean mainFrame, @Nullable String url) {
      if (!ownBrowser) return "another browser of the client";
      if (myMainFrameOnly && !mainFrame) return "a subframe";
      if (myOrigins != null) {
        String origin = originOf(url);
        if (origin == null || !myOrigins.contains(origin)) return "a main frame of another origin";
      }
      return null;
    }

    /**
     * Returns {@code scheme://host[:port]} of the URL, or {@code null} for a URL without an origin.
     */
    @VisibleForTesting
    static @Nullable String originOf(@Nullable String url) {
      return url == null ? null : parseOrigin(url, false);
    }

    // With `strict`, the text must be an origin, with an optional "/" at the end.
    private static @Nullable String parseOrigin(@NotNull String url, boolean strict) {
      int colon = url.indexOf(':');
      if (colon <= 0) return null;
      String scheme = url.substring(0, colon).toLowerCase(Locale.ROOT);
      if (!isScheme(scheme)) return null;
      if (!url.startsWith("//", colon + 1)) {
        // An opaque URL: about:blank, data:, javascript:, blob:. A file URL can have one slash only.
        return scheme.equals("file") && !strict ? "file://" : null;
      }

      int start = colon + 3;
      int end = start;
      while (end < url.length() && "/?#\\".indexOf(url.charAt(end)) < 0) end++;
      if (strict && end < url.length() && !(end == url.length() - 1 && url.charAt(end) == '/')) return null;
      if (scheme.equals("file")) return "file://";

      String authority = url.substring(start, end);
      int at = authority.lastIndexOf('@');
      if (at >= 0) {
        if (strict) return null;
        authority = authority.substring(at + 1);
      }

      String host = authority;
      String port = null;
      if (authority.startsWith("[")) {
        int close = authority.indexOf(']');
        if (close < 0) return null;
        host = authority.substring(0, close + 1);
        String rest = authority.substring(close + 1);
        if (!rest.isEmpty()) {
          if (rest.charAt(0) != ':') return null;
          port = rest.substring(1);
        }
      }
      else {
        int portColon = authority.lastIndexOf(':');
        if (portColon >= 0) {
          host = authority.substring(0, portColon);
          port = authority.substring(portColon + 1);
        }
      }
      if (host.isEmpty()) return null;
      if (strict && !host.startsWith("[")) {
        try {
          host = IDN.toASCII(host, IDN.ALLOW_UNASSIGNED);
        }
        catch (IllegalArgumentException e) {
          return null;
        }
      }

      int portNumber = -1;
      if (port != null && !port.isEmpty()) {
        if (port.length() > 5 || !port.chars().allMatch(c -> c >= '0' && c <= '9')) return null;
        portNumber = Integer.parseInt(port);
        if (portNumber > 65535) return null;
      }
      if (portNumber == defaultPort(scheme)) portNumber = -1;
      return scheme + "://" + host.toLowerCase(Locale.ROOT) + (portNumber < 0 ? "" : ":" + portNumber);
    }

    private static boolean isScheme(@NotNull String scheme) {
      if (scheme.isEmpty() || scheme.charAt(0) < 'a' || scheme.charAt(0) > 'z') return false;
      return scheme.chars().allMatch(c -> (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '+' || c == '-' || c == '.');
    }

    private static int defaultPort(@NotNull String scheme) {
      return switch (scheme) {
        case "http", "ws" -> 80;
        case "https", "wss" -> 443;
        case "ftp" -> 21;
        default -> -1;
      };
    }

    @Override
    public String toString() {
      if (!myMainFrameOnly) return "FramePolicy.anyFrame()";
      if (myOrigins == null) return "FramePolicy.mainFrame()";
      return "FramePolicy.mainFrameOf(" + String.join(", ", myOrigins) + ")";
    }
  }

  private static final StackWalker CALLER_WALKER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

  // The log of the calls that the callers of FramePolicy.allows refuse, for each class of a caller.
  private static final ClassValue<RefusalLog> ourCallerLogs = new ClassValue<>() {
    @Override
    protected RefusalLog computeValue(@NotNull Class<?> type) {
      return new RefusalLog("CefMessageRouter handler " + type.getName(), type);
    }
  };

  /**
   * The log of the calls that one query, one guarded handler or one class of the callers of
   * {@link FramePolicy#allows(CefBrowser, CefBrowser, CefFrame)} refuses: the first refusal for each origin, for a few
   * origins. In the internal mode the first refusal of a frame with an origin is an error. A frame without one, such as
   * {@code about:blank} before the page loads, is a warning.
   */
  @VisibleForTesting
  static final class RefusalLog {
    @VisibleForTesting
    static final int MAX_ORIGINS = 16;

    private final @NotNull String myCaller;
    private final @Nullable Class<?> myHandlerClass;
    private final @NotNull Set<String> myOrigins = ConcurrentHashMap.newKeySet();
    private final @NotNull AtomicBoolean myErrorLogged = new AtomicBoolean();
    private final @NotNull BooleanSupplier myInternalMode;

    RefusalLog(@NotNull String caller, @Nullable Class<?> handlerClass) {
      this(caller, handlerClass, RefusalLog::isInternalMode);
    }

    @VisibleForTesting
    RefusalLog(@NotNull String caller, @Nullable Class<?> handlerClass, @NotNull BooleanSupplier internalMode) {
      myCaller = caller;
      myHandlerClass = handlerClass;
      myInternalMode = internalMode;
    }

    void refused(@NotNull FramePolicy policy, @NotNull String refusal, @Nullable String origin) {
      String loggedOrigin = origin == null ? "null" : origin;
      if (DEBUG_JS) CefLog.Debug("onQuery: %s refused %s (origin %s)", myCaller, refusal, loggedOrigin);
      if (myOrigins.size() >= MAX_ORIGINS || !myOrigins.add(loggedOrigin)) return;

      String message = myCaller + " of plugin " + pluginIdOf(myHandlerClass) + " refused a call of " + refusal +
                       " (origin " + loggedOrigin + ", " + policy + "). A query created with another FramePolicy can accept it. " +
                       "The log shows the first refusal for each origin, for " + MAX_ORIGINS + " origins.";
      if (origin != null && myInternalMode.getAsBoolean() && myErrorLogged.compareAndSet(false, true)) {
        LOG.error(message);
      }
      else {
        LOG.warn(message);
      }
    }

    private static @NotNull String pluginIdOf(@Nullable Class<?> handlerClass) {
      PluginDescriptor plugin = handlerClass == null ? null : PluginManager.getPluginByClass(handlerClass);
      return plugin == null ? "unknown" : plugin.getPluginId().getIdString();
    }

    private static boolean isInternalMode() {
      Application application = ApplicationManager.getApplication();
      return application != null && application.isInternal() && !application.isUnitTestMode();
    }
  }

  /**
   * The browser of a query. A weak reference keeps the query from holding the browser.
   */
  @VisibleForTesting
  static final class OwnBrowser {
    private final @NotNull WeakReference<CefBrowser> myBrowser;
    private volatile int myIdentifier = -1;

    OwnBrowser(@NotNull CefBrowser browser) {
      myBrowser = new WeakReference<>(browser);
    }

    // The out-of-process mode can pass another object of the same browser, so the identifier decides too.
    boolean matches(@NotNull CefBrowser browser) {
      CefBrowser own = myBrowser.get();
      // A browser that is gone matches nothing, also by its identifier.
      if (own == null) return false;
      if (own == browser) return true;
      int identifier = myIdentifier;
      if (identifier < 0) {
        identifier = own.getIdentifier();
        if (identifier >= 0) myIdentifier = identifier;
      }
      return identifier >= 0 && browser.getIdentifier() == identifier;
    }
  }

  /**
   * A JS handler response to a query.
   */
  public static class Response {
    public static final int ERR_CODE_SUCCESS = 0;

    private final @Nullable String myResponse;
    private final int myErrCode;
    private final @Nullable String myErrMsg;


    public Response(@Nullable String response) {
      this(response, ERR_CODE_SUCCESS, null);
    }

    public Response(@Nullable String response, int errCode, @Nullable String errMsg) {
      myResponse = response;
      myErrCode = errCode;
      myErrMsg = errMsg;
    }

    public @Nullable String response() {
      return myResponse;
    }

    public int errCode() {
      return myErrCode;
    }

    public @Nullable String errMsg() {
      return myErrMsg;
    }

    public boolean isSuccess() {
      return myErrCode == ERR_CODE_SUCCESS;
    }

    public boolean hasResponse() {
      return myResponse != null;
    }
  }

  static class JSQueryFunc {
    final @NotNull CefMessageRouter myRouter;
    final @NotNull String myFuncName;
    final boolean myIsSlot;

    JSQueryFunc(@NotNull JBCefClient client) {
      this(client, client.nextJSQueryIndex(), false);
    }

    JSQueryFunc(@NotNull JBCefClient client, int index, boolean isSlot) {
      String postfix = client.hashCode() + "_" + (isSlot ? "slot_" : "") + index;
      myIsSlot = isSlot;
      myFuncName = "cefQuery_" + postfix;
      CefMessageRouter.CefMessageRouterConfig config = new CefMessageRouter.CefMessageRouterConfig();
      config.jsQueryFunction = myFuncName;
      config.jsCancelFunction = "cefQuery_cancel_" + postfix;
      myRouter = JBCefApp.getInstance().createMessageRouter(config);
      client.getCefClient().addMessageRouter(myRouter);
    }
  }
}
