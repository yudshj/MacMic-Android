package com.macmic.micserver;

import android.animation.Animator;
import android.animation.AnimatorInflater;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.res.ColorStateList;
import android.os.Build;
import android.transition.AutoTransition;
import android.transition.TransitionManager;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * REFERENCE binder for design/res/layout/activity_main.xml (platform APIs only, minSdk 29).
 * Copy into src/com/macmic/micserver/ and drive it from MainActivity:
 *
 *   ui = new MainUi(this, actions);          // after setContentView(R.layout.activity_main)
 *   ui.bind(model);                          // every 500 ms tick (cheap: diffs before touching views)
 *
 * It knows nothing about MicService/MicServer: MainActivity fills a {@link Model} from Status.*
 * (see INTEGRATION.md §3.3) and implements {@link Actions}.
 */
final class MainUi {
    enum UiState { STOPPED, WAITING, HANDSHAKING, CONNECTED, STREAMING, ERROR }
    enum Banner { NONE, MIC_PERMISSION, MIC_PERMISSION_PERMANENT, NOTIFICATIONS_OFF, NO_WIFI }

    /** Plain snapshot of everything the screen shows. */
    static final class Model {
        UiState state = UiState.STOPPED;
        boolean serviceRunning;
        boolean portBusy;              // ERROR caused by bind failure on 8125
        String detail = "";            // Status.detail (shown in ERROR)
        String peer = "";              // "192.168.1.40:51234"
        String transport = "";         // Status.transport: "UDP -> ip:port" | "TCP (USB/ADB) ..."
        List<String> addresses = new ArrayList<>(); // "192.168.1.23" (primary first, e.g. wlan0)
        long packets, bytes, dropped, sessions, sessionStartMs;
        boolean wakeLock, wifiLock;
        List<String> events = new ArrayList<>();    // Status.recentEvents(): newest first
        Banner banner = Banner.NONE;
        String version = "1.0.0", opusVersion = "";
    }

    interface Actions {
        void onStartService();               // permission check + startForegroundService(MicService)
        void onStopService();                // stopService(MicService) or ACTION_STOP intent
        void onRetryService();                // ERROR: stop + start
        void onBannerAction(Banner b);  // request permission / open settings / enable notifications
    }

    private static final int PORT = 8125;
    private static final int MAX_LOG_ROWS = 60;  // = Status ring size; viewport shows ~8, rest scrolls

    private final Activity a;
    private final Actions actions;
    private final View root, heroPulse, stateDot, stateP, banner, btnStart, btnStop;
    private final ScrollView scroll;
    private final ImageView heroBadge, startIcon, stopIcon, bannerIcon, transportIcon, peerIcon;
    private final TextView stateLabel, heroTitle, heroSub, startLabel, stopLabel, bannerTitle, bannerMsg;
    private final TextView addressValue, addressExtra, transportValue, peerValue, keepaliveText;
    private final TextView packetsValue, packetsSub, bytesValue, bytesSub, uptimeValue, uptimeSub;
    private final TextView chipWake, chipWifi, logCount, logEmpty, footer, eventsView;
    private final Button bannerAction, bannerDismiss, btnSecondary;
    private final Switch keepScreen;
    private final ViewGroup heroCard;
    private Animator pulse;
    private UiState shownState;
    private Banner shownBanner, dismissedBanner;
    private String shownEventsKey = "";
    private String primaryAddress = "";
    private Model last;

    MainUi(Activity activity, Actions actions) {
        this.a = activity;
        this.actions = actions;
        root = find(R.id.root);
        scroll = find(R.id.scroll);
        stateP = find(R.id.state_pill);
        stateDot = find(R.id.state_dot);
        stateLabel = find(R.id.txt_state);
        banner = find(R.id.banner);
        bannerIcon = find(R.id.banner_icon);
        bannerTitle = find(R.id.banner_title);
        bannerMsg = find(R.id.banner_message);
        bannerAction = find(R.id.banner_action);
        bannerDismiss = find(R.id.banner_dismiss);
        heroCard = find(R.id.card_hero);
        heroPulse = find(R.id.hero_pulse);
        heroBadge = find(R.id.hero_badge);
        heroTitle = find(R.id.hero_title);
        heroSub = find(R.id.txt_status);
        btnStart = find(R.id.btn_start);
        startIcon = find(R.id.btn_start_icon);
        startLabel = find(R.id.btn_start_label);
        btnStop = find(R.id.btn_stop);
        stopIcon = find(R.id.btn_stop_icon);
        stopLabel = find(R.id.btn_stop_label);
        btnSecondary = find(R.id.btn_secondary);
        addressValue = find(R.id.address_value);
        addressExtra = find(R.id.address_extra);
        transportIcon = find(R.id.transport_icon);
        transportValue = find(R.id.transport_value);
        peerIcon = find(R.id.peer_icon);
        peerValue = find(R.id.peer_value);
        packetsValue = find(R.id.stat_packets_value);
        packetsSub = find(R.id.stat_packets_sub);
        bytesValue = find(R.id.stat_bytes_value);
        bytesSub = find(R.id.stat_bytes_sub);
        uptimeValue = find(R.id.stat_uptime_value);
        uptimeSub = find(R.id.stat_uptime_sub);
        keepaliveText = find(R.id.keepalive_text);
        chipWake = find(R.id.chip_wakelock);
        chipWifi = find(R.id.chip_wifilock);
        keepScreen = find(R.id.chk_keep_screen_on);
        eventsView = find(R.id.txt_events);
        logCount = find(R.id.log_count);
        logEmpty = find(R.id.log_empty);
        footer = find(R.id.footer_text);

        applyInsets();
        btnStart.setOnClickListener(v -> { if (last != null && last.state == UiState.ERROR) actions.onRetryService(); else actions.onStartService(); });
        btnStop.setOnClickListener(v -> actions.onStopService());
        btnSecondary.setOnClickListener(v -> actions.onStopService());
        bannerAction.setOnClickListener(v -> { if (last != null) actions.onBannerAction(last.banner); });
        bannerDismiss.setOnClickListener(v -> { dismissedBanner = shownBanner; banner.setVisibility(View.GONE); });
        find(R.id.row_address).setOnClickListener(v -> copy(primaryAddress, a.getString(R.string.copied_toast, primaryAddress)));
        find(R.id.btn_log_copy).setOnClickListener(v -> copyLog());
        find(R.id.row_keep_screen).setOnClickListener(v -> keepScreen.toggle());
        keepScreen.setOnCheckedChangeListener((v, on) -> {
            if (on) a.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            else a.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        });
    }

    /** Call in onCreate BEFORE setContentView on API 29-34 (API 35+ with targetSdk 35+ is edge-to-edge already). */
    @SuppressWarnings("deprecation")
    static void enableEdgeToEdge(Window w) {
        if (Build.VERSION.SDK_INT >= 30) w.setDecorFitsSystemWindows(false);
        else w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    /** Stop the halo animator (call from onPause). */
    void pause() { if (pulse != null) pulse.cancel(); shownState = null; }

    // ------------------------------------------------------------------ bind
    void bind(Model m) {
        last = m;
        if (m.state != shownState) renderState(m);
        else renderHeroText(m);
        renderBanner(m);
        renderConnection(m);
        renderKeepAlive(m);
        renderLog(m.events);
        footer.setText(a.getString(R.string.footer_format, m.version, m.opusVersion.isEmpty() ? "1.5" : m.opusVersion));
    }

    private void renderState(Model m) {
        if (shownState != null) {
            AutoTransition t = new AutoTransition();
            t.setDuration(180);
            TransitionManager.beginDelayedTransition(heroCard, t);
        }
        shownState = m.state;
        int pillLabel, dot, container, on, badgeBg, glyph, glyphTint;
        boolean animate;
        switch (m.state) {
            case WAITING: case HANDSHAKING:
                pillLabel = m.state == UiState.WAITING ? R.string.state_waiting : R.string.state_handshaking;
                dot = R.color.state_waiting_dot; container = R.color.state_waiting_container; on = R.color.state_waiting_on;
                badgeBg = R.color.primary_container; glyph = R.drawable.ic_listening; glyphTint = R.color.on_primary_container;
                animate = true; break;
            case CONNECTED:
                pillLabel = R.string.state_connected;
                dot = R.color.state_connected_dot; container = R.color.state_connected_container; on = R.color.state_connected_on;
                badgeBg = R.color.primary_container; glyph = R.drawable.ic_connected; glyphTint = R.color.on_primary_container;
                animate = false; break;
            case STREAMING:
                pillLabel = R.string.state_streaming;
                dot = R.color.state_streaming_dot; container = R.color.state_streaming_container; on = R.color.state_streaming_on;
                badgeBg = R.color.success_container; glyph = R.drawable.ic_mic; glyphTint = R.color.on_success_container;
                animate = true; break;
            case ERROR:
                pillLabel = R.string.state_error;
                dot = R.color.state_error_dot; container = R.color.state_error_container; on = R.color.state_error_on;
                badgeBg = R.color.state_error_container; glyph = R.drawable.ic_error; glyphTint = R.color.state_error_dot;
                animate = false; break;
            default:
                pillLabel = R.string.state_stopped;
                dot = R.color.state_idle_dot; container = R.color.state_idle_container; on = R.color.state_idle_on;
                badgeBg = R.color.surface_container_highest; glyph = R.drawable.ic_mic_off; glyphTint = R.color.on_surface_variant;
                animate = false;
        }
        String label = a.getString(pillLabel);
        stateLabel.setText(label);
        stateLabel.setTextColor(color(on));
        stateDot.setBackgroundTintList(csl(dot));
        stateP.setBackgroundTintList(csl(container));
        stateP.setContentDescription(a.getString(R.string.cd_state_pill, label));
        heroBadge.setBackgroundTintList(csl(badgeBg));
        heroBadge.setImageResource(glyph);
        heroBadge.setImageTintList(csl(glyphTint));
        heroPulse.setBackgroundTintList(csl(dot));
        setPulse(animate);

        // primary CTA (one of two views): STOPPED -> btn_start filled "启动服务"; ERROR -> btn_start "重试"
        // (+ text button "停止服务" while the service keeps retrying); WAITING/CONNECTED -> btn_stop tonal
        // "停止服务"; STREAMING -> btn_stop red-tonal "停止推流".
        boolean showStart = m.state == UiState.STOPPED || m.state == UiState.ERROR;
        btnStart.setVisibility(showStart ? View.VISIBLE : View.GONE);
        btnStop.setVisibility(showStart ? View.GONE : View.VISIBLE);
        if (showStart) {
            boolean retry = m.state == UiState.ERROR;
            startIcon.setImageResource(retry ? R.drawable.ic_refresh : R.drawable.ic_play);
            startLabel.setText(retry ? R.string.cta_retry : R.string.cta_start);
            btnStart.setContentDescription(a.getString(retry ? R.string.cta_retry : R.string.cd_cta_start));
        } else {
            boolean streaming = m.state == UiState.STREAMING;
            int fg = streaming ? R.color.on_cta_stop : R.color.on_secondary_container;
            btnStop.setBackgroundResource(streaming ? R.drawable.bg_cta_stop : R.drawable.bg_cta_tonal);
            stopIcon.setImageTintList(csl(fg));
            stopLabel.setTextColor(color(fg));
            stopLabel.setText(streaming ? R.string.cta_stop_streaming : R.string.cta_stop);
            btnStop.setContentDescription(a.getString(streaming ? R.string.cta_stop_streaming : R.string.cd_cta_stop));
        }
        btnSecondary.setVisibility(m.state == UiState.ERROR && m.serviceRunning ? View.VISIBLE : View.GONE);
        renderHeroText(m);
    }

    private void renderHeroText(Model m) {
        String peerHost = host(m.peer);
        int title; String sub;
        switch (m.state) {
            case WAITING: title = R.string.hero_title_waiting; sub = a.getString(R.string.hero_sub_waiting); break;
            case HANDSHAKING: title = R.string.hero_title_handshaking; sub = a.getString(R.string.hero_sub_handshaking, peerHost); break;
            case CONNECTED: title = R.string.hero_title_connected; sub = a.getString(R.string.hero_sub_connected, peerHost); break;
            case STREAMING: title = R.string.hero_title_streaming; sub = a.getString(R.string.hero_sub_streaming, peerHost); break;
            case ERROR:
                if (m.portBusy) { title = R.string.hero_title_port_busy; sub = a.getString(R.string.hero_sub_port_busy); }
                else { title = R.string.hero_title_error; sub = m.detail; }
                break;
            default:
                if (m.banner == Banner.MIC_PERMISSION || m.banner == Banner.MIC_PERMISSION_PERMANENT) {
                    title = R.string.hero_title_no_permission; sub = a.getString(R.string.hero_sub_no_permission);
                } else { title = R.string.hero_title_stopped; sub = a.getString(R.string.hero_sub_stopped); }
        }
        setIfChanged(heroTitle, a.getString(title));
        setIfChanged(heroSub, sub);
    }

    private void setPulse(boolean on) {
        boolean motion = ValueAnimator.areAnimatorsEnabled();   // respects "Remove animations"
        if (on && motion) {
            if (pulse == null) {
                pulse = AnimatorInflater.loadAnimator(a, R.animator.pulse);
                pulse.setTarget(heroPulse);
            }
            if (!pulse.isStarted()) pulse.start();
        } else {
            if (pulse != null) pulse.cancel();
            heroPulse.setAlpha(on ? 0.35f : 0f);   // static ring when animations are disabled
            heroPulse.setScaleX(on ? 0.86f : 1f);
            heroPulse.setScaleY(on ? 0.86f : 1f);
        }
    }

    private void renderBanner(Model m) {
        Banner b = m.banner;
        if (b != Banner.NONE && b == dismissedBanner) return;   // user tapped "知道了" for this kind
        if (b == Banner.NONE) dismissedBanner = null;
        if (b == shownBanner) return;
        shownBanner = b;
        if (b == Banner.NONE) { banner.setVisibility(View.GONE); return; }
        int bg, fg, icon, title, msg, action; boolean dismissible;
        switch (b) {
            case MIC_PERMISSION_PERMANENT:
                bg = R.color.state_waiting_container; fg = R.color.state_waiting_on; icon = R.drawable.ic_mic_off;
                title = R.string.banner_perm_title; msg = R.string.banner_perm_denied_msg; action = R.string.banner_perm_settings; dismissible = false; break;
            case NOTIFICATIONS_OFF:
                bg = R.color.secondary_container; fg = R.color.on_secondary_container; icon = R.drawable.ic_notification;
                title = R.string.banner_notif_title; msg = R.string.banner_notif_msg; action = R.string.banner_notif_action; dismissible = true; break;
            case NO_WIFI:
                bg = R.color.secondary_container; fg = R.color.on_secondary_container; icon = R.drawable.ic_wifi;
                title = R.string.banner_network_title; msg = R.string.banner_network_msg; action = 0; dismissible = true; break;
            default:
                bg = R.color.state_waiting_container; fg = R.color.state_waiting_on; icon = R.drawable.ic_mic_off;
                title = R.string.banner_perm_title; msg = R.string.banner_perm_msg; action = R.string.banner_perm_action; dismissible = false;
        }
        banner.setBackgroundTintList(csl(bg));
        bannerIcon.setImageResource(icon);
        bannerIcon.setImageTintList(csl(fg));
        bannerTitle.setText(title);
        bannerTitle.setTextColor(color(fg));
        bannerMsg.setText(msg);
        bannerMsg.setTextColor(color(fg));
        bannerAction.setVisibility(action == 0 ? View.GONE : View.VISIBLE);
        if (action != 0) bannerAction.setText(action);
        bannerAction.setTextColor(color(fg));
        bannerDismiss.setVisibility(dismissible ? View.VISIBLE : View.GONE);
        bannerDismiss.setTextColor(color(fg));
        banner.setVisibility(View.VISIBLE);
    }

    private void renderConnection(Model m) {
        if (m.addresses.isEmpty()) {
            primaryAddress = "";
            setIfChanged(addressValue, a.getString(R.string.address_none));
        } else {
            primaryAddress = a.getString(R.string.address_format, m.addresses.get(0), PORT);
            setIfChanged(addressValue, primaryAddress);
        }
        StringBuilder extra = new StringBuilder();
        if (m.addresses.size() > 1) {
            extra.append(a.getString(R.string.address_more, String.join("、", m.addresses.subList(1, m.addresses.size())))).append('\n');
        }
        extra.append(a.getString(R.string.usb_hint));
        setIfChanged(addressExtra, extra.toString());
        View row = find(R.id.row_address);
        row.setEnabled(!primaryAddress.isEmpty());
        row.setContentDescription(a.getString(R.string.cd_copy_address, primaryAddress));

        boolean active = m.state == UiState.STREAMING || m.state == UiState.CONNECTED || m.state == UiState.HANDSHAKING;
        String t = m.transport == null ? "" : m.transport;
        boolean usb = t.startsWith("TCP") || isLoopback(m.peer);
        transportIcon.setImageResource(usb ? R.drawable.ic_usb : R.drawable.ic_wifi);
        String tv;
        if (!active) tv = a.getString(R.string.transport_none);
        else if (usb) tv = a.getString(R.string.transport_usb);
        else if (t.startsWith("UDP")) tv = a.getString(R.string.transport_wifi) + "  →  " + t.replaceFirst("^UDP\\s*->\\s*", "");
        else tv = a.getString(R.string.transport_wifi);
        setIfChanged(transportValue, tv);

        boolean hasPeer = active && !m.peer.isEmpty();
        peerIcon.setImageResource(hasPeer ? R.drawable.ic_connected : R.drawable.ic_link_off);
        peerIcon.setImageTintList(csl(hasPeer ? R.color.success : R.color.on_surface_variant));
        setIfChanged(peerValue, hasPeer ? m.peer : a.getString(R.string.peer_none));
        peerValue.setTextColor(color(hasPeer ? R.color.on_surface : R.color.on_surface_variant));

        boolean streaming = m.state == UiState.STREAMING && m.sessionStartMs > 0;
        long secs = streaming ? Math.max(1, (System.currentTimeMillis() - m.sessionStartMs) / 1000) : 0;
        String none = a.getString(R.string.stat_none);
        setIfChanged(packetsValue, streaming ? String.format(Locale.getDefault(), "%,d", m.packets) : none);
        setIfChanged(packetsSub, streaming ? a.getString(R.string.stat_rate, (int) (m.packets / secs)) + " · "
                + a.getString(R.string.stat_dropped, (int) m.dropped) : "");
        setIfChanged(bytesValue, streaming ? humanBytes(m.bytes) : none);
        setIfChanged(bytesSub, streaming ? a.getString(R.string.stat_bitrate, (int) (m.bytes * 8 / secs / 1000)) : "");
        setIfChanged(uptimeValue, streaming ? duration(secs) : none);
        setIfChanged(uptimeSub, m.sessions > 0 ? a.getString(R.string.stat_sessions, (int) m.sessions) : "");
        tileCd(R.id.stat_packets, R.string.label_packets, packetsValue, packetsSub);
        tileCd(R.id.stat_bytes, R.string.label_bytes, bytesValue, bytesSub);
        tileCd(R.id.stat_uptime, R.string.label_uptime, uptimeValue, uptimeSub);
    }

    private void renderKeepAlive(Model m) {
        setIfChanged(keepaliveText, a.getString(m.serviceRunning ? R.string.keepalive_on : R.string.keepalive_off));
        chipWake.setCompoundDrawableTintList(csl(m.wakeLock ? R.color.success : R.color.outline));
        chipWifi.setCompoundDrawableTintList(csl(m.wifiLock ? R.color.success : R.color.outline));
    }

    private void renderLog(List<String> events) {
        String key = events.size() + "|" + (events.isEmpty() ? "" : events.get(0));
        if (key.equals(shownEventsKey)) return;
        shownEventsKey = key;
        int n = Math.min(events.size(), MAX_LOG_ROWS);
        SpannableStringBuilder sb = new SpannableStringBuilder();
        int timeColor = color(R.color.log_time);
        for (int i = 0; i < n; i++) {
            String line = events.get(i);                     // "HH:mm:ss.SSS  message"
            int sp = line.indexOf("  ");
            String time = sp > 0 ? line.substring(0, Math.min(sp, 8)) : "";
            String msg = sp > 0 ? line.substring(sp + 2) : line;
            if (i > 0) sb.append('\n');
            int t0 = sb.length();
            sb.append(time);
            sb.setSpan(new ForegroundColorSpan(timeColor), t0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.append("  ");
            int m0 = sb.length();
            sb.append(msg);
            String low = msg.toLowerCase(Locale.ROOT);
            int c = low.contains("error") || low.contains("fail") || low.contains("cannot") ? R.color.log_error
                    : low.contains("rejected") || low.contains("denied") || low.contains("session end") || low.contains("drop")
                    ? R.color.log_warn : 0;
            if (c != 0) sb.setSpan(new ForegroundColorSpan(color(c)), m0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        eventsView.setText(sb);
        logEmpty.setVisibility(n == 0 ? View.VISIBLE : View.GONE);
        logCount.setText(n == 0 ? "" : String.valueOf(n));
    }

    // ------------------------------------------------------------------ actions
    private void copy(String text, String toast) {
        if (text.isEmpty()) return;
        ClipboardManager cm = a.getSystemService(ClipboardManager.class);
        cm.setPrimaryClip(ClipData.newPlainText("MacMic", text));
        if (Build.VERSION.SDK_INT < 33) Toast.makeText(a, toast, Toast.LENGTH_SHORT).show(); // 13+ shows its own UI
    }

    private void copyLog() {
        if (last == null) return;
        copy(String.join("\n", last.events), a.getString(R.string.log_copied));
    }

    // ------------------------------------------------------------------ insets
    @SuppressWarnings("deprecation")
    private void applyInsets() {
        View content = find(R.id.content);
        final int padL = content.getPaddingStart(), padR = content.getPaddingEnd(), padB = content.getPaddingBottom();
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int l, t, r, b;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets i = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                l = i.left; t = i.top; r = i.right; b = i.bottom;
            } else {
                l = insets.getSystemWindowInsetLeft(); t = insets.getSystemWindowInsetTop();
                r = insets.getSystemWindowInsetRight(); b = insets.getSystemWindowInsetBottom();
            }
            scroll.setPadding(l, t, r, 0);                        // scroll content passes under the nav bar…
            content.setPaddingRelative(padL, 0, padR, padB + b);  // …but the last card clears it
            return Build.VERSION.SDK_INT >= 30 ? WindowInsets.CONSUMED : insets.consumeSystemWindowInsets();
        });
        root.requestApplyInsets();
    }

    // ------------------------------------------------------------------ helpers
    @SuppressWarnings("unchecked")
    private <T extends View> T find(int id) { return (T) a.findViewById(id); }
    private int color(int res) { return a.getColor(res); }
    private ColorStateList csl(int res) { return a.getColorStateList(res); }

    private static void setIfChanged(TextView v, CharSequence s) {
        if (!s.toString().contentEquals(v.getText())) v.setText(s);
    }

    private void tileCd(int tileId, int label, TextView value, TextView sub) {
        find(tileId).setContentDescription(a.getString(label) + " " + value.getText() + " " + sub.getText());
    }

    private static String host(String peer) {
        if (peer == null || peer.isEmpty()) return "Mac";
        int c = peer.lastIndexOf(':');
        return c > 0 && peer.indexOf(':') == c ? peer.substring(0, c) : peer;
    }

    private static boolean isLoopback(String peer) {
        return peer != null && (peer.startsWith("127.") || peer.startsWith("/127.") || peer.startsWith("::1"));
    }

    static String humanBytes(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return String.format(Locale.US, "%.1f KB", b / 1024.0);
        if (b < 1024L * 1024 * 1024) return String.format(Locale.US, "%.1f MB", b / 1048576.0);
        return String.format(Locale.US, "%.2f GB", b / 1073741824.0);
    }

    static String duration(long secs) {
        long h = secs / 3600, m = secs / 60 % 60, s = secs % 60;
        return h > 0 ? String.format(Locale.US, "%d:%02d:%02d", h, m, s) : String.format(Locale.US, "%02d:%02d", m, s);
    }
}
