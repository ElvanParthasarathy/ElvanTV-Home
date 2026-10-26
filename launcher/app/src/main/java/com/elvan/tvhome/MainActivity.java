package com.elvan.tvhome;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ChangedPackages;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.SoundEffectConstants;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.animation.AnimationUtils;
import android.view.animation.LayoutAnimationController;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextClock;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.FloatPropertyCompat;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * tvOS style home: black, a pinned dock row, an app grid, and one glass highlight that springs between tiles.
 */
public class MainActivity extends Activity implements AppAdapter.TileListener {

    /** Sent by QuickSettingsActivity: open Launcher settings from here so Home rebuilds when they close. */
    /** The system input switcher, forwarded here by InputsRequestActivity while handle_view_inputs is on. */
    static final String ACTION_VIEW_INPUTS = "com.android.tv.action.VIEW_INPUTS";
    /** A remapped button asked for the Settings panel (RemapService). */
    static final String ACTION_OPEN_SETTINGS = "com.elvan.tvhome.OPEN_SETTINGS";
    /** Tiles per row and dock slots (res/values/config.xml, columns). Set in onCreate. */
    static int COLUMNS = 4;
    private boolean rtlAppGrid;
    private static final long MINUTE_MS = 60_000L;
    private static final long STATS_MS = 10_000L;
    /**
     * 2025-01-01. TVs without a clock battery boot at the epoch (08:00 in China) and keep it until network
     * time arrives; the clock, date and greeting stay hidden before then.
     */
    private static final long TIME_VALID_AFTER_MS = 1_735_689_600_000L;
    private String liveTvPkg;
    /** Launcher settings' change count this Home was built with (see onResume). */
    private long settingsRevision;
    private long lastHomePressTime = 0;
    /** Idle time on Home before ClockActivity; 0 is off (config.xml, home_idle_clock_minutes). */
    private long idleClockMs;
    private final Runnable showIdleClock = () -> startActivity(new Intent(this, ClockActivity.class));
    private String liveTvAction;
    private String liveTvInputId;
    /** The app library is on (config.xml, app_library): only then can the tile menu take an app off Home. */
    private boolean appLibraryEnabled;
    /** The same opt-in menu is available from either hold-OK or the remote's Menu key. */
    private boolean tileMenuEnabled;
    private String inputAction;
    private String inputUriScheme;
    private String sourcePanelService;
    private String sourcePanelAction;
    private String sourcePanelExtra;
    private boolean coocaaSources;

    private AppAdapter adapter;
    private RecyclerView grid;
    private FrameLayout root;
    /** The focused tile card the halo is locked to; the halo is re-centred on it before every frame. */
    private View glowAnchor;
    private final Rect glowRect = new Rect();
    private WallpaperPlayer wallpaperPlayer;
    private GlassView glass;
    private View ambient;
    private TextView heroName;
    /** The top shelf: heroName, plus the cinematic shelf's "Last opened" line and dock position dots. */
    private View hero;
    private TextView heroMeta;
    private LinearLayout heroDots;
    /** Cinematic top shelf (config.xml, cinematic_home): the focused dock app's artwork behind a larger shelf. */
    private boolean cinematic;
    /** config.xml ambient_glow, overridable by a theme. Off: the glow view is gone and no fade or colour animator
     *  is started for it, so a focus move costs nothing extra on a launcher that runs all day. */
    private boolean ambientGlow;
    /** config.xml app_glow: the highlight takes the focused app's colour on every tile. */
    private boolean appGlow;
    /** config.xml glow_style: the shape setAmbient paints the glow mask in. */
    private String glowStyle;
    /** config.xml cinematic_white_focus: the cinematic shelf keeps the white highlight. */
    private boolean cinematicWhiteFocus;
    private ImageView heroArt;
    /** config.xml cinematic_dots: false leaves the dock position dots out of the cinematic shelf. */
    private boolean heroDotsShown = true;
    /** The artwork's opacity behind the cinematic shelf (config.xml cinematic_art); 0 leaves it out. */
    private float heroArtAlpha = 0.5f;
    /** Softened artwork per app (HeroArt, about 5 KB each), made the first time its dock tile is focused. */
    private final Map<String, Bitmap> heroArtCache = new HashMap<>();
    /** The app whose artwork heroArt shows, so focusing it again only fades it back in. */
    private String heroArtKey;
    /** The focus glow's colour while the cinematic shelf tints it, 0 for plain white glass. */
    private int glassAccent;
    private int ambientColor = -1;
    private ValueAnimator ambientAnimator;
    private boolean heroShown;
    private boolean animations;
    private boolean advancedEffects;
    private TextView greeting;
    private View clockBlock;
    private View btnInputs;
    /** config show_inputs_pill: false keeps the Inputs pill hidden even when the TV has inputs. */
    private boolean showInputsPill = true;
    private TextView btnWifi;
    /** The network pill's icon as last set, so a refresh that changes nothing does not lay the top bar out again. */
    private int wifiIcon;
    /** The pills have been filled in once; later resumes refresh them after the first frame. */
    private boolean pillsShown;
    private TextView shamsiDate;
    private TextView stats;
    private List<RaceWeekend.Race> raceCalendar;
    private View racePanel;
    private TextView raceKicker, raceHeadline, raceSessions;
    /** config.xml show_stats, overridable by a theme. Off: the line is gone and nothing is read. */
    private boolean showStats;
    private boolean shamsi;
    /** greeting_name: said after the greeting, which is shown in capitals. */
    private String greetingName = "";
    /** Optional time-of-day backdrop (config.xml, sky_intensity); 0 keeps it hidden. */
    private SkyView sky;
    private String vpnApp;
    private SpringAnimation springX, springY;
    private float glassTargetX, glassTargetY;
    // How far the grid has scrolled the glass. springY works without it, in grid content space, so a
    // scroll moves the glass in lockstep with its tile instead of becoming a moving spring target.
    private float glassScrollShift;
    private int glassCardW;   // width of the tile the glass was last fitted to
    private boolean glassShown;
    private final Rect tileRect = new Rect();
    private final Rect revealRect = new Rect();
    // Static so they survive the activity being destroyed while another app is open (low-memory TVs,
    // or Developer options → "Don't keep activities"); they only reset when the process dies.
    private static int lastFocused = 0;
    /** App of the last focused tile, so focus follows it when the app list changes. */
    private static String lastFocusedKey;
    private float density;
    /** Module cards (res/values/config.xml, module_cards): discovered on resume, shown only after the owner's Show. */
    private boolean moduleCards;
    /** config settings_key_keycodes / settings_key_panel, so the Settings button works on Home without HomeKeyService. */
    private int[] settingsKeycodes = new int[0];
    private boolean settingsKeyPanel;
    /** Calmer motion for weak GPUs (config.xml, reduced_motion): stiff spring, short fades, no replay. */
    private boolean reducedMotion;
    /** config.xml dock_row: the first row is a separate dock (band, parked low, top shelf) rather than plain grid. */
    private boolean dockRow;
    /** The highlight springs past its target (config.xml, focus_overshoot); false goes straight there. */
    private boolean focusOvershoot;
    /** Festive skin from the theme (see Festival); applied while it is active today. */
    private Festival festival = Festival.NONE;
    private boolean festive;
    private DiyaDrawable diya;
    private BellDrawable bell;
    private MarigoldDrawable marigold;
    private int greetingColor;
    /** The day the petal shower last played, so it greets the first Home of each day only. */
    private static LocalDate lastPetalShower;
    /** boot_to_live_tv: open the Live TV app on the first Home of each boot. */
    private boolean bootToLiveTv;
    /** hidden_apps as the adapter sees them (config.xml or a theme's config.json): a hidden app gets no card either. */
    private Set<String> hiddenApps = Collections.emptySet();
    private List<Cards.Card> cards = Collections.emptyList();
    /** The grid has been laid out and focused since the last resume; the card prompt waits for this. */
    private boolean tilesReady;
    private boolean settingsRecreationPending;
    private Dialog cardPrompt;
    /** Set when a decision could not be saved: no more prompts until the next resume, instead of asking in a loop. */
    private boolean promptsPaused;
    /** Between onStop and onStart: Home was fully covered by an app, not just by the translucent quick panel. */
    private boolean stopped;
    /** Between onStart and onStop: Home is (at least partly) on screen. */
    private boolean started;
    /** Rebuild the tiles on the way back to Home only when apps changed meanwhile (TileRefresh). */
    private final TileRefresh tileRefresh = new TileRefresh();
    /** PackageManager's change counter at the last rebuild, to catch a package broadcast that never arrived. */
    private int packageSequence;
    /** The Inputs or Module cards dropdown while one is open, so onPause can close it. */
    private PopupWindow openPopup;
    /** Reorder mode is on (top-bar button). */
    private boolean reordering;
    /** Key of the tile being carried in reorder mode, or null. */
    private String movingKey;
    /** Tile order when reorder mode started, to save only when something moved. */
    private List<String> orderAtStart;
    /**
     * fill_dock false: tiles in the dock when reorder mode started, plus those carried or swapped into it since
     * (#499). A grid app that only slid into a free slot, which reorder mode does not show, is not one of them.
     */
    private Set<String> dockInSession;
    /** fill_dock false: how many dock slots held an app when reorder mode started. */
    private int dockedAtStart;
    private View btnReorder;
    private TextView sectionTitle;
    private TextView btnDoneReorder;
    /** dock_apps as loaded (config.xml or a theme): a saved Order only applies while these are unchanged. */
    private List<String> dockApps;
    /** fill_dock false: the dock can have empty slots, which reorder mode leaves out and restores afterwards. */
    private boolean fillDockLeavesSlots;
    /** Edit-dock mode (Settings menu): a reorder session that swaps one chosen app into one dock place. */
    private boolean dockEditing;
    /** Dock place being changed in edit-dock mode, or -1 while the place is still being chosen. */
    private int dockEditSlot = -1;
    /** Tile menu's "Add to dock": the app waiting for a dock place in edit-dock mode, or null. */
    private String dockEditAppKey;
    private AppLibraryDialog appLibrary;
    private boolean appLibrarySelection;
    private boolean libraryFocusPending;
    private RecyclerView.OnScrollListener pendingFocusScroll;

    private final Handler handler = new Handler(Looper.getMainLooper());
    /** Refreshes the network pill when the connection changes: at boot Wi-Fi often connects after Home is shown. */
    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }

        @Override
        public void onLost(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }
    };
    /** Shows the Inputs pill once the TV registers an input: at boot HDMI inputs can appear after Home is shown. */
    private final TvInputManager.TvInputCallback inputCallback = new TvInputManager.TvInputCallback() {
        @Override
        public void onInputAdded(String inputId) {
            updateInputsButton();
        }

        @Override
        public void onInputRemoved(String inputId) {
            updateInputsButton();
        }
    };
    /** Listens for any VPN coming up or going down, even one this app's traffic is excluded from. */
    private final ConnectivityManager.NetworkCallback vpnCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }

        @Override
        public void onLost(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }
    };
    private final IntentFilter packageFilter = new IntentFilter();
    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Updates send REMOVED followed by ADDED; keep the tile until the new APK is ready.
            if (Intent.ACTION_PACKAGE_REMOVED.equals(intent.getAction())
                    && intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return;
            // In the background this only marks the tiles stale; onResume rebuilds them once.
            if (!tileRefresh.onPackagesChanged()) return;
            reloadApps(false);
            if (appLibrary != null) appLibrary.refresh();
        }
    };
    // Right after boot the clock is often wrong until network time arrives; don't wait a minute to catch up.
    private final IntentFilter timeFilter = new IntentFilter(Intent.ACTION_TIME_CHANGED);
    private final BroadcastReceiver timeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateClockVisibility();
        }
    };
    private final Runnable minuteTick = new Runnable() {
        @Override
        public void run() {
            updateGreeting();
            updateRace();
            // The pill follows the network callbacks and each return to Home; only the diya's flame follows the
            // signal level, so only it needs this minute's binder calls (about 7 ms on the UI thread).
            if (festive) updateWifiLabel();
            sky.update();
            wallpaperPlayer.update();
            handler.postDelayed(this, MINUTE_MS);
        }
    };

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.wrap(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        density = getResources().getDisplayMetrics().density;
        // config.xml, overlaid by config.json from a pushed theme (see Config and tools/theme.sh).
        Config cfg = Config.load(this);
        appLibrarySelection = cfg.appLibrarySelection;
        // restore_key_services: after a force stop, put back the remote's accessibility helpers that Android took
        // off the list (needs WRITE_SECURE_SETTINGS, granted by build-and-install with RESTORE_KEY_SERVICES=1).
        if (cfg.restoreKeyServices) ServiceRepair.restore(this);
        settingsRevision = settingsRevision();
        applyViewInputsHandler(cfg.handleViewInputs);
        settingsKeycodes = cfg.settingsKeycodes;
        settingsKeyPanel = cfg.settingsKeyPanel;
        COLUMNS = cfg.columns;
        animations = cfg.animations;
        advancedEffects = cfg.animations && cfg.advancedEffects;
        reducedMotion = cfg.reducedMotion;
        dockRow = cfg.dockRow;
        focusOvershoot = cfg.focusOvershoot;
        festival = cfg.festival;
        festive = festival.active(LocalDate.now());
        liveTvPkg = cfg.liveTvPackage;
        idleClockMs = cfg.homeIdleClockMinutes * MINUTE_MS;
        bootToLiveTv = cfg.bootToLiveTv;
        liveTvAction = cfg.liveTvAction;
        liveTvInputId = cfg.liveTvInputId;
        appLibraryEnabled = cfg.appLibrary;
        tileMenuEnabled = cfg.tileMenu;
        inputAction = cfg.inputAction;
        inputUriScheme = cfg.inputUriScheme;
        sourcePanelService = cfg.sourcePanelService;
        sourcePanelAction = cfg.sourcePanelAction;
        sourcePanelExtra = cfg.sourcePanelExtra;
        coocaaSources = cfg.coocaaSources;

        Motion.init(this);
        audioOutput = new AudioOutput(this, this::onAudioChanged);
        audioOutput.open();
        root = findViewById(R.id.root);
        // The top bar comes first in the layout, so the window would give it the first focus and the app row
        // would take it a moment later: a pill lit up for a frame or two on every start. Closed until a tile (or
        // the library) has the focus; see openTopBar.
        ((ViewGroup) findViewById(R.id.topButtons)).setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        root.getViewTreeObserver().addOnPreDrawListener(() -> {
            syncHalo();
            return true;
        });
        root.setBackgroundColor(cfg.backgroundColor);
        // root fills the window with an opaque colour (Config keeps background_color opaque), so the window's own
        // black under it is a second full-screen fill on every frame for nothing. The starting window still uses
        // the theme's background, so launch looks the same.
        getWindow().setBackgroundDrawable(null);
        wallpaperPlayer = new WallpaperPlayer(this, root, cfg);
        sky = findViewById(R.id.sky);
        sky.setIntensity(cfg.skyIntensity);
        glass = findViewById(R.id.glass);
        glass.setVisibility(View.GONE);
        if (festive) glass.setAccent(festival.accentColor());
        ambient = findViewById(R.id.ambient);
        // The glow is one bitmap, so its alpha can be applied while drawing it. Otherwise every frame of
        // a fade renders it into a full-screen offscreen buffer first.
        ambient.forceHasOverlappingRendering(false);
        ambientGlow = cfg.ambientGlow;
        appGlow = cfg.appGlow;
        if (!ambientGlow) ambient.setVisibility(View.GONE);
        heroName = findViewById(R.id.heroName);
        hero = findViewById(R.id.hero);
        // The hero is now a group (name, and the cinematic lines): without this every fade of the default
        // shelf would render it through an offscreen buffer first, as the glow above notes.
        hero.forceHasOverlappingRendering(false);
        heroMeta = findViewById(R.id.heroMeta);
        heroDots = findViewById(R.id.heroDots);
        heroArt = findViewById(R.id.heroArt);
        cinematic = cfg.cinematicHome;
        glowStyle = cfg.glowStyle;
        heroDotsShown = cfg.cinematicDots;
        cinematicWhiteFocus = cfg.cinematicWhiteFocus;
        heroArtAlpha = cfg.cinematicArt / 100f;
        if (cinematic) setUpCinematicShelf(cfg.cinematicScrim);
        // dock_title false: the name above the dock (and the cinematic lines under it) never shows; the glow
        // still follows the dock.
        if (!cfg.dockTitle) hero.setVisibility(View.GONE);
        greeting = findViewById(R.id.greeting);
        applyTimeOrder();
        // Tamil (and other scripts with tall marks) make a text's line box taller than its digits, which left the date a
        // little above the time. Re-centre on the digits whenever the clock row is laid out again.
        for (int id : new int[]{R.id.clock, R.id.ampm, R.id.date}) {
            findViewById(id).addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
                if (r - l != or - ol || b - t != ob - ot) root.post(this::alignClock);
            });
        }
        clockBlock = findViewById(R.id.leftBlock);
        greetingColor = greeting.getCurrentTextColor();
        if (getResources().getBoolean(R.bool.rtl_menu_bar)) {
            // Center Hebrew greetings over the numeric time, independent of a visible AM/PM label.
            findViewById(R.id.clock).addOnLayoutChangeListener((v, l, t, right, bottom, ol, ot, or, ob) -> {
                ViewGroup.LayoutParams params = greeting.getLayoutParams();
                if (params.width != right - l) {
                    params.width = right - l;
                    greeting.setLayoutParams(params);
                }
                greeting.setTranslationX(l);
            });
        }
        btnWifi = findViewById(R.id.btnWifi);
        shamsiDate = findViewById(R.id.shamsiDate);
        stats = findViewById(R.id.stats);
        raceCalendar = cfg.raceCalendar;
        racePanel = findViewById(R.id.racePanel);
        raceKicker = findViewById(R.id.raceKicker);
        raceHeadline = findViewById(R.id.raceHeadline);
        raceSessions = findViewById(R.id.raceSessions);
        showStats = cfg.showStats;
        shamsi = cfg.shamsiDate;
        greetingName = cfg.greetingName;
        if (shamsi) {
            shamsiDate.setVisibility(View.VISIBLE);
            TextView date = findViewById(R.id.date);
            date.setTextSize(11f);
            date.setPadding(0, (int) (4 * density), 0, 0);
        }
        vpnApp = cfg.vpnApp;
        moduleCards = cfg.moduleCards;
        hiddenApps = new HashSet<>(cfg.hiddenApps);

        grid = findViewById(R.id.grid);
        rtlAppGrid = getResources().getBoolean(R.bool.rtl_app_grid);
        LinearLayoutManager lm = new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false);
        lm.setReverseLayout(rtlAppGrid);
        grid.setLayoutManager(lm);
        grid.setItemAnimator(null);
        grid.addOnChildAttachStateChangeListener(new RecyclerView.OnChildAttachStateChangeListener() {
            @Override
            public void onChildViewAttachedToWindow(@NonNull View v) {
                v.setAlpha(tileAlpha(grid.getChildAdapterPosition(v)));
            }

            @Override
            public void onChildViewDetachedFromWindow(@NonNull View v) {
            }
        });
        if (animations && !reducedMotion) {
            grid.setLayoutAnimation(new LayoutAnimationController(
                    AnimationUtils.loadAnimation(this, R.anim.tile_enter), 0.06f));
        }
        adapter = new AppAdapter(this, this, COLUMNS, cfg);
        dockApps = cfg.dockApps;
        fillDockLeavesSlots = false;
        Profiles.Profile loadedFor = Profiles.active(this);
        loadedProfileId = loadedFor == null ? null : loadedFor.id;   // whose arrangement the tiles below are
        Order savedOrder = Order.load(Order.file(this));
        adapter.setSavedOrder(Order.ranks(savedOrder, dockApps), Order.docked(savedOrder, dockApps));
        adapter.setProfileHidden(Profiles.hidden(this, loadedProfileId));
        grid.setAdapter(adapter);

        grid.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            grid.post(this::refitGlass);
        });

        // Keep the glass glued to its tile while the grid scrolls.
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (!glassShown) return;
                glassTargetY -= dy;
                glassScrollShift -= dy;
                glass.setTranslationY(glass.getTranslationY() - dy);
            }
        });

        // Liquid feel: soft springs on the highlight's position.
        springX = new SpringAnimation(glass, DynamicAnimation.TRANSLATION_X);
        springY = new SpringAnimation(glass, new FloatPropertyCompat<View>("glassContentY") {
            @Override
            public float getValue(View v) {
                return v.getTranslationY() - glassScrollShift;
            }

            @Override
            public void setValue(View v, float value) {
                v.setTranslationY(value + glassScrollShift);
            }
        });
        for (SpringAnimation s : new SpringAnimation[]{springX, springY}) {
            if (reducedMotion) {
                Motion.reduce(s);
            } else if (!focusOvershoot) {
                // Critically damped: glides straight onto the tile without swinging past it.
                s.setSpring(new SpringForce().setStiffness(advancedEffects ? 520f : 440f).setDampingRatio(1f));
            } else {
                s.setSpring(new SpringForce().setStiffness(advancedEffects ? 320f : 260f)
                        .setDampingRatio(advancedEffects ? 0.76f : 0.58f));
            }
        }

        btnInputs = findViewById(R.id.btnInputs);
        btnProfile = findViewById(R.id.btnProfile);
        View btnSettings = findViewById(R.id.btnSettings);
        btnInputs.setOnClickListener(v -> showInputs());
        btnWifi.setOnClickListener(v -> openNetworkPill());
        btnWifi.setOnLongClickListener(v -> {
            openSliding(systemIntent(Settings.ACTION_WIFI_SETTINGS));
            return true;
        });
        btnSettings.setOnClickListener(v -> {
            panelOpener = btnSettings;
            showSettingsPanel(0);
        });
        btnReorder = findViewById(R.id.btnReorder);
        btnReorder.setOnClickListener(v -> toggleReorder());
        btnReorder.setVisibility(View.GONE);
        sectionTitle = findViewById(R.id.sectionTitle);
        btnDoneReorder = findViewById(R.id.btnDoneReorder);
        if (btnDoneReorder != null) {
            btnDoneReorder.setOnClickListener(v -> endReorder());
            // Focused, the stock launcher's Done grows slightly and lifts off the page.
            btnDoneReorder.setOnFocusChangeListener((v, focused) -> {
                float s = focused && animations ? 1.08f : 1f;
                v.animate().scaleX(s).scaleY(s).setDuration(animations && !reducedMotion ? 150 : 0).start();
                v.setElevation(focused ? 6 * density : 0f);
                if (reordering) dimOthers();   // the "Press to move" caption goes with the tile's focus
            });
        }
        if (!cfg.showNetworkPill) btnWifi.setVisibility(View.GONE);
        showInputsPill = cfg.showInputsPill;
        btnLibraryTop = findViewById(R.id.btnLibraryTop);
        btnLibraryTop.setOnClickListener(v -> {
            onLibraryRequested();
            libraryFromTopBar = true;   // closing it goes back to this button, not to the pill after the last row
        });
        btnLibraryTop.setVisibility(cfg.libraryButton && cfg.appLibrary ? View.VISIBLE : View.GONE);
        iconOnlyButtons = cfg.iconOnlyButtons;
        topMenu = cfg.topMenu;
        wifiIconOnly = iconOnlyButtons && cfg.wifiLabelWidth <= 0;
        if (cfg.wifiStatusColor) {
            wifiPlainTint = btnWifi.getCompoundDrawableTintList();
            wifiOnlineTint = getColorStateList(R.color.pill_icon_online);
        }
        if (cfg.topMenu) {
            // One gear: Inputs and Wi-Fi move into its dropdown. The Wi-Fi pill stays hidden but keeps its
            // label up to date, which the menu's Wi-Fi row shows.
            showInputsPill = false;
            btnInputs.setVisibility(View.GONE);
            btnWifi.setVisibility(View.GONE);
            ((TextView) btnSettings).setText(R.string.settings);
            ((TextView) btnSettings).setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_settings, 0, 0, 0);
            if (!iconOnlyButtons) makeIconOnly((TextView) btnSettings);
            // The lone gear sits at the header's middle, not on its bottom line like the row of pills.
            LinearLayout topButtons = findViewById(R.id.topButtons);
            RelativeLayout.LayoutParams tlp = (RelativeLayout.LayoutParams) topButtons.getLayoutParams();
            tlp.addRule(RelativeLayout.ALIGN_TOP, R.id.leftBlock);
            tlp.bottomMargin = 0;
            topButtons.setLayoutParams(tlp);
            topButtons.setGravity(Gravity.CENTER_VERTICAL);
        }
        if (iconOnlyButtons) {
            if (cfg.wifiLabelWidth > 0) btnWifi.setMaxWidth(Math.round(cfg.wifiLabelWidth * density));
            else makeIconOnly(btnWifi);
            makeIconOnly((TextView) btnSettings);
        }
        if (cfg.iconOnlyInputs) makeIconOnly((TextView) btnInputs);

        // Keep focus on the top bar at its ends and at the top: Android's focus finder would otherwise
        // jump to a tile far below when there is nothing in that direction.
        boolean rtlMenuBar = getResources().getBoolean(R.bool.rtl_menu_bar);
        View.OnKeyListener pillKeys = (v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) return true;
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                // Back to the app tile Up came from, not wherever the focus finder lands.
                focusTile(Math.min(Math.max(0, lastFocused), Math.max(0, adapter.appCount() - 1)));
                return true;
            }
            int firstButton = R.id.btnProfile;
            int leftButton = rtlMenuBar ? R.id.btnSettings : firstButton;
            int rightButton = rtlMenuBar ? firstButton : R.id.btnSettings;
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) return v.getId() == leftButton;
            return keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && v.getId() == rightButton;
        };
        btnInputs.setOnKeyListener(pillKeys);
        btnProfile.setOnKeyListener(pillKeys);
        btnProfile.setOnClickListener(v -> showProfilePicker());
        btnWifi.setOnKeyListener(pillKeys);
        btnSettings.setOnKeyListener(pillKeys);
        btnReorder.setOnKeyListener(pillKeys);
        btnLibraryTop.setOnKeyListener(pillKeys);
        if (advancedEffects) {
            for (View button : new View[]{btnInputs, btnWifi, btnReorder, btnLibraryTop, btnSettings}) {
                button.setOnFocusChangeListener((view, focused) -> view.animate()
                        .scaleX(focused ? 1.04f : 1f).scaleY(focused ? 1.04f : 1f)
                        .setDuration(reducedMotion ? Motion.REDUCED_MS : 160).start());
            }
        }
        if (festive) applyFestiveSkin((TextView) btnInputs, (TextView) btnSettings);

        // Hide the glass when focus leaves the tiles.
        getWindow().getDecorView().getViewTreeObserver().addOnGlobalFocusChangeListener((oldF, newF) -> {
            boolean onTile = newF != null && newF.getId() == R.id.card;
            if (!onTile) {
                hideGlass();
                if (ambientGlow) ambient.animate().alpha(0f).setDuration(220).start();
            }
        });

        packageFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        packageFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        packageFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        packageFilter.addDataScheme("package");
        timeFilter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        // Registered for the activity's whole life, not only while it is started: an app installed or updated while
        // another app is in front must still mark the tiles stale, or Home would have to rebuild on every return.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(packageReceiver, packageFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(packageReceiver, packageFilter);
        }

        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (tim != null) tim.registerCallback(inputCallback, handler);
        if (cm != null) {
            cm.registerDefaultNetworkCallback(networkCallback);
            cm.registerNetworkCallback(new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    .build(), vpnCallback);
        }
        // Not after recreate(): the launch intent survives it and would open the settings again.
        if (savedInstanceState == null && !openInputsIfAsked(getIntent())) openSettingsIfAsked(getIntent());
        startProfileFlow();
    }

    /**
     * handle_view_inputs: switch InputsRequestActivity, the VIEW_INPUTS handler, on or off. It is disabled in the
     * manifest, so a TV with its own input picker never sees it; only the sets that need it turn it on. Written
     * only when the state differs, so an ordinary start does not touch the package manager.
     */
    private void applyViewInputsHandler(boolean on) {
        ComponentName handler = new ComponentName(this, InputsRequestActivity.class);
        PackageManager pm = getPackageManager();
        int want = on ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED : PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
        if (pm.getComponentEnabledSetting(handler) != want) {
            pm.setComponentEnabledSetting(handler, want, PackageManager.DONT_KILL_APP);
        }
    }

    /**
     * VIEW_INPUTS, forwarded by InputsRequestActivity while handle_view_inputs is on: open the Inputs menu once
     * the pill is on screen. The intent is replaced so a later recreate() does not
     * open it again. Nothing to do when the TV has no inputs to list (the pill is hidden then).
     * top_menu hides the pill, so the list hangs under the gear there, as in showInputs().
     */
    private boolean openSettingsIfAsked(Intent intent) {
        if (intent == null || !ACTION_OPEN_SETTINGS.equals(intent.getAction())) return false;
        setIntent(new Intent(Intent.ACTION_MAIN));
        panelOpener = null;
        root.post(() -> {
            if (!isFinishing() && !isDestroyed() && openPopup == null) showSettingsPanel(0);
        });
        return true;
    }

    private boolean openInputsIfAsked(Intent intent) {
        if (intent == null || !ACTION_VIEW_INPUTS.equals(intent.getAction())) return false;
        setIntent(new Intent(Intent.ACTION_MAIN));
        View anchor = topMenu ? findViewById(R.id.btnSettings) : btnInputs;
        if (anchor.getVisibility() != View.VISIBLE) return true;
        if (anchor.isLaidOut()) {
            anchor.requestFocus();
            showInputs();
            return true;
        }
        // Cold start: the popup anchors to the pill (or the gear), so wait for the first layout.
        anchor.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                anchor.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                anchor.post(() -> {
                    if (isFinishing() || anchor.getVisibility() != View.VISIBLE) return;
                    anchor.requestFocus();
                    showInputs();
                });
            }
        });
        return true;
    }

    /**
     * Home pressed while Home is already in front: singleTask delivers it here, then onResume runs.
     * Back to the top with the first dock tile focused, like the stock launcher. Back from an app
     * never comes this way, so returning to the app you left from is unchanged (#118).
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (openInputsIfAsked(intent)) return;
        if (openSettingsIfAsked(intent)) return;
        if (intent != null && intent.hasCategory(Intent.CATEGORY_HOME)) { reopenSettingsRow = -1; reopenAudioPage = false; reopenLeft = false; panelOpener = null; }   // Home: no panel
        if (intent == null || !intent.hasCategory(Intent.CATEGORY_HOME)) return;
        grid.scrollToPosition(0);
        lastFocused = 0;
        lastFocusedKey = adapter.getItemCount() > 0 ? adapter.keyAt(0) : null;
        if (stopped) {
            tileRefresh.onHomeAgain();
        } else {
            // Still on screen (the quick panel was over it, or Home was already in front): the tiles are current,
            // so only go back to the first one; rebuilding would replay the entrance animation for nothing.
            grid.post(() -> focusTile(0));
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        stopped = false;
        started = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(timeReceiver, timeFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(timeReceiver, timeFilter);
        }
        updateClockVisibility();
        tileRefresh.onStart();
    }

    @Override
    protected void onResume() {
        super.onResume();
        homeResumed = true;
        // A festival that started or ended while Home was in the background: rebuild with or without it.
        if (festive != festival.active(LocalDate.now())) {
            recreate();
            return;
        }
        // Something changed in Launcher settings: rebuild with it, however the settings were left. Only Back
        // delivered their RESULT_OK; Home (the remote's button) clears them off this task and the result comes
        // back cancelled, so a new wallpaper or layout waited for the next process start.
        if (Config.settingsChanged(settingsRevision, settingsRevision())) {
            recreate();
            return;
        }
        restartIdleClock();
        promptsPaused = false;
        if (grid.getAdapter() == null) grid.setAdapter(adapter);   // dropped by onTrimMemory
        switch (tileRefresh.onResume(packagesChangedSinceRebuild())) {
            case REBUILD:
                tilesReady = false;
                reloadApps(true);
                break;
            case REFOCUS:
                // The same tiles: only the focus goes back, as after a rebuild, without replaying the entrance.
                tilesReady = false;
                reloadApps(true, false, false);
                break;
            default:
                break;
        }
        if (openLiveTvAfterBoot()) return;
        reopenPanelIfDue();
        // The button remapper's helper: on in Settings but not running (after an update or a slow start) is put right.
        root.removeCallbacks(repairRemap);
        root.postDelayed(repairRemap, 5000);
        updateGreeting();
        updateRace();
        // The network and Inputs pills keep their state while Home is in the background, and callbacks update them
        // when the network or the inputs change, so this refresh only catches anything missed. Its binder calls
        // (about 7 ms on a TCL C755, most of it WifiManager.getConnectionInfo) would hold up the first frame back
        // on Home, so after the first resume it runs once that frame is drawn.
        if (pillsShown) {
            refreshPillsAfterNextDraw();
        } else {
            updateWifiLabel();
            updateInputsButton();
            pillsShown = true;
        }
        if (showStats) startStats();
        sky.update();
        wallpaperPlayer.start();
        handler.postDelayed(minuteTick, MINUTE_MS);
        if (festive && diya != null) {
            diya.start();
            LocalDate today = LocalDate.now();
            if (!reducedMotion && !today.equals(lastPetalShower)) {
                lastPetalShower = today;
                handler.postDelayed(this::playPetalShower, 450);
            }
        }
    }

    /** Launcher settings' change count (Config.SETTINGS_REVISION_KEY); onResume rebuilds Home when it moved. */
    private long settingsRevision() {
        return getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE).getLong(Config.SETTINGS_REVISION_KEY, 0);
    }

    /** Rebuilds the tiles (and the module cards), then puts focus back where it was. */
    private void reloadApps(boolean animate) {
        reloadApps(animate, true);
    }

    /** @param rebuild false keeps the tiles as they are and only puts focus back (TileRefresh.Action.REFOCUS). */
    private void reloadApps(boolean animate, boolean rebuild) {
        reloadApps(animate, rebuild, true);
    }

    /**
     * @param entrance false puts focus back as an animated reload would, without replaying the tiles' entrance:
     *                 on a return to the same tiles it would only mark a change that didn't happen.
     */
    private void reloadApps(boolean animate, boolean rebuild, boolean entrance) {
        cancelPendingFocusScroll();
        RecyclerView.ViewHolder footer = grid.findViewHolderForAdapterPosition(adapter.appCount());
        final boolean restoreLibrary = adapter.isLibraryAt(adapter.appCount()) && footer != null
                && footer.itemView.findViewById(R.id.btnLibrary) != null
                && footer.itemView.findViewById(R.id.btnLibrary).hasFocus();
        // Capture the app before rebinding can move focus and overwrite its key.
        final String returnKey = lastFocusedKey;
        final int returnSlot = lastFocused;
        final boolean restoreFocus = appLibrary == null && (animate || grid.hasFocus());
        if (restoreFocus) {
            glassShown = false;
            glass.setAlpha(0f);
            heroShown = false;
            hero.setAlpha(0f);
            ambient.setAlpha(0f);
            heroArt.animate().cancel();
            heroArt.setAlpha(0f);
        }
        if (rebuild) reloadTiles();
        if (animate && entrance && animations && !reducedMotion) {
            grid.scheduleLayoutAnimation();
            // A rebuild redraws the grid anyway; without one the entrance needs a draw to start.
            if (!rebuild) grid.invalidate();
        }

        if (restoreFocus) {
            // Keep a focused footer at the end even when the app count changes.
            if (restoreLibrary) grid.post(this::focusLibrary);
            else restoreFocus();
        } else {
            // A package change must not steal focus from a top button or the Inputs popup.
            int pos = adapter.positionOf(returnKey);
            lastFocused = Math.max(0, Math.min(pos >= 0 ? pos : returnSlot, adapter.appCount() - 1));
            lastFocusedKey = adapter.keyAt(lastFocused);
            if (pos < 0) hideHero();
            // A newly installed app may have brought a card with it.
            tilesReady = true;
            showCardPrompt();
        }
    }

    /** True when PackageManager reports apps changed since the last rebuild (one binder call). */
    private boolean packagesChangedSinceRebuild() {
        ChangedPackages changed = getPackageManager().getChangedPackages(packageSequence);
        return changed != null && !changed.getPackageNames().isEmpty();
    }

    private int currentPackageSequence(int since) {
        ChangedPackages changed = getPackageManager().getChangedPackages(since);
        return changed != null ? changed.getSequenceNumber() : since;
    }

    /**
     * Rebuilds the tiles; the module cards the owner chose to show stand in for their app's tile.
     * Every step into the card code runs under CardFeature: if it fails, the tiles are the ordinary
     * ones and cards stay off for this process.
     */
    private void reloadTiles() {
        if (reordering) endReorder();   // an app installed or removed while reordering: keep the order so far
        // Read these before reload(): rebinding can move focus to the first tile and overwrite them.
        final String returnKey = lastFocusedKey;
        final int returnSlot = lastFocused;
        final List<Cards.Card> none = Collections.emptyList();
        // An updated app may bring new artwork; the backdrop is cheap to make again.
        heroArtCache.clear();
        heroArtKey = null;
        // One LEANBACK query for both the tiles and the card checks.
        final List<ResolveInfo> leanback = getPackageManager().queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0);
        cards = moduleCards ? CardFeature.run(() -> Cards.discover(this, hiddenApps,
                () -> Cards.load(Cards.storeFile(this)), leanback), none) : none;
        adapter.reload(cards.isEmpty() ? none : CardFeature.run(() -> Cards.shown(cards), none), leanback);
        lastFocusedKey = returnKey;
        lastFocused = returnSlot;
        packageSequence = currentPackageSequence(packageSequence);
    }

    /**
     * Return to the app we left from, or the same slot if that app is gone. Always refocus: after
     * reload() the view that holds focus may no longer show the app it had.
     */
    private void restoreFocus() {
        final boolean returnToLibrary = libraryFocusPending;
        final String returnKey = lastFocusedKey;
        final int returnSlot = lastFocused;
        grid.post(() -> {
            int pos = adapter.positionOf(returnKey);
            if (returnToLibrary || libraryFocusPending) focusLibrary();
            else focusTile(pos >= 0 ? pos : returnSlot);
            tilesReady = true;
            showCardPrompt();
        });
    }

    /** Any key on Home restarts the countdown to the idle clock. */
    @Override
    public void onUserInteraction() {
        super.onUserInteraction();
        restartIdleClock();
    }

    private void restartIdleClock() {
        root.removeCallbacks(showIdleClock);
        if (idleClockMs > 0) root.postDelayed(showIdleClock, idleClockMs);
    }

    @Override
    protected void onPause() {
        super.onPause();
        RemapService.setCapture(null);   // a half-finished "press a button" must never keep swallowing the remote's keys
        homeResumed = false;
        if (reopenSettingsRow >= 0) reopenLeft = true;
        // Coming back from an app, the window hands the focus to the first button in the layout before Home puts it
        // back on the tile: the top bar lit up for a moment. Close it again unless the owner left from it.
        ViewGroup bar = findViewById(R.id.topButtons);
        if (bar != null && !bar.hasFocus()) bar.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        root.removeCallbacks(showIdleClock);
        endReorder();   // Home or another app while reordering keeps the order so far
        wallpaperPlayer.stop();
        stopAmbientAnimation();
        hero.animate().cancel();
        ambient.animate().cancel();
        heroArt.animate().cancel();
        handler.removeCallbacks(minuteTick);
        stopStats();
        if (diya != null) diya.stop();
        cancelPendingFocusScroll();
        // Nothing of ours stays on screen over another app; an undecided prompt simply returns next time.
        if (cardPrompt != null) cardPrompt.dismiss();
        if (appLibrary != null) appLibrary.close();
        // The Settings panel waits for its owner: System settings and TV settings open over it, and coming back finds
        // the panel as it was, with the row that opened them still selected.
        if (!(openPopup instanceof SidePopup && ((SidePopup) openPopup).keepWhenLeaving)) closePopup(openPopup);
    }

    @Override
    protected void onStop() {
        super.onStop();
        started = false;
        unregisterReceiver(timeReceiver);
        handler.removeCallbacks(clockCheck);
        stopped = true;
        tileRefresh.onStop();
    }

    /**
     * Another app needs the memory while Home is in the background: let go of every tile's artwork, and
     * rebuild it when Home comes back. Not at UI_HIDDEN, the ordinary trip to an app, where keeping the
     * tiles is what makes the return instant.
     */
    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        // RUNNING_LOW and CRITICAL also arrive while Home is visible when an accessibility service keeps the
        // process in the foreground; never blank the screen the owner is looking at.
        boolean due = level >= TRIM_MEMORY_BACKGROUND
                || level == TRIM_MEMORY_RUNNING_LOW || level == TRIM_MEMORY_RUNNING_CRITICAL;
        if (!due || started) return;
        adapter.clear();
        // Detach the adapter too: with Home stopped no layout pass runs, so the tile views (and the bitmaps
        // they hold) would stay attached until Home comes back. setAdapter(null) recycles them now.
        grid.setAdapter(null);
        grid.getRecycledViewPool().clear();
        heroArtCache.clear();
        heroArtKey = null;
        tileRefresh.invalidate();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus) root.removeCallbacks(reopenRun);
        if (hasFocus) {
            // The window has placed its first focus (on the row, since the top bar was closed): open the bar again.
            root.post(this::openTopBar);
            reopenPanelIfDue();
            if (libraryFocusPending && appLibrary == null) focusLibrary();
            showCardPrompt();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (audioOutput != null) audioOutput.close();
        unregisterReceiver(packageReceiver);
        wallpaperPlayer.close();
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (tim != null) tim.unregisterCallback(inputCallback);
        if (cm != null) {
            cm.unregisterNetworkCallback(networkCallback);
            cm.unregisterNetworkCallback(vpnCallback);
        }
    }

    /**
     * A Settings button sent as a keycode (settings_key_keycodes, e.g. 83 on TCL Google TV remotes) opens the same
     * thing HomeKeyService would. While that service runs it consumes the key first; without it (not enabled,
     * or switched off by a force-stop) the stock launcher that used to handle the key is gone, and the button did
     * nothing even on Home.
     */
    private boolean handleSettingsKey(KeyEvent event) {
        for (int code : settingsKeycodes) {
            if (event.getKeyCode() != code) continue;
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                open(systemIntent(Settings.ACTION_SETTINGS));
            }
            return true;
        }
        return false;
    }

    // ---- reorder ---------------------------------------------------------------------------------

    /** Blue the glass turns while reorder mode is on. */
    private static final int REORDER_ACCENT = 0xFF6EC1FF;

    /**
     * Reorder mode (top-bar button). While it is on, every key comes here first:
     * - nothing carried: arrows move focus as usual; OK on a tile picks it up instead of opening the app;
     *   Back ends the mode and saves the order.
     * - a tile carried: arrows move it; Back (or OK) puts it down and the mode stays on for the next one.
     * Other keys (volume, Home) pass through; leaving the launcher ends the mode and saves.
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (profilePicker != null) {
            if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                if (event.getAction() == KeyEvent.ACTION_UP) profilePicker.onBack();
                return true;
            }
            return super.dispatchKeyEvent(event);
        }
        if (handleSettingsKey(event)) return true;
        if (tileMenuEnabled && !reordering && openPopup == null && cardPrompt == null
                && event.getKeyCode() == KeyEvent.KEYCODE_MENU) {
            int position = focusedTilePosition();
            if (position >= 0 && position < adapter.appCount()
                    && !getPackageName().equals(adapter.packageAt(position))) {
                if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                    onTileLongPress(position, getCurrentFocus());
                }
                return true;
            }
        }
        if (!reordering) return super.dispatchKeyEvent(event);
        if (dockEditing) return dispatchDockEdit(event);
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        int code = event.getKeyCode();
        boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
        boolean back = code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE;
        if (movingKey != null) {
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (down) moveBy(code);
                    return true;
                default:
                    if (ok || back) {
                        if (!down) drop();
                        return true;
                    }
                    return super.dispatchKeyEvent(event);
            }
        }
        if (back) {
            if (!down) endReorder();
            return true;
        }
        View focus = getCurrentFocus();
        if (focus != null && focus == btnDoneReorder) {
            if (code == KeyEvent.KEYCODE_DPAD_UP) {
                if (down) focusTile(Math.max(0, Math.min(lastFocused, adapter.movableCount() - 1)));
                return true;
            }
            if (ok) {
                if (!down) endReorder();
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_DPAD_DOWN) {
                return true;
            }
        }
        if (code == KeyEvent.KEYCODE_DPAD_DOWN && grid.hasFocus() && btnDoneReorder != null && btnDoneReorder.getVisibility() == View.VISIBLE) {
            if (down) btnDoneReorder.requestFocus();
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_UP && grid.hasFocus()) {
            return true;
        }
        int pos = focusedTilePosition();
        if (ok && focus != null && focus.getId() == R.id.btnLibrary) {
            // The App library pill sits in the grid but is no tile: OK ends the mode (saving the order) and opens it.
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                endReorder();
                onLibraryRequested();
            }
            return true;
        }
        if (ok && grid.hasFocus()) {
            // RecyclerView can briefly report NO_POSITION during a move or rebind. Still consume
            // OK on the grid: falling through would let the focused card open its app in reorder mode.
            if (!down && pos >= 0) pickUp(pos);
            return true;
        }
        return super.dispatchKeyEvent(event);   // arrows navigate; OK on a top-bar pill works as usual
    }

    /** Adapter position of the focused tile, or -1 when focus is not on a tile. */
    private int focusedTilePosition() {
        View f = getCurrentFocus();
        if (f == null || f.getId() != R.id.card || !(f.getParent() instanceof View)) return -1;
        return grid.getChildAdapterPosition((View) f.getParent());
    }

    /** The reorder button: turns the mode on (focus goes back to the last tile), or off again. */
    private void toggleReorder() {
        if (reordering) {
            endReorder();
            return;
        }
        if (adapter.movableCount() < 2) return;
        // Move the focus onto the app first: the Reorder tile it is on stops being focusable in the mode, and
        // letting it go there would send the focus up to the top bar for a moment.
        int target = Math.max(0, Math.min(lastFocused, adapter.movableCount() - 1));
        focusTile(target);
        if (!beginReorder()) return;
        // No toast: the caption under the focused tile ("Press to move") already says what OK does.
        refocusAfterLayout(target);
    }

    /** The start shared by reorder and edit-dock mode. False when there is nothing to move. */
    private boolean beginReorder() {
        if (adapter.movableCount() < 2) return false;
        // Before the reload below: once it has dropped the empty slots, the dock row also shows grid apps.
        dockInSession = new HashSet<>();
        dockedAtStart = adapter.dockFilled();
        for (int i = 0; i < Math.min(dockedAtStart, adapter.movableCount()); i++) dockInSession.add(adapter.keyAt(i));
        adapter.setReordering(true);
        if (adapter.hasEmptySlots()) reloadTiles();   // every position must hold a tile that can move
        reordering = true;
        applyReorderState();
        // Moves slide the two tiles past each other, as on the stock launcher; off otherwise (the row snaps).
        DefaultItemAnimator mover = new DefaultItemAnimator();
        mover.setSupportsChangeAnimations(false);
        mover.setMoveDuration(animations && !reducedMotion ? 250 : 0);
        grid.setItemAnimator(mover);
        orderAtStart = adapter.movableKeys();
        glass.setAccent(REORDER_ACCENT);
        btnReorder.setActivated(true);
        if (sectionTitle != null) sectionTitle.setText(R.string.arrange_apps);
        View leftBlock = findViewById(R.id.leftBlock);
        if (leftBlock != null) leftBlock.animate().alpha(0.2f).setDuration(220).start();
        View topButtons = findViewById(R.id.topButtons);
        if (topButtons != null) topButtons.animate().alpha(0.2f).setDuration(220).start();
        if (btnDoneReorder != null) {
            btnDoneReorder.setVisibility(View.VISIBLE);
            btnDoneReorder.setAlpha(0f);
            btnDoneReorder.animate().alpha(1f).setDuration(220).start();
        }
        return true;
    }

    /** Edit dock (Settings menu): focus goes to the first dock place, which OK then chooses. */
    private void startDockEdit() {
        if (reordering) endReorder();
        if (!beginReorder()) return;
        dockEditing = true;
        dockEditSlot = -1;
        focusTile(0);
        Toast.makeText(this, R.string.dock_edit_hint, Toast.LENGTH_LONG).show();
    }

    /**
     * Keys in edit-dock mode. First OK on a dock tile chooses that place (the other dock tiles dim) and focus moves
     * to the grid; then OK on any tile swaps that app into the place, saves the order and ends the mode. Back goes
     * one step back: from choosing the app to choosing the place, and from there out of the mode.
     */
    private boolean dispatchDockEdit(KeyEvent event) {
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        int code = event.getKeyCode();
        boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
        boolean back = code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE;
        if (back) {
            if (!down) {
                if (dockEditAppKey != null) {
                    endReorder();   // "Add to dock" cancelled before a place was chosen
                } else if (dockEditSlot >= 0) {
                    int slot = dockEditSlot;
                    dockEditSlot = -1;
                    dimOthers();
                    focusTile(slot);
                } else {
                    endReorder();
                }
            }
            return true;
        }
        int pos = focusedTilePosition();
        if (ok && pos >= 0) {
            if (!down) {
                int n = adapter.movableCount();
                if (dockEditAppKey != null) {
                    if (pos < Math.min(COLUMNS, n)) {
                        int from = adapter.positionOf(dockEditAppKey);
                        if (from >= 0) swapIntoDock(pos, from);
                        else endReorder();
                    }
                } else if (dockEditSlot < 0) {
                    if (pos < Math.min(COLUMNS, n)) {
                        dockEditSlot = pos;
                        dimOthers();
                        focusTile(Math.min(COLUMNS, n - 1));
                        Toast.makeText(this, R.string.dock_edit_pick_app, Toast.LENGTH_LONG).show();
                    }
                } else if (pos < n) {
                    swapIntoDock(dockEditSlot, pos);
                }
            }
            return true;   // never opens the app while editing
        }
        return super.dispatchKeyEvent(event);   // arrows navigate as usual
    }

    /**
     * Edit-dock mode started from the tile menu. A dock app: its place is already chosen, so focus goes to the grid
     * for the app to put there. A grid app: it is already chosen, so focus goes to the dock for the place.
     */
    private void startDockEditFor(String key, boolean inDock) {
        if (reordering) endReorder();
        if (!beginReorder()) return;
        dockEditing = true;
        int pos = adapter.positionOf(key);   // beginReorder can rebuild the tiles
        int n = adapter.movableCount();
        if (pos < 0) {
            endReorder();
        } else if (inDock) {
            dockEditSlot = pos;
            dimOthers();
            focusTile(Math.min(COLUMNS, n - 1));
            Toast.makeText(this, R.string.dock_edit_pick_app, Toast.LENGTH_LONG).show();
        } else {
            dockEditAppKey = key;
            focusTile(0);
            Toast.makeText(this, R.string.dock_edit_pick_place, Toast.LENGTH_LONG).show();
        }
    }

    /** Puts the app at pos into dock place slot, and the app that was there where the chosen one was; then saves. */
    private void swapIntoDock(int slot, int pos) {
        String slotKey = adapter.keyAt(slot);
        if (pos != slot) {
            keepScroll(() -> {
                adapter.move(pos, slot);
                adapter.move(adapter.positionOf(slotKey), pos);
            });
            dockInSession.add(adapter.keyAt(slot));
            if (pos >= COLUMNS) dockInSession.remove(slotKey);
        }
        endReorder();
        refocusAfterLayout(slot);
    }

    private void pickUp(int pos) {
        if (pos >= adapter.movableCount()) return;   // the "Default home" tile stays put
        movingKey = adapter.keyAt(pos);
        adapter.setMovingKey(movingKey);
        dimOthers();
    }

    /** Puts the carried tile down where it is; the mode stays on. */
    private void drop() {
        movingKey = null;
        adapter.setMovingKey(null);
        dimOthers();
    }

    /** While a tile is carried, every other tile is dimmed so the carried one stands out; otherwise all are full. */
    private void dimOthers() {
        boolean resized = false;
        for (int i = 0; i < grid.getChildCount(); i++) {
            View item = grid.getChildAt(i);
            int pos = grid.getChildAdapterPosition(item);
            item.setAlpha(tileAlpha(pos));
            TextView prompt = item.findViewById(R.id.reorderPrompt);
            if (prompt != null && reordering) {
                View card = item.findViewById(R.id.card);
                boolean carried = card != null && card.isFocused() && movingKey != null;
                int shown = card != null && card.isFocused() ? View.VISIBLE : View.INVISIBLE;
                if (shown == View.VISIBLE) {
                    prompt.setText(movingKey != null ? R.string.press_to_confirm : R.string.press_to_move);
                }
                if (prompt.getVisibility() != shown) {
                    // From GONE (outside the mode) this makes the tile taller.
                    resized |= prompt.getVisibility() == View.GONE;
                    prompt.setVisibility(shown);
                }
                // Arrows beside the carried tile show which ways it can still move.
                View left = item.findViewById(R.id.arrowLeft);
                View right = item.findViewById(R.id.arrowRight);
                int last = adapter.movableCount() - 1;
                if (left != null) left.setVisibility(carried && pos > 0 ? View.VISIBLE : View.GONE);
                if (right != null) right.setVisibility(carried && pos < last ? View.VISIBLE : View.GONE);
            }
        }
        if (resized) relayoutRow();
    }

    /**
     * Lays the row out again on the next frame. A caption that appears or goes changes the tiles' height, and when
     * that happens inside a layout pass the tile is laid out again without being measured again: on the TCL (Android
     * 11) the caption then stayed 0 px tall and the Done button sat too high until the next focus change.
     */
    private void relayoutRow() {
        grid.post(() -> {
            // The tiles themselves must be measured again: a layout pass alone keeps their old height.
            for (int i = 0; i < grid.getChildCount(); i++) grid.getChildAt(i).requestLayout();
            grid.requestLayout();
        });
    }

    /**
     * Puts the tiles on screen into (or out of) reorder mode where they are: the Reorder tile can or cannot take focus,
     * and the captions and arrows go when the mode ends. Tiles off screen are rebound when they come back. Rebinding
     * the tiles on screen instead would drop the focus for a frame and make the ring and glow flicker.
     */
    private void applyReorderState() {
        for (int i = 0; i < grid.getChildCount(); i++) {
            View item = grid.getChildAt(i);
            int pos = grid.getChildAdapterPosition(item);
            if (pos == RecyclerView.NO_POSITION) continue;
            if (adapter.isReorderAt(pos)) {
                View card = item.findViewById(R.id.card);
                if (card != null) card.setFocusable(!reordering);
            }
            if (!reordering) {
                for (int id : new int[]{R.id.reorderPrompt, R.id.arrowLeft, R.id.arrowRight}) {
                    View v = item.findViewById(id);
                    if (v != null) v.setVisibility(View.GONE);
                }
                relayoutRow();
            }
        }
        RecyclerView.LayoutManager lm = grid.getLayoutManager();
        int n = adapter.getItemCount();
        if (lm instanceof LinearLayoutManager && n > 0) {
            LinearLayoutManager llm = (LinearLayoutManager) lm;
            int first = llm.findFirstVisibleItemPosition();
            int last = llm.findLastVisibleItemPosition();
            if (first == RecyclerView.NO_POSITION) {
                adapter.notifyItemRangeChanged(0, n);
            } else {
                if (first > 0) adapter.notifyItemRangeChanged(0, first);
                if (last < n - 1) adapter.notifyItemRangeChanged(last + 1, n - 1 - last);
            }
        }
        dimOthers();
    }

    /** A tile's alpha: dim unless it is the carried one, or, in edit-dock mode, a dock tile other than the chosen place. */
    private float tileAlpha(int pos) {
        if (reordering && pos != RecyclerView.NO_POSITION && adapter.isReorderAt(pos)) return 0.35f;
        if (dockEditSlot >= 0 && pos >= 0 && pos < COLUMNS && pos != dockEditSlot) return 0.45f;
        return 1f;
    }

    /** Ends the mode and saves the order if it changed since the mode started. */
    private void endReorder() {
        if (!reordering) return;
        // The app the owner was on (the one just moved). Done is about to go: put the focus on that app before it
        // does, or it falls to the top bar.
        int back = lastFocusedKey != null ? adapter.positionOf(lastFocusedKey) : -1;
        if (back < 0) back = Math.max(0, Math.min(lastFocused, adapter.appCount() - 1));
        if (btnDoneReorder != null && btnDoneReorder.hasFocus()) focusTile(back);
        dockEditing = false;
        dockEditSlot = -1;
        dockEditAppKey = null;
        drop();
        reordering = false;
        grid.setItemAnimator(null);
        adapter.setReordering(false);
        applyReorderState();
        glass.setAccent(festive ? festival.accentColor() : 0);
        btnReorder.setActivated(false);
        if (sectionTitle != null) sectionTitle.setText(R.string.your_apps);
        View leftBlock = findViewById(R.id.leftBlock);
        if (leftBlock != null) leftBlock.animate().alpha(1f).setDuration(220).start();
        View topButtons = findViewById(R.id.topButtons);
        if (topButtons != null) topButtons.animate().alpha(1f).setDuration(220).start();
        if (btnDoneReorder != null) {
            // Gone at once: fading it out while the row above shrinks back made it jump.
            btnDoneReorder.animate().cancel();
            btnDoneReorder.setVisibility(View.GONE);
        }
        List<String> keys = adapter.movableKeys();
        List<String> before = orderAtStart;
        orderAtStart = null;
        // fill_dock false: the dock is what the owner put there, saved with its size, so the empty slots that
        // come back after the mode go after it (#499). With fill_dock true every dock slot holds an app anyway.
        int docked = -1;
        if (fillDockLeavesSlots) {
            docked = DockSlots.dockCount(keys, COLUMNS, dockInSession);
            keys = DockSlots.dockFirst(keys, COLUMNS, dockInSession);
        }
        dockInSession = null;
        if (!keys.equals(before) || (fillDockLeavesSlots && docked != dockedAtStart)) {
            Order order = new Order(Order.dockKey(dockApps), keys, docked);
            if (Order.save(Order.file(this), order)) {
                adapter.setSavedOrder(Order.ranks(order, dockApps), Order.docked(order, dockApps));
            } else {
                Toast.makeText(this, R.string.reorder_not_saved, Toast.LENGTH_SHORT).show();
            }
        }
        if (fillDockLeavesSlots) reloadTiles();   // the empty dock slots left out while reordering come back
        // The tiles were rebuilt and focus was on the Done button, which is gone: without this it falls to the top bar.
        // Put it back on the app that was just moved, and clear the dimming reorder mode left on the Reorder tile.
        dimOthers();
        int again = lastFocusedKey != null ? adapter.positionOf(lastFocusedKey) : -1;
        refocusAfterLayout(again >= 0 ? again : back);
    }

    /**
     * Moves the carried tile one step. Left and Right wrap across rows, like a list; Up and Down move a whole row,
     * and Down into a short last row lands on its last tile. Nothing moves past either end or up into the top bar.
     */
    private void moveBy(int keyCode) {
        int from = adapter.positionOf(movingKey);
        int n = adapter.movableCount();
        if (from < 0 || from >= n) {
            drop();
            return;
        }
        int to;
        // rtl_app_grid (Hebrew) mirrors the grid: position 0 is on the right, so Left moves towards the end.
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) to = rtlAppGrid ? from + 1 : from - 1;
        else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) to = rtlAppGrid ? from - 1 : from + 1;
        else return;
        if (to < 0 || to >= n || to == from) return;
        keepScroll(() -> adapter.move(from, to));
        // The owner carries this tile: where it lands decides whether it is in the dock, not dock_apps (#499).
        if (to < COLUMNS) dockInSession.add(movingKey);
        else dockInSession.remove(movingKey);
        refocusAfterLayout(to);
    }

    /**
     * Applies an adapter change without the grid jumping. After a move the layout manager anchors on the focused
     * tile, keeping it at its old place on screen and shifting every other row; pinning the first visible row at its
     * current offset as the next layout's anchor keeps the scroll where it was. onTileFocused still scrolls
     * afterwards if the moved tile itself ends up off screen.
     */
    private void keepScroll(Runnable change) {
        RecyclerView.LayoutManager lm = grid.getLayoutManager();
        if (lm instanceof LinearLayoutManager) {
            LinearLayoutManager llm = (LinearLayoutManager) lm;
            int first = llm.findFirstVisibleItemPosition();
            View firstView = first != RecyclerView.NO_POSITION ? llm.findViewByPosition(first) : null;
            int offset = firstView != null ? llm.getDecoratedLeft(firstView) - grid.getPaddingStart() : 0;
            change.run();
            if (firstView != null) llm.scrollToPositionWithOffset(first, offset);
        } else {
            change.run();
        }
    }

    /**
     * Once the grid has applied the change, put the glass (and focus, if the move dropped it) on the tile at pos.
     * The grid has a fixed size, so it applies adapter changes on the next animation frame rather than in a layout
     * pass; until then it cannot find the tile by position. Wait frame by frame (at most 10) for that. No focus
     * change fires for a moved tile, so focusTile's already-focused path calls onTileFocused itself.
     */
    private void refocusAfterLayout(int pos) {
        grid.postOnAnimation(new Runnable() {
            int frames;

            @Override
            public void run() {
                if ((grid.hasPendingAdapterUpdates() || grid.isLayoutRequested()) && ++frames < 10) {
                    grid.postOnAnimation(this);
                    return;
                }
                focusTile(pos);
                dimOthers();   // views that came into view or were rebound during the move
            }
        });
    }

    @Override
    public void onBackPressed() {
        // Home screen: Back goes nowhere.
    }

    // ---- glass highlight -------------------------------------------------------------------------

    @Override
    public void onReorderRequested() {
        toggleReorder();
    }

    @Override
    public void onLibraryRequested() {
        if (appLibrary != null) appLibrary.close();
        closePopup(openPopup);
        libraryFocusPending = false;
        libraryFromTopBar = false;
        appLibrary = new AppLibraryDialog(this, animations, reducedMotion, appLibrarySelection, adapter::containsPackage, () -> reloadApps(false), () -> {
            appLibrary = null;
            openPopup = null;
            if (libraryFromTopBar) {
                libraryFromTopBar = false;
                btnLibraryTop.requestFocus();
                return;
            }
            libraryFocusPending = true;
            grid.post(() -> {
                if (libraryFocusPending && appLibrary == null && hasWindowFocus() && !isFinishing()) focusLibrary();
            });
        });
        openPopup = appLibrary.popup;
        appLibrary.show();
    }

    @Override
    public void onTileFocused(int position, View card) {
        openTopBar();
        lastFocused = position;
        lastFocusedKey = adapter.keyAt(position);
        if (reordering) dimOthers();
        // Focus restored before the first layout (cold start): the tile has no size yet, and the glass
        // would be sized to 2*pad. Run again once the tile is laid out.
        if (card.getWidth() == 0) {
            card.post(() -> {
                if (card.isFocused() && card.getWidth() > 0) onTileFocused(position, card);
            });
            return;
        }
        // Tile rect in root coordinates, using layout positions (ignores the scale animation).
        Rect r = tileRect;
        r.set(0, 0, card.getWidth(), card.getHeight());
        root.offsetDescendantRectToMyCoords(card, r);
        float pad = 6f * density;        // matches GlassView's glow inset
        float scale = adapter.focusScale();
        float w = card.getWidth() * scale + 2 * pad;
        float h = card.getHeight() * scale + 2 * pad;
        float x = r.exactCenterX() - w / 2f;
        float y = r.exactCenterY() - h / 2f - (advancedEffects ? 4f * density : 0f);

        ViewGroup.LayoutParams lp = glass.getLayoutParams();
        if (lp.width != (int) w || lp.height != (int) h || card.getWidth() != glassCardW) {
            lp.width = (int) w;
            lp.height = (int) h;
            glass.setLayoutParams(lp);
            // GlassView draws its slab inset by pad, i.e. exactly over the zoomed tile: same corners as the tile.
            glass.setRadius(AppAdapter.cornerRadius(card.getWidth(), density) * scale);
        }
        glassCardW = card.getWidth();
        glassTargetX = x;
        glassTargetY = y;
        // Cinematic shelf: the highlight glows in the dock app's colour, plain white in the grid (and on the dock with
        // cinematic_white_focus). app_glow: every tile.
        boolean coloured = (appGlow || cinematic && !cinematicWhiteFocus && position < COLUMNS) && !festive;
        int accent = coloured ? adapter.ambientColorAt(position) : 0;
        if ((cinematic || appGlow) && !festive && accent != glassAccent) {
            glassAccent = accent;
            glass.setAccent(accent);
        }
        if (glass.getVisibility() == View.VISIBLE) {
            if (!glassShown) {
                springX.cancel();
                springY.cancel();
                glass.setTranslationX(x);
                glass.setTranslationY(y);
                if (animations && !reducedMotion) glass.animate().alpha(1f).setDuration(180).start();
                else glass.setAlpha(1f);
                glassShown = true;
            } else if (!animations) {
                springX.cancel();
                springY.cancel();
                glass.setTranslationX(x);
                glass.setTranslationY(y);
            } else {
                springX.animateToFinalPosition(x);
                springY.animateToFinalPosition(y - glassScrollShift);
            }
        }
        View item = (View) card.getParent();
        glowAnchor = card;
        showGlowOnly(position, r.exactCenterX(), r.exactCenterY());
        // Room on both sides, so the next app (and the one before) shows half cut at the screen edges, as on Google TV.
        revealRect.set(-(int) (pad * 2 + 26 * density), 0, item.getWidth() + (int) pad * 2, item.getHeight());
        grid.requestChildRectangleOnScreen(item, revealRect, reducedMotion);
    }

    /** Refits the glass to the focused holder once the grid layout and tile dimensions settle. */
    private void refitGlass() {
        if (isDestroyed() || adapter.appCount() == 0 || libraryFocusPending) return;
        int pos = Math.max(0, Math.min(lastFocused, adapter.appCount() - 1));
        RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(pos);
        View card = vh == null ? null : vh.itemView.findViewById(R.id.card);
        if (card == null || card.getWidth() <= 0 || !card.isFocused()) return;
        float pad = 6f * density;
        float scale = adapter.focusScale();
        int w = (int) (card.getWidth() * scale + 2 * pad);
        int h = (int) (card.getHeight() * scale + 2 * pad);
        if (!glassShown || card.getWidth() != glassCardW || glass.getWidth() != w || glass.getHeight() != h) {
            onTileFocused(pos, card);
        }
    }

    // ---- top shelf -------------------------------------------------------------------------------

    private void showHero(int position, float dockTopInRoot) {
        String label = adapter.labelAt(position);
        int color = adapter.ambientColorAt(position);
        float y = dockTopInRoot - 14 * density - 26 * density - hero.getHeight();
        boolean changed = !label.contentEquals(heroName.getText());
        if (cinematic && heroArtAlpha > 0f) showHeroArt(position);

        if (!animations || reducedMotion) {
            // Snap the shelf instead of sliding and fading it: each of those frames repaints the
            // full-screen panel, and this switch exists for the GPUs that cannot afford it.
            hero.animate().cancel();
            ambient.animate().cancel();
            fillHero(position, label);
            hero.setY(y);
            hero.setTranslationX(0f);
            hero.setAlpha(1f);
            if (ambientGlow) {
                setAmbient(color);
                ambient.setAlpha(1f);
            }
            heroShown = true;
            return;
        }

        if (!heroShown) {
            stopAmbientAnimation();
            hero.animate().cancel();
            ambient.animate().cancel();
            fillHero(position, label);
            hero.setY(y);
            hero.setTranslationX(0f);
            hero.animate().alpha(1f).setDuration(260).start();
            if (ambientGlow) {
                setAmbient(color);
                ambient.animate().alpha(1f).setDuration(600).start();
            }
            heroShown = true;
        } else if (changed) {
            if (advancedEffects) hero.animate().cancel();
            hero.animate().alpha(0f).translationX(-12 * density).setDuration(120).withEndAction(() -> {
                fillHero(position, label);
                hero.setY(y);
                hero.setTranslationX(12 * density);
                hero.animate().alpha(1f).translationX(0f).setDuration(200).start();
            }).start();
            if (ambientGlow && advancedEffects) {
                transitionAmbient(color);
            } else if (ambientGlow && color != ambientColor) {
                ambient.animate().alpha(0.35f).setDuration(150).withEndAction(() -> {
                    setAmbient(color);
                    ambient.animate().alpha(1f).setDuration(300).start();
                }).start();
            }
        } else {
            hero.setY(y);
        }
    }

    /** The shelf's text for this dock app: its name and, on the cinematic shelf, "Last opened" and the dots. */
    private void fillHero(int position, String label) {
        heroName.setText(label);
        if (!cinematic) return;
        updateHeroMeta(position);
        updateHeroDots(position);
    }

    // ---- cinematic shelf -------------------------------------------------------------------------

    /**
     * The parts that never change: a larger name, the meta line and the dots, the artwork and the scrim, at
     * scrimPercent of its strength (cinematic_scrim). cinematic_art 0 leaves the artwork out, 0 percent the scrim.
     */
    private void setUpCinematicShelf(int scrimPercent) {
        heroName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 68f);
        heroName.setLetterSpacing(-0.025f);
        heroMeta.setVisibility(View.INVISIBLE);   // keeps its height, so the name does not jump when it fills in
        heroDots.setVisibility(heroDotsShown ? View.VISIBLE : View.GONE);
        heroArt.setVisibility(heroArtAlpha > 0f ? View.VISIBLE : View.GONE);
        // One stretched bitmap: apply its alpha while drawing instead of through an offscreen buffer.
        heroArt.forceHasOverlappingRendering(false);
        if (scrimPercent <= 0) return;   // cinematic_scrim 0: no scrim at all
        View scrim = findViewById(R.id.heroScrim);
        Drawable shade = scrimDrawable();
        // On the bitmap's paint, not the view: a view's alpha would draw the full screen through an offscreen buffer.
        shade.setAlpha(scrimPercent * 255 / 100);
        scrim.setBackground(shade);
        scrim.setVisibility(View.VISIBLE);
    }

    /**
     * Darkens the side the shelf's text is on and the lower part of the screen, so the name, the dock and
     * the grid stay readable over the artwork. One small bitmap stretched over the screen, like the glow.
     */
    private Drawable scrimDrawable() {
        int w = 480, h = 270;
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        boolean rtl = getResources().getBoolean(R.bool.rtl_menu_bar);
        Paint paint = new Paint();
        paint.setShader(new LinearGradient(rtl ? w : 0, 0, rtl ? 0 : w, 0,
                new int[]{0xD1060406, 0x59060406, 0x00060406}, new float[]{0f, 0.42f, 0.7f}, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, paint);
        paint.setShader(new LinearGradient(0, 0, 0, h,
                new int[]{0x00060406, 0x00060406, 0xDB060406, 0xFF060406}, new float[]{0f, 0.28f, 0.58f, 0.76f},
                Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, paint);
        BitmapDrawable scrim = new BitmapDrawable(getResources(), bmp);
        scrim.setGravity(Gravity.FILL);
        return scrim;
    }

    /** Swaps the backdrop to this app's artwork: fade out, swap, fade in, the way the shelf's name changes. */
    private void showHeroArt(int position) {
        String key = adapter.keyAt(position);
        if (key == null) return;
        heroArt.animate().cancel();
        if (key.equals(heroArtKey)) {
            fadeHeroArt(420);
            return;
        }
        Bitmap art = heroArtCache.get(key);
        if (art == null) {
            art = HeroArt.from(adapter.artworkAt(position));
            if (art != null) heroArtCache.put(key, art);
        }
        heroArtKey = key;
        final Bitmap next = art;
        if (!animations || reducedMotion || heroArt.getAlpha() == 0f) {
            heroArt.setImageBitmap(next);
            fadeHeroArt(600);
            return;
        }
        heroArt.animate().alpha(0f).setDuration(140).withEndAction(() -> {
            heroArt.setImageBitmap(next);
            heroArt.animate().alpha(heroArtAlpha).setDuration(420).start();
        }).start();
    }

    private void fadeHeroArt(int ms) {
        if (!animations) heroArt.setAlpha(heroArtAlpha);
        else heroArt.animate().alpha(heroArtAlpha).setDuration(reducedMotion ? Math.min(ms, Motion.REDUCED_MS) : ms).start();
    }

    /** "Last opened yesterday, 9:40 PM", the time in white; nothing (but the same height) when it was never seen. */
    private void updateHeroMeta(int position) {
        long when = LastOpened.get(this, adapter.packageAt(position));
        if (when <= 0) {
            heroMeta.setVisibility(View.INVISIBLE);
            return;
        }
        String rel = LastOpened.describe(this, when);
        String text = getString(R.string.last_opened, rel);
        SpannableString line = new SpannableString(text);
        int at = text.indexOf(rel);
        if (at >= 0) line.setSpan(new ForegroundColorSpan(0xFFFFFFFF), at, at + rel.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        heroMeta.setText(line);
        heroMeta.setVisibility(View.VISIBLE);
    }

    /** One dot per dock app, the focused one drawn long and white. Hebrew docks start on the right, so do the dots. */
    private void updateHeroDots(int position) {
        if (!heroDotsShown) return;
        int count = Math.min(adapter.dockFilled(), COLUMNS);
        heroDots.setVisibility(count > 1 ? View.VISIBLE : View.INVISIBLE);
        if (heroDots.getChildCount() != count) {
            heroDots.removeAllViews();
            for (int i = 0; i < count; i++) {
                View dot = new View(this);
                GradientDrawable bg = new GradientDrawable();
                bg.setCornerRadius(3 * density);
                dot.setBackground(bg);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams((int) (6 * density), (int) (6 * density));
                if (i > 0) lp.leftMargin = (int) (6 * density);
                heroDots.addView(dot, lp);
            }
        }
        for (int i = 0; i < count; i++) {
            View dot = heroDots.getChildAt(rtlAppGrid ? count - 1 - i : i);
            boolean on = i == position;
            ((GradientDrawable) dot.getBackground()).setColor(on ? 0xFFFFFFFF : 0x4DFFFFFF);
            ViewGroup.LayoutParams lp = dot.getLayoutParams();
            int w = (int) ((on ? 20 : 6) * density);
            if (lp.width != w) {
                lp.width = w;
                dot.setLayoutParams(lp);
            }
        }
    }

    /**
     * The glow used to be a full-screen radial GradientDrawable. On Android 10 that drawable is
     * drawn on the CPU, so fading it for half a second redrew 1920×1080 every frame and the
     * highlight looked like it was running at a few frames per second. A small bitmap stretched
     * over the screen is one texture. It is painted once in white and tinted with the app's colour,
     * so changing apps neither repaints it on the CPU (about 11 ms) nor uploads it again.
     */
    private void setAmbient(int color, float cx, float cy) {
        ambientColor = color;
        int rgb = color & 0x00FFFFFF;
        boolean isReorderTile = (rgb == 0x00666666);
        Drawable current = ambient.getBackground();
        GlowDrawable glow;
        if (current instanceof GlowDrawable) {
            glow = (GlowDrawable) current;
        } else {
            glow = new GlowDrawable();
            ambient.setBackground(glow);
        }
        glow.setLook(rgb, isReorderTile);
        if (haloView == null) {
            float screenH = getResources().getDisplayMetrics().heightPixels;
            haloView = new HaloView(this, screenH);
            ((ViewGroup) ambient).addView(haloView, new FrameLayout.LayoutParams(1, 1));
            haloView.fitSize();
        }
        haloView.setLook(rgb, isReorderTile);
        haloCx = cx;
        haloCy = cy;
        haloView.centreOn(cx, cy);
    }

    /** The glow right behind the focused icon: a small view of its own that is moved with the icon. */
    private HaloView haloView;
    private float haloCx;
    private float haloCy;

    /** Locks the halo to the focused icon's real on-screen position: the row scrolls, springs and zooms under it. */
    private void syncHalo() {
        View anchor = glowAnchor;
        if (anchor == null || !anchor.isAttachedToWindow() || !anchor.isFocused() || anchor.getWidth() == 0) return;
        if (haloView == null) return;
        glowRect.set(0, 0, anchor.getWidth(), anchor.getHeight());
        root.offsetDescendantRectToMyCoords(anchor, glowRect);
        float x = glowRect.exactCenterX(), y = glowRect.exactCenterY();
        if (Math.abs(x - haloCx) < 0.5f && Math.abs(y - haloCy) < 0.5f) return;
        haloCx = x;
        haloCy = y;
        haloView.centreOn(x, y);
    }

    private void setAmbient(int color) {
        // A colour change keeps the halo where the focused icon is, whatever the row has scrolled by since.
        if (haloView != null) {
            setAmbient(color, haloCx, haloCy);
            return;
        }
        float cx = root != null ? root.getWidth() / 2f : 0;
        float cy = root != null ? root.getHeight() * 0.55f : 0;
        setAmbient(color, cx, cy);
    }

    private void transitionAmbient(int color) {
        // A rapid focus change continues from the colour actually displayed on screen.
        stopAmbientAnimation();
        ambient.animate().cancel();
        if (color == ambientColor) {
            ambient.animate().alpha(1f).setDuration(180).start();
            return;
        }
        ambient.animate().alpha(1f).setDuration(180).start();
        ambientAnimator = ValueAnimator.ofObject(new ArgbEvaluator(), ambientColor, color);
        ambientAnimator.setDuration(300);
        ambientAnimator.addUpdateListener(animation -> setAmbient((int) animation.getAnimatedValue()));
        ambientAnimator.start();
    }

    private void stopAmbientAnimation() {
        if (ambientAnimator != null) {
            ambientAnimator.cancel();
            ambientAnimator = null;
        }
    }

    /**
     * Grid tiles (every tile with dock_row false): the glow behind the grid takes the focused tile's colour; the big
     * name above the dock does not show (every grid tile has its name underneath).
     */
    private void showGlowOnly(int position, float cx, float cy) {
        int color = adapter.ambientColorAt(position);
        stopAmbientAnimation();
        hero.animate().cancel();
        ambient.animate().cancel();
        hero.setAlpha(0f);
        heroArt.animate().cancel();
        heroArt.setAlpha(0f);
        heroShown = false;
        if (!ambientGlow) return;
        if (!animations || reducedMotion) {
            setAmbient(color, cx, cy);
            ambient.setAlpha(1f);
            return;
        }
        if (color == ambientColor && ambient.getBackground() != null) {
            ambient.animate().alpha(1f).setDuration(160).start();
            return;
        }
        // Smooth cross-fade: quick dip and bloom into new brand color & position (background stays steady, no pulsing)
        ambient.animate().alpha(0.15f).setDuration(ambient.getAlpha() > 0.15f ? 80 : 0).withEndAction(() -> {
            setAmbient(color, cx, cy);
            ambient.animate().alpha(1f).setDuration(220).start();
        }).start();
    }

    private void showGlowOnly(int position) {
        float cx = root != null ? root.getWidth() / 2f : 0;
        float cy = root != null ? root.getHeight() * 0.55f : 0;
        showGlowOnly(position, cx, cy);
    }

    private void hideHero() {
        if (!heroShown && ambient.getAlpha() == 0f) return;
        stopAmbientAnimation();
        hero.animate().cancel();
        ambient.animate().cancel();
        heroArt.animate().cancel();
        heroShown = false;
        if (animations && !reducedMotion) {
            hero.animate().alpha(0f).setDuration(160).start();
            if (ambientGlow) ambient.animate().alpha(0f).setDuration(300).start();
            heroArt.animate().alpha(0f).setDuration(300).start();
        } else {
            hero.setAlpha(0f);
            ambient.setAlpha(0f);
            heroArt.setAlpha(0f);
        }
    }

    private void hideGlass() {
        if (!glassShown) return;
        glassShown = false;
        glass.animate().cancel();
        if (animations && !reducedMotion) glass.animate().alpha(0f).setDuration(160).start();
        else glass.setAlpha(0f);
    }

    // ---- optional system stats (config.xml show_stats) --------------------------------------------

    /** Background reader. Started only while Home is resumed and show_stats is on. */
    private HandlerThread statsThread;
    /** The current run's handler; a run whose handler is no longer this one stops (volatile: read off-thread). */
    private volatile Handler statsBg;

    private void startStats() {
        if (statsThread != null) return;
        stats.setVisibility(View.VISIBLE);
        cpuTotalPrev = -1;
        idlePrev = -1;
        procStatDead = false;
        statsThread = new HandlerThread("stats");
        statsThread.start();
        // Each start gets its own handler, held by its task: stopStats on the main thread can clear the field
        // at any moment without the reader tripping over it.
        Handler bg = new Handler(statsThread.getLooper());
        statsBg = bg;
        bg.post(new Runnable() {
            @Override
            public void run() {
                if (statsBg != bg) return;
                String line = readStatsLine();
                handler.post(() -> {
                    if (showStats && stats != null && statsBg == bg) stats.setText(line);
                });
                if (statsBg == bg) bg.postDelayed(this, STATS_MS);
            }
        });
    }

    private void stopStats() {
        if (statsThread == null) return;
        statsBg.removeCallbacksAndMessages(null);
        statsThread.quit();
        statsThread = null;
        statsBg = null;
        // Paused (the quick panel or an app on top): keep the last line on screen. Taking it away moved the grid up
        // under a glass highlight that stayed put.
        if (!showStats) stats.setVisibility(View.GONE);
    }

    /** One quiet line, e.g. "CPU 8%  ·  Memory 430 MB free  ·  Storage 3.1 GB free". Runs off the main thread. */
    private static void appendStat(StringBuilder line, String part) {
        if (line.length() > 0) line.append("  ·  ");
        line.append(part);
    }

    private String readStatsLine() {
        int cpu = cpuPercent();
        if (cpu < 0) {
            try {
                Thread.sleep(200);             // second sample, so the first line already has a CPU figure
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "";
            }
            cpu = cpuPercent();
        }
        // Android 8 and later hide /proc/stat from apps on many TVs: then the line leaves CPU out rather than
        // showing a dash that never fills in.
        StringBuilder line = new StringBuilder();
        if (cpu >= 0) line.append(getString(R.string.stats_cpu, cpu));
        long[] mem = memUsedOfTotalMb();
        if (mem != null) appendStat(line, getString(R.string.stats_memory, mem[0], mem[1]));
        String free = formatGb(getResources(), Environment.getDataDirectory().getFreeSpace());
        if (!free.isEmpty()) appendStat(line, getString(R.string.stats_storage, free));
        return StatsText.forLocale(line.toString(), getResources().getConfiguration().getLocales().get(0));
    }

    // -1: need another sample. -2: this source cannot be read (Android 9 hides /proc/stat from apps).
    private static final int CPU_UNREADABLE = -2;
    private long cpuTotalPrev = -1, cpuIdlePrev = -1;
    private boolean procStatDead;
    private long idlePrev = -1, wallPrev = -1;

    private int cpuPercent() {
        if (!procStatDead) {
            int fromStat = cpuFromProcStat();
            if (fromStat != CPU_UNREADABLE) return fromStat;
            procStatDead = true;
            idlePrev = -1;
        }
        return cpuFromIdle();
    }

    private int cpuFromProcStat() {
        String s = readFirstLine("/proc/stat");
        if (s == null || !s.startsWith("cpu")) return CPU_UNREADABLE;
        String[] t = s.trim().split("\\s+");
        if (t.length < 5) return CPU_UNREADABLE;
        try {
            long user = Long.parseLong(t[1]), nice = Long.parseLong(t[2]), sys = Long.parseLong(t[3]);
            long idle = Long.parseLong(t[4]);
            long iowait = t.length > 5 ? Long.parseLong(t[5]) : 0;
            long irq = t.length > 6 ? Long.parseLong(t[6]) : 0;
            long soft = t.length > 7 ? Long.parseLong(t[7]) : 0;
            long total = user + nice + sys + idle + iowait + irq + soft;
            long idleAll = idle + iowait;
            if (cpuTotalPrev < 0) {
                cpuTotalPrev = total;
                cpuIdlePrev = idleAll;
                return -1;
            }
            long dt = total - cpuTotalPrev, di = idleAll - cpuIdlePrev;
            cpuTotalPrev = total;
            cpuIdlePrev = idleAll;
            if (dt <= 0) return -1;
            int busy = (int) Math.round((dt - di) * 100.0 / dt);
            return Math.max(0, Math.min(100, busy));
        } catch (NumberFormatException e) {
            return CPU_UNREADABLE;
        }
    }

    /** Busy percent from cpuidle time (microseconds) across the cores. -1 until the second sample. */
    private int cpuFromIdle() {
        long idle = 0;
        int cores = 0;
        for (int i = 0; i < 8; i++) {
            boolean any = false;
            for (int state = 0; state < 8; state++) {
                String t = readFirstLine("/sys/devices/system/cpu/cpu" + i
                        + "/cpuidle/state" + state + "/time");
                if (t == null) break;
                try {
                    idle += Long.parseLong(t.trim());
                    any = true;
                } catch (NumberFormatException e) {
                    break;
                }
            }
            if (!any) break;
            cores++;
        }
        if (cores == 0) return -1;
        long wall = SystemClock.elapsedRealtime() * 1000L;
        if (idlePrev < 0) {
            idlePrev = idle;
            wallPrev = wall;
            return -1;
        }
        long di = idle - idlePrev;
        long dw = (wall - wallPrev) * cores;
        idlePrev = idle;
        wallPrev = wall;
        if (dw <= 0) return -1;
        int busy = (int) Math.round((dw - di) * 100.0 / dw);
        return Math.max(0, Math.min(100, busy));
    }

    /** RAM usage as {used, total} in MB, used = MemTotal - MemAvailable, e.g. "945 / 1536 MB". Used over
     *  free so the line reads as a load against a known whole; null when /proc/meminfo cannot be read. */
    private long[] memUsedOfTotalMb() {
        long available = -1, total = -1;
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader("/proc/meminfo"))) {
            String line;
            String[] t;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("MemAvailable:")) {
                    t = line.split("\\s+");
                    if (t.length >= 2) available = Long.parseLong(t[1]) / 1024;
                } else if (line.startsWith("MemTotal:")) {
                    t = line.split("\\s+");
                    if (t.length >= 2) total = Long.parseLong(t[1]) / 1024;
                }
            }
        } catch (IOException | NumberFormatException ignored) {
        }
        if (available < 0 || total <= 0) return null;
        return new long[]{Math.max(0, total - available), total};
    }

    private static String readFirstLine(String path) {
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(path))) {
            return r.readLine();
        } catch (IOException e) {
            return null;
        }
    }

    private static String formatGb(Resources res, long bytes) {
        if (bytes <= 0) return "";
        double gb = bytes / (1024.0 * 1024 * 1024);
        return gb >= 10 ? res.getString(R.string.storage_gb_whole, gb)
                : res.getString(R.string.storage_gb_decimal, gb);
    }

    // ---- navigation ------------------------------------------------------------------------------

    @Override
    public boolean onTileVerticalKey(int position, int keyCode) {
        // In reorder mode the dimmed Reorder tile is not a place to go: the apps are the whole row.
        int totalCount = reordering ? adapter.appCount() : adapter.getItemCount();
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            boolean forward = rtlAppGrid ? keyCode == KeyEvent.KEYCODE_DPAD_LEFT : keyCode == KeyEvent.KEYCODE_DPAD_RIGHT;
            if (forward ? position >= totalCount - 1 : position <= 0) return true;
            // The next tile may not be laid out yet while the row scrolls under a fast key repeat; without this the focus
            // search would jump to the top bar pill that lies to the right.
            int next = forward ? position + 1 : position - 1;
            if (grid.findViewHolderForAdapterPosition(next) != null) return false;
            grid.scrollToPosition(next);
            grid.post(() -> {
                RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(next);
                View card = vh == null ? null : vh.itemView.findViewById(R.id.card);
                if (card != null) card.requestFocus();
            });
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            if (reordering) return true;
            return false; // let focus move up to the top bar
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (reordering && btnDoneReorder != null && btnDoneReorder.getVisibility() == View.VISIBLE) {
                btnDoneReorder.requestFocus();
                return true;
            }
            return true; // single row: no rows below
        }
        return false;
    }

    /** Lets the top bar take focus again, once the first focus has gone to the app row. */
    private void openTopBar() {
        ViewGroup bar = findViewById(R.id.topButtons);
        if (bar != null && bar.getDescendantFocusability() != ViewGroup.FOCUS_AFTER_DESCENDANTS) {
            bar.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        }
    }

    @Override public void onLibraryFocused() {
        openTopBar();
        libraryFocusPending = false;
        hideGlass();
        hideHero();
    }

    /** The first top-bar button after Inputs that is shown: the network pill, Reorder, then Settings (always there). */
    private View firstTopButton() {
        if (btnWifi.getVisibility() == View.VISIBLE) return btnWifi;
        if (btnReorder.getVisibility() == View.VISIBLE) return btnReorder;
        return findViewById(R.id.btnSettings);
    }

    @Override public void onLibraryUp() {
        if (adapter.appCount() == 0) { firstTopButton().requestFocus(); return; }
        focusTile(Math.min(Math.max(0, lastFocused), adapter.appCount() - 1));
    }

    private void focusLibrary() {
        cancelPendingFocusScroll();
        int position = adapter.appCount();
        if (!adapter.isLibraryAt(position)) return;
        RecyclerView.ViewHolder holder = grid.findViewHolderForAdapterPosition(position);
        if (holder != null) {
            View button = holder.itemView.findViewById(R.id.btnLibrary);
            Rect visible = new Rect();
            if (button.getGlobalVisibleRect(visible) && visible.height() >= button.getHeight()) {
                if (button.requestFocus()) libraryFocusPending = false;
                return;
            }
        }
        if (!animations) {
            grid.scrollToPosition(position);
            grid.post(() -> {
                if (appLibrary != null || !hasWindowFocus()) return;
                RecyclerView.ViewHolder footer = grid.findViewHolderForAdapterPosition(adapter.appCount());
                if (footer != null && adapter.isLibraryAt(adapter.appCount())
                        && footer.itemView.findViewById(R.id.btnLibrary).requestFocus()) libraryFocusPending = false;
            });
            return;
        }
        pendingFocusScroll = new RecyclerView.OnScrollListener() {
            @Override public void onScrollStateChanged(@NonNull RecyclerView rv, int state) {
                if (state != RecyclerView.SCROLL_STATE_IDLE) return;
                rv.removeOnScrollListener(this);
                if (pendingFocusScroll != this) return;
                pendingFocusScroll = null;
                RecyclerView.ViewHolder footer = rv.findViewHolderForAdapterPosition(position);
                if (footer != null && footer.itemView.findViewById(R.id.btnLibrary).requestFocus())
                    libraryFocusPending = false;
            }
        };
        grid.addOnScrollListener(pendingFocusScroll);
        grid.smoothScrollToPosition(position);
    }

    private void cancelPendingFocusScroll() {
        if (pendingFocusScroll != null) {
            grid.removeOnScrollListener(pendingFocusScroll);
            pendingFocusScroll = null;
            grid.stopScroll();
        }
    }

    private void focusTile(int position) {
        libraryFocusPending = false;
        cancelPendingFocusScroll();
        if (adapter.appCount() == 0) {
            openTopBar();
            if (adapter.isLibraryAt(0)) focusLibrary();
            else firstTopButton().requestFocus();
            return;
        }
        final int pos = adapter.focusablePosition(Math.max(0, Math.min(position, adapter.appCount() - 1)));
        RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(pos);
        if (vh != null) {
            View card = vh.itemView.findViewById(R.id.card);
            // Already focused (e.g. back from an app): no focus change fires, so restore the glass by hand.
            if (card.isFocused()) onTileFocused(pos, card);
            else card.requestFocus();
            return;
        }
        // Not laid out yet: scroll there, then focus once the scroll settles.
        if (!animations) {
            grid.scrollToPosition(pos);
            grid.post(() -> {
                RecyclerView.ViewHolder h = grid.findViewHolderForAdapterPosition(pos);
                if (h != null) h.itemView.findViewById(R.id.card).requestFocus();
            });
            return;
        }
        pendingFocusScroll = new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView rv, int newState) {
                if (newState != RecyclerView.SCROLL_STATE_IDLE) return;
                rv.removeOnScrollListener(this);
                if (pendingFocusScroll != this) return;
                pendingFocusScroll = null;
                RecyclerView.ViewHolder h = rv.findViewHolderForAdapterPosition(pos);
                View target = h != null ? h.itemView.findViewById(R.id.card) : rv.getChildAt(0);
                if (target != null) target.requestFocus();
            }
        };
        grid.addOnScrollListener(pendingFocusScroll);
        grid.smoothScrollToPosition(pos);
    }

    // ---- top bar ---------------------------------------------------------------------------------

    /** Some TVs (Coocaa OS) set the network time without ACTION_TIME_CHANGED, so keep checking until it is valid. */
    private final Runnable clockCheck = this::updateClockVisibility;

    private void updateClockVisibility() {
        boolean valid = System.currentTimeMillis() >= TIME_VALID_AFTER_MS;
        clockBlock.setVisibility(valid ? View.VISIBLE : View.INVISIBLE);
        handler.removeCallbacks(clockCheck);
        if (valid) {
            updateGreeting();
        } else {
            handler.postDelayed(clockCheck, 1_000L);
        }
    }

    private void updateGreeting() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        int res;
        // Locale-specific day parts (app language on Android 13+).
        // Indonesian: Pagi 00-10, Siang 10-15, Sore 15-18, Malam 18-24.
        // Spain (es-ES) and Catalan: "Buenas tardes" into the evening; "Buenas noches" from 21.
        // Latin American Spanish (es-MX, es-AR, …) keeps the default hours: evening strings are also
        // «Buenas noches», and there «Buenas noches» from ~17 matches local use better than Spain's 21.
        Locale locale = getResources().getConfiguration().getLocales().get(0);
        String lang = locale.getLanguage();
        String country = locale.getCountry();
        if ("in".equals(lang) || "id".equals(lang)) {
            if (hour < 10) res = R.string.good_morning;
            else if (hour < 15) res = R.string.good_afternoon;
            else if (hour < 18) res = R.string.good_evening;
            else res = R.string.good_night;
        } else if (("es".equals(lang) && "ES".equals(country)) || "ca".equals(lang)) {
            if (hour < 6) res = R.string.good_night;
            else if (hour < 12) res = R.string.good_morning;
            else if (hour < 21) res = R.string.good_afternoon;
            else res = R.string.good_night;
        } else {
            if (hour < 5) res = R.string.good_night;
            else if (hour < 12) res = R.string.good_morning;
            else if (hour < 17) res = R.string.good_afternoon;
            else if (hour < 22) res = R.string.good_evening;
            else res = R.string.good_night;
        }
        String festiveGreeting = festive ? festival.greetingText(getResources()) : "";
        if (!festiveGreeting.isEmpty()) {
            greeting.setText(festiveGreeting);
            greeting.setTextColor(liftTowardsWhite(festival.accentColor(), 0.25f));
        } else {
            greeting.setText(greetingName.isEmpty() ? getString(res)
                    : getString(R.string.greeting_with_name, getString(res), greetingName));
            greeting.setTextColor(greetingColor);
        }
        if (shamsi) shamsiDate.setText(Shamsi.today(getResources()));
    }

    // ---- race-weekend panel (a theme's race_calendar) -----------------------------------------------

    private static final int RACE_RED = 0xFFFF3B2F;

    /**
     * Shows or hides the race-weekend panel for this minute. Text only, set once a minute with the clock:
     * nothing animates, so the panel costs the GPU nothing between ticks.
     */
    private void updateRace() {
        if (raceCalendar.isEmpty()) return;
        RaceWeekend.State s = RaceWeekend.at(raceCalendar, java.time.Instant.now());
        if (s == null) {
            racePanel.setVisibility(View.GONE);
            return;
        }
        RaceWeekend.Race race = s.race;
        raceKicker.setText(race.place.isEmpty()
                ? getString(R.string.race_kicker_no_place, race.round, race.shortName())
                : getString(R.string.race_kicker, race.round, race.shortName(), race.place));

        if (s.live != null) {
            SpannableString live = new SpannableString("\u25CF  " + getString(R.string.race_live, s.live.title(getResources())));
            live.setSpan(new android.text.style.ForegroundColorSpan(RACE_RED), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            raceHeadline.setText(live);
        } else if (s.next != null) {
            String in = RaceWeekend.countdown(getResources(), s.untilNext);
            raceHeadline.setText(s.next.isRace() ? getString(R.string.race_lights_out, in)
                    : getString(R.string.race_next, s.next.title(getResources()), in));
        } else {
            raceHeadline.setText(R.string.race_finished);
        }

        // FP1 FRI 11:00 PM   FP2 SAT 2:30 AM ...: done sessions fade, the live one is red, the next one bright.
        java.time.format.DateTimeFormatter when = java.time.format.DateTimeFormatter.ofPattern(
                android.text.format.DateFormat.is24HourFormat(this) ? "EEE HH:mm" : "EEE h:mm a", Locale.getDefault())
                .withZone(java.time.ZoneId.systemDefault());
        android.text.SpannableStringBuilder row = new android.text.SpannableStringBuilder();
        for (int i = 0; i < race.sessions.size(); i++) {
            RaceWeekend.Session session = race.sessions.get(i);
            if (i > 0) row.append("     ");
            int start = row.length();
            row.append(session.shortName(getResources())).append("  ").append(when.format(session.start).toUpperCase(Locale.getDefault()));
            int color;
            switch (s.statuses.get(i)) {
                case DONE: color = 0x47FFFFFF; break;
                case LIVE: color = RACE_RED; break;
                case NEXT: color = 0xFFFFFFFF; break;
                default: color = 0x99FFFFFF;
            }
            row.setSpan(new android.text.style.ForegroundColorSpan(color), start, row.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        raceSessions.setText(row);
        racePanel.setVisibility(View.VISIBLE);
    }

    private void refreshPillsAfterNextDraw() {
        root.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                root.getViewTreeObserver().removeOnPreDrawListener(this);
                // Posted from inside the frame's traversal, so it runs after that frame is drawn. A post straight
                // from onResume would run first: the frame is only scheduled after onResume returns.
                handler.post(() -> {
                    if (isDestroyed()) return;
                    updateWifiLabel();
                    updateInputsButton();
                });
                return true;
            }
        });
        root.invalidate();   // a resume that changes nothing on screen still draws once, so the refresh runs
    }

    /** Shows the Wi-Fi network name on the button when we can read it, otherwise the connection type. */
    private void updateWifiLabel() {
        String text = getString(R.string.wifi);
        boolean vpn = false, online = false;
        int icon = R.drawable.ic_wifi;
        // The diya's flame: 0 is out (offline), 1 to 4 follow the Wi-Fi signal, Ethernet burns steady at 4.
        int flame = 0;
        boolean steady = false;
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            boolean wifi = false, ethernet = false;
            // All networks, not just the active one: with a VPN up the active network is the VPN itself.
            for (Network net : cm != null ? cm.getAllNetworks() : new Network[0]) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(net);
                if (caps == null) continue;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    vpn = true;
                    continue;
                }
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue;
                online = true;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) ethernet = true;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) wifi = true;
            }
            if (!online) {
                text = getString(R.string.offline);
                vpn = false;
            } else if (ethernet) {
                text = getString(R.string.ethernet);
                icon = R.drawable.ic_ethernet;
                flame = 4;
                steady = true;
            } else if (wifi) {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                WifiInfo info = wm != null ? wm.getConnectionInfo() : null;
                String ssid = info != null ? info.getSSID() : null;
                flame = info != null ? Math.max(1, Math.min(4, WifiManager.calculateSignalLevel(info.getRssi(), 5))) : 3;
                if (ssid != null) {
                    ssid = ssid.replace("\"", "").trim();
                    if (!ssid.isEmpty() && !ssid.contains("unknown") && !ssid.equals("0x")) text = ssid;
                }
            }
        } catch (Exception ignored) {
        }
        // With a VPN app configured, a VPN that is down matters too: show a red "VPN off" capsule then.
        boolean vpnDown = !vpn && online && !vpnApp.isEmpty();
        if (vpn || vpnDown) {
            // Network name, then the green lock-and-VPN (or red "VPN off") capsule drawn in place of the word.
            String badge = getString(vpn ? R.string.vpn : R.string.vpn_off);
            VpnBadge span = new VpnBadge(vpn ? getDrawable(R.drawable.ic_lock) : null, density);
            // The pill has a maximum width and ellipsizes at the end, which would cut the badge off after
            // a long network name, so shorten the name to whatever room the badge leaves. The total paddings
            // include the Wi-Fi icon.
            TextPaint paint = btnWifi.getPaint();
            float room = btnWifi.getMaxWidth() - btnWifi.getTotalPaddingStart() - btnWifi.getTotalPaddingEnd()
                    - span.getSize(paint, badge, 0, badge.length(), null) - paint.measureText(" ") - 2 * density;
            String name = TextUtils.ellipsize(text, paint, Math.max(0, room), TextUtils.TruncateAt.END).toString();
            // A long badge ("VPN отключён") can leave room for a stub like "MT…", which says nothing and, after
            // rounding, can push the badge itself out of the pill. Show the badge alone then.
            // The space stays even then: a line holding only the span gets no font metrics and collapses.
            text = name.length() < text.length() && name.length() < 5 ? "" : name;
            SpannableString label = new SpannableString(text + " " + badge);
            label.setSpan(span, text.length() + 1, label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (!TextUtils.equals(btnWifi.getText(), label)) btnWifi.setText(label);
        } else if (!TextUtils.equals(btnWifi.getText(), text)) {
            btnWifi.setText(text);
        }
        if (wifiIconOnly) {
            btnWifi.setContentDescription(btnWifi.getText());
            btnWifi.setText(null);
        }
        if (festive && diya != null) {
            if (online && flame == 0) flame = 3;   // online some other way (cellular, USB tethering)
            diya.setSteady(steady);
            diya.setFlame(flame);
        } else {
            if (icon != wifiIcon) {
                wifiIcon = icon;
                btnWifi.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0);
            }
            ColorStateList tint = online ? wifiOnlineTint : wifiPlainTint;
            if (wifiOnlineTint != null && btnWifi.getCompoundDrawableTintList() != tint) {
                btnWifi.setCompoundDrawableTintList(tint);
            }
        }
    }

    /** config.xml icon_only_buttons. */
    private boolean iconOnlyButtons;
    /** config.xml top_menu: Inputs, Wi-Fi and Settings folded into one gear. */
    private boolean topMenu;
    /** icon_only_buttons without wifi_label_width: the Wi-Fi pill shows no network name. */
    private boolean wifiIconOnly;
    /** config.xml wifi_status_color: the Wi-Fi icon's green while online and its usual pill colours; null while off. */
    private ColorStateList wifiOnlineTint, wifiPlainTint;
    /** The app library button in the top bar (library_button). */
    private View btnLibraryTop;
    /** The open library came from that button: closing it returns there instead of scrolling to the grid's end. */
    private boolean libraryFromTopBar;

    /** Turns a labelled top pill into the round TopButtonIcon shape: no text, the icon centred by even padding. */
    private void makeIconOnly(TextView pill) {
        pill.setContentDescription(pill.getText());
        pill.setText(null);
        pill.setMaxWidth(Integer.MAX_VALUE);
        int pad = Math.round(10 * density);
        pill.setPaddingRelative(pad, 0, pad, 0);
        pill.setCompoundDrawablePadding(0);
        pill.getLayoutParams().width = Math.round(42 * density);
    }

    // ---- festive skin ------------------------------------------------------------------------------

    /**
     * Swaps the pill icons for the festival's: a bell for Inputs that swings when focused, a diya for the
     * network whose flame follows the connection, and a marigold for Settings that turns when focused.
     */
    private void applyFestiveSkin(TextView inputs, TextView settings) {
        bell = new BellDrawable(density);
        marigold = new MarigoldDrawable(density);
        diya = new DiyaDrawable(density);
        bell.setReducedMotion(reducedMotion);
        marigold.setReducedMotion(reducedMotion);
        diya.setReducedMotion(reducedMotion);
        setFestiveIcon(inputs, bell);
        setFestiveIcon(btnWifi, diya);
        setFestiveIcon(settings, marigold);
        addFocusAction(inputs, bell::ring);
        addFocusAction(settings, marigold::turn);
    }

    private static void setFestiveIcon(TextView pill, Drawable icon) {
        // The pills tint their icons to follow focus; the festive ones keep their own colours.
        pill.setCompoundDrawableTintList(null);
        pill.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, null, null, null);
    }

    /** Runs {@code action} when the view gains focus, keeping any focus listener it already had. */
    private static void addFocusAction(View view, Runnable action) {
        View.OnFocusChangeListener previous = view.getOnFocusChangeListener();
        view.setOnFocusChangeListener((v, focused) -> {
            if (previous != null) previous.onFocusChange(v, focused);
            if (focused) action.run();
        });
    }

    private void playPetalShower() {
        if (isFinishing() || isDestroyed()) return;
        PetalShowerView shower = new PetalShowerView(this);
        root.addView(shower, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        shower.play(60, 2200, () -> root.removeView(shower));
    }

    private static int liftTowardsWhite(int argb, float amount) {
        int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
        r += (int) ((255 - r) * amount);
        g += (int) ((255 - g) * amount);
        b += (int) ((255 - b) * amount);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** The network pill opens the VPN app when one is configured and installed, otherwise Wi-Fi settings. */
    private void openNetworkPill() {
        Intent i = vpnApp.isEmpty() ? null : getPackageManager().getLeanbackLaunchIntentForPackage(vpnApp);
        if (i == null && !vpnApp.isEmpty()) i = getPackageManager().getLaunchIntentForPackage(vpnApp);
        // The network screen (or the VPN app) the same way as Settings: in our own task, with the dim and fade. As a new
        // task it left Home stopped behind it and the Mi TV Stick (Android 9) showed a black screen.
        if (i != null) open(i);
        else openSliding(systemIntent(Settings.ACTION_WIFI_SETTINGS));
    }


    /** Every settings entry shares the standard intent and the vendor fallback (SystemSettings). */
    private Intent systemIntent(String standardAction) {
        return SystemSettings.intent(this, standardAction);
    }

    /** Hides the Inputs pill when it would have nothing to list: streaming boxes have no HDMI-in, AV or tuner. */
    private void updateInputsButton() {
        if (!showInputsPill) {
            btnInputs.setVisibility(View.GONE);
            return;
        }
        boolean any = liveTvIntent() != null || !sourcePanelService.isEmpty()
                || (coocaaSources && !CoocaaSources.list(this).isEmpty());
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (!any && tim != null) {
            for (TvInputInfo info : tim.getTvInputList()) {
                // Inputs hidden in Settings > Inputs are left out of the menu, so they don't count here either.
                if (info.isPassthroughInput() && !info.isHidden(this)) {
                    any = true;
                    break;
                }
            }
        }
        btnInputs.setVisibility(any ? View.VISIBLE : View.GONE);
    }

    /** boot_to_live_tv: the first time Home comes up after the TV starts, open the Live TV app instead. Once per
     *  boot, keyed on Settings.Global.BOOT_COUNT, so a later Home press stays on Home. */
    private boolean openLiveTvAfterBoot() {
        if (!bootToLiveTv) return false;
        int boot = Settings.Global.getInt(getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        SharedPreferences prefs = getSharedPreferences("boot", MODE_PRIVATE);
        if (boot < 0 || prefs.getInt("live_tv_boot", -1) == boot) return false;
        prefs.edit().putInt("live_tv_boot", boot).apply();
        Intent i = liveTvIntent();
        if (i == null) return false;
        open(i);
        return true;
    }

    /** The Live TV app's launch intent: its leanback entry point, or the configured action if it has none. */
    private Intent liveTvIntent() {
        if (liveTvPkg.isEmpty()) return null;
        Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(liveTvPkg);
        if (i != null || liveTvAction.isEmpty()) return i;
        Intent byAction = new Intent(liveTvAction).setPackage(liveTvPkg);
        return getPackageManager().resolveActivity(byAction, 0) != null ? byAction : null;
    }

    /** The TV's own source-selection panel, for TVs that have no TvInputManager (TCL Android TV 9): a
     *  service, or an activity on sets whose panel is a dialog (BYINTEK U4's QuickSourceActivity). */
    private void openSourcePanel() {
        Intent i = new Intent(sourcePanelAction);
        ComponentName c = ComponentName.unflattenFromString(sourcePanelService);
        if (c != null) i.setComponent(c);
        int eq = sourcePanelExtra.indexOf('=');
        if (eq > 0) {
            try {
                i.putExtra(sourcePanelExtra.substring(0, eq).trim(),
                        Integer.parseInt(sourcePanelExtra.substring(eq + 1).trim()));
            } catch (NumberFormatException ignored) {
            }
        }
        try {
            if (getPackageManager().resolveActivity(i, 0) != null) {
                startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            }
            startService(i);
        } catch (Exception e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    private void open(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Opens a Settings screen from the Settings panel: it slides in from the right over the panel, which stays, and
     * Home dims a little behind it, instead of the system's zoom for a new task (no animation at all on some TVs).
     */
    private void openSliding(Intent i) {
        // In our own task, not a new one: a Settings screen that is a translucent panel only leaves the screen below it
        // showing when both are in the same task. As a new task it got a stack of its own and Android stopped Home
        // behind it, which showed as a black screen on the Mi TV Stick (Android 9). Home (singleTask) then closes it.
        try {
            startActivity(i);
            if (animations && !reducedMotion) overridePendingTransition(R.anim.settings_open_enter, R.anim.settings_open_exit);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    /** `input_sources` in config.xml: "HW5=23" maps the framework input whose id ends in /HW5 to a vendor
     *  source number; "HW8=-" leaves that input out of the Inputs menu (a port the board reports but the set
     *  does not have). Returns the number, -1 for a left-out input, or null when the input is not listed. */
    private Integer mappedSource(String inputId) {
        if (inputId == null) return null;
        String hw = inputId.substring(inputId.lastIndexOf('/') + 1);
        for (String m : getResources().getStringArray(R.array.input_sources)) {
            int eq = m.indexOf('=');
            if (eq <= 0 || !m.substring(0, eq).trim().equals(hw)) continue;
            String v = m.substring(eq + 1).trim();
            if (v.equals("-")) return -1;
            try { return Integer.parseInt(v); } catch (NumberFormatException e) { return null; }
        }
        return null;
    }

    /** Switches to a passthrough input (HDMI, AV). An input mapped in `input_sources` is opened by sending
     *  `input_action` with its source number as the int extra "input" (Xiaomi China firmware, whose tvplayer
     *  answers com.xiaomi.mitv.tvplayer.EXTSRC_PLAY and nothing else). Otherwise the standard way is
     *  ACTION_VIEW on its channel URI, which the Live TV app handles; where nothing takes that, the
     *  configured `input_action` is sent with the same URI instead (XGIMI's HDMI player only answers
     *  com.xgimi.action.hdmiPlayer). With `input_uri_scheme` set (YaOS: live-tv) the same URI goes out
     *  with that scheme and no MIME type. A player guarded by a permission this build does not declare
     *  throws SecurityException, which used to take Home down with it (YaOS before the manifest asked
     *  for ACCESS_TV_SOURCE). */
    private void openInput(String inputId) {
        Integer source = mappedSource(inputId);
        if (source != null && source >= 0 && !inputAction.isEmpty()) {
            try {
                startActivity(new Intent(inputAction).putExtra("input", source.intValue())
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            } catch (ActivityNotFoundException | SecurityException e) {
                // fall through to the standard way
            }
        }
        Uri uri = TvContract.buildChannelUriForPassthroughInput(inputId);
        Intent view = (inputUriScheme.isEmpty()
                ? new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "vnd.android.cursor.item/channel")
                : new Intent(Intent.ACTION_VIEW, uri.buildUpon().scheme(inputUriScheme).build()))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(view);
            return;
        } catch (ActivityNotFoundException | SecurityException e) {
            if (inputAction.isEmpty()) {
                Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
                return;
            }
        }
        open(new Intent(inputAction).setDataAndType(uri, "vnd.android.cursor.item/channel"));
    }

    /** Dropdown menu under the Inputs pill: HDMI ports (with connection state), AV and Live TV. */
    private void showInputs() {
        // A configured source panel and no Live TV row to offer next to it: the panel is the whole menu.
        // The BYINTEK U4 needs this, as its TvInputManager lists three HDMI ports and an AV input it doesn't have.
        List<CoocaaSources.Source> coocaa = coocaaSources ? CoocaaSources.list(this) : Collections.emptyList();
        if (coocaa.isEmpty() && !sourcePanelService.isEmpty() && liveTvIntent() == null) {
            openSourcePanel();
            return;
        }
        // top_menu: the pill is gone, so the list hangs under the gear, growing inward from the right.
        // Read the switch, not the pill: show_inputs_pill=false hides the pill too, and there the list
        // keeps its usual place.
        // Google TV style: a panel that slides in on the right edge, full height, with the sources listed under a title.
        View panel = getLayoutInflater().inflate(R.layout.panel_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        final PopupWindow popup = sidePopup(panel);

        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        boolean sawNonHdmi = false;
        String tunerId = null;
        boolean tunerIsHw = false;
        boolean tunerIsAnalog = false;
        if (tim != null) {
            List<TvInputInfo> inputs = new ArrayList<>(tim.getTvInputList());
            // HDMI ports first in port order, then AV and the rest.
            Collections.sort(inputs, (a, b) -> {
                boolean ha = a.getType() == TvInputInfo.TYPE_HDMI, hb = b.getType() == TvInputInfo.TYPE_HDMI;
                if (ha != hb) return ha ? -1 : 1;
                return String.valueOf(a.loadLabel(this)).compareToIgnoreCase(String.valueOf(b.loadLabel(this)));
            });
            // An HDMI-CEC device (an Apple TV, a soundbar) gets an input of its own whose parent is the
            // port it sits on, so the port and the device would each get a row (Vestel MT7332: "HDMI 1"
            // and "HDMI Apple TV"). The device takes its port's row instead, and a bare port is listed
            // only when nothing on it speaks CEC.
            Set<String> inputIds = new HashSet<>();
            for (TvInputInfo info : inputs) inputIds.add(info.getId());
            Map<String, List<TvInputInfo>> cecDevices = new HashMap<>();
            for (TvInputInfo info : inputs) {
                String parent = info.getParentId();
                if (parent == null || !inputIds.contains(parent)) continue;
                if (!info.isPassthroughInput() || info.isHidden(this)) continue;
                List<TvInputInfo> onPort = cecDevices.get(parent);
                if (onPort == null) cecDevices.put(parent, onPort = new ArrayList<>());
                onPort.add(info);
            }
            // Some TVs (Sony) report every HDMI port as connected whether or not anything is plugged in,
            // so drop the HDMI status then. A TV with every port really in use loses it too.
            int hdmiPorts = 0, hdmiConnected = 0;
            for (TvInputInfo info : inputs) {
                if (!info.isPassthroughInput() || info.getType() != TvInputInfo.TYPE_HDMI) continue;
                if (info.getParentId() != null && inputIds.contains(info.getParentId())) continue;
                hdmiPorts++;
                if (tim.getInputState(info.getId()) == TvInputManager.INPUT_STATE_CONNECTED) hdmiConnected++;
            }
            boolean hdmiStateBogus = hdmiPorts > 1 && hdmiConnected == hdmiPorts;
            for (TvInputInfo info : inputs) {
                if (info.getType() == TvInputInfo.TYPE_TUNER
                        && (info.getServiceInfo().applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                        && (liveTvInputId.isEmpty() || liveTvInputId.equals(info.getId()))) {
                    // Prefer a hardware tuner: the framework names those ".../HW<n>". System apps such as
                    // AirPlay also register software inputs of type TUNER and may sort first (seen on TCL G05).
                    // Among tuners of the same kind, skip an analog input when another is present.
                    // MediaTek names them AnalogInputService and TunerInputService; alphabetical order
                    // would pick the analog one, and Live TV then opens a dead ATV source.
                    boolean hw = info.getId().matches(".*/HW\\d+$");
                    boolean analog = info.getId().toLowerCase(Locale.ROOT).contains("analog");
                    boolean better = tunerId == null
                            || (hw && !tunerIsHw)
                            || (hw == tunerIsHw && tunerIsAnalog && !analog)
                            || (hw == tunerIsHw && analog == tunerIsAnalog
                                    && info.getId().compareTo(tunerId) < 0);
                    if (better) {
                        tunerId = info.getId();
                        tunerIsHw = hw;
                        tunerIsAnalog = analog;
                    }
                }
                // Inputs hidden in the TV's Settings > Inputs stay out of the menu too.
                if (!info.isPassthroughInput() || info.isHidden(this)) continue;
                // input_sources can leave out ports the board reports but the set does not have ("HW8=-").
                if (Integer.valueOf(-1).equals(mappedSource(info.getId()))) continue;
                // A CEC device's row is added with its port, below.
                if (info.getParentId() != null && inputIds.contains(info.getParentId())) continue;
                CharSequence l = info.loadLabel(this);
                String label = l == null ? info.getId() : l.toString();
                boolean hdmi = info.getType() == TvInputInfo.TYPE_HDMI;
                if (hdmi && !label.toUpperCase(Locale.ROOT).contains("HDMI")) label = "HDMI " + label;
                CharSequence custom = info.loadCustomLabel(this);
                boolean named = custom != null && custom.length() > 0 && !custom.toString().equals(label);
                if (!hdmi && rows.getChildCount() > 0 && !sawNonHdmi) addSeparator(rows);
                if (!hdmi) sawNonHdmi = true;
                List<TvInputInfo> devices = cecDevices.get(info.getId());
                if (devices != null) {
                    // "Apple TV · HDMI 1": the device's own name (or the one given to it or to its port in
                    // Settings > Inputs), then the port. Opening the device's input also makes it the CEC
                    // active source, which wakes it.
                    for (TvInputInfo device : devices) {
                        CharSequence name = device.loadCustomLabel(this);
                        if (name == null || name.length() == 0) name = named ? custom : device.loadLabel(this);
                        String deviceLabel = name == null || name.length() == 0 ? label : name + " \u00B7 " + label;
                        final String deviceId = device.getId();
                        int state = hdmiStateBogus ? -1 : tim.getInputState(deviceId);
                        addInputRow(rows, popup, R.drawable.ic_input, deviceLabel, state, () -> openInput(deviceId));
                    }
                    continue;
                }
                // The name given in Settings > Inputs (e.g. "MacBook Pro") goes first, the port after it.
                if (named) label = custom + " \u00B7 " + label;
                final String id = info.getId();
                // Analog AV jacks have no hotplug detection, and some TVs report them as always connected
                // (TCL Smart TV Pro G03 says "Connected" with nothing plugged in), so AV rows show no state.
                int state = !hdmi || hdmiStateBogus ? -1 : tim.getInputState(id);
                addInputRow(rows, popup, hdmi ? R.drawable.ic_input : R.drawable.ic_av, label, state,
                        () -> openInput(id));
            }
        }
        // Coocaa OS registers nothing with TvInputManager; com.skyworth.tv lists its sources instead.
        if (rows.getChildCount() == 0) {
            for (CoocaaSources.Source s : coocaa) {
                int icon = "HDMI".equals(s.name) ? R.drawable.ic_input
                        : "VOD".equals(s.name) ? R.drawable.ic_livetv : R.drawable.ic_av;
                addInputRow(rows, popup, icon, s.label, -1, () -> {
                    if (!CoocaaSources.open(this, s)) {
                        Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }
        // Nothing to list where there is no TvInputManager: the TV's own source panel switches inputs.
        if (rows.getChildCount() == 0 && !sourcePanelService.isEmpty()) {
            addInputRow(rows, popup, R.drawable.ic_input, getString(R.string.inputs), -1, this::openSourcePanel);
        }
        Intent liveTv = liveTvIntent();
        if (liveTv != null && tunerId != null) {
            // Tune that input only when the Live TV package itself handles the URI.
            // The unrestricted input player also resolves it (that is how HDMI opens), but on the
            // AIWA AI PONT SA that player stays black, and the Live TV package does not handle the
            // URI at all, which used to toast "Not available on this TV". Keep the leanback activity
            // then: it is the same path as the Live TV tile. Do not drop the package restriction.
            // An explicit tuner uses its channel collection (e.g. DVB-S rather than DVB-T/C).
            // Keep the legacy passthrough route for automatic selection: some OEM players need it.
            // If the configured input is absent, tunerId stays null and the app opens normally.
            boolean configured = !liveTvInputId.isEmpty();
            Intent tuned = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(configured ? TvContract.buildChannelsUriForInput(tunerId)
                                    : TvContract.buildChannelUriForPassthroughInput(tunerId),
                            configured ? TvContract.Channels.CONTENT_TYPE : TvContract.Channels.CONTENT_ITEM_TYPE)
                    .setPackage(liveTvPkg);
            if (tuned.resolveActivity(getPackageManager()) != null) liveTv = tuned;
        }
        if (liveTv != null) {
            if (rows.getChildCount() > 0) addSeparator(rows);
            final Intent liveTvIntent = liveTv;
            addInputRow(rows, popup, R.drawable.ic_livetv, getString(R.string.live_tv), -1, () -> open(liveTvIntent));
        }
        if (rows.getChildCount() == 0) {
            Toast.makeText(this, R.string.no_inputs, Toast.LENGTH_SHORT).show();
            return;
        }

        slideIn(popup, panel);
        rows.getChildAt(0).requestFocus();
    }

    /** The side panel's window: leaving (Back, a row, a tap outside) slides it out instead of cutting it. */
    private final class SidePopup extends PopupWindow {
        private final View panel;
        private boolean closing;
        /** Back (the window dismisses itself on it) goes up one level instead of closing, while this is set. */
        Runnable backHandler;
        /** The panel stays open while another app is in front (see onPause), as the Settings panel does. */
        boolean keepWhenLeaving;

        SidePopup(View panel, int width, int height) {
            super(panel, width, height, true);
            this.panel = panel;
        }

        /** Closes the panel whatever its Back handler says: a row chosen, the launcher leaving. */
        void close() {
            // A row chosen: no slide-out, which looked awkward when choosing again and again. Back still slides it out.
            backHandler = null;
            closing = true;
            super.dismiss();
        }

        @Override
        public void dismiss() {
            if (backHandler != null) {
                backHandler.run();
                return;
            }
            if (closing) return;
            if (!isShowing() || reducedMotion || !animations || panel.getWindowToken() == null) {
                super.dismiss();
                return;
            }
            closing = true;
            boolean rtl = getResources().getBoolean(R.bool.rtl_menu_bar);
            panel.animate().cancel();
            panel.animate().translationX((rtl ? -1 : 1) * panel.getWidth()).alpha(Motion.lowEnd ? 1f : 0f).setDuration(200)
                    .setInterpolator(new android.view.animation.PathInterpolator(0.3f, 0f, 0.8f, 0.15f))
                    .withEndAction(() -> SidePopup.super.dismiss()).start();
        }
    }

    /** Closes a popup for good (a side panel's Back handler is skipped). */
    private void closePopup(PopupWindow popup) {
        if (popup == null) return;
        if (popup instanceof SidePopup) ((SidePopup) popup).close();
        else popup.dismiss();
    }

    /** A full-height panel on the right edge of the screen (Google TV's side panel), dismissed by Back. */
    private PopupWindow sidePopup(View panel) {
        final SidePopup popup = new SidePopup(panel, (int) (360 * density), ViewGroup.LayoutParams.MATCH_PARENT);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));   // needed for Back to dismiss
        popup.setOutsideTouchable(true);
        popup.setAnimationStyle(0);
        popup.setElevation(0f);
        popup.setOnDismissListener(() -> {
            if (openPopup == popup) openPopup = null;
            // Closed with Back (not because Home left, and not to open something else): the focus goes back to the button
            // that opened it, not to the apps.
            final View opener = panelOpener;
            if (opener != null) {
                root.post(() -> {
                    if (isFinishing() || isDestroyed() || !homeResumed || openPopup != null || profilePicker != null
                            || reopenSettingsRow >= 0) return;
                    openTopBar();
                    opener.requestFocus();
                });
            }
        });
        openPopup = popup;
        return popup;
    }

    /** The top-bar button whose panel is open (Settings), or null when the panel was opened some other way. */
    private View panelOpener;

    /**
     * Slides the side panel in from the right edge. The slide starts on the panel's first drawn frame: starting it
     * when the window is only being added skips its first frames, which made it jump in.
     */
    private void slideIn(PopupWindow popup, View panel) {
        boolean rtl = getResources().getBoolean(R.bool.rtl_menu_bar);
        panel.setTranslationX((rtl ? -1 : 1) * 360 * density);
        panel.setAlpha(Motion.lowEnd ? 1f : 0f);
        popup.showAtLocation(root, (rtl ? Gravity.START : Gravity.END) | Gravity.TOP, 0, 0);
        panel.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                panel.getViewTreeObserver().removeOnPreDrawListener(this);
                panel.animate().translationX(0f).alpha(1f).setDuration(reducedMotion || !animations ? 0 : 320)
                        .setInterpolator(new android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)).start();
                return true;
            }
        });
    }

    private void marquee(TextView text, boolean on) {
        TitleScroller.set(text, on);
    }

    /** Fades a menu row's text and icons between light (off the pill) and dark (on it). */
    private void tintRow(View row, boolean onPill) {
        final TextView title = row.findViewById(R.id.title);
        final TextView status = row.findViewById(R.id.status);
        final ImageView icon = row.findViewById(R.id.icon);
        final ImageView check = row.findViewById(R.id.check);
        final int lightMain = 0xFFE8EAED, darkMain = 0xFF202124, lightDim = 0xFF9AA0A6, darkDim = 0xFF5F6368;
        Object running = row.getTag(R.id.title);
        if (running instanceof ValueAnimator) ((ValueAnimator) running).cancel();
        Object last = row.getTag(R.id.status);
        float from = last instanceof Float ? (Float) last : (onPill ? 0f : 1f);
        float to = onPill ? 1f : 0f;
        final ArgbEvaluator argb = new ArgbEvaluator();
        ValueAnimator animator = ValueAnimator.ofFloat(from, to);
        animator.setDuration(animations && !reducedMotion ? 200 : 0);
        animator.addUpdateListener(a -> {
            float t = (Float) a.getAnimatedValue();
            row.setTag(R.id.status, t);
            int main = (Integer) argb.evaluate(t, lightMain, darkMain);
            int dim = (Integer) argb.evaluate(t, lightDim, darkDim);
            title.setTextColor(main);
            status.setTextColor(dim);
            if (icon.getTag() == null) icon.setImageTintList(ColorStateList.valueOf(main));
            check.setImageTintList(ColorStateList.valueOf(main));
        });
        row.setTag(R.id.title, animator);
        animator.start();
    }

    /** Glides a side panel's highlight to the row that just took focus. */
    private void moveHighlight(LinearLayout rows, View row) {
        if (!(rows.getParent() instanceof ViewGroup)) return;
        final View highlight = ((ViewGroup) rows.getParent()).findViewById(R.id.highlight);
        if (highlight == null) return;
        if (row.getHeight() == 0) {
            row.post(() -> {
                if (row.hasFocus() || row.isFocused()) moveHighlight(rows, row);
            });
            return;
        }
        ViewGroup.LayoutParams lp = highlight.getLayoutParams();
        if (lp.height != row.getHeight()) {
            lp.height = row.getHeight();
            highlight.setLayoutParams(lp);
        }
        float y = row.getTop();
        // The text turns dark on the pill and light off it in step with the pill, not ahead of it: switched at once,
        // the old row's light text sat on the light pill (and the new row's dark text on the dark panel) for the
        // length of the glide, and both vanished.
        View previous = (View) highlight.getTag(R.id.rowsHost);
        if (previous != null && previous != row) tintRow(previous, false);
        tintRow(row, true);
        highlight.setTag(R.id.rowsHost, row);
        highlight.animate().cancel();
        if (highlight.getAlpha() == 0f) {
            highlight.setTranslationY(y);
            highlight.animate().alpha(1f).setDuration(animations && !reducedMotion ? 160 : 0).start();
        } else {
            highlight.animate().translationY(y).setDuration(animations && !reducedMotion ? 240 : 0)
                    .setInterpolator(new android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)).start();
        }
    }

    private PopupWindow glassPopup(View panel, int width) {
        final PopupWindow popup = new PopupWindow(panel, width, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));   // needed for Back to dismiss
        popup.setOutsideTouchable(true);
        popup.setAnimationStyle(0);
        popup.setElevation(0f);
        popup.setOnDismissListener(() -> {
            if (openPopup == popup) openPopup = null;
        });
        openPopup = popup;
        return popup;
    }

    /** Drop in: fade and unfold from the top edge, hanging under the anchor pill. */
    private void dropIn(PopupWindow popup, View panel, View anchor, int gravity) {
        panel.setAlpha(0f);
        panel.setPivotY(0f);
        panel.setScaleY(0.92f);
        panel.setTranslationY(-8 * density);
        popup.showAsDropDown(anchor, 0, (int) (10 * density), gravity);
        panel.animate().alpha(1f).scaleY(1f).translationY(0f).setDuration(reducedMotion ? Motion.REDUCED_MS : 220)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
    }

    private void addSeparator(LinearLayout rows) {
        View line = new View(this);
        line.setBackgroundColor(0x1FFFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) density);
        int m = (int) (6 * density), h = (int) (12 * density);
        lp.setMargins(h, m, h, m);
        rows.addView(line, lp);
    }

    private void addInputRow(LinearLayout rows, PopupWindow popup, int iconRes, String title, int state, Runnable action) {
        View row = addMenuRow(rows, popup, iconRes, title, action);
        TextView status = row.findViewById(R.id.status);
        // This TV reports "standby" for every port whether or not something is plugged in, so only
        // definite states are shown.
        if (state == TvInputManager.INPUT_STATE_CONNECTED) {
            status.setText(R.string.connected);
            status.setVisibility(View.VISIBLE);
        } else if (state == TvInputManager.INPUT_STATE_DISCONNECTED) {
            status.setText(R.string.not_connected);
            status.setVisibility(View.VISIBLE);
        }
        // The side panel's rows are a little taller, like Google TV's.
        row.getLayoutParams().height = (int) (56 * density);
    }

    /**
     * One row of a Google TV style menu (item_menu): Material icon and label, a light pill when focused. The OK-up
     * that opened the menu (holding OK) must not activate its first row, so a row acts on an OK that went down on it.
     */
    /** Each text that names the TV in full, with the short form (தொ.கா.) used only when the full one does not fit. */
    private static final int[][] TV_NAMES = {
            {R.string.language_system_default, R.string.language_system_default_short},
            {R.string.settings_tv, R.string.settings_tv_short},
            {R.string.audio_tv_speaker, R.string.audio_tv_speaker_short},
            {R.string.live_tv, R.string.live_tv_short}};

    /** Sets the text; if it is one of the TV_NAMES it is shown in full when it fits its place and short when it would be cut. */
    private void fitTv(TextView tv, CharSequence text) {
        tv.setText(text);
        tv.setTag(R.id.fit_short, null);
        for (int[] pair : TV_NAMES) {
            if (!getString(pair[0]).contentEquals(text) && !getString(pair[1]).contentEquals(text)) continue;
            tv.setTag(R.id.fit_short, pair);
            if (tv.getTag(R.id.fit_listener) == null) {
                tv.setTag(R.id.fit_listener, Boolean.TRUE);
                tv.addOnLayoutChangeListener((v, l, tp, r, b, ol, ot, or, ob) -> {
                    if (r - l != or - ol) tv.post(() -> applyFit(tv));
                });
            }
            applyFit(tv);
            return;
        }
    }

    private void applyFit(TextView tv) {
        int[] pair = (int[]) tv.getTag(R.id.fit_short);
        if (pair == null) return;
        String full = getString(pair[0]);
        int room = tv.getWidth() - tv.getPaddingLeft() - tv.getPaddingRight();
        String pick = room <= 0 || tv.getPaint().measureText(full) <= room ? full : getString(pair[1]);
        if (!pick.contentEquals(tv.getText())) tv.setText(pick);
    }

    private View addMenuRow(LinearLayout rows, PopupWindow popup, int iconRes, CharSequence title, Runnable action) {
        return addMenuRow(rows, popup, iconRes, title, action, true);
    }

    /**
     * closes false: the panel is not closed when the row is chosen (the action changes what the panel shows).
     */
    private View addMenuRow(LinearLayout rows, PopupWindow popup, int iconRes, CharSequence title, Runnable action,
                            boolean closes) {
        View row = getLayoutInflater().inflate(R.layout.item_menu, rows, false);
        ImageView icon = row.findViewById(R.id.icon);
        if (iconRes != 0) icon.setImageResource(iconRes);
        else icon.setVisibility(View.GONE);
        fitTv(row.findViewById(R.id.title), title);
        row.setOnClickListener(v -> {
            if (closes) closePopup(popup);
            action.run();
        });
        final boolean[] pressed = {false};
        row.setOnKeyListener((v, code, event) -> {
            if (code != KeyEvent.KEYCODE_DPAD_CENTER && code != KeyEvent.KEYCODE_ENTER) return false;
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (event.getRepeatCount() == 0) pressed[0] = true;
                return true;
            }
            boolean activate = pressed[0];
            pressed[0] = false;
            if (activate) {
                if (closes) closePopup(popup);
                action.run();
            }
            return true;
        });
        row.setOnFocusChangeListener((v, focused) -> {
            if (focused) moveHighlight(rows, v);
            // A name too long for the panel scrolls once the row has been focused for a moment, then stops, like the
            // stock launcher's titles; off the row it is cut with an ellipsis again.
            marquee((TextView) v.findViewById(R.id.title), focused);
            marquee((TextView) v.findViewById(R.id.status), focused);
        });
        rows.addView(row);
        return row;
    }


    // ---- launcher settings and module cards -------------------------------------------------------

    /**
     * Hold OK on an app: the same side panel as Inputs, titled with the app's name. Open starts it, Move picks it up
     * in reorder mode, App info opens the TV's page for it, Uninstall (only for apps the owner installed) asks the
     * system to remove it, and the system confirms before anything is removed.
     */
    @Override
    public void onTileLongPress(int position, View card) {
        if (reordering) return;
        String key = adapter.keyAt(position);
        String pkg = adapter.packageAt(position);
        // Empty dock slots and the Default-home tile carry the launcher's own package: no menu for them.
        if (key == null || pkg == null || pkg.equals(getPackageName())) return;
        View panel = inflateSidePanel(adapter.labelAt(position));
        LinearLayout rows = panel.findViewById(R.id.rows);
        PopupWindow popup = sidePopup(panel);
        addInputRow(rows, popup, R.drawable.ic_menu_open, getString(R.string.tile_open), -1, () -> {
            if (card != null) card.performClick();
        });
        if (position < adapter.movableCount()) {
            addInputRow(rows, popup, R.drawable.ic_menu_move, getString(R.string.tile_move), -1, () -> {
                // Focus onto the app before the mode starts (see toggleReorder), then pick it up.
                int at = adapter.positionOf(key);
                if (at < 0) return;
                focusTile(at);
                if (!beginReorder()) return;
                at = adapter.positionOf(key);   // beginReorder can rebuild the tiles
                if (at < 0) return;
                pickUp(at);
                dimOthers();
            });
        }
        addInputRow(rows, popup, R.drawable.ic_menu_info, getString(R.string.library_app_info), -1, () -> open(new Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg))));
        // Built-in apps can only be turned off, from App info.
        if (isRemovable(pkg)) addInputRow(rows, popup, R.drawable.ic_menu_delete, getString(R.string.tile_uninstall), -1,
                () -> open(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + pkg))));
        slideIn(popup, panel);
        rows.getChildAt(0).requestFocus();
    }

    /**
     * The Settings button: the same side panel as Inputs. System settings is the TV's standard Settings; TV settings
     * is the maker's own settings app (picture, sound), listed only on a TV that has one; Language is the launcher's
     * own language, which does not touch the TV's.
     */
    private void showSettingsPanel(int focusRow) {
        showSettingsPanel(focusRow, false);
    }

    /** audioPage: opens on the Sound output page itself (coming back from the Bluetooth pairing screen), Back from it to the list. */
    private void showSettingsPanel(int focusRow, boolean audioPage) {
        showSettingsPanel(focusRow, audioPage, false);
    }

    /** profilesPage: opens on the Manage profiles page (coming back from the profile editor), Back from it to the list. */
    private void showSettingsPanel(int focusRow, boolean audioPage, boolean profilesPage) {
        audioPage &= audioOutput != null && audioOutput.available();
        View panel = inflateSidePanel(getString(profilesPage ? R.string.profile_manage_title
                : audioPage ? R.string.audio_output : R.string.settings));
        PopupWindow popup = sidePopup(panel);
        if (profilesPage) {
            pageReturnIndex = focusRow;
            fillProfileRows(panel, popup);
        } else if (audioPage) {
            pageReturnIndex = focusRow;
            fillAudioRows(panel, popup);
        } else {
            fillSettingsRows(panel, popup);
        }
        slideIn(popup, panel);
        LinearLayout rows = panel.findViewById(R.id.rows);
        int at = profilesPage ? 0 : audioPage ? rows.getChildCount() - 1 : Math.max(0, Math.min(focusRow, rows.getChildCount() - 1));
        rows.getChildAt(Math.max(0, at)).requestFocus();
    }

    /** Where the Profiles row is in the Settings list, for coming back to it from the profile editor. */
    private int profilesRowIndex;

    /** The Bluetooth pairing screen is in front: Back reopens the Sound output page, not the Settings list. */
    private boolean reopenAudioPage;

    /**
     * Back from a Settings screen: the panel that opened it comes back, sliding in again, on the same row. Only once Home
     * has the window focus: TCL's TV settings is an activity that ends at once and leaves a floating window of its own
     * on screen, so Home is resumed at that moment but is not in front, and opening our panel then put it behind TCL's.
     * Called from onResume and again when the window gains the focus; whichever comes last, with the focus, opens it.
     */
    private void reopenPanelIfDue() {
        if (reopenSettingsRow < 0 || !reopenLeft || !homeResumed || (reopenNeedsFocus && !hasWindowFocus()) || openPopup != null) return;
        root.removeCallbacks(reopenRun);
        // The wait is for the focus that comes right after the screen opened, before its floating window is up; once the
        // owner has had that window and closed it, there is nothing to wait for.
        boolean early = android.os.SystemClock.uptimeMillis() - reopenLaunchedAt < 2500;
        if (reopenDelayMs > 0 && early) root.postDelayed(reopenRun, reopenDelayMs);
        else root.post(reopenRun);
    }

    /** How long Home must keep the focus before the panel comes back: TCL's floating window appears a moment after Home resumes. */
    private int reopenDelayMs;
    /** Only for a screen that leaves a floating window of its own behind (TCL's TV settings); a normal screen reopens at once. */
    private boolean reopenNeedsFocus;
    /** Home has really been paused since the screen was opened: before that, a focus change is only our own popup closing. */
    private boolean reopenLeft;
    /** Between onResume and onPause. A focus event can still reach a paused Home (our own popup closing as the screen opens). */
    private boolean homeResumed;
    private long reopenLaunchedAt;

    private final Runnable reopenRun = this::reopenNow;

    private void reopenNow() {
        if (reopenSettingsRow < 0 || !reopenLeft || !homeResumed || (reopenNeedsFocus && !hasWindowFocus()) || openPopup != null || isFinishing() || isDestroyed()) return;
        final int row = reopenSettingsRow;
        final boolean audioPage = reopenAudioPage;
        reopenSettingsRow = -1;
        reopenAudioPage = false;
        reopenDelayMs = 0;
        reopenNeedsFocus = false;
        reopenLeft = false;
        showSettingsPanel(row, audioPage);
    }

    /**
     * The row of the Settings panel that opened a Settings screen, while that screen is in front: -1 when none. The
     * panel closes as the screen opens; coming back with Back opens it again on that row (onResume). Home clears it
     * (onNewIntent), so Home then shows no panel.
     */
    private int reopenSettingsRow = -1;

    /** The Settings list: System settings, TV settings (when this TV has them) and Home language. */
    private void fillSettingsRows(View panel, PopupWindow popup) {
        LinearLayout rows = panel.findViewById(R.id.rows);
        addMenuRow(rows, popup, R.drawable.ic_settings, getString(R.string.settings_system), () -> {
            reopenSettingsRow = 0;
            reopenAudioPage = false;
            reopenDelayMs = 0;
            reopenNeedsFocus = false;
            openSliding(systemIntent(Settings.ACTION_SETTINGS));
        }).getLayoutParams().height = (int) (56 * density);
        final Intent tvSettings = tvSettingsIntent();
        if (tvSettings != null) addMenuRow(rows, popup, R.drawable.ic_menu_tv, getString(R.string.settings_tv), () -> {
            reopenSettingsRow = 1;
            reopenAudioPage = false;
            reopenDelayMs = 900;
            reopenNeedsFocus = true;
            reopenLaunchedAt = android.os.SystemClock.uptimeMillis();
            openSliding(tvSettings);
        }).getLayoutParams().height = (int) (56 * density);
        // Audio output: only on a TV with Bluetooth and a paired audio device.
        if (audioOutput != null && audioOutput.available()) {
            AudioOutput.Sink now = audioOutput.active();
            final View[] self = new View[1];
            View audioRow = addMenuRow(rows, popup, now == null ? R.drawable.ic_menu_speaker : R.drawable.ic_menu_bluetooth,
                    getString(R.string.audio_output),
                    () -> {
                        markReturnRow(rows, self[0]);
                        swapPanelContent(panel, getString(R.string.audio_output), true, () -> fillAudioRows(panel, popup));
                    }, false);
            self[0] = audioRow;
            audioRow.getLayoutParams().height = (int) (56 * density);
            TextView status = audioRow.findViewById(R.id.status);
            fitTv(status, now == null ? getString(R.string.audio_tv_speaker) : now.name);
            status.setVisibility(View.VISIBLE);
        }
        // Button remapper: a page inside this panel, like Sound output and Home language.
        final View[] selfRemap = new View[1];
        selfRemap[0] = addMenuRow(rows, popup, R.drawable.ic_menu_remote, getString(R.string.remap_title), () -> {
            markReturnRow(rows, selfRemap[0]);
            swapPanelContent(panel, getString(R.string.remap_title), true, () -> fillRemapRows(panel, popup));
        }, false);
        selfRemap[0].getLayoutParams().height = (int) (56 * density);
        final View[] selfProfile = new View[1];
        selfProfile[0] = addMenuRow(rows, popup, R.drawable.ic_menu_person, getString(R.string.profile_title), () -> {
            markReturnRow(rows, selfProfile[0]);
            swapPanelContent(panel, getString(R.string.profile_manage_title), true, () -> fillProfileRows(panel, popup));
        }, false);
        selfProfile[0].getLayoutParams().height = (int) (56 * density);
        profilesRowIndex = rows.indexOfChild(selfProfile[0]);
        // The Profiles row wears the picture of the person in use, not a generic icon.
        Profiles.Profile inUse = Profiles.active(this);
        if (inUse != null) {
            ImageView personIcon = selfProfile[0].findViewById(R.id.icon);
            personIcon.setImageTintList(null);
            personIcon.setImageDrawable(new ProfileAvatar(this, inUse.shape, inUse.color, (int) (44 * density)));
        }
        Profiles.Profile current = Profiles.active(this);
        if (current != null) setRowStatus(selfProfile[0], current.name);
        // Home language is part of this panel: the panel stays where it is and only its content changes.
        final View[] selfLang = new View[1];
        View languageRow = addMenuRow(rows, popup, R.drawable.ic_menu_language, getString(R.string.language_label),
                () -> {
                    markReturnRow(rows, selfLang[0]);
                    swapPanelContent(panel, getString(R.string.language_label), true, () -> fillLanguageRows(panel, popup));
                }, false);
        selfLang[0] = languageRow;
        languageRow.getLayoutParams().height = (int) (56 * density);
        final View[] selfAbout = new View[1];
        selfAbout[0] = addMenuRow(rows, popup, R.drawable.ic_menu_info, getString(R.string.about_title), () -> {
            markReturnRow(rows, selfAbout[0]);
            swapPanelContent(panel, getString(R.string.about_title), true, () -> fillAboutRows(panel, popup));
        }, false);
        selfAbout[0].getLayoutParams().height = (int) (56 * density);
    }

    /** Coming back from a list, the focus returns to the row that opened it, not to the top of the list. */
    private void markReturnRow(LinearLayout rows, View row) {
        for (int i = 0; i < rows.getChildCount(); i++) rows.getChildAt(i).setTag(R.id.check, null);
        if (row != null) row.setTag(R.id.check, Boolean.TRUE);
        // The list is built again when the page closes, so the row itself is gone by then: remember its place.
        pageReturnIndex = row == null ? -1 : rows.indexOfChild(row);
    }

    /** Which row of the Settings list opened the page that is showing; Back puts the focus on it. */
    private int pageReturnIndex = -1;

    // ---- Button remapper ------------------------------------------------------------------------------------------
    /** The row of the Settings list that opened the remapper, for Back and for coming back from Accessibility. */
    private int remapSettingsIndex = -1;
    private Runnable remapTimeout;

    private void backToSettings(View panel, PopupWindow popup) {
        RemapService.setCapture(null);
        root.removeCallbacks(remapTimeout);
        ((SidePopup) popup).backHandler = null;
        pageReturnIndex = remapSettingsIndex;
        swapPanelContent(panel, getString(R.string.settings), false, () -> fillSettingsRows(panel, popup));
    }

    private void backToRemap(View panel, PopupWindow popup) {
        pageReturnIndex = -1;
        swapPanelContent(panel, getString(R.string.remap_title), false, () -> fillRemapRows(panel, popup));
    }

    /** The remapper page: the buttons that have a job, a way to add one, and the permission it needs. */
    private void fillRemapRows(View panel, PopupWindow popup) {
        LinearLayout rows = panel.findViewById(R.id.rows);
        final int h = (int) (56 * density);
        if (pageReturnIndex >= 0) remapSettingsIndex = pageReturnIndex;
        ((SidePopup) popup).backHandler = () -> backToSettings(panel, popup);
        if (!RemapService.isEnabled(this)) {
            View allow = addMenuRow(rows, popup, R.drawable.ic_menu_remote, getString(R.string.remap_allow), () -> {
                if (RemapService.enable(this)) {
                    root.postDelayed(() -> swapPanelContent(panel, getString(R.string.remap_title), true,
                            () -> fillRemapRows(panel, popup)), 500);
                    return;
                }
                // No way to switch it on from here: the TV's Accessibility screen, and back to the Settings list.
                reopenSettingsRow = Math.max(0, remapSettingsIndex);
                reopenAudioPage = false;
                reopenDelayMs = 0;
                reopenNeedsFocus = false;
                openSliding(systemIntent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            }, false);
            allow.getLayoutParams().height = h;
            setRowStatus(allow, getString(R.string.remap_allow_hint));
            return;
        }
        // Enabled in Settings but not started by Android (after an install or an update): start it now, not 5 seconds from now.
        if (!RemapService.isConnected()) RemapService.repair(this);
        final View[] add = new View[1];
        add[0] = addMenuRow(rows, popup, R.drawable.ic_menu_remote, getString(R.string.remap_add), () -> {
            if (!RemapService.isConnected()) {
                RemapService.repair(this);
                Toast.makeText(this, R.string.remap_starting, Toast.LENGTH_SHORT).show();
                return;
            }
            TextView title = add[0].findViewById(R.id.title);
            title.setText(R.string.remap_waiting);
            setRowStatus(add[0], getString(R.string.remap_waiting_hint));
            root.removeCallbacks(remapTimeout);
            remapTimeout = () -> {
                RemapService.setCapture(null);
                title.setText(R.string.remap_add);
                setRowStatus(add[0], getString(R.string.remap_add_hint));
            };
            root.postDelayed(remapTimeout, 10000);
            RemapService.setCapture((code, scan) -> {
                root.removeCallbacks(remapTimeout);
                RemapService.setCapture(null);
                if (isFinishing() || isDestroyed() || openPopup != popup) return;
                openRemapChooser(panel, popup, code, scan);
            });
        }, false);
        add[0].getLayoutParams().height = h;
        setRowStatus(add[0], getString(R.string.remap_add_hint));
        for (ButtonMap.Entry e : ButtonMap.list(this)) {
            View row = addMenuRow(rows, popup, R.drawable.ic_menu_remote, ButtonMap.keyName(e.keyCode, e.scanCode),
                    () -> openRemapChooser(panel, popup, e.keyCode, e.scanCode), false);
            row.getLayoutParams().height = h;
            setRowStatus(row, remapActionLabel(e));
        }
    }

    private String remapActionLabel(ButtonMap.Entry e) {
        switch (e.action) {
            case ButtonMap.ACTION_APP: return e.label.isEmpty() ? getString(R.string.remap_open_app) : e.label;
            case ButtonMap.ACTION_HOME: return getString(R.string.remap_home);
            case ButtonMap.ACTION_SETTINGS: return getString(R.string.remap_settings);
            case ButtonMap.ACTION_INPUTS: return getString(R.string.remap_inputs);
            case ButtonMap.ACTION_MEDIA:
            case ButtonMap.ACTION_SYSTEM: return e.label;
            default: return getString(R.string.remap_block);
        }
    }

    private void openRemapChooser(View panel, PopupWindow popup, int code, int scan) {
        swapPanelContent(panel, ButtonMap.keyName(code, scan), true, () -> fillRemapChooser(panel, popup, code, scan));
    }

    private void saveRemap(View panel, PopupWindow popup, int code, int scan, String action, String arg, String label) {
        ButtonMap.set(this, code, scan, action, arg, label);
        backToRemap(panel, popup);
    }

    /** What one button does: the choices, with a tick on the current one. */
    private void fillRemapChooser(View panel, PopupWindow popup, int code, int scan) {
        LinearLayout rows = panel.findViewById(R.id.rows);
        final int h = (int) (56 * density);
        ((SidePopup) popup).backHandler = () -> backToRemap(panel, popup);
        ButtonMap.Entry cur = ButtonMap.find(this, code, scan);
        String now = cur == null ? "" : cur.action;
        View app = addMenuRow(rows, popup, R.drawable.ic_apps, getString(R.string.remap_open_app), () -> {
            pageReturnIndex = -1;
            swapPanelContent(panel, getString(R.string.remap_choose_app), true, () -> fillRemapApps(panel, popup, code, scan));
        }, false);
        if (cur != null && ButtonMap.ACTION_APP.equals(now)) setRowStatus(app, cur.label);
        View home = addMenuRow(rows, popup, R.drawable.ic_menu_tv, getString(R.string.remap_home),
                () -> saveRemap(panel, popup, code, scan, ButtonMap.ACTION_HOME, "", ""), false);
        View settings = addMenuRow(rows, popup, R.drawable.ic_settings, getString(R.string.remap_settings),
                () -> saveRemap(panel, popup, code, scan, ButtonMap.ACTION_SETTINGS, "", ""), false);
        View inputs = null;
        if (btnInputs != null && btnInputs.getVisibility() == View.VISIBLE) {
            inputs = addMenuRow(rows, popup, R.drawable.ic_menu_tv, getString(R.string.remap_inputs),
                    () -> saveRemap(panel, popup, code, scan, ButtonMap.ACTION_INPUTS, "", ""), false);
        }
        View media = addMenuRow(rows, popup, R.drawable.ic_menu_remote, getString(R.string.remap_media_group), () -> {
            pageReturnIndex = -1;
            swapPanelContent(panel, getString(R.string.remap_media_group), true,
                    () -> fillRemapGroup(panel, popup, code, scan, ButtonMap.ACTION_MEDIA, ButtonMap.MEDIA_IDS, MEDIA_LABELS));
        }, false);
        View quick = addMenuRow(rows, popup, R.drawable.ic_settings, getString(R.string.remap_sys_group), () -> {
            pageReturnIndex = -1;
            swapPanelContent(panel, getString(R.string.remap_sys_group), true,
                    () -> fillRemapGroup(panel, popup, code, scan, ButtonMap.ACTION_SYSTEM, ButtonMap.SYSTEM_IDS, SYSTEM_LABELS));
        }, false);
        if (cur != null && ButtonMap.ACTION_MEDIA.equals(now)) setRowStatus(media, cur.label);
        if (cur != null && ButtonMap.ACTION_SYSTEM.equals(now)) setRowStatus(quick, cur.label);
        View block = addMenuRow(rows, popup, R.drawable.ic_menu_remote, getString(R.string.remap_block),
                () -> saveRemap(panel, popup, code, scan, ButtonMap.ACTION_BLOCK, "", ""), false);
        View[] all = {app, home, settings, inputs, media, quick, block};
        String[] actions = {ButtonMap.ACTION_APP, ButtonMap.ACTION_HOME, ButtonMap.ACTION_SETTINGS, ButtonMap.ACTION_INPUTS,
                ButtonMap.ACTION_MEDIA, ButtonMap.ACTION_SYSTEM, ButtonMap.ACTION_BLOCK};
        for (int i = 0; i < all.length; i++) {
            if (all[i] == null) continue;
            all[i].getLayoutParams().height = h;
            if (actions[i].equals(now)) {
                all[i].findViewById(R.id.check).setVisibility(View.VISIBLE);
                all[i].setTag(R.id.check, Boolean.TRUE);
            }
        }
        if (cur != null) {
            View remove = addMenuRow(rows, popup, R.drawable.ic_menu_delete, getString(R.string.remap_remove),
                    () -> saveRemap(panel, popup, code, scan, null, "", ""), false);
            remove.getLayoutParams().height = h;
        }
    }

    private static final int[] MEDIA_LABELS = {R.string.remap_media_play_pause, R.string.remap_media_next, R.string.remap_media_previous,
            R.string.remap_media_rewind, R.string.remap_media_fast_forward, R.string.remap_media_stop, R.string.remap_media_vol_up,
            R.string.remap_media_vol_down, R.string.remap_media_mute};
    private static final int[] SYSTEM_LABELS = {R.string.remap_sys_wifi, R.string.remap_sys_bluetooth, R.string.remap_sys_display,
            R.string.remap_sys_apps, R.string.remap_sys_storage, R.string.remap_sys_date, R.string.remap_sys_accessibility,
            R.string.remap_sys_developer};

    /** One group of jobs (media and volume, or the settings pages): the choices, with a tick on the current one. */
    private void fillRemapGroup(View panel, PopupWindow popup, int code, int scan, String action, String[] ids, int[] labels) {
        LinearLayout rows = panel.findViewById(R.id.rows);
        final int h = (int) (56 * density);
        ((SidePopup) popup).backHandler = () -> {
            pageReturnIndex = -1;
            swapPanelContent(panel, ButtonMap.keyName(code, scan), false, () -> fillRemapChooser(panel, popup, code, scan));
        };
        ButtonMap.Entry cur = ButtonMap.find(this, code, scan);
        for (int i = 0; i < ids.length; i++) {
            final String id = ids[i], label = getString(labels[i]);
            View row = addMenuRow(rows, popup, 0, label, () -> saveRemap(panel, popup, code, scan, action, id, label), false);
            row.getLayoutParams().height = h;
            if (cur != null && action.equals(cur.action) && id.equals(cur.arg)) {
                row.findViewById(R.id.check).setVisibility(View.VISIBLE);
                row.setTag(R.id.check, Boolean.TRUE);
            }
        }
    }

    /** The apps to choose from for "Open an app": everything with a launcher entry except this launcher. */
    private void fillRemapApps(View panel, PopupWindow popup, int code, int scan) {
        LinearLayout rows = panel.findViewById(R.id.rows);
        final int h = (int) (56 * density);
        ((SidePopup) popup).backHandler = () -> {
            pageReturnIndex = -1;
            swapPanelContent(panel, ButtonMap.keyName(code, scan), false, () -> fillRemapChooser(panel, popup, code, scan));
        };
        PackageManager pm = getPackageManager();
        java.util.Map<String, String> apps = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        java.util.Map<String, String> byLabel = new java.util.HashMap<>();
        for (String category : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            for (ResolveInfo ri : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(category), 0)) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(getPackageName()) || byLabel.containsValue(pkg)) continue;
                String label = String.valueOf(ri.loadLabel(pm));
                while (apps.containsKey(label)) label = label + " ";
                apps.put(label, pkg);
                byLabel.put(label, pkg);
            }
        }
        ButtonMap.Entry cur = ButtonMap.find(this, code, scan);
        for (java.util.Map.Entry<String, String> a : apps.entrySet()) {
            final String label = a.getKey().trim(), pkg = a.getValue();
            View row = addMenuRow(rows, popup, 0, label,
                    () -> saveRemap(panel, popup, code, scan, ButtonMap.ACTION_APP, pkg, label), false);
            row.getLayoutParams().height = h;
            if (cur != null && ButtonMap.ACTION_APP.equals(cur.action) && pkg.equals(cur.arg)) {
                row.findViewById(R.id.check).setVisibility(View.VISIBLE);
                row.setTag(R.id.check, Boolean.TRUE);
            }
        }
    }

    // ---- profiles --------------------------------------------------------------------------------------------------
    /** Set once a profile was chosen in this process: the picker opens on every start of the launcher, not on every Home press. */
    private static boolean profileChosen;
    private final Runnable repairRemap = () -> RemapService.repair(this);
    /** The profile whose app arrangement this screen was built with; a different choice rebuilds the screen. */
    private String loadedProfileId;
    private ProfilePicker profilePicker;
    private TextView btnProfile;

    /** At start: nobody yet asks for a name, one person goes straight in, several choose. */
    private void startProfileFlow() {
        if (!profileChosen) {
            java.util.List<Profiles.Profile> all = Profiles.list(this);
            if (!all.isEmpty() && Profiles.chosenThisBoot(this)) {
                // Android restarted the launcher (memory) after this start of the TV's choice was made: no questions.
                profileChosen = true;
            } else if (all.size() == 1) {
                Profiles.setActive(this, all.get(0).id);
                Profiles.markChosen(this);
                profileChosen = true;
            } else {
                showProfilePicker();
                return;
            }
        }
        applyProfile();
    }

    /** The profile in use: its name on the greeting and its avatar on the top bar. */
    private void applyProfile() {
        Profiles.Profile p = Profiles.active(this);
        if (p != null) greetingName = p.name;
        if (btnProfile != null && p != null) {
            // The picture fills the round button (it is a person, not an icon), so it is bigger than the others.
            btnProfile.setPadding((int) (4 * density), 0, (int) (4 * density), 0);
            btnProfile.setCompoundDrawablesRelativeWithIntrinsicBounds(
                    new ProfileAvatar(this, p.shape, p.color, (int) (34 * density)), null, null, null);
            btnProfile.setCompoundDrawableTintList(null);
            btnProfile.setContentDescription(p.name);
        }
        updateGreeting();
    }

    private void showProfilePicker() {
        showProfilePicker(false, null);
    }

    /** The profile screen: the list of people to choose from, or (editor) just the editor for one person, or a new one (target null). */
    private void showProfilePicker(boolean editor, Profiles.Profile target) {
        if (profilePicker != null) return;
        closePopup(openPopup);
        // The editor opened from Settings gives Settings back, on the Manage profiles page, when it is left.
        final boolean fromSettings = editor;
        final ViewGroup host = (ViewGroup) root.getParent();
        final int focusability = root.getDescendantFocusability();
        root.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);   // nothing under the picker takes keys
        profilePicker = new ProfilePicker(this, chosen -> {
            profileChosen = true;
            Profiles.markChosen(this);
            final ProfilePicker picker = profilePicker;
            root.setDescendantFocusability(focusability);
            applyProfile();
            // Another person: Home is rebuilt with their arrangement right here, behind the picker, which then fades
            // away on it. No restart, so no black screen between the two.
            final boolean switched = loadedProfileId != null && !loadedProfileId.equals(chosen.id);
            if (switched) rebuildForProfile(chosen);
            picker.animate().alpha(0f).setDuration(switched ? 300 : 180).withEndAction(() -> {
                host.removeView(picker);
                if (profilePicker == picker) profilePicker = null;
                if (fromSettings) {
                    root.post(() -> {
                        if (!isFinishing() && !isDestroyed() && openPopup == null) showSettingsPanel(profilesRowIndex, false, true);
                    });
                    return;
                }
                root.post(() -> focusTile(Math.min(Math.max(0, lastFocused), Math.max(0, adapter.appCount() - 1))));
            }).start();
        });
        host.addView(profilePicker, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (editor) profilePicker.startEdit(target);
        else profilePicker.start();
    }

    /** Home with another profile's arrangement of the apps (and its shortcuts from the library), in place. */
    private void rebuildForProfile(Profiles.Profile p) {
        loadedProfileId = p.id;
        Order saved = Order.load(Order.file(this));
        adapter.setSavedOrder(Order.ranks(saved, dockApps), Order.docked(saved, dockApps));
        adapter.setProfileHidden(Profiles.hidden(this, p.id));
        lastFocused = 0;
        lastFocusedKey = null;
        reloadApps(true, true, true);
    }

    /** Switch to a profile from Settings, without the picker. */
    private void switchProfile(Profiles.Profile p) {
        Profiles.setActive(this, p.id);
        applyProfile();
        if (loadedProfileId != null && !loadedProfileId.equals(p.id)) rebuildForProfile(p);
    }

    // ---- Settings: manage profiles --------------------------------------------------------------------------------
    private int profilesSettingsIndex = -1;

    /** The people of this TV: pick one to switch to, edit or delete, or add another. */
    private void fillProfileRows(View panel, PopupWindow popup) {
        LinearLayout rows = panel.findViewById(R.id.rows);
        final int h = (int) (56 * density);
        if (pageReturnIndex >= 0) profilesSettingsIndex = pageReturnIndex;
        ((SidePopup) popup).backHandler = () -> {
            ((SidePopup) popup).backHandler = null;
            pageReturnIndex = profilesSettingsIndex;
            swapPanelContent(panel, getString(R.string.settings), false, () -> fillSettingsRows(panel, popup));
        };
        Profiles.Profile active = Profiles.active(this);
        for (Profiles.Profile p : Profiles.list(this)) {
            View row = addMenuRow(rows, popup, R.drawable.ic_menu_person, p.name, () -> {
                pageReturnIndex = -1;
                swapPanelContent(panel, p.name, true, () -> fillProfileActions(panel, popup, p));
            }, false);
            ImageView icon = row.findViewById(R.id.icon);
            icon.setImageTintList(null);
            icon.setImageDrawable(new ProfileAvatar(this, p.shape, p.color, (int) (44 * density)));
            row.getLayoutParams().height = h;
            if (active != null && active.id.equals(p.id)) setRowStatus(row, getString(R.string.profile_current));
        }
        View add = addMenuRow(rows, popup, R.drawable.ic_menu_person, getString(R.string.profile_add), () -> showProfilePicker(true, null));
        add.getLayoutParams().height = h;
    }

    /** Every app of the TV, ticked when this person sees it on Home. */
    private void fillProfileApps(View panel, PopupWindow popup, Profiles.Profile p) {
        LinearLayout rows = panel.findViewById(R.id.rows);
        final int h = (int) (56 * density);
        ((SidePopup) popup).backHandler = () -> {
            pageReturnIndex = -1;
            Profiles.Profile now = Profiles.active(this);
            if (now != null && now.id.equals(p.id)) {   // the person in use changed what they see: Home follows
                adapter.setProfileHidden(Profiles.hidden(this, p.id));
                reloadApps(true, true, false);
            }
            swapPanelContent(panel, p.name, false, () -> fillProfileActions(panel, popup, p));
        };
        PackageManager pm = getPackageManager();
        java.util.Map<String, String> apps = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String category : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            for (ResolveInfo ri : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(category), 0)) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(getPackageName()) || !seen.add(pkg)) continue;
                String label = String.valueOf(ri.loadLabel(pm));
                while (apps.containsKey(label)) label = label + " ";
                apps.put(label, pkg);
            }
        }
        java.util.Set<String> hidden = Profiles.hidden(this, p.id);
        for (java.util.Map.Entry<String, String> a : apps.entrySet()) {
            final String pkg = a.getValue();
            final View[] self = new View[1];
            self[0] = addMenuRow(rows, popup, 0, a.getKey().trim(), () -> {
                boolean nowHidden = !Profiles.hidden(this, p.id).contains(pkg);
                Profiles.setHidden(this, p.id, pkg, nowHidden);
                self[0].findViewById(R.id.check).setVisibility(nowHidden ? View.GONE : View.VISIBLE);
            }, false);
            self[0].getLayoutParams().height = h;
            if (!hidden.contains(pkg)) self[0].findViewById(R.id.check).setVisibility(View.VISIBLE);
        }
    }

    private void fillProfileActions(View panel, PopupWindow popup, Profiles.Profile p) {
        LinearLayout rows = panel.findViewById(R.id.rows);
        final int h = (int) (56 * density);
        final Runnable backToList = () -> {
            pageReturnIndex = -1;
            swapPanelContent(panel, getString(R.string.profile_manage_title), false, () -> fillProfileRows(panel, popup));
        };
        ((SidePopup) popup).backHandler = backToList;
        Profiles.Profile active = Profiles.active(this);
        boolean isActive = active != null && active.id.equals(p.id);
        if (!isActive) {
            addMenuRow(rows, popup, R.drawable.ic_menu_person, getString(R.string.profile_switch_to, p.name), () -> switchProfile(p))
                    .getLayoutParams().height = h;
        }
        addMenuRow(rows, popup, R.drawable.ic_settings, getString(R.string.profile_edit_one), () -> showProfilePicker(true, p))
                .getLayoutParams().height = h;
        if (!Profiles.isMain(this, p.id)) {
            // The first profile has every app; the others choose which ones they see.
            View apps = addMenuRow(rows, popup, R.drawable.ic_apps, getString(R.string.profile_apps), () -> {
                pageReturnIndex = -1;
                swapPanelContent(panel, getString(R.string.profile_apps), true, () -> fillProfileApps(panel, popup, p));
            }, false);
            apps.getLayoutParams().height = h;
            int hiddenCount = Profiles.hidden(this, p.id).size();
            if (hiddenCount > 0) setRowStatus(apps, String.valueOf(hiddenCount));
        }
        if (Profiles.list(this).size() > 1) {
            addMenuRow(rows, popup, R.drawable.ic_menu_delete, getString(R.string.profile_delete), () -> {
                Profiles.delete(this, p.id);
                Profiles.Profile now = Profiles.active(this);
                if (now != null && loadedProfileId != null && !loadedProfileId.equals(now.id)) {
                    applyProfile();
                    rebuildForProfile(now);
                }
                backToList.run();
            }, false).getLayoutParams().height = h;
        }
    }

    private AudioOutput audioOutput;
    /** The Audio output list while it is on screen (so a connection coming or going can update it), else null. */
    private View audioPanel;
    private PopupWindow audioPopup;

    /** The Bluetooth audio state changed: refresh the list if it is showing. */
    private void onAudioChanged() {
        if (audioPanel != null) root.post(this::refreshAudioRows);
    }

    /** Audio output: the TV speaker and each paired Bluetooth audio device, a tick on the one in use. */
    private void fillAudioRows(View panel, PopupWindow popup) {
        audioPanel = panel;
        audioPopup = popup;
        final Runnable back = () -> {
            ((SidePopup) popup).backHandler = null;
            audioPanel = null;
            swapPanelContent(panel, getString(R.string.settings), false, () -> fillSettingsRows(panel, popup));
        };
        ((SidePopup) popup).backHandler = back;
        buildAudioRows(panel, popup);
    }

    /** Devices that were asked to connect and have not answered yet (by address), so their row says "Connecting…". */
    private final java.util.Set<String> audioPending = new java.util.HashSet<>();

    private void buildAudioRows(View panel, PopupWindow popup) {
        LinearLayout rows = panel.findViewById(R.id.rows);
        List<AudioOutput.Sink> sinks = audioOutput.sinks();
        // The one in use first, then the other saved devices, each by name.
        java.util.Collections.sort(sinks, (x, y) -> {
            if (x.connected() != y.connected()) return x.connected() ? -1 : 1;
            return x.name.compareToIgnoreCase(y.name);
        });
        boolean anyOn = false;
        for (AudioOutput.Sink s : sinks) anyOn |= s.connected();
        final int rowHeight = (int) (56 * density);

        // The TV's own speaker: always there, and what the sound uses whenever no device is connected.
        View tv = addMenuRow(rows, popup, R.drawable.ic_menu_speaker, getString(R.string.audio_tv_speaker), () -> {
            audioPending.clear();
            if (!audioOutput.useTv()) Toast.makeText(this, R.string.audio_failed, Toast.LENGTH_SHORT).show();
            root.postDelayed(this::refreshAudioRows, 500);
        }, false);
        tv.getLayoutParams().height = rowHeight;
        setRowStatus(tv, getString(R.string.audio_builtin));
        if (!anyOn) {
            tv.findViewById(R.id.check).setVisibility(View.VISIBLE);
            tv.setTag(R.id.check, Boolean.TRUE);
        }

        // Saved Bluetooth devices. One that is switched off or out of range says so, and trying it says why it failed.
        for (AudioOutput.Sink s : sinks) {
            final String address = s.device.getAddress();
            View row = addMenuRow(rows, popup, R.drawable.ic_menu_bluetooth, s.name, () -> {
                if (!audioOutput.useBluetooth(s)) {
                    Toast.makeText(this, R.string.audio_failed, Toast.LENGTH_SHORT).show();
                } else if (!s.connected()) {
                    audioPending.add(address);
                    // No answer in 12 seconds: it is off, or too far. Say so and keep the sound on the TV.
                    root.postDelayed(() -> {
                        if (!audioPending.remove(address)) return;
                        for (AudioOutput.Sink now : audioOutput.sinks()) {
                            if (now.device.getAddress().equals(address) && !now.connected()) {
                                audioOutput.cancel(now.device);
                                Toast.makeText(this, getString(R.string.audio_connect_failed, now.name), Toast.LENGTH_LONG).show();
                            }
                        }
                        root.postDelayed(this::refreshAudioRows, 400);
                    }, 12000);
                }
                root.postDelayed(this::refreshAudioRows, 500);
            }, false);
            row.getLayoutParams().height = rowHeight;
            if (s.connected()) {
                audioPending.remove(address);
                setRowStatus(row, getString(R.string.audio_connected));
                row.findViewById(R.id.check).setVisibility(View.VISIBLE);
                row.setTag(R.id.check, Boolean.TRUE);
            } else if (s.connecting() || audioPending.contains(address)) {
                setRowStatus(row, getString(R.string.audio_connecting));
            } else {
                setRowStatus(row, getString(R.string.audio_not_connected));
            }
        }

        // Nothing saved, or a new one to save: Bluetooth settings (the pairing itself is the TV's own screen).
        View add = addMenuRow(rows, popup, R.drawable.ic_menu_bluetooth, getString(R.string.audio_add), () -> {
            // Back from the pairing screen lands on this page again; Back from it goes to the Sound output row.
            reopenSettingsRow = 1 + (tvSettingsIntent() != null ? 1 : 0);
            reopenAudioPage = true;
            reopenDelayMs = 0;
            reopenNeedsFocus = false;
            openSliding(pairingIntent());
        }, false);
        add.getLayoutParams().height = rowHeight;
        setRowStatus(add, getString(audioOutput.bluetoothOn() ? R.string.audio_add_hint : R.string.audio_bt_off));
    }

    /**
     * The TV's "add an accessory" screen, where a speaker or headphones is paired. Android TV answers
     * CONNECT_INPUT for it (the standard Bluetooth settings action has no handler on these TVs); where nothing does,
     * the TV's Settings opens instead.
     */
    private Intent pairingIntent() {
        Intent pair = new Intent("com.google.android.intent.action.CONNECT_INPUT");
        if (getPackageManager().resolveActivity(pair, 0) != null) return pair;
        return systemIntent(Settings.ACTION_SETTINGS);
    }

    private void setRowStatus(View row, CharSequence text) {
        TextView status = row.findViewById(R.id.status);
        status.setText(text);
        status.setVisibility(View.VISIBLE);
    }

    /** Rebuilds the open Audio output list in place, keeping the focus on the same row number. */
    private void refreshAudioRows() {
        final View panel = audioPanel;
        final PopupWindow popup = audioPopup;
        if (panel == null || popup == null || !popup.isShowing()) return;
        LinearLayout rows = panel.findViewById(R.id.rows);
        int focused = -1;
        for (int i = 0; i < rows.getChildCount(); i++) if (rows.getChildAt(i).hasFocus()) focused = i;
        rows.removeAllViews();
        View highlight = panel.findViewById(R.id.highlight);
        highlight.animate().cancel();
        highlight.setTag(R.id.rowsHost, null);
        buildAudioRows(panel, popup);
        View target = rows.getChildAt(Math.max(0, Math.min(focused < 0 ? 0 : focused, rows.getChildCount() - 1)));
        if (target != null) target.requestFocus();
    }

    /** Home language: System default, English, Tamil. Back returns to the Settings list inside the same panel. */
    /** About: the app, its version and who made it (the Elvan Navil branding of the Neram app). */
    private void fillAboutRows(View panel, PopupWindow popup) {
        LinearLayout rows = panel.findViewById(R.id.rows);
        final int h = (int) (56 * density);
        ((SidePopup) popup).backHandler = () -> {
            ((SidePopup) popup).backHandler = null;
            swapPanelContent(panel, getString(R.string.settings), false, () -> fillSettingsRows(panel, popup));
        };
        String version = "";
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        String[][] items = {
                {getString(R.string.app_name), getString(R.string.about_version, version)},
                {getString(R.string.about_designer), getString(R.string.about_designer_value)},
                {getString(R.string.about_from), ""},
        };
        for (String[] item : items) {
            View row = addMenuRow(rows, popup, 0, item[0], () -> { }, false);
            row.getLayoutParams().height = h;
            if (!item[1].isEmpty()) setRowStatus(row, item[1]);
        }
    }

    private void fillLanguageRows(View panel, PopupWindow popup) {
        LinearLayout rows = panel.findViewById(R.id.rows);
        final Runnable back = () -> {
            ((SidePopup) popup).backHandler = null;
            swapPanelContent(panel, getString(R.string.settings), false, () -> fillSettingsRows(panel, popup));
        };
        ((SidePopup) popup).backHandler = back;
        String current = LocaleHelper.getLanguage(this);
        String[] codes = {"system", "ta", "en"};
        String[] labels = {getString(R.string.language_system_default), getString(R.string.language_tamil),
                getString(R.string.language_english)};
        for (int i = 0; i < codes.length; i++) {
            final String code = codes[i];
            View row = addMenuRow(rows, popup, 0, labels[i], () -> {
                if (code.equals(LocaleHelper.getLanguage(this))) return;
                applyLanguage(code);
                refreshLanguagePanel(panel);
            }, false);
            row.getLayoutParams().height = (int) (56 * density);
            if (code.equals(current)) {
                row.findViewById(R.id.check).setVisibility(View.VISIBLE);
                row.setTag(R.id.check, Boolean.TRUE);
            }
        }
    }

    /**
     * Switches the launcher's language where it stands: the screen is not rebuilt, so nothing moves, the focus stays
     * and the open panel stays open. The saved choice is what the next start reads (LocaleHelper.wrap).
     */
    private void applyLanguage(String code) {
        LocaleHelper.setLanguage(this, code);
        java.util.Locale locale = "system".equals(code) || code.isEmpty()
                ? android.content.res.Resources.getSystem().getConfiguration().getLocales().get(0)
                : LocaleHelper.getLocaleForTag(code);
        java.util.Locale.setDefault(locale);
        android.content.res.Configuration config = new android.content.res.Configuration(getResources().getConfiguration());
        config.setLocales(new android.os.LocaleList(locale));
        getResources().updateConfiguration(config, getResources().getDisplayMetrics());
        // The texts that are set once, at start, in the layout or in code.
        if (sectionTitle != null) sectionTitle.setText(reordering ? R.string.arrange_apps : R.string.your_apps);
        if (btnDoneReorder != null) btnDoneReorder.setText(R.string.done);
        DateClock date = findViewById(R.id.date);
        if (date != null) {
            date.setFormat12Hour(getString(R.string.date_format_home));
            date.setFormat24Hour(getString(R.string.date_format_home));
        }
        for (int id : new int[]{R.id.clock, R.id.ampm}) {
            TextClock clock = findViewById(id);
            if (clock != null) {
                CharSequence f12 = clock.getFormat12Hour(), f24 = clock.getFormat24Hour();
                clock.setFormat12Hour(f12);
                clock.setFormat24Hour(f24);
            }
        }
        applyTimeOrder();
        root.post(this::alignClock);
        adapter.notifyItemChanged(adapter.appCount());   // the Reorder tile's label
    }

    /** The open language list in the new language: the title and the first row's name change, the ticks move. */
    private void refreshLanguagePanel(View panel) {
        ((TextView) panel.findViewById(R.id.panelTitle)).setText(R.string.language_label);
        LinearLayout rows = panel.findViewById(R.id.rows);
        String current = LocaleHelper.getLanguage(this);
        String[] codes = {"system", "ta", "en"};
        String[] labels = {getString(R.string.language_system_default), getString(R.string.language_tamil),
                getString(R.string.language_english)};
        for (int i = 0; i < codes.length && i < rows.getChildCount(); i++) {
            View row = rows.getChildAt(i);
            fitTv(row.findViewById(R.id.title), labels[i]);
            row.findViewById(R.id.check).setVisibility(codes[i].equals(current) ? View.VISIBLE : View.GONE);
            row.setTag(R.id.check, codes[i].equals(current) ? Boolean.TRUE : null);
        }
    }

    /**
     * Puts the digits of the time, the date's digits and the divider on one centre line, and that line level with the
     * middle of the top-bar pill. The centre comes from the digits' real height and the text baseline, not from the line
     * boxes, so it does not depend on the font: a Tamil day period or date (with its taller line box) sits the same as
     * Latin text.
     */
    private void alignClock() {
        TextView clock = findViewById(R.id.clock);
        TextView date = findViewById(R.id.date);
        View dateBox = findViewById(R.id.dateBox);
        View divider = findViewById(R.id.clockDivider);
        View block = findViewById(R.id.leftBlock);
        View pill = findViewById(R.id.topButtons);
        if (clock == null || date == null || dateBox == null || divider == null || block == null) return;
        if (clock.getHeight() == 0 || date.getHeight() == 0 || date.getVisibility() != View.VISIBLE) return;
        // A text whose layout is being rebuilt has no baseline yet (-1): measuring now would shift the date for good.
        if (clock.getBaseline() < 0 || date.getBaseline() < 0) return;
        dateBox.setTranslationY(0f);
        divider.setTranslationY(0f);
        block.setTranslationY(0f);
        android.graphics.Rect digits = new android.graphics.Rect();
        clock.getPaint().getTextBounds("0123456789", 0, 10, digits);
        android.graphics.Rect dateDigits = new android.graphics.Rect();
        date.getPaint().getTextBounds("0123456789", 0, 10, dateDigits);
        // Layout positions only (the sum of each view's top), never window positions: those include whatever the panel
        // slide or any other animation is doing to the parents at that moment, and the offset would be wrong for good.
        float clockCentre = layoutTop(clock) + clock.getBaseline() - digits.height() / 2f;
        float dateCentre = layoutTop(date) + date.getBaseline() - dateDigits.height() / 2f;
        float dividerCentre = layoutTop(divider) + divider.getHeight() / 2f;
        dateBox.setTranslationY(clockCentre - dateCentre);
        divider.setTranslationY(clockCentre - dividerCentre);
        // The whole row level with the pill's middle.
        if (pill != null && pill.getVisibility() == View.VISIBLE && pill.getHeight() > 0) {
            float pillCentre = layoutTop(pill) + pill.getHeight() / 2f;
            block.setTranslationY(pillCentre - clockCentre);
        }
    }

    /** The view's top within the screen's layout, ignoring every translation, scale and animation. */
    private float layoutTop(View v) {
        float top = 0f;
        for (View cur = v; cur != null; ) {
            top += cur.getTop();
            if (!(cur.getParent() instanceof View)) break;
            cur = (View) cur.getParent();
        }
        return top;
    }

    /**
     * The clock reads the way the language writes it: where the day period (AM/PM) comes before the time, as in Tamil
     * ("பிற்பகல் 1:30"), it is shown before it; elsewhere after. Both have the time's size.
     */
    private void applyTimeOrder() {
        View clock = findViewById(R.id.clock);
        View period = findViewById(R.id.ampm);
        if (clock == null || period == null || !(period.getParent() instanceof ViewGroup)) return;
        ViewGroup parent = (ViewGroup) period.getParent();
        java.util.Locale locale = getResources().getConfiguration().getLocales().get(0);
        String pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, "hm");
        boolean first = pattern != null && pattern.replace("\u200E", "").trim().startsWith("a");
        int clockAt = parent.indexOfChild(clock);
        int periodAt = parent.indexOfChild(period);
        if (first != (periodAt < clockAt)) {
            parent.removeView(period);
            parent.addView(period, first ? parent.indexOfChild(clock) : parent.indexOfChild(clock) + 1);
        }
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) period.getLayoutParams();
        lp.setMarginStart(first ? 0 : (int) (8 * density));
        lp.setMarginEnd(first ? (int) (8 * density) : 0);
        period.setLayoutParams(lp);
    }

    /**
     * Changes what an open side panel lists without moving the panel, the way the stock TV Settings changes page
     * (measured on the stock app): the title and the list move as one block; the new page slides in about 125dp from
     * the right (from the left going back) with a long ease-out, settling in about 400ms, and fades in; the old page,
     * kept as a picture, fades out over the first 100ms and drifts the other way. Both are on screen together.
     * forward: going into a sub-list.
     */
    private void swapPanelContent(View panel, CharSequence title, boolean forward, Runnable fill) {
        final ViewGroup group = (ViewGroup) panel;
        final TextView titleView = panel.findViewById(R.id.panelTitle);
        final View host = panel.findViewById(R.id.rowsHost);
        final View scroller = (View) host.getParent();
        final LinearLayout rows = panel.findViewById(R.id.rows);
        final View highlight = panel.findViewById(R.id.highlight);
        final boolean animate = animations && !reducedMotion && panel.getWidth() > 0 && panel.getHeight() > 0;

        // The old page, as a picture over the panel.
        final android.graphics.drawable.BitmapDrawable old;
        if (animate && !Motion.lowEnd) {
            Bitmap shot = Bitmap.createBitmap(panel.getWidth(), panel.getHeight(), Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(shot);
            canvas.save();
            canvas.translate(titleView.getLeft(), titleView.getTop());
            titleView.draw(canvas);
            canvas.restore();
            canvas.save();
            canvas.translate(scroller.getLeft(), scroller.getTop());
            canvas.clipRect(0, 0, scroller.getWidth(), scroller.getHeight());
            scroller.draw(canvas);
            canvas.restore();
            old = new android.graphics.drawable.BitmapDrawable(getResources(), shot);
            old.setBounds(0, 0, shot.getWidth(), shot.getHeight());
            group.getOverlay().add(old);
        } else {
            old = null;
        }

        // The new page, built at once.
        rows.removeAllViews();
        highlight.animate().cancel();
        highlight.setAlpha(0f);
        highlight.setTag(R.id.rowsHost, null);
        titleView.setText(title);
        fill.run();
        View target = rows.getChildAt(0);
        for (int i = 0; i < rows.getChildCount(); i++) {
            if (Boolean.TRUE.equals(rows.getChildAt(i).getTag(R.id.check))) target = rows.getChildAt(i);
        }
        if (!forward) {
            if (pageReturnIndex >= 0 && pageReturnIndex < rows.getChildCount()) target = rows.getChildAt(pageReturnIndex);
            pageReturnIndex = -1;
        }
        if (target != null) target.requestFocus();
        if (!animate) return;

        final float travel = 125 * density * (forward ? 1 : -1);
        titleView.setAlpha(0f);
        scroller.setAlpha(0f);
        titleView.setTranslationX(travel);
        scroller.setTranslationX(travel);
        final android.view.animation.Interpolator slide = new android.view.animation.PathInterpolator(0.05f, 0.7f, 0.1f, 1f);
        ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(400);
        anim.addUpdateListener(a -> {
            float t = (Float) a.getAnimatedValue();
            float moved = slide.getInterpolation(t);
            float x = travel * (1f - moved);
            titleView.setTranslationX(x);
            scroller.setTranslationX(x);
            float in = Math.min(1f, t / 0.45f);
            titleView.setAlpha(in);
            scroller.setAlpha(in);
            // The old page: gone within the first fifth, drifting a little away from where the new one comes from.
            if (old != null) {
                old.setAlpha(Math.round(255 * Math.max(0f, 1f - t / 0.18f)));
                int dx = Math.round(-travel * 0.16f * moved);
                old.setBounds(dx, 0, dx + old.getIntrinsicWidth(), old.getIntrinsicHeight());
                panel.invalidate();
            }
        });
        anim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                if (old != null) group.getOverlay().remove(old);
                titleView.setTranslationX(0f);
                scroller.setTranslationX(0f);
                titleView.setAlpha(1f);
                scroller.setAlpha(1f);
            }
        });
        anim.start();
    }

    /** The maker's own TV settings app, when this TV has one and it can be opened; otherwise null (no row is shown). */
    private Intent tvSettingsIntent() {
        PackageManager pm = getPackageManager();
        for (String value : getResources().getStringArray(R.array.tv_settings_components)) {
            ComponentName component = ComponentName.unflattenFromString(value);
            if (component == null) continue;
            try {
                ActivityInfo info = pm.getActivityInfo(component, 0);
                if (info.exported && info.isEnabled()) return new Intent(Intent.ACTION_MAIN).setComponent(component);
            } catch (PackageManager.NameNotFoundException ignored) {
            }
        }
        return null;
    }

    /** The side panel (panel_inputs) with its title set; its rows are added to R.id.rows. */
    private View inflateSidePanel(CharSequence title) {
        View panel = getLayoutInflater().inflate(R.layout.panel_inputs, root, false);
        ((TextView) panel.findViewById(R.id.panelTitle)).setText(title);
        return panel;
    }

    /** True for an app the owner installed (not one that came with the TV), which Uninstall can remove. */
    private boolean isRemovable(String pkg) {
        try {
            ApplicationInfo ai = getPackageManager().getApplicationInfo(pkg, 0);
            return (ai.flags & ApplicationInfo.FLAG_SYSTEM) == 0;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /**
     * Asks about the next card that has no decision for its current signing key. One prompt at a
     * time, only over a laid out grid with window focus (not under a popup or another app), and
     * the next one follows as soon as this one is answered. Nothing is shown until the owner says Show.
     */
    private void showCardPrompt() {
        if (!moduleCards || !tilesReady || promptsPaused || cardPrompt != null || !hasWindowFocus() || isFinishing()
                || reordering) return;
        if (cards.isEmpty() || !CardFeature.working()) return;
        CardFeature.run(() -> {
            showCardPromptNow();
            return null;
        }, null);
    }

    private void showCardPromptNow() {
        final Cards.Card card = Cards.firstPending(cards);
        if (card == null) return;

        View panel = getLayoutInflater().inflate(R.layout.dialog_card_prompt, root, false);
        ((TextView) panel.findViewById(R.id.title)).setText(getString(R.string.card_prompt_title, card.appLabel));
        ((TextView) panel.findViewById(R.id.body)).setText(card.state == Cards.State.RESIGNED
                ? getString(R.string.card_prompt_resigned, card.appLabel)
                : card.state == Cards.State.RELABELLED
                ? getString(R.string.card_prompt_relabelled, card.appLabel)
                : getString(R.string.card_prompt_body, card.appLabel));
        TextView names = panel.findViewById(R.id.names);
        if (Cards.fold(card.label).equals(Cards.fold(card.appLabel))) names.setVisibility(View.GONE);
        else names.setText(getString(R.string.card_prompt_names, card.label, card.appLabel));
        ((TextView) panel.findViewById(R.id.identity)).setText(
                getString(R.string.card_prompt_identity, card.pkg, Cards.shortDigest(card.digest)));
        TextView keep = panel.findViewById(R.id.btnKeep);
        TextView show = panel.findViewById(R.id.btnShow);

        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(panel);
        dialog.setCanceledOnTouchOutside(false);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout((int) (440 * density), ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setDimAmount(0.6f);
        }
        // One decision per prompt: the buttons and Back (cancel) all end here.
        final boolean[] decided = {false};
        View.OnClickListener choose = v -> {
            if (decided[0]) return;
            decided[0] = true;
            cardPrompt = null;
            dialog.dismiss();
            decideCard(card, v.getId() == R.id.btnShow);   // guards its own card calls; the tile reload stays outside
            restoreFocus();   // the tiles changed under the prompt; this also chains the next prompt
        };
        keep.setOnClickListener(choose);
        show.setOnClickListener(choose);
        dialog.setOnCancelListener(d -> choose.onClick(keep));
        dialog.setOnDismissListener(d -> {
            if (cardPrompt == dialog) cardPrompt = null;
        });
        // Left and Right move between the two buttons; Up and Down stay put; the ends don't wrap.
        View.OnKeyListener keys = (v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) return true;
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) return v.getId() == R.id.btnKeep;
            return keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && v.getId() == R.id.btnShow;
        };
        keep.setOnKeyListener(keys);
        show.setOnKeyListener(keys);
        cardPrompt = dialog;
        dialog.show();
        keep.requestFocus();
    }

    /** Writes the owner's decision and rebuilds the tiles; focus is left where it is. */
    private void decideCard(Cards.Card card, boolean show) {
        boolean saved = CardFeature.run(() -> Cards.decide(Cards.storeFile(this), card, show), false);
        if (!saved) {
            Toast.makeText(this, R.string.card_not_saved, Toast.LENGTH_SHORT).show();
            promptsPaused = true;
        }
        reloadTiles();
    }

    /** Dropdown under the Settings pill (hold OK): every discovered card, its package and key; OK toggles Shown and Hidden. */
    private void showModuleCards() {
        if (cards.isEmpty() || !CardFeature.working()) {
            Toast.makeText(this, R.string.no_module_cards, Toast.LENGTH_SHORT).show();
            return;
        }
        CardFeature.run(() -> {
            showModuleCardsNow();
            return null;
        }, null);
    }

    private void showModuleCardsNow() {
        View anchor = findViewById(R.id.btnSettings);
        View panel = getLayoutInflater().inflate(R.layout.popup_cards, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        final PopupWindow popup = glassPopup(panel, (int) (400 * density));
        List<Cards.Card> listed = new ArrayList<>(cards);
        Collections.sort(listed, Cards::byLabel);
        for (Cards.Card card : listed) {
            View row = getLayoutInflater().inflate(R.layout.item_card_row, rows, false);
            row.setTag(card.pkg);
            ((TextView) row.findViewById(R.id.title)).setText(card.label);
            TextView app = row.findViewById(R.id.app);
            if (Cards.fold(card.label).equals(Cards.fold(card.appLabel))) app.setVisibility(View.GONE);
            else app.setText(getString(R.string.card_app_name, card.appLabel));
            ((TextView) row.findViewById(R.id.pkg)).setText(card.pkg);
            ((TextView) row.findViewById(R.id.digest)).setText(Cards.longDigest(card.digest));
            final String pkg = card.pkg;
            row.setOnClickListener(v -> CardFeature.run(() -> {
                // The list stays open; the decision is for the card as it is now, not as it was listed.
                Cards.Card now = cardFor(pkg);
                if (now == null) return null;
                decideCard(now, now.state != Cards.State.SHOWN);
                // Every row, not only this one: a toggle can move another card across the limit of 8.
                updateCardStates(rows);
                return null;
            }, null));
            rows.addView(row);
        }
        updateCardStates(rows);
        // Scroll inside the panel instead of running off the bottom of a 720p screen.
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int[] at = new int[2];
        anchor.getLocationInWindow(at);
        int room = root.getHeight() - (at[1] + anchor.getHeight()) - (int) (34 * density);
        popup.setHeight(Math.min(panel.getMeasuredHeight(), Math.max(room, (int) (120 * density))));
        // Hebrew places Settings on the left: align the outer panel inward, like its parent menu.
        dropIn(popup, panel, anchor, getResources().getBoolean(R.bool.rtl_menu_bar)
                ? Gravity.START : Gravity.END);
        rows.getChildAt(0).requestFocus();
    }

    private Cards.Card cardFor(String pkg) {
        for (Cards.Card c : cards) if (c.pkg.equals(pkg)) return c;
        return null;
    }

    /** Writes the state of every row in the Module cards list from the current card list. */
    private void updateCardStates(LinearLayout rows) {
        List<Cards.Card> drawn = Cards.shown(cards);
        for (int i = 0; i < rows.getChildCount(); i++) {
            View row = rows.getChildAt(i);
            Cards.Card card = row.getTag() instanceof String ? cardFor((String) row.getTag()) : null;
            ((TextView) row.findViewById(R.id.state)).setText(card != null ? stateText(card, drawn.contains(card)) : "");
        }
    }

    /** The row's state: a card the owner set to Shown but which is beyond the eight drawn says so. */
    private String stateText(Cards.Card card, boolean drawn) {
        switch (card.state) {
            case SHOWN:
                return getString(drawn ? R.string.card_state_shown : R.string.card_state_over_limit);
            case HIDDEN:
                return getString(R.string.card_state_hidden);
            case RESIGNED:
                return getString(R.string.card_state_new_key);
            case RELABELLED:
                return getString(R.string.card_state_new_label);
            default:
                return getString(R.string.card_state_new);
        }
    }
}
