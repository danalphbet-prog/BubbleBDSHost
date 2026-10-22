package com.bubble.bdshost;

import android.Manifest;
import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.net.Uri;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    public static final String EXTRA_OPEN_TAB = "bubble_open_tab";

    private static final int RED = Color.rgb(255, 20, 43);
    private static final int WHITE = Color.WHITE;
    private static final int CARD = Color.rgb(46, 14, 18);
    private static final int TERMINAL = Color.rgb(14, 13, 15);
    private static final int MUTED = Color.rgb(210, 198, 202);

    private Typeface minecraftRegular;
    private Typeface minecraftBold;

    private static final int TAB_HOME = 0;
    private static final int TAB_ADDONS = 1;
    private static final int TAB_CONSOLE = 2;
    private static final int REQUEST_IMPORT_ADDON = 4106;

    private static final String ADDON_FILTER_ALL = "all";
    private static final String ADDON_FILTER_BEHAVIOR = "behavior";
    private static final String ADDON_FILTER_RESOURCE = "resource";
    private static final String ADDON_FILTER_ACTIVE = "active";
    private static final String ADDON_FILTER_INACTIVE = "inactive";

    private final android.os.Handler handler =
            new android.os.Handler(android.os.Looper.getMainLooper());

    // Installer
    private ProgressBar generalProgress, stageProgress;
    private TextView generalLabel, stageLabel, installButton, versionSelector, installError;
    private LinearLayout versionOptions;
    private String selectedChannel = InstallerManager.CHANNEL_STABLE;

    // World / server form
    private EditText worldName, seed, serverName, maxPlayers, serverPort, idleTimeout;
    private EditText viewDistance, tickDistance, maxThreads;
    private Switch cheats, forceGamemode, allowList, onlineMode;
    private Switch resourcePackRequired, clientChunkGeneration;
    private TextView survival, creative, serverButton;
    private TextView[] difficultyButtons = new TextView[4];
    private TextView[] permissionButtons = new TextView[3];
    private boolean creativeMode;
    private String difficultyValue = "normal";
    private String permissionValue = "member";

    private LinearLayout configurationArea;
    private TextView configLockNotice;

    // Server summary
    private TextView infoState, infoVersion, infoWorld, infoMode, infoAddress, infoAddons;

    // BDS version manager
    private TextView bdsVersionSpinner;
    private PopupWindow bdsUpdateBubble;
    private volatile boolean bdsVersionChecking = false;
    private long lastBdsVersionCheckMs = 0L;
    private BdsVersionManager.Catalog bdsVersionCatalog;

    // Console
    private EditText commandInput;
    private TextView consoleText;
    private ScrollView consoleScroll;
    private boolean consoleAutoFollow = true;
    private long lastServerLogModified = -1;
    private String lastConsoleValue = "";

    // Navigation
    private FrameLayout dashboardContent;
    private View homePage, addonPage, consolePage;
    private TextView navHome, navAddons, navConsole;
    private int currentTab = TAB_HOME;

    // Add-on manager
    private LinearLayout addonPageBody, addonListContainer;
    private EditText addonSearchField, addonExploreSearchField;
    private TextView addonImportButton, addonExploreButton;
    private TextView addonStatus, addonWorldLabel, addonLockNotice, addonExplorePlaceholder;
    private final TextView[] addonFilterTags = new TextView[5];
    private final List<TextView> addonExploreCategoryViews = new ArrayList<>();
    private String addonFilter = ADDON_FILTER_ALL;
    private String addonExploreCategory = "Todos";
    private final List<View> addonActionViews = new ArrayList<>();

    // Collapsible category state
    private boolean worldOpen = true;
    private boolean serverOpen = false;
    private boolean performanceOpen = false;
    private boolean contentOpen = false;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        configureWindow();
        AppPaths.ensure(this);
        requestNotificationPermission();
        selectedChannel = InstallService.selectedChannel(this);
        handleIntent(getIntent(), false);
        render();
        handler.post(uiTicker);
    }

    @Override protected void onResume() {
        super.onResume();
        configureWindow();
        if (screenShouldChange()) render();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent, true);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_IMPORT_ADDON || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        if (BdsService.isRunning(this)) {
            Toast.makeText(this, "Pare o servidor antes de importar add-ons.", Toast.LENGTH_LONG).show();
            return;
        }
        if (addonStatus != null) addonStatus.setText("Importando e validando pacote...");
        if (addonImportButton != null) addonImportButton.setEnabled(false);

        new Thread(() -> {
            try {
                List<AddonManager.PackInfo> imported = AddonManager.importArchive(this, uri);
                handler.post(() -> {
                    if (addonStatus != null) {
                        addonStatus.setText(imported.size() + (imported.size() == 1
                                ? " pacote importado." : " pacotes importados."));
                    }
                    refreshAddonList();
                    setAddonLocked(BdsService.isRunning(this));
                    Toast.makeText(this, "Add-on importado com sucesso.", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                handler.post(() -> {
                    if (addonStatus != null) addonStatus.setText("Falha na importação: " + e.getMessage());
                    setAddonLocked(BdsService.isRunning(this));
                    Toast.makeText(this, "Não foi possível importar o add-on.", Toast.LENGTH_LONG).show();
                });
            }
        }, "bubble-addon-import").start();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) configureWindow();
    }

    @Override public void onConfigurationChanged(Configuration newConfig) {
        captureDraft();
        super.onConfigurationChanged(newConfig);
        configureWindow();
        render();
        restoreDraft();
    }

    private void handleIntent(Intent intent, boolean showNow) {
        if (intent == null) return;
        if ("console".equalsIgnoreCase(intent.getStringExtra(EXTRA_OPEN_TAB))) {
            currentTab = TAB_CONSOLE;
            if (showNow && ready()) showTab(TAB_CONSOLE, false);
        } else if ("addons".equalsIgnoreCase(intent.getStringExtra(EXTRA_OPEN_TAB))) {
            currentTab = TAB_ADDONS;
            if (showNow && ready()) showTab(TAB_ADDONS, false);
        } else if ("home".equalsIgnoreCase(intent.getStringExtra(EXTRA_OPEN_TAB))) {
            currentTab = TAB_HOME;
            if (showNow && ready()) showTab(TAB_HOME, false);
        }
    }

    private void configureWindow() {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setNavigationBarColor(Color.BLACK);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            View decor = getWindow().getDecorView();
            if (decor != null && decor.isAttachedToWindow()) {
                WindowInsetsController controller = getWindow().getInsetsController();
                if (controller != null) {
                    controller.hide(WindowInsets.Type.statusBars());
                    controller.setSystemBarsBehavior(
                            WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                }
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN |
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        }
    }

    private void applyBottomSafeInset(View root, int baseBottomDp) {
        final int left = root.getPaddingLeft();
        final int top = root.getPaddingTop();
        final int right = root.getPaddingRight();
        final int baseBottom = dp(baseBottomDp);

        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                int nav = insets.getInsets(WindowInsets.Type.navigationBars()).bottom;
                int ime = insets.getInsets(WindowInsets.Type.ime()).bottom;
                bottom = Math.max(nav, ime);
            } else {
                bottom = insets.getSystemWindowInsetBottom();
            }
            v.setPadding(left, top, right, baseBottom + bottom);
            return insets;
        });
        root.requestApplyInsets();
    }

    private boolean ready() {
        return RuntimeEngine.runtimePresent(this)
                && new File(AppPaths.rootfs(this), "bin/bash").exists()
                && AppPaths.box64(this).exists()
                && new File(AppPaths.bds(this), "bedrock_server").exists();
    }

    private boolean screenShouldChange() {
        Object tag = getWindow().getDecorView().getTag();
        boolean dashboard = ready();
        return !(tag instanceof Boolean) || ((Boolean) tag) != dashboard;
    }

    private void render() {
        boolean dashboard = ready();
        getWindow().getDecorView().setTag(dashboard);
        if (dashboard) buildDashboard();
        else buildInstaller();
    }

    private GradientDrawable pageBg() {
        return gradient(new int[]{
                Color.rgb(255,34,56),
                Color.rgb(248,9,38),
                Color.rgb(220,0,27)}, 0);
    }

    private GradientDrawable gradient(int[] colors, float radius) {
        GradientDrawable g = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR, colors);
        g.setCornerRadius(dp(radius));
        return g;
    }

    private GradientDrawable box(int fill, int stroke, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radius));
        g.setStroke(dp(1), stroke);
        return g;
    }

    private LinearLayout pageRoot() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.VERTICAL);
        int side = isWide() ? 28 : 16;
        r.setPadding(dp(side), dp(12), dp(side), dp(10));
        r.setBackground(pageBg());
        return r;
    }

    private Typeface appTypeface(int style) {
        try {
            if (style == Typeface.BOLD) {
                if (minecraftBold == null) {
                    minecraftBold = getResources().getFont(R.font.minecraft_bold);
                }
                return minecraftBold;
            }
            if (minecraftRegular == null) {
                minecraftRegular = getResources().getFont(R.font.minecraft_regular);
            }
            return minecraftRegular;
        } catch (Throwable ignored) {
            return Typeface.create("sans-serif", style);
        }
    }

    private void applyAppTypeface(TextView view, int style) {
        if (view != null) view.setTypeface(appTypeface(style));
    }

    private TextView tv(String text, float size, int color, int style) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(size);
        v.setTextColor(color);
        applyAppTypeface(v, style);
        return v;
    }

    private LinearLayout header() { return header(false); }

    private LinearLayout homeHeader() { return header(true); }

    private LinearLayout header(boolean showVersionControl) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, 0, 0, dp(10));

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.bubble_internal_icon);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        row.addView(logo, new LinearLayout.LayoutParams(dp(46), dp(46)));

        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        words.setGravity(Gravity.CENTER_VERTICAL);
        words.setPadding(dp(10), 0, 0, 0);

        ImageView wordmark = new ImageView(this);
        wordmark.setImageResource(R.drawable.bubble_wordmark);
        wordmark.setScaleType(ImageView.ScaleType.FIT_START);
        wordmark.setAdjustViewBounds(true);
        words.addView(wordmark, new LinearLayout.LayoutParams(
                isWide() ? dp(220) : dp(178),
                isWide() ? dp(32) : dp(27)));

        TextView sub = tv("S E R V E R   H O S T", 9.5f, WHITE, Typeface.BOLD);
        sub.setAlpha(.82f);
        words.addView(sub, match(0,2,0,0));
        row.addView(words, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        if (showVersionControl) {
            bdsVersionSpinner = tv(versionSpinnerText(), 10.5f, WHITE, Typeface.BOLD);
            bdsVersionSpinner.setGravity(Gravity.CENTER);
            bdsVersionSpinner.setPadding(dp(10), dp(8), dp(10), dp(8));
            bdsVersionSpinner.setBackground(box(
                    Color.argb(72,30,12,16),
                    Color.argb(115,255,255,255), 14));
            bdsVersionSpinner.setClickable(true);
            bdsVersionSpinner.setFocusable(true);
            bdsVersionSpinner.setOnClickListener(v -> showBdsVersionModal());
            LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(
                    isWide() ? dp(150) : dp(112), dp(46));
            vp.setMargins(dp(8), 0, 0, 0);
            row.addView(bdsVersionSpinner, vp);
        }

        return row;
    }

    private TextView mainButton(String label) {
        TextView b = tv(label, 16.5f, WHITE, Typeface.BOLD);
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(dp(60));
        b.setPadding(dp(14), dp(15), dp(14), dp(15));
        b.setBackground(gradient(new int[]{
                Color.rgb(255,54,76),
                Color.rgb(255,20,43),
                Color.rgb(237,18,48)}, 32));
        b.setClickable(true);
        b.setFocusable(true);
        return b;
    }

    private TextView smallButton(String glyph) {
        TextView b = tv(glyph, 18, WHITE, Typeface.BOLD);
        b.setGravity(Gravity.CENTER);
        b.setBackground(box(
                Color.argb(48,255,255,255),
                Color.argb(55,255,255,255), 12));
        b.setClickable(true);
        return b;
    }

    // -----------------------------------------------------------------
    // Installer
    // -----------------------------------------------------------------
    private void buildInstaller() {
        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setBackground(pageBg());
        applyBottomSafeInset(screen, 6);

        LinearLayout top = pageRoot();
        top.setBackgroundColor(Color.TRANSPARENT);
        top.addView(header());

        ScrollView contentScroll = new ScrollView(this);
        contentScroll.setFillViewport(true);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);

        ImageView hero = new ImageView(this);
        hero.setImageResource(R.drawable.bubble_internal_icon);
        hero.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(dp(92), dp(92));
        hp.gravity = Gravity.CENTER_HORIZONTAL;
        content.addView(hero, hp);

        TextView heroTitle = tv("Sua Bubble começa aqui", 21, WHITE, Typeface.BOLD);
        heroTitle.setGravity(Gravity.CENTER);
        content.addView(heroTitle, match(0,8,0,5));

        TextView heroSub = tv(
                "Prepare o ambiente local para hospedar mundos e experiências compartilhadas.",
                11.5f, Color.argb(230,255,238,241), Typeface.NORMAL);
        heroSub.setGravity(Gravity.CENTER);
        heroSub.setPadding(dp(14),0,dp(14),0);
        content.addView(heroSub, match(0,0,0,13));

        content.addView(infoCard(
                "◈  Ambiente sandbox",
                "Cria uma área isolada no aparelho para executar o host local com segurança e persistência."),
                match(0,0,0,8));
        content.addView(infoCard(
                "⌁  Compatibilidade virtual",
                "Prepara o compilador virtual e os componentes de compatibilidade necessários para o servidor."),
                match(0,0,0,8));
        content.addView(infoCard(
                "⬡  Núcleo do servidor",
                "Baixa, valida e prepara a versão selecionada sem expor detalhes internos da implementação."),
                match(0,0,0,8));
        content.addView(infoCard(
                "↻  Execução resiliente",
                "A preparação continua em segundo plano e pode retomar etapas interrompidas."),
                match(0,0,0,10));

        contentScroll.addView(content);
        top.addView(contentScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        screen.addView(top, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        screen.addView(buildInstallBottomPanel());

        setContentView(screen);
        refreshInstallUi();
    }

    private View infoCard(String title, String body) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(15),dp(13),dp(15),dp(13));
        card.setBackground(box(
                Color.argb(76,55,17,22),
                Color.argb(70,255,255,255),18));
        card.addView(tv(title,14.5f,WHITE,Typeface.BOLD));
        TextView bodyView = tv(body,10.5f,MUTED,Typeface.NORMAL);
        bodyView.setPadding(0,dp(5),0,0);
        card.addView(bodyView);
        return card;
    }

    private View buildInstallBottomPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(18),dp(12),dp(18),dp(12));
        panel.setBackground(box(
                Color.rgb(77,10,20),
                Color.argb(90,255,135,150),24));

        stageLabel = tv("Processo • aguardando",10.5f,WHITE,Typeface.BOLD);
        panel.addView(stageLabel);
        stageProgress = horizontalProgress();
        panel.addView(stageProgress, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(6)));

        generalLabel = tv("Preparação geral • 0%",10.5f,MUTED,Typeface.NORMAL);
        panel.addView(generalLabel, match(0,8,0,0));
        generalProgress = horizontalProgress();
        panel.addView(generalProgress, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(6)));

        versionSelector = tv("",12.5f,WHITE,Typeface.BOLD);
        versionSelector.setGravity(Gravity.CENTER_VERTICAL);
        versionSelector.setPadding(dp(14),0,dp(14),0);
        versionSelector.setBackground(box(
                Color.rgb(49,22,27), Color.rgb(115,78,86),14));
        versionSelector.setOnClickListener(v -> toggleVersionOptions());
        panel.addView(versionSelector, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(62)));

        versionOptions = new LinearLayout(this);
        versionOptions.setOrientation(LinearLayout.VERTICAL);
        versionOptions.setVisibility(View.GONE);
        versionOptions.setPadding(0,dp(5),0,0);

        TextView stable = versionOption(
                "Atual / Estável", InstallerManager.BDS_STABLE_VERSION);
        stable.setOnClickListener(v -> selectChannel(InstallerManager.CHANNEL_STABLE));
        TextView preview = versionOption(
                "Preview / Beta", InstallerManager.BDS_PREVIEW_VERSION);
        preview.setOnClickListener(v -> selectChannel(InstallerManager.CHANNEL_PREVIEW));
        versionOptions.addView(stable, match(0,0,0,5));
        versionOptions.addView(preview);
        panel.addView(versionOptions);

        installButton = mainButton("⇩   Preparar Ambiente");
        installButton.setOnClickListener(v -> startInstall());
        panel.addView(installButton, match(0,9,0,0));

        installError = tv("",9.5f,Color.rgb(255,205,211),Typeface.NORMAL);
        installError.setGravity(Gravity.CENTER);
        panel.addView(installError, match(0,5,0,0));

        updateVersionSelector();
        return panel;
    }

    private ProgressBar horizontalProgress() {
        ProgressBar p = new ProgressBar(
                this, null, android.R.attr.progressBarStyleHorizontal);
        p.setMax(100);
        p.setProgressTintList(ColorStateList.valueOf(WHITE));
        p.setProgressBackgroundTintList(
                ColorStateList.valueOf(Color.argb(62,255,255,255)));
        return p;
    }

    private TextView versionOption(String name, String version) {
        TextView v = tv(name + "   •   " + version,12,WHITE,Typeface.BOLD);
        v.setGravity(Gravity.CENTER_VERTICAL);
        v.setPadding(dp(14),0,dp(14),0);
        v.setBackground(box(
                Color.rgb(55,27,32), Color.rgb(100,69,76),12));
        v.setMinHeight(dp(60));
        v.setClickable(true);
        return v;
    }

    private void toggleVersionOptions() {
        if (InstallService.isRunning(this)) return;
        versionOptions.setVisibility(
                versionOptions.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
    }

    private void selectChannel(String channel) {
        selectedChannel = channel;
        versionOptions.setVisibility(View.GONE);
        updateVersionSelector();
    }

    private void updateVersionSelector() {
        if (versionSelector == null) return;
        versionSelector.setText(
                InstallerManager.CHANNEL_PREVIEW.equals(selectedChannel)
                        ? "Preview / Beta   •   " + InstallerManager.BDS_PREVIEW_VERSION + "     ▾"
                        : "Atual / Estável   •   " + InstallerManager.BDS_STABLE_VERSION + "     ▾");
    }

    private void startInstall() {
        if (InstallService.isRunning(this)) return;
        Intent i = new Intent(this, InstallService.class)
                .setAction(InstallService.ACTION_START)
                .putExtra(InstallService.EXTRA_CHANNEL, selectedChannel);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
        else startService(i);
        refreshInstallUi();
    }

    private void refreshInstallUi() {
        if (generalProgress == null) return;
        boolean running = InstallService.isRunning(this);
        int g = InstallService.generalProgress(this);
        int s = InstallService.stageProgress(this);
        String stage = InstallService.stage(this);
        String error = InstallService.error(this);

        generalProgress.setProgress(g);
        stageProgress.setProgress(s);
        generalLabel.setText("Preparação geral • " + g + "%");
        stageLabel.setText(stage + " • " + s + "%");
        installButton.setText(running
                ? "↻   Preparando em segundo plano..."
                : "⇩   Preparar Ambiente");
        installButton.setEnabled(!running);
        installButton.setAlpha(running ? .72f : 1f);
        versionSelector.setEnabled(!running);
        versionSelector.setAlpha(running ? .72f : 1f);
        installError.setText(error == null ? "" : error);

        String stored = InstallService.selectedChannel(this);
        if (running && stored != null) {
            selectedChannel = stored;
            updateVersionSelector();
        }
        if (!running && g >= 100 && ready()) {
            handler.postDelayed(this::render, 250);
        }
    }

    // -----------------------------------------------------------------
    // Dashboard
    // -----------------------------------------------------------------
    private void buildDashboard() {
        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setBackground(pageBg());
        applyBottomSafeInset(shell, 0);

        dashboardContent = new FrameLayout(this);
        homePage = buildHomePage();
        addonPage = buildAddonPage();
        consolePage = buildConsolePage();

        dashboardContent.addView(homePage, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        dashboardContent.addView(addonPage, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        dashboardContent.addView(consolePage, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        shell.addView(dashboardContent, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        shell.addView(buildBottomNavigation());

        setContentView(shell);
        loadConfig();
        showTab(currentTab, false);
        updateServerUi();
        refreshServerConsole(true);
        checkBdsUpdatesInBackground(false);
    }

    private View buildHomePage() {
        LinearLayout page = pageRoot();
        page.addView(homeHeader());

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);

        content.addView(serverInfoCard(), match(0,0,0,10));

        configLockNotice = tv(
                "🔒 Configurações bloqueadas enquanto o servidor estiver ativo.",
                10.5f, WHITE, Typeface.BOLD);
        configLockNotice.setGravity(Gravity.CENTER);
        configLockNotice.setPadding(dp(12),dp(10),dp(12),dp(10));
        configLockNotice.setBackground(box(
                Color.argb(80,20,10,12),
                Color.argb(90,255,255,255),14));
        configLockNotice.setVisibility(View.GONE);
        content.addView(configLockNotice, match(0,0,0,9));

        configurationArea = new LinearLayout(this);
        configurationArea.setOrientation(LinearLayout.VERTICAL);

        configurationArea.addView(categoryCard(
                "◈ Mundo",
                "Identidade, geração e regras básicas",
                buildWorldCategory(),
                worldOpen,
                0), match(0,0,0,8));

        configurationArea.addView(categoryCard(
                "⬡ Servidor",
                "Nome, jogadores, rede e acesso",
                buildServerCategory(),
                serverOpen,
                1), match(0,0,0,8));

        configurationArea.addView(categoryCard(
                "⚙ Performance",
                "Distâncias, threads e geração",
                buildPerformanceCategory(),
                performanceOpen,
                2), match(0,0,0,8));

        configurationArea.addView(categoryCard(
                "▦ Conteúdo",
                "Pacotes, permissões e recursos",
                buildContentCategory(),
                contentOpen,
                3), match(0,0,0,10));

        content.addView(configurationArea);

        serverButton = mainButton("▶  Iniciar Servidor");
        serverButton.setOnClickListener(v -> toggleServer());
        content.addView(serverButton, match(0,0,0,10));

        scroll.addView(content);
        page.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        return page;
    }

    private View categoryCard(
            String title,
            String subtitle,
            View categoryContent,
            boolean expanded,
            int categoryIndex) {

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14),dp(12),dp(14),dp(12));
        card.setBackground(box(CARD,Color.argb(70,255,90,110),20));

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setClickable(true);

        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        words.addView(tv(title,16.5f,WHITE,Typeface.BOLD));
        TextView sub = tv(subtitle,10.5f,MUTED,Typeface.NORMAL);
        sub.setPadding(0,dp(2),0,0);
        words.addView(sub);
        head.addView(words,new LinearLayout.LayoutParams(
                0,ViewGroup.LayoutParams.WRAP_CONTENT,1));

        TextView arrow = tv(expanded ? "−" : "+",22,WHITE,Typeface.BOLD);
        arrow.setGravity(Gravity.CENTER);
        head.addView(arrow,new LinearLayout.LayoutParams(dp(42),dp(42)));

        categoryContent.setVisibility(expanded ? View.VISIBLE : View.GONE);
        head.setOnClickListener(v -> {
            boolean open = categoryContent.getVisibility() != View.VISIBLE;
            categoryContent.setVisibility(open ? View.VISIBLE : View.GONE);
            arrow.setText(open ? "−" : "+");
            setCategoryOpen(categoryIndex, open);
        });

        card.addView(head);
        card.addView(categoryContent, match(0,10,0,0));
        return card;
    }

    private void setCategoryOpen(int index, boolean value) {
        if (index == 0) worldOpen = value;
        else if (index == 1) serverOpen = value;
        else if (index == 2) performanceOpen = value;
        else if (index == 3) contentOpen = value;
    }

    private View buildWorldCategory() {
        LinearLayout c = categoryContent();

        worldName = field("Meu Mundo Bubble");
        seed = field("Opcional");
        seed.setInputType(InputType.TYPE_CLASS_TEXT);

        c.addView(responsivePair(
                fieldBlock("Nome do mundo", worldName),
                fieldBlock("Seed", seed)), match(0,0,0,11));

        c.addView(label("Modo de jogo"));
        LinearLayout modes = new LinearLayout(this);
        modes.setOrientation(LinearLayout.HORIZONTAL);
        survival = segment("⚔ Survival", true);
        creative = segment("◇ Criativo", false);
        survival.setOnClickListener(v -> mode(false));
        creative.setOnClickListener(v -> mode(true));
        modes.addView(survival,new LinearLayout.LayoutParams(0,dp(60),1));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0,dp(60),1);
        cp.setMargins(dp(7),0,0,0);
        modes.addView(creative,cp);
        c.addView(modes, match(0,5,0,11));

        c.addView(label("Dificuldade"));
        String[] diffs = {"Pacífico","Fácil","Normal","Difícil"};
        String[] values = {"peaceful","easy","normal","hard"};
        LinearLayout diffRow = new LinearLayout(this);
        diffRow.setOrientation(LinearLayout.HORIZONTAL);
        for (int i=0;i<4;i++) {
            final String value = values[i];
            difficultyButtons[i] = segment(diffs[i], "normal".equals(value));
            difficultyButtons[i].setTextSize(isWide() ? 12.5f : 10.5f);
            difficultyButtons[i].setOnClickListener(v -> setDifficulty(value));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,dp(56),1);
            if (i>0) lp.setMargins(dp(5),0,0,0);
            diffRow.addView(difficultyButtons[i],lp);
        }
        c.addView(diffRow, match(0,5,0,11));

        cheats = styledSwitch("Cheats", "Permitir comandos no mundo");
        forceGamemode = styledSwitch(
                "Forçar modo de jogo",
                "Jogadores entram no modo definido acima");

        c.addView(responsivePair(
                switchBlock(cheats),
                switchBlock(forceGamemode)));

        TextView experimentHint = tv(
                "◈  Experimentos padrão: Beta APIs / GameTest + recursos de blocos e itens", 
                10.5f, WHITE, Typeface.BOLD);
        experimentHint.setPadding(dp(12),dp(12),dp(12),dp(12));
        experimentHint.setBackground(box(
                Color.rgb(66,20,30), Color.rgb(181,49,67), 14));
        c.addView(experimentHint, match(0,11,0,3));
        return c;
    }

    private View buildServerCategory() {
        LinearLayout c = categoryContent();

        serverName = field("Bubble BDS Host");
        maxPlayers = numericField("8");
        serverPort = numericField("19132");
        idleTimeout = numericField("30");

        c.addView(fieldBlock("Nome do servidor", serverName), match(0,0,0,10));
        c.addView(responsivePair(
                fieldBlock("Máximo de jogadores", maxPlayers),
                fieldBlock("Porta", serverPort)), match(0,0,0,10));
        c.addView(fieldBlock("Timeout AFK (minutos)", idleTimeout), match(0,0,0,10));

        allowList = styledSwitch("Lista de acesso", "Somente jogadores autorizados");
        onlineMode = styledSwitch("Validação online", "Validar identidade dos jogadores");

        c.addView(responsivePair(
                switchBlock(allowList),
                switchBlock(onlineMode)));

        return c;
    }

    private View buildPerformanceCategory() {
        LinearLayout c = categoryContent();

        viewDistance = numericField("32");
        tickDistance = numericField("4");
        maxThreads = numericField("8");

        c.addView(responsivePair(
                fieldBlock("Distância de visão", viewDistance),
                fieldBlock("Distância de simulação", tickDistance)), match(0,0,0,10));
        c.addView(fieldBlock("Threads máximas (0 = automático)", maxThreads), match(0,0,0,10));

        clientChunkGeneration = styledSwitch(
                "Geração pelo cliente",
                "Permitir geração de chunks pelo cliente");
        c.addView(switchBlock(clientChunkGeneration));

        return c;
    }

    private View buildContentCategory() {
        LinearLayout c = categoryContent();

        c.addView(label("Permissão padrão"));
        String[] labels = {"Visitante","Membro","Operador"};
        String[] values = {"visitor","member","operator"};
        LinearLayout permRow = new LinearLayout(this);
        permRow.setOrientation(LinearLayout.HORIZONTAL);
        for (int i=0;i<3;i++) {
            final String value = values[i];
            permissionButtons[i] = segment(labels[i], "member".equals(value));
            permissionButtons[i].setTextSize(isWide() ? 12.5f : 11f);
            permissionButtons[i].setOnClickListener(v -> setPermission(value));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,dp(58),1);
            if (i>0) lp.setMargins(dp(5),0,0,0);
            permRow.addView(permissionButtons[i],lp);
        }
        c.addView(permRow, match(0,5,0,11));

        resourcePackRequired = styledSwitch(
                "Pacote de recursos obrigatório",
                "Exigir aceite do pacote para entrar");
        c.addView(switchBlock(resourcePackRequired), match(0,0,0,8));

        TextView addons = tv(
                "Add-ons ativos são detectados automaticamente no mundo.",
                10.5f,MUTED,Typeface.NORMAL);
        addons.setPadding(dp(2),dp(4),dp(2),dp(2));
        c.addView(addons);
        return c;
    }

    private LinearLayout categoryContent() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        return c;
    }

    private View fieldBlock(String title, EditText field) {
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        block.addView(label(title));
        block.addView(field, fixedHeight(76,0,5,0,0));
        return block;
    }

    private View switchBlock(Switch sw) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setGravity(Gravity.CENTER_VERTICAL);
        wrap.setMinimumHeight(dp(68));
        wrap.setPadding(dp(12),dp(7),dp(8),dp(7));
        wrap.setBackground(box(
                Color.rgb(39,24,27),
                Color.rgb(87,66,72),14));
        wrap.addView(sw,new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return wrap;
    }

    private Switch styledSwitch(String title, String subtitle) {
        Switch sw = new Switch(this);
        sw.setText(title + "\n" + subtitle);
        sw.setTextColor(MUTED);
        sw.setTextSize(11.5f);
        applyAppTypeface(sw, Typeface.NORMAL);
        sw.setGravity(Gravity.CENTER_VERTICAL);
        sw.setPadding(dp(2),0,0,0);
        sw.setThumbTintList(new ColorStateList(
                new int[][]{
                        new int[]{android.R.attr.state_checked},
                        new int[]{}},
                new int[]{WHITE,Color.rgb(210,205,207)}));
        sw.setTrackTintList(new ColorStateList(
                new int[][]{
                        new int[]{android.R.attr.state_checked},
                        new int[]{}},
                new int[]{RED,Color.rgb(92,72,78)}));
        return sw;
    }

    private View responsivePair(View a, View b) {
        LinearLayout row = new LinearLayout(this);
        if (isWide()) {
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.addView(a,new LinearLayout.LayoutParams(
                    0,ViewGroup.LayoutParams.WRAP_CONTENT,1));
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                    0,ViewGroup.LayoutParams.WRAP_CONTENT,1);
            bp.setMargins(dp(9),0,0,0);
            row.addView(b,bp);
        } else {
            row.setOrientation(LinearLayout.VERTICAL);
            row.addView(a,new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            bp.setMargins(0,dp(9),0,0);
            row.addView(b,bp);
        }
        return row;
    }

    private boolean isWide() {
        return getResources().getConfiguration().screenWidthDp >= 600;
    }

    private View buildAddonPage() {
        LinearLayout page = pageRoot();
        page.addView(header());

        addonPageBody = new LinearLayout(this);
        addonPageBody.setOrientation(LinearLayout.VERTICAL);
        page.addView(addonPageBody, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        renderAddonLibraryScreen();
        return page;
    }

    private void renderAddonLibraryScreen() {
        if (addonPageBody == null) return;
        addonPageBody.removeAllViews();
        addonActionViews.clear();

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout title = new LinearLayout(this);
        title.setOrientation(LinearLayout.VERTICAL);
        title.addView(tv("Addon Bubble", 20, WHITE, Typeface.BOLD));

        addonWorldLabel = tv("", 9.8f, Color.argb(225,255,236,239), Typeface.NORMAL);
        title.addView(addonWorldLabel, match(0,2,0,0));
        top.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        addonExploreButton = addonIconButton("⌕", "Explorar add-ons");
        addonExploreButton.setOnClickListener(v -> renderAddonExploreScreen());
        top.addView(addonExploreButton, new LinearLayout.LayoutParams(dp(48),dp(48)));

        addonImportButton = addonIconButton("⇩", "Importar add-on");
        addonImportButton.setOnClickListener(v -> openAddonPicker());
        addonActionViews.add(addonImportButton);
        LinearLayout.LayoutParams importLp = new LinearLayout.LayoutParams(dp(48),dp(48));
        importLp.setMargins(dp(7),0,0,0);
        top.addView(addonImportButton, importLp);

        addonPageBody.addView(top, match(0,0,0,8));

        addonLockNotice = tv(
                "🔒 Servidor ativo • você pode pesquisar e visualizar; alterações ficam bloqueadas.",
                9.8f, WHITE, Typeface.BOLD);
        addonLockNotice.setGravity(Gravity.CENTER);
        addonLockNotice.setPadding(dp(11),dp(8),dp(11),dp(8));
        addonLockNotice.setBackground(box(
                Color.argb(80,20,10,12),
                Color.argb(90,255,255,255),14));
        addonLockNotice.setVisibility(View.GONE);
        addonPageBody.addView(addonLockNotice, match(0,0,0,7));

        addonSearchField = field("Pesquisar add-ons...");
        addonSearchField.setSingleLine(true);
        addonSearchField.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        addonSearchField.setPadding(dp(16),0,dp(16),0);
        addonSearchField.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                refreshAddonList();
            }
            @Override public void afterTextChanged(android.text.Editable s) {}
        });
        addonPageBody.addView(addonSearchField, fixedHeight(62,0,0,0,8));

        android.widget.HorizontalScrollView tagScroll = new android.widget.HorizontalScrollView(this);
        tagScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout tags = new LinearLayout(this);
        tags.setOrientation(LinearLayout.HORIZONTAL);

        String[] tagNames = {"Todos","Comportamentos","Textura","Ativos","Desativados"};
        String[] tagValues = {
                ADDON_FILTER_ALL,
                ADDON_FILTER_BEHAVIOR,
                ADDON_FILTER_RESOURCE,
                ADDON_FILTER_ACTIVE,
                ADDON_FILTER_INACTIVE
        };

        for (int i = 0; i < tagNames.length; i++) {
            final String value = tagValues[i];
            addonFilterTags[i] = addonTag(tagNames[i], value.equals(addonFilter));
            addonFilterTags[i].setOnClickListener(v -> {
                addonFilter = value;
                updateAddonFilterTags();
                refreshAddonList();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(42));
            if (i > 0) lp.setMargins(dp(6),0,0,0);
            tags.addView(addonFilterTags[i], lp);
        }
        tagScroll.addView(tags);
        addonPageBody.addView(tagScroll, match(0,0,0,8));

        addonStatus = tv(
                "Biblioteca local de add-ons",
                9.5f, MUTED, Typeface.NORMAL);
        addonStatus.setGravity(Gravity.CENTER_VERTICAL);
        addonPageBody.addView(addonStatus, match(2,0,2,6));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        addonListContainer = new LinearLayout(this);
        addonListContainer.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(addonListContainer);
        addonPageBody.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        refreshAddonList();
        setAddonLocked(BdsService.isRunning(this));
    }

    private TextView addonIconButton(String glyph, String description) {
        TextView button = tv(glyph, 21, WHITE, Typeface.BOLD);
        button.setGravity(Gravity.CENTER);
        button.setContentDescription(description);
        button.setClickable(true);
        button.setFocusable(true);
        button.setBackground(box(
                Color.argb(65,28,13,17),
                Color.argb(120,255,255,255), 13));
        return button;
    }

    private TextView addonTag(String label, boolean active) {
        TextView tag = tv(label, 9.7f, active ? WHITE : MUTED, Typeface.BOLD);
        tag.setGravity(Gravity.CENTER);
        tag.setPadding(dp(13),0,dp(13),0);
        tag.setClickable(true);
        tag.setFocusable(true);
        styleAddonTag(tag, active);
        return tag;
    }

    private void styleAddonTag(TextView tag, boolean active) {
        if (tag == null) return;
        tag.setBackground(box(
                active ? Color.rgb(126,18,35) : Color.rgb(42,27,31),
                active ? Color.rgb(255,74,95) : Color.rgb(82,62,68), 18));
        tag.setTextColor(active ? WHITE : MUTED);
        tag.setAlpha(active ? 1f : .78f);
    }

    private void updateAddonFilterTags() {
        String[] values = {
                ADDON_FILTER_ALL,
                ADDON_FILTER_BEHAVIOR,
                ADDON_FILTER_RESOURCE,
                ADDON_FILTER_ACTIVE,
                ADDON_FILTER_INACTIVE
        };
        for (int i = 0; i < addonFilterTags.length; i++) {
            styleAddonTag(addonFilterTags[i], values[i].equals(addonFilter));
        }
    }

    private void renderAddonExploreScreen() {
        if (addonPageBody == null) return;
        addonPageBody.removeAllViews();
        addonActionViews.clear();
        addonLockNotice = null;
        addonImportButton = null;

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);

        TextView back = addonIconButton("‹", "Voltar para meus add-ons");
        back.setTextSize(28);
        back.setOnClickListener(v -> renderAddonLibraryScreen());
        top.addView(back, new LinearLayout.LayoutParams(dp(48),dp(48)));

        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        words.setPadding(dp(10),0,0,0);
        words.addView(tv("Explorar Addons", 19, WHITE, Typeface.BOLD));
        words.addView(tv(
                "Descubra novos conteúdos para o seu mundo",
                9.5f, Color.argb(220,255,236,239), Typeface.NORMAL));
        top.addView(words, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        addonPageBody.addView(top, match(0,0,0,9));

        addonExploreSearchField = field("Pesquisar no catálogo...");
        addonExploreSearchField.setSingleLine(true);
        addonExploreSearchField.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        addonExploreSearchField.setPadding(dp(16),0,dp(16),0);
        addonExploreSearchField.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                updateExplorePlaceholder();
            }
            @Override public void afterTextChanged(android.text.Editable s) {}
        });
        addonPageBody.addView(addonExploreSearchField, fixedHeight(62,0,0,0,9));

        TextView categoryLabel = tv("CATEGORIAS", 9.2f, WHITE, Typeface.BOLD);
        categoryLabel.setAlpha(.8f);
        addonPageBody.addView(categoryLabel, match(2,0,2,6));

        android.widget.HorizontalScrollView categoryScroll =
                new android.widget.HorizontalScrollView(this);
        categoryScroll.setHorizontalScrollBarEnabled(false);

        LinearLayout categories = new LinearLayout(this);
        categories.setOrientation(LinearLayout.HORIZONTAL);
        addonExploreCategoryViews.clear();

        String[] categoryNames = {
                "Todos",
                "Fantasia",
                "Ficção científica",
                "Aventura",
                "Tecnologia",
                "Sobrevivência",
                "Utilidades"
        };

        for (int i = 0; i < categoryNames.length; i++) {
            final String category = categoryNames[i];
            TextView chip = addonTag(category, category.equals(addonExploreCategory));
            chip.setTag(category);
            chip.setOnClickListener(v -> {
                addonExploreCategory = category;
                updateExploreCategories();
                updateExplorePlaceholder();
            });
            addonExploreCategoryViews.add(chip);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(42));
            if (i > 0) lp.setMargins(dp(6),0,0,0);
            categories.addView(chip, lp);
        }
        categoryScroll.addView(categories);
        addonPageBody.addView(categoryScroll, match(0,0,0,10));

        LinearLayout filterRow = new LinearLayout(this);
        filterRow.setOrientation(LinearLayout.HORIZONTAL);

        TextView featured = addonMiniFilter("✦  Em destaque");
        TextView newest = addonMiniFilter("◷  Recentes");
        TextView downloads = addonMiniFilter("⇩  Mais baixados");

        filterRow.addView(featured, new LinearLayout.LayoutParams(0,dp(44),1));
        LinearLayout.LayoutParams fp2 = new LinearLayout.LayoutParams(0,dp(44),1);
        fp2.setMargins(dp(5),0,0,0);
        filterRow.addView(newest, fp2);
        LinearLayout.LayoutParams fp3 = new LinearLayout.LayoutParams(0,dp(44),1);
        fp3.setMargins(dp(5),0,0,0);
        filterRow.addView(downloads, fp3);

        addonPageBody.addView(filterRow, match(0,0,0,11));

        ScrollView results = new ScrollView(this);
        results.setFillViewport(true);

        LinearLayout resultBody = new LinearLayout(this);
        resultBody.setOrientation(LinearLayout.VERTICAL);
        resultBody.setGravity(Gravity.CENTER);

        addonExplorePlaceholder = tv("", 11, MUTED, Typeface.NORMAL);
        addonExplorePlaceholder.setGravity(Gravity.CENTER);
        addonExplorePlaceholder.setPadding(dp(20),dp(30),dp(20),dp(30));
        addonExplorePlaceholder.setBackground(box(
                Color.rgb(42,25,29),
                Color.rgb(85,61,68),18));
        resultBody.addView(addonExplorePlaceholder, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        results.addView(resultBody);
        addonPageBody.addView(results, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,0,1));

        updateExploreCategories();
        updateExplorePlaceholder();
    }

    private TextView addonMiniFilter(String label) {
        TextView filter = tv(label, isWide() ? 9.5f : 8.2f, MUTED, Typeface.BOLD);
        filter.setGravity(Gravity.CENTER);
        filter.setBackground(box(
                Color.rgb(39,26,29),
                Color.rgb(79,61,66),12));
        return filter;
    }

    private void updateExploreCategories() {
        for (TextView chip : addonExploreCategoryViews) {
            Object tag = chip.getTag();
            styleAddonTag(chip,
                    tag != null && tag.toString().equals(addonExploreCategory));
        }
    }

    private void updateExplorePlaceholder() {
        if (addonExplorePlaceholder == null) return;
        String query = addonExploreSearchField == null
                ? "" : addonExploreSearchField.getText().toString().trim();

        StringBuilder message = new StringBuilder();
        message.append("CATÁLOGO ONLINE\n\n");
        message.append("Categoria: ").append(addonExploreCategory);
        if (!query.isEmpty()) {
            message.append("\nPesquisa: “").append(query).append("”");
        }
        message.append("\n\n");
        message.append("A interface de descoberta já está pronta. ");
        message.append("Os resultados aparecerão aqui quando o catálogo Firebase Realtime Database for conectado.");
        addonExplorePlaceholder.setText(message.toString());
    }

    private void openAddonPicker() {
        if (BdsService.isRunning(this)) {
            Toast.makeText(this, "Pare o servidor antes de importar add-ons.", Toast.LENGTH_LONG).show();
            return;
        }
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        pick.addCategory(Intent.CATEGORY_OPENABLE);
        pick.setType("*/*");
        startActivityForResult(Intent.createChooser(pick, "Selecionar add-on"), REQUEST_IMPORT_ADDON);
    }

    private String addonWorldName() {
        if (worldName != null) {
            String draft = worldName.getText().toString().trim();
            if (!draft.isEmpty()) return draft;
        }
        return getProperty("level-name", "Meu Mundo Bubble");
    }

    private void refreshAddonList() {
        if (addonListContainer == null) return;

        addonListContainer.removeAllViews();
        addonActionViews.clear();
        if (addonImportButton != null) addonActionViews.add(addonImportButton);

        String world = addonWorldName();
        if (addonWorldLabel != null) addonWorldLabel.setText("Mundo alvo: " + world);

        try {
            List<AddonManager.PackInfo> packs = new ArrayList<>();
            packs.addAll(AddonManager.listPacks(
                    this, world, AddonManager.TYPE_BEHAVIOR));
            packs.addAll(AddonManager.listPacks(
                    this, world, AddonManager.TYPE_RESOURCE));

            String query = addonSearchField == null
                    ? "" : addonSearchField.getText().toString().trim().toLowerCase(Locale.ROOT);

            List<AddonManager.PackInfo> visible = new ArrayList<>();
            int activeCount = 0;

            for (AddonManager.PackInfo pack : packs) {
                if (pack.active) activeCount++;

                boolean typeOk = true;
                if (ADDON_FILTER_BEHAVIOR.equals(addonFilter)) {
                    typeOk = AddonManager.TYPE_BEHAVIOR.equals(pack.type);
                } else if (ADDON_FILTER_RESOURCE.equals(addonFilter)) {
                    typeOk = AddonManager.TYPE_RESOURCE.equals(pack.type);
                } else if (ADDON_FILTER_ACTIVE.equals(addonFilter)) {
                    typeOk = pack.active;
                } else if (ADDON_FILTER_INACTIVE.equals(addonFilter)) {
                    typeOk = !pack.active;
                }

                String name = pack.name == null ? "" : pack.name.toLowerCase(Locale.ROOT);
                String description = pack.description == null
                        ? "" : pack.description.toLowerCase(Locale.ROOT);
                boolean searchOk = query.isEmpty()
                        || name.contains(query)
                        || description.contains(query);

                if (typeOk && searchOk) visible.add(pack);
            }

            if (addonStatus != null) {
                addonStatus.setText(
                        visible.size() + " encontrado" + (visible.size() == 1 ? "" : "s")
                                + " • " + activeCount + " ativo"
                                + (activeCount == 1 ? "" : "s"));
            }

            if (visible.isEmpty()) {
                TextView empty = tv(
                        query.isEmpty()
                                ? "Nenhum add-on corresponde a este filtro."
                                : "Nenhum add-on encontrado para “"
                                    + addonSearchField.getText().toString().trim() + "”.",
                        11.5f, MUTED, Typeface.NORMAL);
                empty.setGravity(Gravity.CENTER);
                empty.setPadding(dp(16),dp(30),dp(16),dp(30));
                empty.setBackground(box(
                        Color.rgb(42,25,29),
                        Color.rgb(84,61,68),18));
                addonListContainer.addView(empty, match(0,0,0,8));
            } else {
                for (AddonManager.PackInfo pack : visible) {
                    addonListContainer.addView(addonPackCard(pack), match(0,0,0,8));
                }
            }
        } catch (Exception e) {
            TextView error = tv(
                    "Falha ao ler add-ons: " + e.getMessage(),
                    11, WHITE, Typeface.NORMAL);
            error.setPadding(dp(12),dp(12),dp(12),dp(12));
            error.setBackground(box(
                    CARD,Color.argb(80,255,90,110),16));
            addonListContainer.addView(error);
        }

        setAddonLocked(BdsService.isRunning(this));
    }

    private String addonSizeLabel(long bytes) {
        if (bytes < 1024L) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024.0) return String.format(Locale.getDefault(), "%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024.0) return String.format(Locale.getDefault(), "%.1f MB", mb);
        double gb = mb / 1024.0;
        return String.format(Locale.getDefault(), "%.2f GB", gb);
    }

    private View addonPackCard(AddonManager.PackInfo pack) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(13),dp(12),dp(13),dp(12));
        card.setBackground(box(
                Color.rgb(45,20,25),
                pack.active ? Color.rgb(255,76,96) : Color.rgb(89,62,69),
                18));

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);

        ImageView icon = new ImageView(this);
        icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
        icon.setBackground(box(
                Color.rgb(34,23,26),
                Color.argb(70,255,255,255),12));
        if (pack.iconFile != null) {
            try {
                icon.setImageBitmap(BitmapFactory.decodeFile(pack.iconFile.getAbsolutePath()));
            } catch (Exception ignored) {
                icon.setImageResource(R.drawable.bubble_internal_icon);
            }
        } else {
            icon.setImageResource(R.drawable.bubble_internal_icon);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        }
        head.addView(icon, new LinearLayout.LayoutParams(dp(64),dp(64)));

        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        words.setPadding(dp(11),0,0,0);

        TextView name = tv(
                pack.name == null ? "Add-on" : pack.name,
                14, WHITE, Typeface.BOLD);
        name.setMaxLines(2);
        words.addView(name);

        String typeLabel = AddonManager.TYPE_RESOURCE.equals(pack.type)
                ? "TEXTURA" : "COMPORTAMENTO";
        TextView meta = tv(
                typeLabel + "  •  v" + pack.versionLabel()
                        + "  •  " + addonSizeLabel(pack.sizeBytes),
                9.1f,
                pack.active ? Color.rgb(255,146,159) : MUTED,
                Typeface.BOLD);
        meta.setPadding(0,dp(4),0,0);
        words.addView(meta);

        head.addView(words, new LinearLayout.LayoutParams(
                0,ViewGroup.LayoutParams.WRAP_CONTENT,1));

        TextView state = tv(
                pack.active ? "ATIVO" : "DESATIVADO",
                8.8f,
                WHITE,
                Typeface.BOLD);
        state.setGravity(Gravity.CENTER);
        state.setPadding(dp(8),dp(5),dp(8),dp(5));
        state.setBackground(box(
                pack.active ? Color.rgb(116,17,33) : Color.rgb(53,39,43),
                pack.active ? Color.rgb(255,80,100) : Color.rgb(92,72,78),
                12));
        head.addView(state);

        card.addView(head);

        TextView desc = tv(
                pack.description == null ? "Sem descrição." : pack.description,
                10.4f,MUTED,Typeface.NORMAL);
        desc.setMaxLines(3);
        desc.setPadding(0,dp(10),0,dp(10));
        card.addView(desc);

        TextView install = addonInstallButton(
                pack.active ? "DESINSTALAR" : "INSTALAR",
                pack.active);
        install.setOnClickListener(v -> toggleAddonInstall(pack, install));
        addonActionViews.add(install);
        card.addView(install, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(50)));

        return card;
    }

    private TextView addonInstallButton(String text, boolean installed) {
        TextView button = tv(text,11.5f,WHITE,Typeface.BOLD);
        button.setGravity(Gravity.CENTER);
        button.setClickable(true);
        button.setFocusable(true);
        button.setBackground(box(
                installed ? Color.rgb(68,28,35) : Color.rgb(124,19,37),
                installed ? Color.rgb(120,78,87) : Color.rgb(255,75,96),
                14));
        return button;
    }

    private void toggleAddonInstall(AddonManager.PackInfo pack, TextView button) {
        if (BdsService.isRunning(this)) {
            Toast.makeText(
                    this,
                    "Pare o servidor antes de alterar add-ons.",
                    Toast.LENGTH_LONG).show();
            return;
        }

        boolean install = !pack.active;

        button.setEnabled(false);
        button.setAlpha(.76f);
        button.setText(install ? "INSTALANDO..." : "DESINSTALANDO...");
        button.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
        button.animate().scaleX(.96f).scaleY(.96f).setDuration(90).withEndAction(() ->
                button.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
        ).start();

        if (addonStatus != null) {
            addonStatus.setText(
                    (install ? "Instalando " : "Desinstalando ")
                            + (pack.name == null ? "add-on" : pack.name) + "...");
        }

        try {
            AddonManager.setActive(this, addonWorldName(), pack, install);
            pack.active = install;

            button.setAlpha(1f);
            button.setText(install ? "✓ INSTALADO" : "✓ DESINSTALADO");
            button.setBackground(box(
                    Color.rgb(86,24,36),
                    Color.rgb(255,99,117),
                    14));

            if (addonStatus != null) {
                addonStatus.setText(
                        (pack.name == null ? "Add-on" : pack.name)
                                + (install
                                    ? " foi instalado no mundo."
                                    : " foi desinstalado do mundo."));
            }

            handler.postDelayed(this::refreshAddonList, 650);
        } catch (Exception e) {
            button.setEnabled(true);
            button.setAlpha(1f);
            button.setText(install ? "INSTALAR" : "DESINSTALAR");
            if (addonStatus != null) {
                addonStatus.setText("Falha: " + e.getMessage());
            }
            Toast.makeText(
                    this,
                    "Não foi possível alterar o add-on.",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void setAddonLocked(boolean locked) {
        for (View action : addonActionViews) {
            if (action != null) {
                action.setEnabled(!locked);
                action.setAlpha(locked ? .46f : 1f);
            }
        }
        if (addonLockNotice != null) addonLockNotice.setVisibility(locked ? View.VISIBLE : View.GONE);
    }

    private View buildConsolePage() {
        LinearLayout page = pageRoot();
        page.setPadding(dp(isWide()?28:14),dp(12),dp(isWide()?28:14),dp(10));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout title = new LinearLayout(this);
        title.setOrientation(LinearLayout.VERTICAL);
        title.addView(tv("Console do Servidor",20,WHITE,Typeface.BOLD));
        title.addView(tv(
                "Log em tempo real • comandos seguros",
                10.5f,Color.argb(225,255,236,239),Typeface.NORMAL));
        top.addView(title,new LinearLayout.LayoutParams(
                0,ViewGroup.LayoutParams.WRAP_CONTENT,1));

        TextView stateBadge = tv("LIVE",10,WHITE,Typeface.BOLD);
        stateBadge.setGravity(Gravity.CENTER);
        stateBadge.setBackground(box(
                Color.argb(65,255,255,255),
                Color.argb(80,255,255,255),12));
        top.addView(stateBadge,new LinearLayout.LayoutParams(dp(58),dp(36)));
        page.addView(top,match(0,0,0,9));

        consoleScroll = new ScrollView(this);
        consoleScroll.setFillViewport(true);
        consoleScroll.setBackground(box(TERMINAL,Color.rgb(45,40,43),16));
        consoleText = tv("",10,Color.rgb(235,231,233),Typeface.NORMAL);
        consoleText.setTypeface(Typeface.MONOSPACE);
        consoleText.setTextIsSelectable(true);
        consoleText.setPadding(dp(13),dp(12),dp(13),dp(12));
        consoleScroll.addView(consoleText);
        consoleScroll.setOnScrollChangeListener((v, sx, sy, osx, osy) -> {
            int contentHeight = consoleText == null ? 0 : consoleText.getHeight();
            consoleAutoFollow = sy + consoleScroll.getHeight() >= contentHeight - dp(48);
        });
        page.addView(consoleScroll,new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,0,1));

        LinearLayout commandRow = new LinearLayout(this);
        commandRow.setGravity(Gravity.CENTER_VERTICAL);
        commandInput = field("Comando seguro ou !!op Jogador...");
        commandInput.setTypeface(Typeface.MONOSPACE);
        commandInput.setImeOptions(EditorInfo.IME_ACTION_SEND);
        commandInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendSafeCommand();
                return true;
            }
            return false;
        });
        commandRow.addView(commandInput,new LinearLayout.LayoutParams(
                0,dp(76),1));

        TextView send = smallButton("›");
        send.setTextSize(24);
        send.setOnClickListener(v -> sendSafeCommand());
        LinearLayout.LayoutParams sendLp = new LinearLayout.LayoutParams(dp(76),dp(76));
        sendLp.setMargins(dp(8),0,0,0);
        commandRow.addView(send,sendLp);
        page.addView(commandRow,match(0,9,0,0));

        TextView allowed = tv(
                "Seguro: list • help • say • tell/msg/w • seed   |   !! = modo administrador",
                9.2f,MUTED,Typeface.NORMAL);
        allowed.setGravity(Gravity.CENTER);
        page.addView(allowed,match(0,5,0,0));

        return page;
    }

    private View buildBottomNavigation() {
        LinearLayout outer = new LinearLayout(this);
        outer.setGravity(Gravity.CENTER);
        outer.setPadding(dp(10),dp(6),dp(10),dp(6));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER);
        bar.setPadding(dp(8),dp(6),dp(8),dp(6));
        bar.setBackground(box(
                Color.rgb(38,12,17),
                Color.argb(110,255,82,103),22));

        navHome = navItem("⌂\nInício");
        navAddons = navItem("◇\nAddon Bubble");
        navConsole = navItem(">_\nConsole");

        navHome.setOnClickListener(v -> showTab(TAB_HOME, true));
        navAddons.setOnClickListener(v -> showTab(TAB_ADDONS, true));
        navConsole.setOnClickListener(v -> showTab(TAB_CONSOLE, true));

        LinearLayout.LayoutParams one = new LinearLayout.LayoutParams(0,dp(62),1);
        bar.addView(navHome,one);
        LinearLayout.LayoutParams two = new LinearLayout.LayoutParams(0,dp(62),1);
        two.setMargins(dp(6),0,0,0);
        bar.addView(navAddons,two);
        LinearLayout.LayoutParams three = new LinearLayout.LayoutParams(0,dp(62),1);
        three.setMargins(dp(6),0,0,0);
        bar.addView(navConsole,three);

        int maxWidth = isWide() ? dp(620) : ViewGroup.LayoutParams.MATCH_PARENT;
        outer.addView(bar,new LinearLayout.LayoutParams(maxWidth,ViewGroup.LayoutParams.WRAP_CONTENT));
        return outer;
    }

    private TextView navItem(String label) {
        TextView item = tv(label,12,WHITE,Typeface.BOLD);
        item.setGravity(Gravity.CENTER);
        item.setClickable(true);
        item.setFocusable(true);
        return item;
    }

    private void showTab(int tab, boolean userAction) {
        currentTab = tab;
        if (homePage != null) homePage.setVisibility(tab == TAB_HOME ? View.VISIBLE : View.GONE);
        if (addonPage != null) addonPage.setVisibility(tab == TAB_ADDONS ? View.VISIBLE : View.GONE);
        if (consolePage != null) consolePage.setVisibility(tab == TAB_CONSOLE ? View.VISIBLE : View.GONE);

        if (navHome != null) {
            navHome.setBackground(box(
                    tab == TAB_HOME ? Color.rgb(169,21,42) : Color.TRANSPARENT,
                    tab == TAB_HOME ? Color.rgb(255,70,91) : Color.TRANSPARENT,16));
            navHome.setAlpha(tab == TAB_HOME ? 1f : .68f);
        }
        if (navAddons != null) {
            navAddons.setBackground(box(
                    tab == TAB_ADDONS ? Color.rgb(169,21,42) : Color.TRANSPARENT,
                    tab == TAB_ADDONS ? Color.rgb(255,70,91) : Color.TRANSPARENT,16));
            navAddons.setAlpha(tab == TAB_ADDONS ? 1f : .68f);
        }
        if (navConsole != null) {
            navConsole.setBackground(box(
                    tab == TAB_CONSOLE ? Color.rgb(169,21,42) : Color.TRANSPARENT,
                    tab == TAB_CONSOLE ? Color.rgb(255,70,91) : Color.TRANSPARENT,16));
            navConsole.setAlpha(tab == TAB_CONSOLE ? 1f : .68f);
        }

        if (tab == TAB_ADDONS) {
            refreshAddonList();
        }
        if (tab == TAB_CONSOLE) {
            refreshServerConsole(true);
            if (userAction) {
                consoleAutoFollow = true;
                scrollConsoleWithoutFocus();
            }
        }
    }

    // -----------------------------------------------------------------
    // BDS version manager
    // -----------------------------------------------------------------
    private String versionSpinnerText() {
        String version = InstallerManager.readInstalledBdsVersion(this);
        if (version == null || version.trim().isEmpty()) version = "—";
        boolean update = bdsVersionCatalog != null
                && BdsVersionManager.noticeCandidate(this, bdsVersionCatalog) != null;
        return (update ? "● " : "") + version + "  ▾";
    }

    private void refreshVersionSpinnerLabel() {
        if (bdsVersionSpinner == null) return;
        bdsVersionSpinner.setText(versionSpinnerText());
        boolean update = bdsVersionCatalog != null
                && BdsVersionManager.noticeCandidate(this, bdsVersionCatalog) != null;
        bdsVersionSpinner.setTextColor(update ? Color.rgb(255,238,142) : WHITE);
    }

    private void checkBdsUpdatesInBackground(boolean force) {
        if (!ready() || bdsVersionChecking) return;
        long now = System.currentTimeMillis();
        if (!force && lastBdsVersionCheckMs > 0
                && now - lastBdsVersionCheckMs < 15L * 60L * 1000L) return;

        bdsVersionChecking = true;
        new Thread(() -> {
            try {
                BdsVersionManager.Catalog catalog = BdsVersionManager.fetchCatalog(this);
                handler.post(() -> {
                    bdsVersionCatalog = catalog;
                    lastBdsVersionCheckMs = System.currentTimeMillis();
                    bdsVersionChecking = false;
                    refreshVersionSpinnerLabel();
                    BdsVersionManager.VersionInfo candidate =
                            BdsVersionManager.noticeCandidate(this, catalog);
                    if (candidate != null) showBdsUpdateBubble(candidate);
                });
            } catch (Exception ignored) {
                handler.post(() -> bdsVersionChecking = false);
            }
        }, "bubble-bds-version-check").start();
    }

    private void showBdsUpdateBubble(BdsVersionManager.VersionInfo candidate) {
        if (candidate == null || bdsVersionSpinner == null || isFinishing()) return;
        bdsVersionSpinner.post(() -> {
            if (bdsUpdateBubble != null) bdsUpdateBubble.dismiss();

            LinearLayout bubble = new LinearLayout(this);
            bubble.setOrientation(LinearLayout.VERTICAL);
            bubble.setPadding(dp(14), dp(11), dp(14), dp(11));
            bubble.setBackground(box(
                    Color.rgb(37,20,23),
                    Color.rgb(255,101,119), 16));

            TextView title = tv(
                    "Nova " + candidate.channelLabel().toLowerCase(Locale.ROOT)
                            + " disponível",
                    12.5f, WHITE, Typeface.BOLD);
            bubble.addView(title);
            TextView body = tv(
                    candidate.version + " • toque para gerenciar versões",
                    10.5f, MUTED, Typeface.NORMAL);
            body.setPadding(0, dp(3), 0, 0);
            bubble.addView(body);
            bubble.setOnClickListener(v -> {
                if (bdsUpdateBubble != null) bdsUpdateBubble.dismiss();
                showBdsVersionModal();
            });

            bdsUpdateBubble = new PopupWindow(
                    bubble,
                    isWide() ? dp(330) : dp(270),
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    false);
            bdsUpdateBubble.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            bdsUpdateBubble.setOutsideTouchable(true);
            bdsUpdateBubble.setElevation(dp(8));
            bdsUpdateBubble.showAsDropDown(
                    bdsVersionSpinner,
                    isWide() ? -dp(180) : -dp(150),
                    dp(6));
            handler.postDelayed(() -> {
                if (bdsUpdateBubble != null && bdsUpdateBubble.isShowing()) {
                    bdsUpdateBubble.dismiss();
                }
            }, 20_000L);
        });
    }

    private void showBdsVersionModal() {
        if (bdsUpdateBubble != null) bdsUpdateBubble.dismiss();

        Dialog dialog = new Dialog(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(16), dp(18), dp(16));
        root.setBackground(box(
                Color.rgb(37,16,21),
                Color.rgb(112,57,68), 22));

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titleWords = new LinearLayout(this);
        titleWords.setOrientation(LinearLayout.VERTICAL);
        titleWords.addView(tv("Versões do servidor", 18, WHITE, Typeface.BOLD));
        TextView current = tv(
                "Em uso: " + InstallerManager.readInstalledBdsChannel(this)
                        + " • " + InstallerManager.readInstalledBdsVersion(this),
                10.5f, MUTED, Typeface.NORMAL);
        current.setPadding(0, dp(3), 0, 0);
        titleWords.addView(current);
        titleRow.addView(titleWords, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView close = smallButton("×");
        close.setOnClickListener(v -> dialog.dismiss());
        titleRow.addView(close, new LinearLayout.LayoutParams(dp(42), dp(42)));
        root.addView(titleRow);

        TextView status = tv(
                "Verificando versões oficiais...",
                10.5f, MUTED, Typeface.NORMAL);
        status.setPadding(0, dp(9), 0, dp(8));
        root.addView(status);

        ScrollView scroll = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                isWide() ? dp(480) : dp(390)));

        TextView refresh = tv("↻  Verificar agora", 12.5f, WHITE, Typeface.BOLD);
        refresh.setGravity(Gravity.CENTER);
        refresh.setPadding(dp(12), dp(12), dp(12), dp(12));
        refresh.setBackground(box(
                Color.rgb(73,31,39),
                Color.rgb(121,68,78), 14));
        refresh.setClickable(true);
        refresh.setOnClickListener(v -> loadBdsVersionCatalogIntoDialog(dialog, list, status, true));
        root.addView(refresh, match(0, 10, 0, 0));

        dialog.setContentView(root);
        dialog.setCanceledOnTouchOutside(true);
        dialog.show();

        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams lp = window.getAttributes();
            lp.dimAmount = .56f;
            lp.width = isWide() ? dp(620)
                    : (int)(getResources().getDisplayMetrics().widthPixels * .92f);
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            window.setAttributes(lp);
        }

        BdsVersionManager.Catalog local = bdsVersionCatalog != null
                ? bdsVersionCatalog : BdsVersionManager.localCatalog(this);
        renderBdsVersionList(dialog, list, status, local);
        loadBdsVersionCatalogIntoDialog(dialog, list, status, false);
    }

    private void loadBdsVersionCatalogIntoDialog(
            Dialog dialog,
            LinearLayout list,
            TextView status,
            boolean force) {
        if (bdsVersionChecking && !force) return;
        status.setText("Verificando versões oficiais...");
        bdsVersionChecking = true;
        new Thread(() -> {
            try {
                BdsVersionManager.Catalog catalog = BdsVersionManager.fetchCatalog(this);
                handler.post(() -> {
                    bdsVersionChecking = false;
                    bdsVersionCatalog = catalog;
                    lastBdsVersionCheckMs = System.currentTimeMillis();
                    refreshVersionSpinnerLabel();
                    if (!dialog.isShowing()) return;
                    renderBdsVersionList(dialog, list, status, catalog);
                });
            } catch (Exception e) {
                handler.post(() -> {
                    bdsVersionChecking = false;
                    if (dialog.isShowing()) {
                        status.setText("Sem conexão com a lista oficial. Versões baixadas continuam disponíveis.");
                    }
                });
            }
        }, "bubble-bds-version-modal").start();
    }

    private void renderBdsVersionList(
            Dialog dialog,
            LinearLayout list,
            TextView status,
            BdsVersionManager.Catalog catalog) {
        list.removeAllViews();
        if (catalog == null) catalog = BdsVersionManager.localCatalog(this);

        if (catalog.stable != null || catalog.preview != null) {
            list.addView(versionSectionTitle("Disponíveis agora"), match(0, 0, 0, 5));
            if (catalog.stable != null) {
                list.addView(bdsVersionRow(dialog, list, status, catalog.stable), match(0, 0, 0, 7));
            }
            if (catalog.preview != null) {
                list.addView(bdsVersionRow(dialog, list, status, catalog.preview), match(0, 0, 0, 7));
            }
        }

        List<BdsVersionManager.VersionInfo> history = new ArrayList<>();
        for (BdsVersionManager.VersionInfo info : catalog.all) {
            if (!info.downloaded) continue;
            boolean isStableLatest = catalog.stable != null && info.key().equals(catalog.stable.key());
            boolean isPreviewLatest = catalog.preview != null && info.key().equals(catalog.preview.key());
            if (!isStableLatest && !isPreviewLatest) history.add(info);
        }
        if (!history.isEmpty()) {
            list.addView(versionSectionTitle("Baixadas"), match(0, 5, 0, 5));
            for (BdsVersionManager.VersionInfo info : history) {
                list.addView(bdsVersionRow(dialog, list, status, info), match(0, 0, 0, 7));
            }
        }

        if (catalog.all.isEmpty() && catalog.stable == null && catalog.preview == null) {
            TextView empty = tv(
                    "Nenhuma versão baixada ainda.",
                    11, MUTED, Typeface.NORMAL);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(8), dp(24), dp(8), dp(24));
            list.addView(empty);
        }

        BdsVersionManager.VersionInfo candidate = BdsVersionManager.noticeCandidate(this, catalog);
        if (candidate != null) {
            status.setText("Atualização disponível • "
                    + candidate.channelLabel() + " " + candidate.version);
        } else {
            status.setText("Servidor atualizado. Você também pode baixar uma prévia e alternar quando quiser.");
        }
    }

    private TextView versionSectionTitle(String text) {
        TextView t = tv(text.toUpperCase(Locale.ROOT), 9.5f, MUTED, Typeface.BOLD);
        t.setAlpha(.86f);
        return t;
    }

    private View bdsVersionRow(
            Dialog dialog,
            LinearLayout list,
            TextView status,
            BdsVersionManager.VersionInfo info) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(11), dp(10), dp(11));
        row.setBackground(box(
                info.installed ? Color.rgb(80,25,34) : Color.rgb(45,28,32),
                info.installed ? Color.rgb(242,69,91) : Color.rgb(91,72,77), 16));

        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        TextView name = tv(
                info.channelLabel() + "  " + info.version,
                13.5f, WHITE, Typeface.BOLD);
        words.addView(name);
        String meta;
        if (info.installed) meta = "Em uso";
        else if (info.downloaded) meta = "Baixada • pronta para alternar";
        else if (info.remote) meta = "Disponível para download";
        else meta = "Histórico local";
        TextView sub = tv(meta, 9.5f, MUTED, Typeface.NORMAL);
        sub.setPadding(0, dp(3), 0, 0);
        words.addView(sub);
        row.addView(words, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView action;
        if (info.installed) action = versionMiniButton("EM USO", false);
        else if (info.downloaded) action = versionMiniButton("USAR", true);
        else if (info.remote) action = versionMiniButton("BAIXAR", true);
        else action = versionMiniButton("—", false);
        row.addView(action, new LinearLayout.LayoutParams(dp(78), dp(42)));

        if (!info.installed && info.downloaded) {
            TextView trash = versionMiniButton("🗑", true);
            trash.setTypeface(Typeface.DEFAULT);
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(dp(44), dp(42));
            tp.setMargins(dp(6), 0, 0, 0);
            row.addView(trash, tp);
            trash.setOnClickListener(v -> runBdsVersionDelete(dialog, list, status, info));
        }

        if (!info.installed && (info.downloaded || info.remote)) {
            action.setOnClickListener(v -> {
                if (info.downloaded) runBdsVersionActivate(dialog, list, status, info);
                else runBdsVersionDownload(dialog, list, status, info);
            });
        }
        return row;
    }

    private TextView versionMiniButton(String text, boolean enabled) {
        TextView b = tv(text, 9.5f, enabled ? WHITE : MUTED, Typeface.BOLD);
        b.setGravity(Gravity.CENTER);
        b.setClickable(enabled);
        b.setEnabled(enabled);
        b.setBackground(box(
                enabled ? Color.rgb(166,24,43) : Color.rgb(53,39,42),
                enabled ? Color.rgb(242,69,91) : Color.rgb(83,67,72), 12));
        return b;
    }

    private void runBdsVersionDownload(
            Dialog dialog,
            LinearLayout list,
            TextView status,
            BdsVersionManager.VersionInfo info) {
        status.setText("Preparando download de " + info.version + "...");
        new Thread(() -> {
            try {
                BdsVersionManager.download(this, info, (percent, stage) ->
                        handler.post(() -> {
                            if (dialog.isShowing()) status.setText(stage + " • " + percent + "%");
                        }));
                BdsVersionManager.Catalog fresh = BdsVersionManager.fetchCatalog(this);
                handler.post(() -> {
                    bdsVersionCatalog = fresh;
                    if (dialog.isShowing()) renderBdsVersionList(dialog, list, status, fresh);
                    Toast.makeText(this, "Versão baixada.", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                handler.post(() -> {
                    if (dialog.isShowing()) status.setText("Falha no download: " + e.getMessage());
                    Toast.makeText(this, "Falha ao baixar a versão.", Toast.LENGTH_LONG).show();
                });
            }
        }, "bubble-bds-version-download").start();
    }

    private void runBdsVersionActivate(
            Dialog dialog,
            LinearLayout list,
            TextView status,
            BdsVersionManager.VersionInfo info) {
        if (BdsService.isRunning(this)) {
            Toast.makeText(this, "Pare o servidor antes de alternar a versão.", Toast.LENGTH_LONG).show();
            return;
        }
        status.setText("Alternando para " + info.channelLabel() + " " + info.version + "...");
        new Thread(() -> {
            try {
                BdsVersionManager.activate(this, info, (percent, stage) ->
                        handler.post(() -> {
                            if (dialog.isShowing()) status.setText(stage + " • " + percent + "%");
                        }));
                BdsVersionManager.Catalog fresh;
                try { fresh = BdsVersionManager.fetchCatalog(this); }
                catch (Exception ignored) { fresh = BdsVersionManager.localCatalog(this); }
                BdsVersionManager.Catalog result = fresh;
                handler.post(() -> {
                    bdsVersionCatalog = result;
                    updateServerUi();
                    if (dialog.isShowing()) renderBdsVersionList(dialog, list, status, result);
                    Toast.makeText(this, "Versão do servidor alterada.", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                handler.post(() -> {
                    if (dialog.isShowing()) status.setText("Falha ao alternar: " + e.getMessage());
                    Toast.makeText(this, "Não foi possível alternar a versão.", Toast.LENGTH_LONG).show();
                });
            }
        }, "bubble-bds-version-activate").start();
    }

    private void runBdsVersionDelete(
            Dialog dialog,
            LinearLayout list,
            TextView status,
            BdsVersionManager.VersionInfo info) {
        new Thread(() -> {
            try {
                BdsVersionManager.deleteDownloaded(this, info);
                BdsVersionManager.Catalog fresh;
                try { fresh = BdsVersionManager.fetchCatalog(this); }
                catch (Exception ignored) { fresh = BdsVersionManager.localCatalog(this); }
                BdsVersionManager.Catalog result = fresh;
                handler.post(() -> {
                    bdsVersionCatalog = result;
                    if (dialog.isShowing()) renderBdsVersionList(dialog, list, status, result);
                    Toast.makeText(this, "Versão baixada removida.", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                handler.post(() -> {
                    if (dialog.isShowing()) status.setText("Não foi possível apagar: " + e.getMessage());
                });
            }
        }, "bubble-bds-version-delete").start();
    }

    // -----------------------------------------------------------------
    // Summary + form state
    // -----------------------------------------------------------------
    private View serverInfoCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(15),dp(14),dp(15),dp(14));
        card.setBackground(box(CARD,Color.argb(70,255,90,110),20));

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        infoState = tv("● Servidor pronto",15.5f,WHITE,Typeface.BOLD);
        titleRow.addView(infoState,new LinearLayout.LayoutParams(
                0,ViewGroup.LayoutParams.WRAP_CONTENT,1));
        infoVersion = tv("",10.5f,MUTED,Typeface.BOLD);
        titleRow.addView(infoVersion);
        card.addView(titleRow);

        infoWorld = infoLine();
        infoMode = infoLine();
        infoAddress = infoLine();
        infoAddons = infoLine();
        card.addView(infoWorld, match(0,8,0,0));
        card.addView(infoMode);
        card.addView(infoAddress);
        card.addView(infoAddons);
        return card;
    }

    private TextView infoLine() {
        TextView t = tv("",10.5f,MUTED,Typeface.NORMAL);
        t.setPadding(0,dp(3),0,0);
        return t;
    }

    private TextView label(String text) {
        return tv(text,12,MUTED,Typeface.BOLD);
    }

    private EditText field(String hint) {
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setTextColor(WHITE);
        e.setHintTextColor(Color.rgb(150,138,143));
        e.setHint(hint);
        e.setTextSize(13.5f);
        applyAppTypeface(e, Typeface.NORMAL);
        e.setMinHeight(dp(76));
        e.setPadding(dp(16),dp(8),dp(16),dp(8));
        e.setBackground(box(
                Color.rgb(39,24,27),
                Color.rgb(111,90,96),14));
        return e;
    }

    private EditText numericField(String hint) {
        EditText e = field(hint);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        return e;
    }

    private TextView segment(String label, boolean active) {
        TextView b = tv(label,13,active ? WHITE : MUTED,Typeface.BOLD);
        b.setGravity(Gravity.CENTER);
        b.setClickable(true);
        b.setBackground(box(
                active ? Color.rgb(175,22,43) : Color.rgb(45,28,32),
                active ? Color.rgb(255,68,89) : Color.rgb(91,72,77),14));
        return b;
    }

    private void mode(boolean creativeValue) {
        creativeMode = creativeValue;
        if (survival == null || creative == null) return;
        survival.setBackground(box(
                creativeValue ? Color.rgb(45,28,32) : Color.rgb(175,22,43),
                creativeValue ? Color.rgb(91,72,77) : Color.rgb(255,68,89),14));
        creative.setBackground(box(
                creativeValue ? Color.rgb(175,22,43) : Color.rgb(45,28,32),
                creativeValue ? Color.rgb(255,68,89) : Color.rgb(91,72,77),14));
        survival.setTextColor(creativeValue ? MUTED : WHITE);
        creative.setTextColor(creativeValue ? WHITE : MUTED);
    }

    private void setDifficulty(String value) {
        difficultyValue = value;
        String[] values = {"peaceful","easy","normal","hard"};
        for (int i=0;i<difficultyButtons.length;i++) {
            if (difficultyButtons[i] == null) continue;
            boolean active = values[i].equals(value);
            difficultyButtons[i].setBackground(box(
                    active ? Color.rgb(175,22,43) : Color.rgb(45,28,32),
                    active ? Color.rgb(255,68,89) : Color.rgb(91,72,77),14));
            difficultyButtons[i].setTextColor(active ? WHITE : MUTED);
        }
    }

    private void setPermission(String value) {
        permissionValue = value;
        String[] values = {"visitor","member","operator"};
        for (int i=0;i<permissionButtons.length;i++) {
            if (permissionButtons[i] == null) continue;
            boolean active = values[i].equals(value);
            permissionButtons[i].setBackground(box(
                    active ? Color.rgb(175,22,43) : Color.rgb(45,28,32),
                    active ? Color.rgb(255,68,89) : Color.rgb(91,72,77),14));
            permissionButtons[i].setTextColor(active ? WHITE : MUTED);
        }
    }

    private void setConfigurationLocked(boolean locked) {
        if (configurationArea == null) return;
        setEnabledRecursive(configurationArea, !locked);
        configurationArea.setAlpha(locked ? .48f : 1f);
        if (configLockNotice != null) {
            configLockNotice.setVisibility(locked ? View.VISIBLE : View.GONE);
        }
        if (locked) {
            View focused = getCurrentFocus();
            if (focused != null && isDescendant(configurationArea, focused)) focused.clearFocus();
        }
    }

    private boolean isDescendant(ViewGroup parent, View child) {
        View current = child;
        while (current != null) {
            if (current == parent) return true;
            if (!(current.getParent() instanceof View)) break;
            current = (View) current.getParent();
        }
        return false;
    }

    private void setEnabledRecursive(View view, boolean enabled) {
        view.setEnabled(enabled);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup)view;
            for (int i=0;i<group.getChildCount();i++) {
                setEnabledRecursive(group.getChildAt(i), enabled);
            }
        }
    }

    // -----------------------------------------------------------------
    // Server control
    // -----------------------------------------------------------------
    private void sendSafeCommand() {
        if (commandInput == null) return;

        String raw = commandInput.getText().toString().trim();
        if (raw.isEmpty()) return;

        boolean adminMode = raw.startsWith("!!");
        String cmd;

        if (adminMode) {
            cmd = raw.substring(2).trim();

            // Aceita !!op Player e também !!/op Player.
            if (cmd.startsWith("/")) cmd = cmd.substring(1).trim();

            // Um envio = uma única linha do console.
            cmd = cmd.replace("\r", "").replace("\n", "").trim();

            if (cmd.isEmpty()) {
                commandInput.setError("Digite um comando depois de !!");
                return;
            }

            if (cmd.length() > 1024) {
                commandInput.setError("Comando administrativo muito longo");
                return;
            }
        } else {
            cmd = raw;
            if (cmd.startsWith("/")) cmd = cmd.substring(1).trim();
            if (cmd.isEmpty()) return;

            if (!isAllowedCommand(cmd)) {
                commandInput.setError(
                        "Comando protegido. Use !! antes do comando para executar como administrador.");
                return;
            }
        }

        if (!BdsService.isRunning(this)) {
            commandInput.setError("O servidor não está rodando");
            return;
        }

        Intent i = new Intent(this,BdsService.class)
                .setAction(BdsService.ACTION_COMMAND)
                .putExtra(BdsService.EXTRA_COMMAND,cmd);
        startService(i);

        commandInput.setText("");
        consoleAutoFollow = true;

        if (adminMode) {
            Toast.makeText(
                    this,
                    "Comando administrativo enviado",
                    Toast.LENGTH_SHORT).show();
        }
    }

    private boolean isAllowedCommand(String cmd) {
        String lower = cmd.toLowerCase(Locale.ROOT);
        return lower.equals("list")
                || lower.equals("help")
                || lower.equals("seed")
                || lower.startsWith("help ")
                || lower.startsWith("say ")
                || lower.startsWith("tell ")
                || lower.startsWith("msg ")
                || lower.startsWith("w ");
    }

    private void toggleServer() {
        if (BdsService.isRunning(this)) {
            startService(new Intent(this,BdsService.class)
                    .setAction(BdsService.ACTION_STOP));
            updateServerUi();
            return;
        }

        try {
            saveConfig();
        } catch (Exception e) {
            appendLocalConsole("[bubble] Configuração inválida: " + e.getMessage());
            return;
        }

        Intent start = new Intent(this,BdsService.class)
                .setAction(BdsService.ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(start);
        else startService(start);
        handler.postDelayed(this::updateServerUi,250);
    }

    private void updateServerUi() {
        if (!ready() || serverButton == null) return;

        String state = BdsService.state(this);
        boolean running = BdsService.isRunning(this);
        setConfigurationLocked(running);

        if ("stopping".equals(state)) {
            serverButton.setText("■  Parando Servidor...");
            serverButton.setEnabled(false);
        } else if ("starting".equals(state)) {
            serverButton.setText("↻  Iniciando Servidor...");
            serverButton.setEnabled(false);
        } else if (running) {
            serverButton.setText("■  Parar Servidor");
            serverButton.setEnabled(true);
        } else {
            serverButton.setText("▶  Iniciar Servidor");
            serverButton.setEnabled(true);
        }

        String world = getProperty("level-name","Meu Mundo Bubble");
        String mode = getProperty("gamemode","survival");
        String difficulty = getProperty("difficulty","normal");
        String port = getProperty("server-port","19132");
        String server = getProperty("server-name","Bubble BDS Host");
        String ip = BdsService.getLocalIpv4();

        infoState.setText(running
                ? "● " + server + " • Online"
                : "● " + server + " • Pronto");
        infoState.setTextColor(running ? Color.rgb(89,246,158) : WHITE);
        infoVersion.setText(
                InstallerManager.readInstalledBdsChannel(this)
                        + " • " + InstallerManager.readInstalledBdsVersion(this));
        infoWorld.setText("Mundo: " + world);
        infoMode.setText(
                "Modo: " + prettyMode(mode)
                        + "   •   Dificuldade: " + prettyDifficulty(difficulty));
        infoAddress.setText("Rede local: " + ip + ":" + port);
        infoAddons.setText(
                "Add-ons ativos: " + countWorldAddons(world)
                        + "   •   Jogadores: " + getProperty("max-players","8"));
        refreshVersionSpinnerLabel();
    }

    private String prettyMode(String mode) {
        if ("creative".equalsIgnoreCase(mode)) return "Criativo";
        if ("adventure".equalsIgnoreCase(mode)) return "Aventura";
        return "Survival";
    }

    private String prettyDifficulty(String value) {
        if ("peaceful".equalsIgnoreCase(value)) return "Pacífico";
        if ("easy".equalsIgnoreCase(value)) return "Fácil";
        if ("hard".equalsIgnoreCase(value)) return "Difícil";
        return "Normal";
    }

    private int countWorldAddons(String world) {
        try {
            File dir = new File(new File(AppPaths.bds(this),"worlds"),world);
            return countPackIds(new File(dir,"world_behavior_packs.json"))
                    + countPackIds(new File(dir,"world_resource_packs.json"));
        } catch (Exception e) {
            return 0;
        }
    }

    private int countPackIds(File file) throws Exception {
        if (!file.isFile()) return 0;
        String text = read(file);
        int count = 0;
        int index = 0;
        while ((index = text.indexOf("\"pack_id\"",index)) >= 0) {
            count++;
            index += 9;
        }
        return count;
    }

    // -----------------------------------------------------------------
    // Configuration persistence
    // -----------------------------------------------------------------
    private void loadConfig() {
        if (worldName == null) return;

        worldName.setText(getProperty("level-name","Meu Mundo Bubble"));
        seed.setText(getProperty("level-seed",""));
        serverName.setText(getProperty("server-name","Bubble BDS Host"));
        maxPlayers.setText(getProperty("max-players","8"));
        serverPort.setText(getProperty("server-port","19132"));
        idleTimeout.setText(getProperty("player-idle-timeout","30"));
        viewDistance.setText(getProperty("view-distance","32"));
        tickDistance.setText(getProperty("tick-distance","4"));
        maxThreads.setText(getProperty("max-threads","8"));

        cheats.setChecked(boolProperty("allow-cheats",false));
        forceGamemode.setChecked(boolProperty("force-gamemode",false));
        allowList.setChecked(boolProperty("allow-list",false));
        onlineMode.setChecked(boolProperty("online-mode",true));
        resourcePackRequired.setChecked(boolProperty("texturepack-required",false));
        clientChunkGeneration.setChecked(
                boolProperty("client-side-chunk-generation-enabled",true));

        mode("creative".equalsIgnoreCase(getProperty("gamemode","survival")));
        setDifficulty(getProperty("difficulty","normal"));
        setPermission(getProperty("default-player-permission-level","member"));
    }

    private boolean boolProperty(String key, boolean fallback) {
        String v = getProperty(key, fallback ? "true" : "false");
        return "true".equalsIgnoreCase(v);
    }

    private void saveConfig() throws Exception {
        File f = new File(AppPaths.bds(this),"server.properties");
        if (!f.isFile()) {
            throw new IllegalStateException("Arquivo de configuração do servidor não encontrado");
        }

        String text = read(f);

        String world = clean(worldName.getText().toString());
        if (world.isEmpty()) world = "Meu Mundo Bubble";

        String server = clean(serverName.getText().toString());
        if (server.isEmpty()) server = "Bubble BDS Host";

        int players = intValue(maxPlayers, 1, 1000, "Máximo de jogadores");
        int port = intValue(serverPort, 1, 65535, "Porta");
        int idle = intValue(idleTimeout, 0, 1440, "Timeout AFK");
        int view = intValue(viewDistance, 4, 96, "Distância de visão");
        int tick = intValue(tickDistance, 4, 12, "Distância de simulação");
        int threads = intValue(maxThreads, 0, 128, "Threads máximas");

        text = replace(text,"level-name",world);
        text = replace(text,"level-seed",clean(seed.getText().toString()));
        text = replace(text,"gamemode",creativeMode ? "creative" : "survival");
        text = replace(text,"difficulty",difficultyValue);
        text = replace(text,"allow-cheats",String.valueOf(cheats.isChecked()));
        text = replace(text,"force-gamemode",String.valueOf(forceGamemode.isChecked()));
        text = replace(text,"default-player-permission-level",permissionValue);

        text = replace(text,"server-name",server);
        text = replace(text,"max-players",String.valueOf(players));
        text = replace(text,"server-port",String.valueOf(port));
        text = replace(text,"allow-list",String.valueOf(allowList.isChecked()));
        text = replace(text,"online-mode",String.valueOf(onlineMode.isChecked()));
        text = replace(text,"player-idle-timeout",String.valueOf(idle));

        text = replace(text,"view-distance",String.valueOf(view));
        text = replace(text,"tick-distance",String.valueOf(tick));
        text = replace(text,"max-threads",String.valueOf(threads));
        text = replace(text,"client-side-chunk-generation-enabled",
                String.valueOf(clientChunkGeneration.isChecked()));

        text = replace(text,"texturepack-required",
                String.valueOf(resourcePackRequired.isChecked()));

        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private int intValue(EditText field, int min, int max, String label) {
        try {
            String raw = field.getText().toString().trim();
            int value = Integer.parseInt(raw);
            if (value < min || value > max) throw new NumberFormatException();
            field.setError(null);
            return value;
        } catch (Exception e) {
            field.setError(label + ": " + min + "–" + max);
            throw new IllegalArgumentException(label + " inválido");
        }
    }

    private String clean(String value) {
        if (value == null) return "";
        return value.replace('\n',' ').replace('\r',' ').trim();
    }

    private String getProperty(String key, String fallback) {
        File f = new File(AppPaths.bds(this),"server.properties");
        if (!f.isFile()) return fallback;
        try {
            String text = read(f);
            for (String line : text.split("\\R")) {
                if (line.startsWith(key + "=")) {
                    return line.substring(key.length()+1).trim();
                }
            }
        } catch (Exception ignored) {}
        return fallback;
    }

    private String replace(String text, String key, String value) {
        StringBuilder out = new StringBuilder();
        boolean found = false;
        for (String line : text.split("\\R",-1)) {
            if (line.startsWith(key + "=")) {
                out.append(key).append('=').append(value).append('\n');
                found = true;
            } else {
                out.append(line).append('\n');
            }
        }
        if (!found) out.append(key).append('=').append(value).append('\n');
        return out.toString();
    }

    private String read(File f) throws Exception {
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) >= 0) {
                if (n > 0) out.write(b,0,n);
            }
            return new String(out.toByteArray(),StandardCharsets.UTF_8);
        }
    }

    // Draft preservation during rotation
    private String[] draft;
    private boolean draftValid;

    private void captureDraft() {
        if (worldName == null || BdsService.isRunning(this)) return;
        draft = new String[]{
                worldName.getText().toString(),
                seed.getText().toString(),
                serverName.getText().toString(),
                maxPlayers.getText().toString(),
                serverPort.getText().toString(),
                idleTimeout.getText().toString(),
                viewDistance.getText().toString(),
                tickDistance.getText().toString(),
                maxThreads.getText().toString(),
                String.valueOf(cheats.isChecked()),
                String.valueOf(forceGamemode.isChecked()),
                String.valueOf(allowList.isChecked()),
                String.valueOf(onlineMode.isChecked()),
                String.valueOf(resourcePackRequired.isChecked()),
                String.valueOf(clientChunkGeneration.isChecked()),
                String.valueOf(creativeMode),
                difficultyValue,
                permissionValue
        };
        draftValid = true;
    }

    private void restoreDraft() {
        if (!draftValid || draft == null || worldName == null || draft.length < 18) return;
        worldName.setText(draft[0]);
        seed.setText(draft[1]);
        serverName.setText(draft[2]);
        maxPlayers.setText(draft[3]);
        serverPort.setText(draft[4]);
        idleTimeout.setText(draft[5]);
        viewDistance.setText(draft[6]);
        tickDistance.setText(draft[7]);
        maxThreads.setText(draft[8]);
        cheats.setChecked(Boolean.parseBoolean(draft[9]));
        forceGamemode.setChecked(Boolean.parseBoolean(draft[10]));
        allowList.setChecked(Boolean.parseBoolean(draft[11]));
        onlineMode.setChecked(Boolean.parseBoolean(draft[12]));
        resourcePackRequired.setChecked(Boolean.parseBoolean(draft[13]));
        clientChunkGeneration.setChecked(Boolean.parseBoolean(draft[14]));
        mode(Boolean.parseBoolean(draft[15]));
        setDifficulty(draft[16]);
        setPermission(draft[17]);
    }

    // -----------------------------------------------------------------
    // Console
    // -----------------------------------------------------------------
    private void refreshServerConsole(boolean force) {
        if (consoleText == null) return;
        File f = AppPaths.serverLog(this);
        if (!f.isFile()) return;

        long modified = f.lastModified();
        if (!force && modified == lastServerLogModified) return;
        lastServerLogModified = modified;

        try {
            long len = f.length();
            long start = Math.max(0,len - 60000);
            byte[] data = new byte[(int)(len - start)];
            try (RandomAccessFile raf = new RandomAccessFile(f,"r")) {
                raf.seek(start);
                raf.readFully(data);
            }

            String raw = new String(data,StandardCharsets.UTF_8).trim();
            if (raw.length() > 50000) {
                raw = raw.substring(raw.length() - 50000);
            }
            if (raw.equals(lastConsoleValue)) return;

            lastConsoleValue = raw;
            consoleText.setText(raw);

            View focused = getCurrentFocus();
            boolean typing = focused instanceof EditText;
            if (!typing && consoleAutoFollow) {
                scrollConsoleWithoutFocus();
            }
        } catch (Exception ignored) {}
    }

    private void appendLocalConsole(String line) {
        String next = (lastConsoleValue + "\n" + line).trim();
        lastConsoleValue = next;
        if (consoleText != null) consoleText.setText(next);
    }

    private void scrollConsoleWithoutFocus() {
        if (consoleScroll == null || consoleText == null) return;
        consoleScroll.post(() -> consoleScroll.scrollTo(
                0,
                Math.max(0,consoleText.getHeight() - consoleScroll.getHeight())));
    }

    private final Runnable uiTicker = new Runnable() {
        @Override public void run() {
            if (ready()) {
                updateServerUi();
                setAddonLocked(BdsService.isRunning(MainActivity.this));
                refreshServerConsole(false);
            } else {
                refreshInstallUi();
            }
            handler.postDelayed(this,750);
        }
    };

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{Manifest.permission.POST_NOTIFICATIONS},100);
        }
    }

    private LinearLayout.LayoutParams match(int l,int t,int r,int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        p.setMargins(dp(l),dp(t),dp(r),dp(b));
        return p;
    }

    private LinearLayout.LayoutParams fixedHeight(
            int height, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(height));
        p.setMargins(dp(l),dp(t),dp(r),dp(b));
        return p;
    }

    private int dp(float x) {
        return (int)(x * getResources().getDisplayMetrics().density + .5f);
    }

    @Override protected void onDestroy() {
        handler.removeCallbacks(uiTicker);
        if (bdsUpdateBubble != null) bdsUpdateBubble.dismiss();
        super.onDestroy();
    }
}
