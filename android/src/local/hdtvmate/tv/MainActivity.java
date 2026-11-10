package local.hdtvmate.tv;

import android.Manifest;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.json.JSONArray;
import org.json.JSONObject;

public final class MainActivity extends Activity implements SurfaceHolder.Callback {
    private static final String TAG = "HDTVMate";
    private static final String ACTION_PERM = "local.hdtvmate.tv.USB_PERMISSION";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private UsbManager usb;
    private Tuner tuner;
    private volatile LivePlayer player;
    private SurfaceView surface;
    private volatile boolean surfaceReady;

    // mini player
    private View mini, bottomScrim;
    private FrameLayout miniTile;
    private TextView miniTitle, miniSub, miniClock, miniAlert;
    private Ui.SignalBars miniBars;
    private Ui.Equalizer miniEq;
    private String videoLabel = "";
    // channel list
    private View panel, leftScrim;
    private LinearLayout rows;
    private ScrollView rowScroll;
    private TextView panelCount;
    private final List<View> rowViews = new ArrayList<>();
    // status
    private View statusBox;
    private ProgressBar spinner;
    private TextView statusTitle, statusSub;
    private Ui.ProgressLine statusProgress;

    private final List<Ts.Channel> channels = new ArrayList<>();
    private int current = -1, playToken;
    private volatile boolean scanning, active;
    private volatile Socket devSocket;

    // ================= layout =================

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.init(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        usb = (UsbManager) getSystemService(USB_SERVICE);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);
        surface = new SurfaceView(this);
        surface.getHolder().addCallback(this);
        root.addView(surface, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));

        bottomScrim = new View(this);
        bottomScrim.setBackground(Ui.scrim(GradientDrawable.Orientation.BOTTOM_TOP, 0xB3000000));
        bottomScrim.setVisibility(View.GONE);
        root.addView(bottomScrim, new FrameLayout.LayoutParams(-1, dp(240), Gravity.BOTTOM));
        leftScrim = new View(this);
        leftScrim.setBackground(Ui.scrim(GradientDrawable.Orientation.LEFT_RIGHT, 0xCC000000));
        leftScrim.setVisibility(View.GONE);
        root.addView(leftScrim, new FrameLayout.LayoutParams(dp(820), -1, Gravity.START));

        FrameLayout.LayoutParams mp = new FrameLayout.LayoutParams(dp(880), -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        mp.setMargins(0, 0, 0, dp(36));
        root.addView(buildMini(), mp);
        root.addView(buildPanel(), new FrameLayout.LayoutParams(dp(470), -1, Gravity.START));
        root.addView(buildStatus(), new FrameLayout.LayoutParams(dp(600), -2, Gravity.CENTER));
        setContentView(root);
        loadChannels();
    }

    private int dp(float v) { return Ui.dp(this, v); }

    /** Queue DJ's mini player: a glass bar with artwork tile, title, subtitle and trailing info. */
    private View buildMini() {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackground(Ui.rounded(Ui.GLASS, dp(24)));
        bar.setPadding(dp(14), dp(14), dp(26), dp(14));
        bar.setElevation(dp(12));

        miniTile = new FrameLayout(this);
        bar.addView(miniTile, new LinearLayout.LayoutParams(dp(72), dp(72)));

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        miniTitle = Ui.text(this, 26, Ui.bold, Ui.TEXT);
        text.addView(miniTitle);
        LinearLayout subRow = new LinearLayout(this);
        subRow.setGravity(Gravity.CENTER_VERTICAL);
        miniEq = new Ui.Equalizer(this, Ui.ACCENT);
        LinearLayout.LayoutParams eqp = new LinearLayout.LayoutParams(dp(15), dp(13));
        eqp.setMargins(0, 0, dp(10), 0);
        subRow.addView(miniEq, eqp);
        miniSub = Ui.text(this, 17, Ui.regular, Ui.TEXT_2);
        subRow.addView(miniSub);
        miniAlert = Ui.text(this, 17, Ui.semibold, Ui.ACCENT);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(-2, -2);
        alp.setMargins(dp(12), 0, 0, 0);
        subRow.addView(miniAlert, alp);
        LinearLayout.LayoutParams srp = new LinearLayout.LayoutParams(-2, -2);
        srp.setMargins(0, dp(8), 0, 0);
        text.addView(subRow, srp);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, -2, 1);
        tp.setMargins(dp(18), 0, dp(18), 0);
        bar.addView(text, tp);

        LinearLayout trail = new LinearLayout(this);
        trail.setOrientation(LinearLayout.VERTICAL);
        trail.setGravity(Gravity.END);
        miniClock = Ui.text(this, 22, Ui.semibold, Ui.TEXT);
        trail.addView(miniClock);
        miniBars = new Ui.SignalBars(this);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(dp(28), dp(16));
        bp.setMargins(0, dp(10), 0, 0);
        bp.gravity = Gravity.END;
        trail.addView(miniBars, bp);
        bar.addView(trail);

        mini = bar;
        bar.setVisibility(View.GONE);
        return bar;
    }

    private View buildPanel() {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setBackgroundColor(0xF5000000);
        p.setPadding(dp(36), dp(44), dp(28), 0);
        TextView title = Ui.text(this, 38, Ui.heavy, Ui.TEXT);
        title.setText("Channels");
        p.addView(title);
        panelCount = Ui.text(this, 17, Ui.regular, Ui.TEXT_2);
        panelCount.setPadding(0, dp(8), 0, dp(20));
        p.addView(panelCount);
        rowScroll = new ScrollView(this);
        rowScroll.setVerticalScrollBarEnabled(false);
        rowScroll.setClipToPadding(false);
        rowScroll.setPadding(0, dp(8), 0, dp(40));
        rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        rowScroll.addView(rows, new FrameLayout.LayoutParams(-1, -2));
        p.addView(rowScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        panel = p;
        p.setVisibility(View.GONE);
        return p;
    }

    private View buildStatus() {
        LinearLayout s = new LinearLayout(this);
        s.setOrientation(LinearLayout.VERTICAL);
        s.setGravity(Gravity.CENTER_HORIZONTAL);
        spinner = new ProgressBar(this);
        spinner.setIndeterminate(true);
        spinner.setIndeterminateTintList(ColorStateList.valueOf(Ui.ACCENT));
        s.addView(spinner, new LinearLayout.LayoutParams(dp(44), dp(44)));
        statusTitle = Ui.text(this, 30, Ui.heavy, Ui.TEXT);
        statusTitle.setSingleLine(false);
        statusTitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-2, -2);
        tp.setMargins(0, dp(22), 0, 0);
        s.addView(statusTitle, tp);
        statusProgress = new Ui.ProgressLine(this);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(dp(340), dp(6));
        pp.setMargins(0, dp(22), 0, 0);
        s.addView(statusProgress, pp);
        statusSub = Ui.text(this, 18, Ui.regular, Ui.TEXT_2);
        statusSub.setSingleLine(false);
        statusSub.setGravity(Gravity.CENTER);
        statusSub.setLineSpacing(dp(4), 1f);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-2, -2);
        sp.setMargins(0, dp(14), 0, 0);
        s.addView(statusSub, sp);
        statusBox = s;
        s.setVisibility(View.GONE);
        return s;
    }

    // ================= UI state =================

    /** Centre status. title == null hides it; progress < 0 hides the bar. */
    private void status(final String title, final String sub, final boolean busy, final float progress) {
        ui.post(new Runnable() { public void run() {
            if (title == null) { Ui.show(statusBox, false); return; }
            statusTitle.setText(title);
            statusSub.setText(sub == null ? "" : sub);
            statusSub.setVisibility(sub == null ? View.GONE : View.VISIBLE);
            spinner.setVisibility(busy ? View.VISIBLE : View.GONE);
            statusProgress.setVisibility(progress < 0 ? View.GONE : View.VISIBLE);
            if (progress >= 0) statusProgress.set(progress);
            Ui.show(statusBox, true);
        } });
    }

    private final Runnable hideMini = new Runnable() { public void run() {
        Ui.show(mini, false); Ui.show(bottomScrim, false);
    } };

    private void nowPlaying(final Ts.Channel c, final String sub, final int strength, final String alert, final boolean live) {
        ui.post(new Runnable() { public void run() {
            miniTile.removeAllViews();
            View t = Ui.tile(MainActivity.this, c.major + "." + c.minor, 72, true);
            miniTile.addView(t, new FrameLayout.LayoutParams(-1, -1));
            miniTitle.setText(c.name);
            miniSub.setText(sub);
            miniClock.setText(new SimpleDateFormat("h:mm", Locale.US).format(new Date()));
            miniBars.setStrength(strength);
            miniBars.setVisibility(strength < 0 ? View.INVISIBLE : View.VISIBLE);
            miniEq.setVisibility(live ? View.VISIBLE : View.GONE);
            if (live) miniEq.invalidate();
            miniAlert.setText(alert == null ? "" : alert);
            miniAlert.setVisibility(alert == null ? View.GONE : View.VISIBLE);
            Ui.show(bottomScrim, true);
            Ui.show(mini, true);
            ui.removeCallbacks(hideMini);
            ui.postDelayed(hideMini, 5000);
        } });
    }

    private String subFor(Ts.Channel c) {
        return "Channel " + c.major + "." + c.minor + "  ·  RF " + c.rf + (videoLabel.isEmpty() ? "" : "  ·  " + videoLabel);
    }

    private void buildRows() {
        rows.removeAllViews();
        rowViews.clear();
        for (int i = 0; i < channels.size(); i++) {
            final int idx = i;
            Ts.Channel c = channels.get(i);
            rowViews.add(addRow(c.major + "." + c.minor, c.name, "RF " + c.rf, i == current, i < channels.size() - 1,
                    new View.OnClickListener() { public void onClick(View v) { closePanel(); play(idx); } }));
        }
        rowViews.add(addRow("↻", "Scan for Channels", null, false, false,
                new View.OnClickListener() { public void onClick(View v) { closePanel(); startScan(); } }));
        panelCount.setText(channels.isEmpty() ? "No channels yet" : channels.size() + " channels");
    }

    private View addRow(String num, String name, String right, final boolean playing, boolean separator, View.OnClickListener click) {
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        final LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(9), dp(16), dp(9));
        row.setFocusable(true);
        row.setClickable(true);
        row.setOnClickListener(click);
        row.addView(Ui.tile(this, num, 50, playing), new LinearLayout.LayoutParams(dp(50), dp(50)));
        final TextView t = Ui.text(this, 21, Ui.semibold, playing ? Ui.ACCENT : Ui.TEXT);
        t.setText(name);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(0, -2, 1);
        np.setMargins(dp(16), 0, dp(12), 0);
        row.addView(t, np);
        if (playing) {
            Ui.Equalizer eq = new Ui.Equalizer(this, Ui.ACCENT);
            LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(dp(15), dp(13));
            ep.setMargins(0, 0, dp(12), 0);
            row.addView(eq, ep);
        }
        final TextView r = Ui.text(this, 15, Ui.regular, Ui.TEXT_2);
        if (right != null) { r.setText(right); row.addView(r); }
        final GradientDrawable focusBg = Ui.rounded(Ui.MUTED, dp(14));
        row.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            public void onFocusChange(View v, boolean f) {
                row.setBackground(f ? focusBg : null);
                row.animate().scaleX(f ? 1.025f : 1f).scaleY(f ? 1.025f : 1f)
                        .setInterpolator(new android.view.animation.OvershootInterpolator(2f)).setDuration(220).start();
            }
        });
        outer.addView(row, new LinearLayout.LayoutParams(-1, -2));
        if (separator) {                                  // inset separator, starts after the tile
            View sep = new View(this);
            sep.setBackgroundColor(Ui.SEPARATOR);
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, Math.max(1, dp(0.5f)));
            sp.setMargins(dp(76), dp(1), dp(10), dp(1));
            outer.addView(sep, sp);
        }
        rows.addView(outer, new LinearLayout.LayoutParams(-1, -2));
        return row;
    }

    private void openPanel() {
        if (scanning || panel.getVisibility() == View.VISIBLE) return;
        buildRows();
        ui.removeCallbacks(hideMini);
        hideMini.run();
        panel.setTranslationX(-dp(40));
        panel.setAlpha(0f);
        panel.setVisibility(View.VISIBLE);
        panel.animate().translationX(0).alpha(1f).setInterpolator(new DecelerateInterpolator(2f)).setDuration(280).start();
        leftScrim.setAlpha(0f);
        leftScrim.setVisibility(View.VISIBLE);
        leftScrim.animate().alpha(1f).setDuration(280).start();
        final View target = rowViews.get(current >= 0 && current < channels.size() ? current : 0);
        ui.post(new Runnable() { public void run() {
            target.requestFocus();
            View item = (View) target.getParent();
            rowScroll.smoothScrollTo(0, Math.max(0, item.getTop() - rowScroll.getHeight() / 3));
        } });
    }

    private void closePanel() {
        panel.animate().translationX(-dp(40)).alpha(0f).setDuration(200).withEndAction(new Runnable() {
            public void run() { panel.setVisibility(View.GONE); }
        }).start();
        leftScrim.animate().alpha(0f).setDuration(200).withEndAction(new Runnable() {
            public void run() { leftScrim.setVisibility(View.GONE); }
        }).start();
    }

    // ================= lifecycle =================

    @Override protected void onResume() {
        super.onResume();
        active = true;
        IntentFilter f = new IntentFilter(ACTION_PERM);
        f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        registerReceiver(usbReceiver, f);
        String dev = getIntent().getStringExtra("devlive");     // developer only: live TS over the LAN
        if (dev != null) { devLive(dev, getIntent().getIntExtra("program", 1)); return; }
        if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
                && !askedStorage) {
            askedStorage = true;                          // ask once; without it the app still works from app storage
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, 1);
        } else
            connect();
    }

    private boolean askedStorage;

    @Override public void onRequestPermissionsResult(int code, String[] p, int[] r) { ui.post(new Runnable() { public void run() { connect(); } }); }

    @Override protected void onPause() {
        active = false;
        try { unregisterReceiver(usbReceiver); } catch (Exception ignored) { }
        playToken++;
        stopPlayer();
        Socket s = devSocket;
        if (s != null) try { s.close(); } catch (IOException ignored) { }
        worker.execute(new Runnable() { public void run() { closeTuner(); } });
        super.onPause();
    }

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (ACTION_PERM.equals(i.getAction())) {
                if (i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) connect();
                else status("USB Access Needed", "Allow OTA TV to use the tuner when asked.", false, -1);
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(i.getAction())) {
                UsbDevice d = android.os.Build.VERSION.SDK_INT >= 33
                        ? i.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class)
                        : (UsbDevice) i.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (d != null && d.getVendorId() == Tuner.VID && d.getProductId() == Tuner.PID) {
                    playToken++;
                    stopPlayer();
                    worker.execute(new Runnable() { public void run() { closeTuner(); } });
                    status("Tuner Disconnected", "Plug the tuner back in to keep watching.", false, -1);
                }
            }
        }
    };

    private UsbDevice findDevice() {
        for (UsbDevice d : usb.getDeviceList().values())
            if (d.getVendorId() == Tuner.VID && d.getProductId() == Tuner.PID) return d;
        return null;
    }

    private void connect() {
        final UsbDevice dev = findDevice();
        if (dev == null) { status("Connect Your Tuner", "Plug the HDTV Mate into a USB port.", false, -1); return; }
        if (!usb.hasPermission(dev)) {
            usb.requestPermission(dev, PendingIntent.getBroadcast(this, 0, new Intent(ACTION_PERM).setPackage(getPackageName()), 0));
            return;
        }
        status("Starting Tuner", null, true, -1);
        worker.execute(new Runnable() { public void run() {
            try {
                if (tuner == null) {
                    byte[] blob = findBlob();
                    if (blob == null) {
                        status("Tuner Firmware Needed", "Insert the HDTVMATE card into the tuner's card slot,\n"
                                + "or copy hdtvmate.bin to\n" + getExternalFilesDir(null), false, -1);
                        return;
                    }
                    tuner = new Tuner(usb, dev, new Tuner.Blob(blob));
                }
                if (channels.isEmpty()) scanNow();
                else {
                    int last = getPrefs().getInt("last", 0);
                    final int idx = last < channels.size() ? last : 0;
                    status(null, null, false, -1);
                    ui.post(new Runnable() { public void run() { play(idx); } });
                }
            } catch (Exception e) {
                Log.e(TAG, "connect", e);
                status("Tuner Error", e.getMessage(), false, -1);
                closeTuner();
            }
        } });
    }

    private void closeTuner() {
        if (tuner != null) { try { tuner.close(); } catch (Exception ignored) { } tuner = null; }
    }

    /** App storage first, then any mounted volume's PRIVATE-firmware/ (e.g. the card in the tuner's reader). */
    private byte[] findBlob() {
        File mine = new File(getExternalFilesDir(null), "hdtvmate.bin");
        List<File> cands = new ArrayList<>();
        cands.add(mine);
        File[] vols = new File("/storage").listFiles();
        if (vols != null) for (File v : vols) cands.add(new File(v, "PRIVATE-firmware/hdtvmate.bin"));
        cands.add(new File("/sdcard/PRIVATE-firmware/hdtvmate.bin"));
        cands.add(new File("/sdcard/Download/hdtvmate.bin"));
        for (File f : cands) {
            if (!f.isFile()) continue;
            try {
                byte[] d = new byte[(int) f.length()];
                FileInputStream in = new FileInputStream(f);
                int n = 0;
                while (n < d.length) { int r = in.read(d, n, d.length - n); if (r < 0) break; n += r; }
                in.close();
                if (!f.equals(mine)) {                     // keep a copy so the card is only needed once
                    FileOutputStream out = new FileOutputStream(mine);
                    out.write(d); out.close();
                }
                return d;
            } catch (IOException e) {
                Log.w(TAG, "blob " + f, e);
            }
        }
        return null;
    }

    // ================= channels =================

    private SharedPreferences getPrefs() { return getSharedPreferences("tv", MODE_PRIVATE); }

    private void loadChannels() {
        channels.clear();
        try {
            JSONArray a = new JSONArray(getPrefs().getString("channels", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Ts.Channel c = new Ts.Channel();
                c.rf = o.getInt("rf"); c.khz = o.getInt("khz"); c.major = o.getInt("major");
                c.minor = o.getInt("minor"); c.program = o.getInt("program"); c.name = o.getString("name");
                channels.add(c);
            }
        } catch (Exception e) { channels.clear(); }
    }

    private void saveChannels(List<Ts.Channel> list) {
        JSONArray a = new JSONArray();
        try {
            for (Ts.Channel c : list) {
                JSONObject o = new JSONObject();
                o.put("rf", c.rf); o.put("khz", c.khz); o.put("major", c.major);
                o.put("minor", c.minor); o.put("program", c.program); o.put("name", c.name);
                a.put(o);
            }
        } catch (Exception ignored) { }
        getPrefs().edit().putString("channels", a.toString()).putInt("last", 0).apply();
    }

    private void startScan() {
        playToken++;
        stopPlayer();
        worker.execute(new Runnable() { public void run() {
            try { scanNow(); } catch (Exception e) { status("Scan Failed", e.getMessage(), false, -1); }
        } });
    }

    /** Worker thread. Tunes RF 2-36 and reads the PSIP channel table (names, x.y numbers). */
    private void scanNow() throws IOException {
        if (tuner == null) return;
        scanning = true;
        final List<Ts.Channel> found = new ArrayList<>();
        try {
            for (int rf = 2; rf <= 36 && active; rf++) {
                final int khz = Tuner.channelKhz(rf), rfNo = rf;
                status("Scanning for Channels", found.isEmpty() ? "Searching VHF and UHF…"
                        : found.size() + " channel" + (found.size() == 1 ? "" : "s") + " found", false, (rf - 2) / 34f);
                if (!tuner.tune(khz)) continue;
                final List<Ts.Channel> here = new ArrayList<>();
                final Ts.Sections sec = new Ts.Sections(new Ts.Sections.Handler() {
                    public void section(int pid, byte[] s, int len) {
                        if (here.isEmpty()) here.addAll(Ts.parseTvct(s, len, rfNo, khz));
                    }
                });
                sec.watch(0x1ffb);
                tuner.startStream(new Tuner.TsSink() {
                    public void packets(byte[] b, int off, int len) {
                        synchronized (here) { for (int i = off; i + 188 <= off + len; i += 188) sec.packet(b, i); }
                    }
                });
                for (long end = System.currentTimeMillis() + 3000; System.currentTimeMillis() < end; ) {
                    synchronized (here) { if (!here.isEmpty()) break; }
                    Tuner.sleep(100);
                }
                tuner.stopStream();
                synchronized (here) { found.addAll(here); }
            }
        } finally {
            scanning = false;
        }
        if (!active) return;                              // interrupted: keep the previous channel list
        java.util.Collections.sort(found, new java.util.Comparator<Ts.Channel>() {
            public int compare(Ts.Channel a, Ts.Channel b) { return a.major != b.major ? a.major - b.major : a.minor - b.minor; }
        });
        saveChannels(found);
        ui.post(new Runnable() { public void run() {
            loadChannels();
            if (channels.isEmpty())
                status("No Channels Found", "Check the antenna connection, then press OK to scan again.", false, -1);
            else { status(null, null, false, -1); play(0); }
        } });
    }

    // ================= playback (live only, straight to the hardware decoders) =================

    private LivePlayer.Listener listenerFor(final Ts.Channel c, final int token, final int strength) {
        return new LivePlayer.Listener() {
            public void onVideoSize(final int w, final int h) {
                ui.post(new Runnable() { public void run() {
                    if (token != playToken) return;
                    fitVideo(w, h);
                    String label = h >= 1000 ? "1080i" : h >= 700 ? "720p" : h > 0 ? "SD" : "";
                    if (!label.equals(videoLabel)) {
                        videoLabel = label;
                        if (mini.getVisibility() == View.VISIBLE) nowPlaying(c, subFor(c), strength, null, true);
                    }
                } });
            }
            public void onFirstFrame(long ms) { Log.i(TAG, "first frame " + ms + " ms after start"); }
            public void onLatency(long ms) { Log.i(TAG, "latency arrival->screen " + ms + " ms"); }
        };
    }

    private void play(final int idx) {
        if (idx < 0 || idx >= channels.size() || scanning) return;
        current = idx;
        getPrefs().edit().putInt("last", idx).apply();
        final Ts.Channel c = channels.get(idx);
        final int token = ++playToken;
        stopPlayer();
        videoLabel = "";
        status(null, null, false, -1);
        nowPlaying(c, "Channel " + c.major + "." + c.minor + "  ·  Tuning…", -1, null, false);
        worker.execute(new Runnable() { public void run() {
            try {
                if (tuner == null || token != playToken) return;
                boolean lk = tuner.tune(c.khz);
                Tuner.Status st = tuner.status();
                if (token != playToken) return;
                nowPlaying(c, subFor(c), st.strength, lk ? null : "No Signal", lk);
                if (!lk) return;
                waitSurface();
                final LivePlayer p = new LivePlayer(surface.getHolder().getSurface(), c.program, listenerFor(c, token, st.strength));
                player = p;
                tuner.startStream(new Tuner.TsSink() {
                    public void packets(byte[] b, int off, int len) { p.feed(b, off, len); }
                });
            } catch (Exception e) {
                Log.e(TAG, "play", e);
                status("Tuner Error", e.getMessage(), false, -1);
            }
        } });
    }

    /** Developer test only (tuner on another machine): live TS from a TCP socket into the same player. */
    private void devLive(final String hostPort, final int program) {
        final int token = ++playToken;
        final Ts.Channel c = new Ts.Channel();
        c.major = 0; c.minor = program; c.name = "LAN test"; c.rf = 0;
        nowPlaying(c, "Live from " + hostPort, 100, null, true);
        new Thread(new Runnable() { public void run() {
            try {
                waitSurface();
                String[] hp = hostPort.split(":");
                Socket s = new Socket(hp[0], Integer.parseInt(hp[1]));
                s.setTcpNoDelay(true);
                devSocket = s;
                LivePlayer p = new LivePlayer(surface.getHolder().getSurface(), program, listenerFor(c, token, 100));
                player = p;
                InputStream in = s.getInputStream();
                byte[] buf = new byte[188 * 512];
                int have = 0, n;
                while (token == playToken && (n = in.read(buf, have, buf.length - have)) > 0) {
                    have += n;
                    int whole = have - have % 188;
                    p.feed(buf, 0, whole);
                    System.arraycopy(buf, whole, buf, 0, have - whole);
                    have -= whole;
                }
                s.close();
            } catch (Exception e) { Log.w(TAG, "devlive", e); }
        } }, "hdtvmate-devlive").start();
    }

    private void waitSurface() {
        for (int i = 0; i < 50 && !surfaceReady; i++) Tuner.sleep(50);
    }

    private void stopPlayer() {
        if (tuner != null) {
            final Tuner t = tuner;
            worker.execute(new Runnable() { public void run() { t.stopStream(); } });
        }
        LivePlayer p = player;
        player = null;
        if (p != null) p.release();
    }

    private void fitVideo(int w, int h) {
        if (w <= 0 || h <= 0) return;
        View root = (View) surface.getParent();
        int W = root.getWidth(), H = root.getHeight();
        double ar = w / (double) h;
        if (h == 480 && w >= 704) ar = 4.0 / 3;     // SD subchannels: 4:3
        if (h >= 720) ar = 16.0 / 9;
        int vw = W, vh = (int) (W / ar);
        if (vh > H) { vh = H; vw = (int) (H * ar); }
        surface.setLayoutParams(new FrameLayout.LayoutParams(vw, vh, Gravity.CENTER));
    }

    @Override public void surfaceCreated(SurfaceHolder h) { surfaceReady = true; }
    @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int hh) { }
    @Override public void surfaceDestroyed(SurfaceHolder h) { surfaceReady = false; }

    // ================= remote =================

    @Override public boolean onKeyDown(int code, KeyEvent e) {
        if (panel.getVisibility() == View.VISIBLE) {
            if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_DPAD_RIGHT) { closePanel(); return true; }
            return super.onKeyDown(code, e);
        }
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP: case KeyEvent.KEYCODE_CHANNEL_UP: case KeyEvent.KEYCODE_PAGE_UP:
                if (!channels.isEmpty()) play((current + 1) % channels.size());
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN: case KeyEvent.KEYCODE_CHANNEL_DOWN: case KeyEvent.KEYCODE_PAGE_DOWN:
                if (!channels.isEmpty()) play((current - 1 + channels.size()) % channels.size());
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (!scanning && channels.isEmpty() && tuner != null) { startScan(); return true; }
                openPanel();
                return true;
            case KeyEvent.KEYCODE_INFO: case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (current >= 0 && current < channels.size())
                    nowPlaying(channels.get(current), subFor(channels.get(current)), -1, null, player != null);
                return true;
        }
        return super.onKeyDown(code, e);
    }
}
