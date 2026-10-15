package com.bubble.bdshost;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int RED = Color.rgb(255, 20, 43);
    private static final int WHITE = Color.WHITE;
    private static final int CARD = Color.rgb(46, 14, 18);
    private static final int TERMINAL = Color.rgb(14, 13, 15);
    private static final int MUTED = Color.rgb(210, 198, 202);

    private static final int TAB_HOME = 0;
    private static final int TAB_CONSOLE = 1;

    private final android.os.Handler handler =
            new android.os.Handler(android.os.Looper.getMainLooper());

    private ProgressBar generalProgress, stageProgress;
    private TextView generalLabel, stageLabel, installButton, versionSelector, installError;
    private LinearLayout versionOptions;
    private String selectedChannel = InstallerManager.CHANNEL_STABLE;

    private EditText worldName, seed, commandInput;
    private Switch cheats;
    private TextView survival, creative, serverButton;
    private boolean creativeMode;

    private TextView infoState, infoVersion, infoWorld, infoMode, infoAddress, infoAddons;
    private TextView consoleText;
    private ScrollView consoleScroll;
    private boolean consoleAutoFollow = true;
    private long lastServerLogModified = -1;
    private String lastConsoleValue = "";

    private FrameLayout dashboardContent;
    private View homePage, consolePage;
    private TextView navHome, navConsole;
    private int currentTab = TAB_HOME;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        configureWindow();
        AppPaths.ensure(this);
        requestNotificationPermission();
        selectedChannel = InstallService.selectedChannel(this);
        render();
        handler.post(uiTicker);
    }

    @Override protected void onResume() {
        super.onResume();
        configureWindow();
        if (screenShouldChange()) render();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) configureWindow();
    }

    private void configureWindow() {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setNavigationBarColor(Color.BLACK);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars());
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
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
        r.setPadding(dp(18), dp(14), dp(18), dp(14));
        r.setBackground(pageBg());
        return r;
    }

    private TextView tv(String text, float size, int color, int style) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(size);
        v.setTextColor(color);
        v.setTypeface(Typeface.create("sans-serif", style));
        return v;
    }

    private LinearLayout header() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, 0, 0, dp(12));

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.bubble_internal_icon);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        row.addView(logo, new LinearLayout.LayoutParams(dp(52), dp(52)));

        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        words.setPadding(dp(10), 0, 0, 0);
        words.addView(tv("B U B B L E", 17, WHITE, Typeface.BOLD));
        words.addView(tv("B D S   H O S T", 10.5f, WHITE, Typeface.NORMAL));
        row.addView(words, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        return row;
    }

    private TextView mainButton(String label) {
        TextView b = tv(label, 17, WHITE, Typeface.BOLD);
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(dp(58));
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

    private void buildInstaller() {
        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setBackground(pageBg());
        screen.setPadding(0,0,0,0);
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
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(dp(104), dp(104));
        hp.gravity = Gravity.CENTER_HORIZONTAL;
        content.addView(hero, hp);

        TextView heroTitle = tv("Sua Bubble começa aqui", 21, WHITE, Typeface.BOLD);
        heroTitle.setGravity(Gravity.CENTER);
        content.addView(heroTitle, match(0,9,0,6));

        TextView heroSub = tv(
                "Prepare um host Bedrock local para mundos, experiências e áreas compartilhadas da Bubble.",
                11.5f, Color.argb(230,255,238,241), Typeface.NORMAL);
        heroSub.setGravity(Gravity.CENTER);
        heroSub.setPadding(dp(18),0,dp(18),0);
        content.addView(heroSub, match(0,0,0,14));

        content.addView(infoCard(
                "◈  Universo de bolso",
                "A Bubble conecta experiências Bedrock em áreas compartilhadas sem transformar a interface em um painel técnico."),
                match(0,0,0,9));
        content.addView(infoCard(
                "⌁  Runtime local",
                "Debian Trixie + Box64 + BDS ficam no próprio aparelho. O build parcial do Box64 é retomável."),
                match(0,0,0,9));
        content.addView(infoCard(
                "↻  Continua em segundo plano",
                "A instalação roda em foreground com Wake Lock. Girar a tela, bloquear ou sair do app não interrompe o processo."),
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

        generalLabel = tv("Instalação geral • 0%",10.5f,MUTED,Typeface.NORMAL);
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
                ViewGroup.LayoutParams.MATCH_PARENT, dp(60)));

        versionOptions = new LinearLayout(this);
        versionOptions.setOrientation(LinearLayout.VERTICAL);
        versionOptions.setVisibility(View.GONE);
        versionOptions.setPadding(0,dp(5),0,0);

        TextView stable = versionOption(
                "Atual / Alpha", InstallerManager.BDS_STABLE_VERSION);
        stable.setOnClickListener(v -> selectChannel(InstallerManager.CHANNEL_STABLE));
        TextView preview = versionOption(
                "Preview / Beta", InstallerManager.BDS_PREVIEW_VERSION);
        preview.setOnClickListener(v -> selectChannel(InstallerManager.CHANNEL_PREVIEW));
        versionOptions.addView(stable, match(0,0,0,5));
        versionOptions.addView(preview);
        panel.addView(versionOptions);

        installButton = mainButton("⇩   Instalar Tudo");
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
                        : "Atual / Alpha   •   " + InstallerManager.BDS_STABLE_VERSION + "     ▾");
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
        generalLabel.setText("Instalação geral • " + g + "%");
        stageLabel.setText(stage + " • " + s + "%");
        installButton.setText(running
                ? "↻   Instalando em segundo plano..."
                : "⇩   Instalar Tudo");
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

    private void buildDashboard() {
        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setBackground(pageBg());
        shell.setPadding(0,0,0,0);
        applyBottomSafeInset(shell, 0);

        dashboardContent = new FrameLayout(this);
        homePage = buildHomePage();
        consolePage = buildConsolePage();
        dashboardContent.addView(homePage, new FrameLayout.LayoutParams(
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
    }

    private View buildHomePage() {
        LinearLayout content = pageRoot();
        content.addView(header());
        content.addView(serverInfoCard(), match(0,0,0,12));
        content.addView(worldCard(), match(0,0,0,10));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(content);
        return scroll;
    }

    private View buildConsolePage() {
        LinearLayout page = pageRoot();
        page.setPadding(dp(14),dp(12),dp(14),dp(10));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout title = new LinearLayout(this);
        title.setOrientation(LinearLayout.VERTICAL);
        title.addView(tv("Console do Servidor",20,WHITE,Typeface.BOLD));
        title.addView(tv(
                "Log completo em tempo real • comandos limitados",
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
        commandInput = field("Digite um comando: list, help, say...");
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
                "Permitidos pela Bubble: list • help • say • tell/msg/w • seed",
                9.5f,MUTED,Typeface.NORMAL);
        allowed.setGravity(Gravity.CENTER);
        page.addView(allowed,match(0,5,0,0));

        return page;
    }

    private View buildBottomNavigation() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER);
        bar.setPadding(dp(12),dp(7),dp(12),dp(7));
        bar.setBackground(box(
                Color.rgb(38,12,17),
                Color.argb(110,255,82,103),22));

        navHome = navItem("⌂\nInício");
        navConsole = navItem(">_\nConsole");

        navHome.setOnClickListener(v -> showTab(TAB_HOME, true));
        navConsole.setOnClickListener(v -> showTab(TAB_CONSOLE, true));

        LinearLayout.LayoutParams one = new LinearLayout.LayoutParams(0,dp(62),1);
        bar.addView(navHome,one);
        LinearLayout.LayoutParams two = new LinearLayout.LayoutParams(0,dp(62),1);
        two.setMargins(dp(8),0,0,0);
        bar.addView(navConsole,two);
        return bar;
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
        if (consolePage != null) consolePage.setVisibility(tab == TAB_CONSOLE ? View.VISIBLE : View.GONE);

        if (navHome != null) {
            navHome.setBackground(box(
                    tab == TAB_HOME ? Color.rgb(169,21,42) : Color.TRANSPARENT,
                    tab == TAB_HOME ? Color.rgb(255,70,91) : Color.TRANSPARENT,16));
            navHome.setAlpha(tab == TAB_HOME ? 1f : .68f);
        }
        if (navConsole != null) {
            navConsole.setBackground(box(
                    tab == TAB_CONSOLE ? Color.rgb(169,21,42) : Color.TRANSPARENT,
                    tab == TAB_CONSOLE ? Color.rgb(255,70,91) : Color.TRANSPARENT,16));
            navConsole.setAlpha(tab == TAB_CONSOLE ? 1f : .68f);
        }

        if (tab == TAB_CONSOLE) {
            refreshServerConsole(true);
            if (userAction) {
                consoleAutoFollow = true;
                scrollConsoleWithoutFocus();
            }
        }
    }

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

    private View worldCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16),dp(16),dp(16),dp(16));
        card.setBackground(box(CARD,Color.argb(70,255,90,110),22));

        card.addView(tv("◈  Configurações do Mundo",17.5f,WHITE,Typeface.BOLD));
        card.addView(label("Nome do mundo"), match(0,13,0,0));
        worldName = field("Meu Mundo Bubble");
        card.addView(worldName, fixedHeight(76,0,5,0,13));

        card.addView(label("Modo de jogo"));
        LinearLayout modes = new LinearLayout(this);
        survival = segment("⚔  Survival",true);
        creative = segment("◇  Criativo",false);
        survival.setOnClickListener(v -> mode(false));
        creative.setOnClickListener(v -> mode(true));
        modes.addView(survival,new LinearLayout.LayoutParams(0,dp(60),1));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0,dp(60),1);
        cp.setMargins(dp(7),0,0,0);
        modes.addView(creative,cp);
        card.addView(modes, match(0,5,0,13));

        LinearLayout cheatsRow = new LinearLayout(this);
        cheatsRow.setGravity(Gravity.CENTER_VERTICAL);
        cheatsRow.setMinimumHeight(dp(60));
        LinearLayout cheatText = new LinearLayout(this);
        cheatText.setOrientation(LinearLayout.VERTICAL);
        cheatText.addView(tv("Cheats",13,MUTED,Typeface.BOLD));
        cheatText.addView(tv("Permitir comandos no mundo",10,MUTED,Typeface.NORMAL));
        cheatsRow.addView(cheatText,new LinearLayout.LayoutParams(
                0,ViewGroup.LayoutParams.WRAP_CONTENT,1));

        cheats = new Switch(this);
        cheats.setThumbTintList(new ColorStateList(
                new int[][]{
                        new int[]{android.R.attr.state_checked},
                        new int[]{}},
                new int[]{WHITE,Color.rgb(210,205,207)}));
        cheats.setTrackTintList(new ColorStateList(
                new int[][]{
                        new int[]{android.R.attr.state_checked},
                        new int[]{}},
                new int[]{RED,Color.rgb(92,72,78)}));
        cheatsRow.addView(cheats);
        card.addView(cheatsRow, match(0,0,0,13));

        card.addView(label("Seed"));
        seed = field("");
        seed.setInputType(InputType.TYPE_CLASS_TEXT);
        card.addView(seed, fixedHeight(76,0,5,0,13));

        serverButton = mainButton("▶  Iniciar Servidor");
        serverButton.setOnClickListener(v -> toggleServer());
        card.addView(serverButton);
        return card;
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
        e.setMinHeight(dp(76));
        e.setPadding(dp(16),dp(8),dp(16),dp(8));
        e.setBackground(box(
                Color.rgb(39,24,27),
                Color.rgb(111,90,96),14));
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

    private void sendSafeCommand() {
        if (commandInput == null) return;
        String cmd = commandInput.getText().toString().trim();
        if (cmd.startsWith("/")) cmd = cmd.substring(1).trim();
        if (cmd.isEmpty()) return;

        if (!isAllowedCommand(cmd)) {
            commandInput.setError("Comando bloqueado pela Bubble");
            return;
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
            appendLocalConsole("[bubble] Falha ao salvar configurações: " + e.getMessage());
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
        String port = getProperty("server-port","19132");
        String serverName = getProperty("server-name","Bubble BDS Host");
        String ip = BdsService.getLocalIpv4();

        infoState.setText(running
                ? "● " + serverName + " • Online"
                : "● " + serverName + " • Pronto");
        infoState.setTextColor(running ? Color.rgb(89,246,158) : WHITE);
        infoVersion.setText(
                InstallerManager.readInstalledBdsChannel(this)
                        + " • " + InstallerManager.readInstalledBdsVersion(this));
        infoWorld.setText("Mundo: " + world);
        infoMode.setText(
                "Modo: "
                        + ("creative".equalsIgnoreCase(mode) ? "Criativo" : "Survival")
                        + "   •   Cheats: "
                        + ("true".equalsIgnoreCase(
                                getProperty("allow-cheats","false")) ? "ON" : "OFF"));
        infoAddress.setText("IP local: " + ip + ":" + port);
        infoAddons.setText("Add-ons do mundo: " + countWorldAddons(world));
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

    private void loadConfig() {
        if (worldName == null) return;
        worldName.setText(getProperty("level-name","Meu Mundo Bubble"));
        seed.setText(getProperty("level-seed",""));
        cheats.setChecked(
                "true".equalsIgnoreCase(getProperty("allow-cheats","false")));
        mode("creative".equalsIgnoreCase(getProperty("gamemode","survival")));
    }

    private void saveConfig() throws Exception {
        File f = new File(AppPaths.bds(this),"server.properties");
        if (!f.isFile()) {
            throw new IllegalStateException("server.properties não encontrado");
        }

        String text = read(f);
        String name = worldName.getText().toString().trim();
        if (name.isEmpty()) name = "Meu Mundo Bubble";

        text = replace(text,"level-name",name);
        text = replace(text,"gamemode",creativeMode ? "creative" : "survival");
        text = replace(text,"allow-cheats",cheats.isChecked() ? "true" : "false");
        text = replace(text,"level-seed",seed.getText().toString().trim());

        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
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
        super.onDestroy();
    }
}
