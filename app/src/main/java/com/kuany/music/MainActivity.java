package com.kuany.music;

import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebChromeClient;

import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "KuanyMusic";
    private static final String BASE_URL = "https://music.kuany.cloud";

    private WebView webView;
    private MediaController controller;
    private boolean controllerReady = false;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable progressRunnable = this::syncProgress;

    private final List<String> songUrls   = new ArrayList<>();
    private final List<String> songTitles  = new ArrayList<>();
    private final List<String> songAlbums  = new ArrayList<>();
    private final List<String> songCovers  = new ArrayList<>();
    private int pendingPlayIdx = -1;
    private boolean progressSyncActive = false;

    // ── JS Injection ─────────────────────────────────────────────
    // Replaces playSong/togglePlay/playNext/playPrev/setVolume
    // so they call androidBridge. Also syncs progress and display.
    private static final String INJECT = ""
        + "(function(){"
        + "  var _songsDataReady=false;"
        + "  function log(m){ console.log('[KUANY] '+m); }"
        // ── Replace playSong ──
        + "  window.playSong=function(idx){"
        + "    log('playSong('+idx+')');"
        + "    if(typeof currentIdx!=='undefined') currentIdx=idx;"
        + "    if(typeof songsData!=='undefined' && songsData[idx]){"
        + "      if(typeof updatePlayerDisplay==='function') updatePlayerDisplay(songsData[idx]);"
        + "    }"
        + "    if(typeof playerBar!=='undefined' && playerBar.classList) playerBar.classList.add('active');"
        + "    if(typeof $$==='function'){"
        + "      $$('.song-row').forEach(function(r,i){"
        + "        if(r.classList) r.classList.toggle('playing',i===idx);"
        + "      });"
        + "    }"
        + "    if(window.androidBridge) window.androidBridge.playSong(idx);"
        + "  };"
        // ── Replace togglePlay ──
        + "  window.togglePlay=function(){"
        + "    log('togglePlay');"
        + "    if(window.androidBridge) window.androidBridge.togglePlay();"
        + "  };"
        // ── Replace playNext ──
        + "  window.playNext=function(){"
        + "    log('playNext');"
        + "    if(window.androidBridge) window.androidBridge.playNext();"
        + "  };"
        // ── Replace playPrev ──
        + "  window.playPrev=function(){"
        + "    log('playPrev');"
        + "    if(window.androidBridge) window.androidBridge.playPrev();"
        + "  };"
        // ── Replace setVolume ──
        + "  window.setVolume=function(v){"
        + "    log('setVolume('+v+')');"
        + "    if(window.androidBridge) window.androidBridge.setVolume(v/100);"
        + "    if(typeof audioPlayer!=='undefined'&&audioPlayer.volume!==undefined) audioPlayer.volume=v/100;"
        + "  };"
        // ── Native callbacks ──
        + "  window.__onNativePlay=function(idx){"
        + "    log('__onNativePlay('+idx+')');"
        + "    if(typeof currentIdx!=='undefined') currentIdx=idx;"
        + "    if(typeof songsData!=='undefined' && songsData[idx]){"
        + "      if(typeof updatePlayerDisplay==='function') updatePlayerDisplay(songsData[idx]);"
        + "    }"
        + "    if(typeof isPlaying!=='undefined') isPlaying=true;"
        + "    if(typeof syncPlayBtn==='function') syncPlayBtn();"
        + "    if(typeof updateProgress==='function') updateProgress();"
        + "  };"
        + "  window.__onNativePause=function(){"
        + "    log('__onNativePause');"
        + "    if(typeof isPlaying!=='undefined') isPlaying=false;"
        + "    if(typeof syncPlayBtn==='function') syncPlayBtn();"
        + "  };"
        + "  window.__onNativeEnded=function(){"
        + "    log('__onNativeEnded');"
        + "    if(typeof playNext==='function') window.playNext();"
        + "  };"
        // ── Progress: hook audioPlayer.currentTime/duration ──
        + "  window.__nativePos=0; window.__nativeDur=0;"
        + "  if(typeof audioPlayer!=='undefined'){"
        + "    try{"
        + "      Object.defineProperty(audioPlayer,'currentTime',{"
        + "        get:function(){return window.__nativePos;},"
        + "        set:function(v){"
        + "          if(window.androidBridge) window.androidBridge.seekTo(Math.floor(v));"
        + "          window.__nativePos=v; return v;"
        + "        }"
        + "      });"
        + "      Object.defineProperty(audioPlayer,'duration',{"
        + "        get:function(){return window.__nativeDur||0;}"
        + "      });"
        + "      log('hooked audioPlayer.currentTime/duration');"
        + "    }catch(e){log('hook error:'+e);}"
        + "  }"
        // ── Progress bar click: hook to seek ──
        + "  function hookProgressClick(){"
        + "    var pf=document.getElementById('progress-fill');"
        + "    if(!pf) return;"
        + "    pf.style.pointerEvents='auto';"
        + "    pf.addEventListener('click',function(e){"
        + "      var rect=pf.getBoundingClientRect();"
        + "      var pct=(e.clientX-rect.left)/rect.width;"
        + "      var dur=window.__nativeDur||0;"
        + "      if(dur>0 && window.androidBridge) window.androidBridge.seekTo(Math.floor(pct*dur));"
        + "    });"
        + "    log('progress bar hooked');"
        + "  }"
        + "  setTimeout(hookProgressClick,2000);"
        // ── Poll songsData and auto-register ──
        + "  var _registered=false;"
        + "  function _register(){"
        + "    if(_registered) return;"
        + "    if(typeof songsData!=='undefined'&&songsData.length){"
        + "      _registered=true;"
        + "      var u=[],t=[],a=[],c=[];"
        + "      songsData.forEach(function(s){"
        + "        u.push(s.audioFile||'');"
        + "        t.push(s.title||'');"
        + "        a.push(s.album||'');"
        + "        c.push(s.coverFile||'');"
        + "      });"
        + "      if(window.androidBridge){"
        + "        window.androidBridge.setSongList("
        + "          JSON.stringify(u),JSON.stringify(t),"
        + "          JSON.stringify(a),JSON.stringify(c));"
        + "        log('registered '+u.length+' songs');"
        + "      }"
        + "    }"
        + "  }"
        + "  var _poll=0;"
        + "  var _tid=setInterval(function(){_register();_poll++;if(_poll>50)clearInterval(_tid);},200);"
        // also hook fetch for config.json
        + "  var _origFetch=window.fetch.bind(window);"
        + "  window.fetch=function(url,opts){"
        + "    return _origFetch(url,opts).then(function(r){"
        + "      if(typeof url==='string'&&url.indexOf('config.json')>=0){"
        + "        r.clone().json().then(function(cfg){"
        + "          if(cfg.songs) songsData=cfg.songs;"
        + "          setTimeout(_register,100);"
        + "        }).catch(function(){});"
        + "      }"
        + "      return r;"
        + "    });"
        + "  };"
        + "  log('inject done');"
        + "})();";

    @Override
    @SuppressLint("SetJavaScriptEnabled")
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initWebView();
        connectService();
    }

    private void initWebView() {
        webView = findViewById(R.id.webview);
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setDatabaseEnabled(true);
        ws.setCacheMode(WebSettings.LOAD_DEFAULT);
        ws.setUseWideViewPort(true);
        ws.setLoadWithOverviewMode(true);
        ws.setBuiltInZoomControls(false);
        ws.setDisplayZoomControls(false);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setAllowFileAccess(true);

        webView.addJavascriptInterface(new Bridge(), "androidBridge");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                Log.d(TAG, "onPageFinished: " + url);
                view.evaluateJavascript(INJECT, null);
            }
        });

        webView.setWebChromeClient(new WebChromeClient());
        webView.loadUrl(BASE_URL + "/");
    }

    private void connectService() {
        SessionToken token = new SessionToken(this,
                new ComponentName(this, PlayerService.class));
        ListenableFuture<MediaController> future =
                new MediaController.Builder(this, token).buildAsync();
        future.addListener(() -> {
            try {
                controller = future.get();
                controllerReady = true;
                Log.d(TAG, "MediaController connected");
                runOnUiThread(() -> {
                    setupControllerListener();
                    startProgressSync();
                });
                if (pendingPlayIdx >= 0) {
                    int idx = pendingPlayIdx;
                    pendingPlayIdx = -1;
                    handler.post(() -> playSongNative(idx));
                }
            } catch (Exception e) {
                Log.e(TAG, "connect failed", e);
            }
        }, MoreExecutors.directExecutor());
    }

    private void setupControllerListener() {
        if (controller == null) return;
        controller.addListener(new Player.Listener() {
            @Override
            public void onIsPlayingChanged(boolean playing) {
                Log.d(TAG, "onIsPlayingChanged: " + playing);
                if (playing) {
                    int idx = getCurrentIdx();
                    evaluate("if(window.__onNativePlay) window.__onNativePlay(" + idx + ")");
                } else {
                    evaluate("if(window.__onNativePause) window.__onNativePause()");
                }
            }
            @Override
            public void onMediaItemTransition(MediaItem item, int reason) {
                if (item != null && item.mediaId != null) {
                    try {
                        int idx = Integer.parseInt(item.mediaId);
                        evaluate("if(window.__onNativePlay) window.__onNativePlay(" + idx + ")");
                    } catch (NumberFormatException ignored) {}
                }
            }
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_ENDED) {
                    evaluate("if(window.__onNativeEnded) window.__onNativeEnded()");
                }
            }
        });
    }

    // ── Progress sync: native → web every 1s ──
    private void startProgressSync() {
        progressSyncActive = true;
        handler.post(progressRunnable);
    }

    private void syncProgress() {
        if (!progressSyncActive || controller == null) return;
        try {
            long pos = controller.getCurrentPosition();
            long dur = controller.getDuration();
            if (dur > 0) {
                evaluate("if(typeof window!=='undefined'){"
                        + "window.__nativePos=" + (pos / 1000.0) + ";"
                        + "window.__nativeDur=" + (dur / 1000.0) + ";"
                        + "if(typeof updateProgress==='function') updateProgress();"
                        + "}");
            }
        } catch (Exception ignored) {}
        handler.postDelayed(progressRunnable, 1000);
    }

    private void stopProgressSync() {
        progressSyncActive = false;
        handler.removeCallbacks(progressRunnable);
    }

    private int getCurrentIdx() {
        if (controller == null) return -1;
        MediaItem item = controller.getCurrentMediaItem();
        if (item != null && item.mediaId != null) {
            try { return Integer.parseInt(item.mediaId); }
            catch (NumberFormatException ignored) {}
        }
        return -1;
    }

    private void evaluate(String code) {
        if (webView != null) {
            webView.evaluateJavascript(code, null);
        }
    }

    // ── Bridge ──
    class Bridge {

        @JavascriptInterface
        public void setSongList(String urlsJson, String titlesJson,
                               String albumsJson, String coversJson) {
            Log.d(TAG, "setSongList: " + urlsJson);
            try {
                List<String> u = parseJsonArray(urlsJson);
                List<String> t = parseJsonArray(titlesJson);
                List<String> a = parseJsonArray(albumsJson);
                List<String> c = parseJsonArray(coversJson);

                synchronized (songUrls) {
                    songUrls.clear();  songTitles.clear();
                    songAlbums.clear(); songCovers.clear();
                    songUrls.addAll(u); songTitles.addAll(t);
                    songAlbums.addAll(a); songCovers.addAll(c);
                }

                Log.d(TAG, "parsed " + songUrls.size() + " songs");

                post(() -> {
                    if (controller == null) return;
                    List<MediaItem> items = new ArrayList<>();
                    for (int i = 0; i < songUrls.size(); i++) {
                        items.add(buildItem(i));
                    }
                    controller.setMediaItems(items, 0, 0);
                    Log.d(TAG, "setMediaItems done, count=" + items.size());
                });
            } catch (Exception e) {
                Log.e(TAG, "setSongList error", e);
            }
        }

        @JavascriptInterface
        public void playSong(int idx) {
            Log.d(TAG, "playSong(" + idx + "), ready=" + controllerReady);
            if (!controllerReady) {
                pendingPlayIdx = idx;
                return;
            }
            post(() -> playSongNative(idx));
        }

        @JavascriptInterface
        public void togglePlay() {
            if (!controllerReady) return;
            post(() -> {
                if (controller.isPlaying()) controller.pause();
                else {
                    if (controller.getMediaItemCount() == 0 && !songUrls.isEmpty()) {
                        playSongNative(0);
                    } else {
                        controller.play();
                    }
                }
            });
        }

        @JavascriptInterface
        public void playNext() {
            if (!controllerReady) return;
            post(() -> {
                if (controller.getMediaItemCount() > 1) controller.seekToNextMediaItem();
            });
        }

        @JavascriptInterface
        public void playPrev() {
            if (!controllerReady) return;
            post(() -> {
                if (controller.getCurrentMediaItemIndex() > 0) controller.seekToPreviousMediaItem();
                else controller.seekTo(0);
            });
        }

        @JavascriptInterface
        public void setVolume(double v) {
            if (!controllerReady) return;
            post(() -> controller.setVolume((float) v));
        }

        @JavascriptInterface
        public void seekTo(int seconds) {
            if (!controllerReady) return;
            post(() -> {
                if (controller != null) {
                    controller.seekTo(seconds * 1000L);
                    Log.d(TAG, "seekTo " + seconds + "s");
                }
            });
        }
    }

    private void playSongNative(int idx) {
        if (controller == null) return;
        int count = controller.getMediaItemCount();
        Log.d(TAG, "playSongNative: count=" + count + " idx=" + idx);
        if (count > 0 && idx >= 0 && idx < count) {
            controller.seekTo(idx, 0);
            controller.play();
        } else if (!songUrls.isEmpty() && idx < songUrls.size()) {
            MediaItem item = buildItem(idx);
            controller.setMediaItem(item);
            controller.prepare();
            controller.play();
        }
    }

    private MediaItem buildItem(int idx) {
        String url   = idx < songUrls.size()   ? songUrls.get(idx)   : "";
        String title = idx < songTitles.size() ? songTitles.get(idx) : "";
        String album = idx < songAlbums.size() ? songAlbums.get(idx) : "";
        String cover = idx < songCovers.size() ? songCovers.get(idx) : "";
        return new MediaItem.Builder()
                .setMediaId(String.valueOf(idx))
                .setUri(Uri.parse(resolveUrl(url)))
                .setMediaMetadata(new MediaMetadata.Builder()
                        .setTitle(title)
                        .setArtist("油叔")
                        .setAlbumTitle(album)
                        .setArtworkUri(cover.isEmpty() ? null : Uri.parse(resolveUrl(cover)))
                        .build())
                .build();
    }

    private String resolveUrl(String path) {
        if (path == null || path.isEmpty()) return "";
        if (path.startsWith("http")) return path;
        return BASE_URL + "/" + path;
    }

    private List<String> parseJsonArray(String json) {
        List<String> list = new ArrayList<>();
        if (json == null || json.isEmpty() || json.equals("[]")) return list;
        String s = json.trim();
        if (s.startsWith("[")) s = s.substring(1);
        if (s.endsWith("]")) s = s.substring(0, s.length() - 1);
        if (s.isEmpty()) return list;
        boolean inStr = false;
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' && (i == 0 || s.charAt(i - 1) != '\\')) {
                inStr = !inStr;
            } else if (c == ',' && !inStr) {
                list.add(cur.toString().trim());
                cur = new StringBuilder();
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) list.add(cur.toString().trim());
        return list;
    }

    private void post(Runnable r) {
        handler.post(r);
    }

    @Override
    protected void onDestroy() {
        stopProgressSync();
        if (controller != null) controller.release();
        if (webView != null) webView.destroy();
        super.onDestroy();
    }
}