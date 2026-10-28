package fr.speedtest;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * Test du téléphone : ping, débit descendant et montant, 50 Mo, vrais GHz, RAM.
 * Tout est écrit en Java pur, sans bibliothèque externe.
 */
public class MainActivity extends Activity {

    // ------------------------------------------------------------------ serveurs
    /** Un serveur de test. « exact » = accepte ?bytes=N (Cloudflare). Sinon c'est un gros fichier fixe lu avec « Range ». */
    static class Server {
        final String name, down, up;
        final boolean exact;
        Server(String name, String down, String up, boolean exact) {
            this.name = name;
            this.down = down;
            this.up = up;
            this.exact = exact;
        }
    }

    /** Erreur HTTP : le code 429 veut dire « trop de requêtes », le serveur nous limite. */
    static class HttpFail extends IOException {
        final int code, retryAfter;
        HttpFail(int code, int retryAfter) {
            super("HTTP " + code + (code == 429 ? " (serveur de test saturé : trop de requêtes)" : ""));
            this.code = code;
            this.retryAfter = retryAfter;
        }
    }

    // Si un serveur refuse (429, panne…), l'appli passe automatiquement au suivant.
    static Server[] SERVERS = {
            new Server("Cloudflare", "https://speed.cloudflare.com/__down", "https://speed.cloudflare.com/__up", true),
            new Server("Hetzner Falkenstein", "https://fsn1-speed.hetzner.com/100MB.bin", null, false),
            new Server("Hetzner Nuremberg", "https://nbg1-speed.hetzner.com/100MB.bin", null, false),
            new Server("Hetzner Helsinki", "https://hel1-speed.hetzner.com/100MB.bin", null, false),
            new Server("OVH", "https://proof.ovh.net/files/100Mb.dat", null, false),
            new Server("Vultr Francfort", "https://fra-de-ping.vultr.com/vultr.com.100MB.bin", null, false),
            new Server("Leaseweb", "https://mirror.nl.leaseweb.net/speedtest/100mb.bin", null, false),
            new Server("Cachefly", "https://cachefly.cachefly.net/100mb.test", null, false),
            new Server("Tele2", "https://speedtest.tele2.net/100MB.zip", "https://speedtest.tele2.net/upload.php", false)
    };
    volatile Server cur = SERVERS[0];      // serveur utilisé pour le ping et le téléchargement
    volatile Server upCur = null;          // serveur utilisé pour l'envoi
    volatile String dlTried = "", upTried = "";
    volatile List<Server> alive = new ArrayList<>();   // serveurs qui ont répondu au départ, du plus rapide au plus lent
    volatile String probeInfo = "";   // serveurs refusés avant celui qui a marché, avec la raison

    // ------------------------------------------------------------------ couleurs
    static final int BG = 0xFF0E1116, CARD = 0xFF181D26, TXT = 0xFFE8ECF2, MUT = 0xFF8B95A7,
            ACC = 0xFF4DA3FF, GOOD = 0xFF3DDC97, WARN = 0xFFFFB454, BAD = 0xFFFF6B6B, PUR = 0xFFB48CFF;

    // ------------------------------------------------------------------ interface
    LinearLayout root;
    Gauge gauge;
    TextView status;
    Button go, copy, copyLog;
    Spinner spin;
    Card cPing, cDl, c50, cUl, cCpu, cRam, cVerdict, cPhone;
    TextView logView;

    // ------------------------------------------------------------------ état
    volatile boolean running = false;
    volatile boolean cancelled = false;
    volatile String lastReport = "";
    final StringBuilder logBuf = new StringBuilder();
    static volatile long sink;           // empêche le compilateur de supprimer les boucles de calcul

    // réglages selon le niveau de précision choisi
    int dur = 10, passes = 1, pingCount = 20;

    // compteurs réseau
    final AtomicLong dlBytes = new AtomicLong();
    final AtomicLong upBytes = new AtomicLong();
    final AtomicInteger okCount = new AtomicInteger();
    final AtomicInteger failCount = new AtomicInteger();
    volatile String lastErr = "";
    volatile boolean netStop = false;
    final List<HttpURLConnection> conns = new ArrayList<>();

    // =====================================================================================
    //  INTERFACE
    // =====================================================================================

    int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    TextView tv(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    class Card {
        LinearLayout box;
        TextView val, det, err;

        Card(String title) {
            box = new LinearLayout(MainActivity.this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(dp(16), dp(14), dp(16), dp(14));
            GradientDrawable g = new GradientDrawable();
            g.setColor(CARD);
            g.setCornerRadius(dp(16));
            box.setBackground(g);
            TextView t = tv(title.toUpperCase(Locale.FRANCE), 11, MUT, false);
            val = tv("–", 30, TXT, true);
            det = tv("", 12.5f, MUT, false);
            err = tv("", 12.5f, BAD, false);
            box.addView(t);
            box.addView(val);
            box.addView(det);
            box.addView(err);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(14);
            root.addView(box, lp);
        }

        void reset() {
            set(val, "–", TXT);
            set(det, "", MUT);
            set(err, "", BAD);
        }

        void value(String s, int color) { set(val, s, color); }
        void detail(String s) { set(det, s, MUT); }
        void error(String s) { set(err, s, BAD); }
    }

    void set(final TextView t, final String s, final int color) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                t.setText(s);
                t.setTextColor(color);
            }
        });
    }

    void say(final String s) {
        runOnUiThread(new Runnable() {
            @Override public void run() { status.setText(s); }
        });
    }

    void log(String msg) {
        final String line = new java.text.SimpleDateFormat("HH:mm:ss", Locale.FRANCE)
                .format(new java.util.Date()) + "  " + msg + "\n";
        synchronized (logBuf) {
            logBuf.append(line);
        }
        runOnUiThread(new Runnable() {
            @Override public void run() {
                synchronized (logBuf) {
                    logView.setText(logBuf.toString());
                }
            }
        });
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(28), dp(16), dp(40));
        sv.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(sv);

        root.addView(tv("📱 Test de mon téléphone", 22, TXT, true));
        root.addView(tv("Ping · Débit · 50 Mo · Vrais GHz · RAM · Verdict", 13, MUT, false));

        gauge = new Gauge(this);
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(230));
        gl.topMargin = dp(10);
        root.addView(gauge, gl);

        status = tv("Prêt", 14, MUT, false);
        status.setGravity(Gravity.CENTER);
        root.addView(status);

        spin = new Spinner(this);
        spin.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{
                        "Rapide (6 s, 1 passe)",
                        "Précis (10 s, 1 passe)",
                        "Maximum (15 s, 2 passes)",
                        "Ultra (20 s, 3 passes, beaucoup de data)"}));
        spin.setSelection(1);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sl.topMargin = dp(10);
        root.addView(spin, sl);

        go = new Button(this);
        go.setText("Lancer le test complet");
        go.setTransformationMethod(null);
        go.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        root.addView(go);

        copy = new Button(this);
        copy.setText("Copier les résultats");
        copy.setTransformationMethod(null);
        copy.setEnabled(false);
        root.addView(copy);

        cPing = new Card("Ping (au repos)");
        cDl = new Card("Débit descendant (téléchargement)");
        c50 = new Card("Temps pour télécharger 50 Mo");
        cUl = new Card("Débit montant (envoi)");
        cCpu = new Card("Fréquence du processeur (lue sur le téléphone)");
        cRam = new Card("Mémoire (RAM)");
        cVerdict = new Card("Ce que ta connexion permet");
        cPhone = new Card("Mon téléphone");
        cVerdict.val.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        cPhone.val.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        cVerdict.val.setText("Lance le test pour voir le verdict.");

        TextView lt = tv("DÉTAILS TECHNIQUES", 11, MUT, false);
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ll.topMargin = dp(18);
        root.addView(lt, ll);
        logView = tv("", 10.5f, MUT, false);
        logView.setTypeface(Typeface.MONOSPACE);
        root.addView(logView);
        copyLog = new Button(this);
        copyLog.setText("Copier les détails techniques");
        copyLog.setTransformationMethod(null);
        root.addView(copyLog);

        TextView how = tv(
                "Comment c'est mesuré :\n"
                        + "• GHz : lus directement dans le système Android (fichiers cpufreq), pendant que le processeur "
                        + "travaille à fond. Ce sont les vraies fréquences, pas une estimation. Si ton téléphone bloque cette "
                        + "lecture, l'appli le dit et utilise un calcul chronométré à la place.\n"
                        + "• Ping : requêtes sur une connexion déjà ouverte, pics isolés écartés, médiane et marge d'erreur.\n"
                        + "• Débit : 4 flux en parallèle, les 2 premières secondes ignorées, moyenne sur la partie stable.\n"
                        + "• Le test peut consommer beaucoup de data : préfère le Wi-Fi.",
                12, MUT, false);
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hl.topMargin = dp(18);
        root.addView(how, hl);

        go.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (running) {
                    cancelled = true;
                    netStop = true;
                    disconnectAll();
                    say("Annulation…");
                } else {
                    startTest();
                }
            }
        });
        copy.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { clip(lastReport); }
        });
        copyLog.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                synchronized (logBuf) { clip(logBuf.toString()); }
            }
        });

        showPhoneInfo();
    }

    void clip(String s) {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("test", s));
            say("Copié ✅");
        } catch (Exception e) {
            say("Copie impossible");
        }
    }

    // =====================================================================================
    //  JAUGE
    // =====================================================================================

    static class Gauge extends View {
        volatile float target = 0, max = 10;
        float val = 0;
        volatile int color = 0xFF4DA3FF;
        volatile String unit = "Mb/s", label = "Prêt";
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        final RectF r = new RectF();
        static final float[] STEPS = {10, 25, 50, 100, 250, 500, 1000, 2500, 5000, 10000};

        Gauge(Context c) { super(c); }

        void setValue(float v, String label, String unit, int color) {
            this.target = v;
            this.label = label;
            this.unit = unit;
            this.color = color;
            postInvalidate();
        }

        static float niceMax(float v) {
            for (float s : STEPS) if (s >= v * 1.1f) return s;
            return 10000;
        }

        @Override
        protected void onDraw(Canvas c) {
            super.onDraw(c);
            float dens = getResources().getDisplayMetrics().density;
            float w = getWidth(), h = getHeight();
            float nm = niceMax(Math.max(val, target));
            val += (target - val) * 0.2f;
            max += (nm - max) * 0.15f;
            float cx = w / 2f, cy = h * 0.56f;
            float rad = Math.min(w / 2f - 24 * dens, h * 0.46f);
            r.set(cx - rad, cy - rad, cx + rad, cy + rad);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeWidth(14 * dens);
            p.setColor(0xFF262D3A);
            c.drawArc(r, 135, 270, false, p);
            float f = Math.max(0.003f, Math.min(1f, val / max));
            p.setColor(color);
            c.drawArc(r, 135, 270 * f, false, p);

            p.setStyle(Paint.Style.FILL);
            p.setTextAlign(Paint.Align.CENTER);
            p.setColor(0xFFE8ECF2);
            p.setTypeface(Typeface.DEFAULT_BOLD);
            p.setTextSize(46 * dens);
            String big = val >= 100 ? String.format(Locale.FRANCE, "%.0f", val)
                    : String.format(Locale.FRANCE, "%.1f", val);
            c.drawText(big, cx, cy + 12 * dens, p);
            p.setTypeface(Typeface.DEFAULT);
            p.setColor(0xFF8B95A7);
            p.setTextSize(15 * dens);
            c.drawText(unit, cx, cy + 38 * dens, p);
            p.setTextSize(13 * dens);
            c.drawText(label, cx, cy - rad * 0.5f, p);
            p.setTextSize(11 * dens);
            p.setTextAlign(Paint.Align.LEFT);
            c.drawText("0", cx - rad * 0.75f, cy + rad * 0.95f, p);
            p.setTextAlign(Paint.Align.RIGHT);
            c.drawText(String.format(Locale.FRANCE, "%.0f", max), cx + rad * 0.75f, cy + rad * 0.95f, p);

            if (Math.abs(val - target) > 0.01f || Math.abs(max - nm) > 0.5f) postInvalidateOnAnimation();
        }
    }

    void gset(double v, String label, String unit, int color) {
        gauge.setValue((float) v, label, unit, color);
    }

    // =====================================================================================
    //  OUTILS STATISTIQUES
    // =====================================================================================

    static String fr(double v, int d) {
        return String.format(Locale.FRANCE, "%." + d + "f", v);
    }

    static double mean(List<Double> a) {
        if (a.isEmpty()) return 0;
        double s = 0;
        for (double v : a) s += v;
        return s / a.size();
    }

    static double sd(List<Double> a) {
        if (a.size() < 2) return 0;
        double m = mean(a), s = 0;
        for (double v : a) s += (v - m) * (v - m);
        return Math.sqrt(s / (a.size() - 1));
    }

    static double median(List<Double> a) {
        if (a.isEmpty()) return 0;
        List<Double> s = new ArrayList<>(a);
        Collections.sort(s);
        int k = s.size() / 2;
        return s.size() % 2 == 1 ? s.get(k) : (s.get(k - 1) + s.get(k)) / 2.0;
    }

    static double quant(List<Double> sorted, double f) {
        int i = (int) Math.floor(f * (sorted.size() - 1) + 0.5);
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, i)));
    }

    static String fmtTime(double s) {
        if (Double.isInfinite(s) || Double.isNaN(s)) return "∞";
        if (s < 60) return fr(s, 1) + " s";
        int m = (int) (s / 60), r = (int) Math.round(s % 60);
        if (m < 60) return m + " min " + r + " s";
        return (m / 60) + " h " + (m % 60) + " min";
    }

    // =====================================================================================
    //  RÉSEAU
    // =====================================================================================

    static String rnd() {
        return Long.toString(Math.abs(new Random().nextLong()), 36);
    }

    HttpURLConnection open(String url, String method) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(8000);
        c.setReadTimeout(15000);
        c.setUseCaches(false);
        c.setRequestProperty("Cache-Control", "no-store");
        c.setRequestProperty("Accept-Encoding", "identity");   // pas de compression : on compte les vrais octets
        return c;
    }

    void disconnectAll() {
        List<HttpURLConnection> copyList;
        synchronized (conns) {
            copyList = new ArrayList<>(conns);
        }
        for (HttpURLConnection c : copyList) {
            try { c.disconnect(); } catch (Exception ignored) { }
        }
    }

    void chk() {
        if (cancelled) throw new RuntimeException("annulé");
    }

    static void sleepQuiet(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }

    HttpFail failOf(HttpURLConnection c, int code) {
        int ra = 0;
        try { ra = Integer.parseInt(c.getHeaderField("Retry-After").trim()); } catch (Exception ignored) { }
        return new HttpFail(code, ra);
    }

    void checkDown(HttpURLConnection c) throws IOException {
        int code = c.getResponseCode();
        if (code != 200 && code != 206) throw failOf(c, code);
    }

    HttpURLConnection openDown(Server s, long start, long len) throws IOException {
        if (s.exact) return open(s.down + "?bytes=" + len + "&r=" + rnd(), "GET");
        HttpURLConnection c = open(s.down, "GET");
        c.setRequestProperty("Range", "bytes=" + start + "-" + (start + len - 1));
        return c;
    }

    double pingOnce(Server s) throws IOException {
        long t0 = System.nanoTime();
        HttpURLConnection c = openDown(s, 0, s.exact ? 0 : 1);
        checkDown(c);
        InputStream in = c.getInputStream();
        byte[] buf = new byte[1024];
        int total = 0, n;
        while ((n = in.read(buf)) >= 0) {
            total += n;
            if (total > 4096) break;               // serveur qui ignore « Range » : on n'avale pas le fichier
        }
        in.close();
        if (total > 4096) c.disconnect();
        return (System.nanoTime() - t0) / 1e6;
    }

    /** Teste tous les serveurs en même temps et garde ceux qui répondent, du plus rapide au plus lent. */
    Server pickServer() throws IOException {
        final int n = SERVERS.length;
        final double[] lat = new double[n];
        final String[] err = new String[n];
        Arrays.fill(lat, -1);
        List<Thread> ts = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            final int k = i;
            Thread th = new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        pingOnce(SERVERS[k]);                       // 1re requête : DNS + TLS
                        double best = Double.MAX_VALUE;
                        for (int j = 0; j < 2; j++) best = Math.min(best, pingOnce(SERVERS[k]));
                        lat[k] = best;
                    } catch (Exception e) {
                        err[k] = String.valueOf(e.getMessage());
                    }
                }
            });
            th.setDaemon(true);
            th.start();
            ts.add(th);
        }
        long deadline = System.currentTimeMillis() + 12000;
        for (Thread th : ts) {
            long left = deadline - System.currentTimeMillis();
            if (left > 0) {
                try { th.join(left); } catch (InterruptedException ignored) { }
            }
        }
        chk();
        List<Integer> okIdx = new ArrayList<>();
        StringBuilder bad = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (lat[i] >= 0) {
                okIdx.add(i);
            } else {
                if (bad.length() > 0) bad.append(" ; ");
                bad.append(SERVERS[i].name).append(" (").append(err[i] != null ? err[i] : "délai dépassé").append(")");
            }
        }
        Collections.sort(okIdx, new java.util.Comparator<Integer>() {
            @Override public int compare(Integer x, Integer y) { return Double.compare(lat[x], lat[y]); }
        });
        List<Server> ok = new ArrayList<>();
        StringBuilder good = new StringBuilder();
        for (int i : okIdx) {
            ok.add(SERVERS[i]);
            if (good.length() > 0) good.append(", ");
            good.append(SERVERS[i].name).append(' ').append(fr(lat[i], 0)).append(" ms");
        }
        probeInfo = "Serveurs joignables : " + (good.length() > 0 ? good : "aucun")
                + (bad.length() > 0 ? "\nInjoignables : " + bad : "");
        log(probeInfo.replace("\n", " | "));
        if (ok.isEmpty()) throw new IOException("aucun serveur de test accessible (" + bad + ")");
        alive = ok;
        cur = ok.get(0);
        return cur;
    }

    /** Ordre d'essai des serveurs : d'abord celui demandé, puis ceux qui ont répondu, et en dernier recours les autres. */
    List<Server> candidates(Server first, boolean needUp) {
        List<Server> order = new ArrayList<>();
        if (first != null && (!needUp || first.up != null)) order.add(first);
        List<Server> pool = alive.isEmpty() ? Arrays.asList(SERVERS) : alive;
        for (Server s : pool) {
            if (s == first) continue;
            if (needUp && s.up == null) continue;
            order.add(s);
        }
        if (needUp || order.isEmpty()) {
            for (Server s : SERVERS) {
                if (order.contains(s)) continue;
                if (needUp && s.up == null) continue;
                order.add(s);
            }
        }
        return order;
    }

    static class PingRes {
        double best, med, mean, std, margin, p95, worst, jitter;
        int lost, n, outliers;
    }

    PingRes measurePing(int count) throws Exception {
        final Server srv = pickServer();
        List<Double> raw = new ArrayList<>();
        int lost = 0;
        for (int i = 0; i < count; i++) {
            chk();
            try {
                double v = pingOnce(srv);
                raw.add(v);
                gset(v, "Ping…", "ms", GOOD);
            } catch (IOException e) {
                lost++;
                lastErr = String.valueOf(e.getMessage());
            }
            say("Mesure du ping… " + (i + 1) + "/" + count);
            sleepQuiet(80);
        }
        if (raw.size() < 8) throw new IOException("trop de requêtes perdues (" + lastErr + ")");
        raw.remove(0);                                   // la 1re inclut DNS + TLS
        double jit = 0;
        for (int i = 1; i < raw.size(); i++) jit += Math.abs(raw.get(i) - raw.get(i - 1));
        jit /= Math.max(1, raw.size() - 1);
        List<Double> s = new ArrayList<>(raw);
        Collections.sort(s);
        double q1 = quant(s, 0.25), q3 = quant(s, 0.75);
        List<Double> kept = new ArrayList<>();
        for (double v : s) if (v <= q3 + 1.5 * (q3 - q1)) kept.add(v);   // on écarte les pics isolés
        PingRes r = new PingRes();
        r.best = s.get(0);
        r.worst = s.get(s.size() - 1);
        r.med = quant(s, 0.5);
        r.mean = mean(kept);
        r.std = sd(kept);
        r.margin = 1.96 * r.std / Math.sqrt(kept.size());
        r.p95 = quant(s, 0.95);
        r.jitter = jit;
        r.lost = lost;
        r.n = raw.size();
        r.outliers = s.size() - kept.size();
        gset(r.med, "Ping", "ms", GOOD);
        log("Ping : médiane " + fr(r.med, 2) + " ms, moyenne " + fr(r.mean, 2) + " ± " + fr(r.margin, 2)
                + " ms, " + kept.size() + "/" + raw.size() + " mesures gardées, perdues " + lost);
        return r;
    }

    /** Mesure le ping pendant un transfert pour détecter si la connexion sature. */
    class LoadedPinger extends Thread {
        volatile boolean stop;
        final Server srv;
        final List<Double> vals = Collections.synchronizedList(new ArrayList<Double>());

        LoadedPinger(Server srv) { this.srv = srv; }

        @Override public void run() {
            sleepQuiet(1500);
            while (!stop) {
                try { vals.add(pingOnce(srv)); } catch (Exception ignored) { }
                sleepQuiet(700);                       // peu de requêtes : on évite de se faire limiter
            }
        }

        double finish() {
            stop = true;
            List<Double> c;
            synchronized (vals) { c = new ArrayList<>(vals); }
            return c.isEmpty() ? -1 : median(c);
        }
    }

    // ------------------------------------------------------------------ débit
    static class Rate {
        double avg, med, sd, peak, low, se, margin, rel, loaded = -1;
        List<Double> runs = new ArrayList<>();
    }

    static Rate rateOf(List<double[]> pts, double warm) {
        Rate r = new Rate();
        double[] last = pts.get(pts.size() - 1);
        int i0 = -1;
        for (int i = 0; i < pts.size(); i++) {
            if (pts.get(i)[0] >= warm) { i0 = i; break; }
        }
        if (i0 < 0 || last[0] - pts.get(i0)[0] < 1) i0 = pts.size() / 3;
        double[] a = pts.get(i0);
        double dt = Math.max(0.001, last[0] - a[0]);
        r.avg = (last[1] - a[1]) * 8 / 1e6 / dt;
        List<Double> wins = new ArrayList<>();
        for (int i = i0; i < pts.size(); i++) {
            int j = -1;
            for (int k = i + 1; k < pts.size(); k++) {
                if (pts.get(k)[0] >= pts.get(i)[0] + 1) { j = k; break; }
            }
            if (j < 0) break;
            wins.add((pts.get(j)[1] - pts.get(i)[1]) * 8 / 1e6 / (pts.get(j)[0] - pts.get(i)[0]));
        }
        if (wins.isEmpty()) {
            r.peak = r.avg; r.low = r.avg; r.med = r.avg;
        } else {
            List<Double> s = new ArrayList<>(wins);
            Collections.sort(s);
            r.peak = s.get(s.size() - 1);
            r.low = s.get(0);
            r.med = median(s);
            r.sd = sd(wins);
        }
        r.se = r.sd / Math.sqrt(Math.max(1, dt));
        return r;
    }

    Rate meter(AtomicLong counter, double seconds, double warm, String label, int color, BooleanSupplier giveUp) {
        long t0 = System.nanoTime();
        List<double[]> pts = new ArrayList<>();
        pts.add(new double[]{0, 0});
        double lastT = 0;
        long lastB = 0;
        while (true) {
            sleepQuiet(250);
            double t = (System.nanoTime() - t0) / 1e9;
            long b = counter.get();
            double inst = (b - lastB) * 8 / 1e6 / Math.max(0.001, t - lastT);
            pts.add(new double[]{t, b});
            lastT = t;
            lastB = b;
            gset(inst, label, "Mb/s", ACC);
            if (t >= seconds || b > 450_000_000L || cancelled || giveUp.getAsBoolean()) break;
        }
        return rateOf(pts, warm);
    }

    static Rate combine(List<Rate> rs) {
        Rate r = new Rate();
        List<Double> avgs = new ArrayList<>();
        List<Double> lps = new ArrayList<>();
        r.peak = 0;
        r.low = Double.MAX_VALUE;
        for (Rate x : rs) {
            avgs.add(x.avg);
            r.peak = Math.max(r.peak, x.peak);
            r.low = Math.min(r.low, x.low);
            if (x.loaded >= 0) lps.add(x.loaded);
        }
        r.avg = median(avgs);
        r.med = median(avgs);
        r.runs = avgs;
        r.margin = rs.size() >= 2 ? sd(avgs) / Math.sqrt(rs.size()) : rs.get(0).se;
        r.rel = r.avg > 0 ? r.margin / r.avg : 0;
        r.loaded = lps.isEmpty() ? -1 : median(lps);
        return r;
    }

    static String reliability(double rel) {
        return rel < 0.03 ? "très fiable" : rel < 0.08 ? "fiable" : "variable : connexion instable";
    }

    void joinAll(List<Thread> ts) {
        for (Thread t : ts) {
            try { t.join(1500); } catch (InterruptedException ignored) { }
        }
    }

    Rate runDownload(final Server srv) throws Exception {
        return runDownload(srv, 4);
    }

    Rate runDownload(final Server srv, final int streams) throws Exception {
        final long chunk = srv.exact ? 50_000_000L : 90_000_000L;   // peu de gros blocs = peu de requêtes
        netStop = false;
        dlBytes.set(0);
        okCount.set(0);
        failCount.set(0);
        lastErr = "";
        List<Thread> ts = new ArrayList<>();
        for (int i = 0; i < streams; i++) {
            Thread t = new Thread(new Runnable() {
                @Override public void run() {
                    byte[] buf = new byte[65536];
                    while (!netStop) {
                        HttpURLConnection c = null;
                        try {
                            c = openDown(srv, 0, chunk);
                            synchronized (conns) { conns.add(c); }
                            checkDown(c);
                            InputStream in = c.getInputStream();
                            int n;
                            while (!netStop && (n = in.read(buf)) >= 0) dlBytes.addAndGet(n);
                            in.close();
                            okCount.incrementAndGet();
                        } catch (Exception e) {
                            if (!netStop) {
                                failCount.incrementAndGet();
                                lastErr = String.valueOf(e.getMessage());
                                sleepQuiet(backoff(e));
                            }
                        } finally {
                            if (c != null) {
                                synchronized (conns) { conns.remove(c); }
                                c.disconnect();
                            }
                        }
                    }
                }
            });
            t.setDaemon(true);
            t.start();
            ts.add(t);
        }
        LoadedPinger lp = new LoadedPinger(srv);
        lp.setDaemon(true);
        lp.start();
        Rate r = meter(dlBytes, dur, Math.min(2, dur * 0.3), "Téléchargement", ACC, new BooleanSupplier() {
            @Override public boolean getAsBoolean() { return dlBytes.get() == 0 && failCount.get() >= 3; }
        });
        r.loaded = lp.finish();
        netStop = true;
        disconnectAll();
        joinAll(ts);
        log("Descendant : " + okCount.get() + " blocs reçus, " + failCount.get() + " échecs, "
                + Math.round(dlBytes.get() / 1e6) + " Mo");
        chk();
        if (dlBytes.get() < 1000) throw new IOException("aucune donnée reçue (" + lastErr + ")");
        return r;
    }

    Rate runUpload(final Server srv) throws Exception {
        final int len = 25 * 1024 * 1024;
        netStop = false;
        upBytes.set(0);
        okCount.set(0);
        failCount.set(0);
        lastErr = "";
        List<Thread> ts = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Thread t = new Thread(new Runnable() {
                @Override public void run() {
                    byte[] data = new byte[65536];
                    new Random().nextBytes(data);
                    while (!netStop) {
                        HttpURLConnection c = null;
                        try {
                            c = open(srv.up + (srv.up.contains("?") ? "&" : "?") + "r=" + rnd(), "POST");
                            synchronized (conns) { conns.add(c); }
                            c.setDoOutput(true);
                            c.setFixedLengthStreamingMode(len);
                            OutputStream out = c.getOutputStream();
                            int sent = 0;
                            while (!netStop && sent < len) {
                                int n = Math.min(data.length, len - sent);
                                out.write(data, 0, n);
                                sent += n;
                                upBytes.addAndGet(n);
                            }
                            if (netStop) break;
                            out.close();
                            int code = c.getResponseCode();
                            if (code < 200 || code >= 300) throw failOf(c, code);
                            okCount.incrementAndGet();
                        } catch (Exception e) {
                            if (!netStop) {
                                failCount.incrementAndGet();
                                lastErr = String.valueOf(e.getMessage());
                                sleepQuiet(backoff(e));
                            }
                        } finally {
                            if (c != null) {
                                synchronized (conns) { conns.remove(c); }
                                c.disconnect();
                            }
                        }
                    }
                }
            });
            t.setDaemon(true);
            t.start();
            ts.add(t);
        }
        LoadedPinger lp = new LoadedPinger(cur);
        lp.setDaemon(true);
        lp.start();
        Rate r = meter(upBytes, dur, Math.min(2, dur * 0.3), "Envoi", WARN, new BooleanSupplier() {
            @Override public boolean getAsBoolean() { return okCount.get() == 0 && failCount.get() >= 3; }
        });
        r.loaded = lp.finish();
        netStop = true;
        disconnectAll();
        joinAll(ts);
        log("Montant : " + okCount.get() + " blocs envoyés, " + failCount.get() + " échecs, "
                + Math.round(upBytes.get() / 1e6) + " Mo");
        chk();
        // les octets sont comptés quand ils partent ; si le serveur a refusé tous les envois, ce n'est pas une mesure valable
        if (upBytes.get() < 1000 || (okCount.get() == 0 && failCount.get() > 0))
            throw new IOException("aucune donnée envoyée (" + lastErr + ")");
        return r;
    }

    /** Pause avant de réessayer : on respecte « Retry-After » quand le serveur dit qu'on va trop vite (429). */
    static long backoff(Exception e) {
        if (e instanceof HttpFail && ((HttpFail) e).code == 429) {
            long ms = ((HttpFail) e).retryAfter * 1000L;
            return Math.max(1500, Math.min(ms, 5000));
        }
        return 300;
    }

    /** Une passe de mesure. Si le serveur refuse ou ne répond pas, on essaie automatiquement le suivant. */
    Rate onePass(boolean download) throws Exception {
        Server first = download ? cur : upCur;
        List<Server> order = candidates(first, !download);
        StringBuilder errs = new StringBuilder();
        for (Server s : order) {
            // certains serveurs limitent les connexions simultanées : si 4 flux sont refusés, on réessaie avec 1 seul
            int[] tries = download ? new int[]{4, 1} : new int[]{4};
            for (int k = 0; k < tries.length; k++) {
                chk();
                try {
                    Rate r = download ? runDownload(s, tries[k]) : runUpload(s);
                    if (download) { cur = s; dlTried = errs.toString(); } else { upCur = s; upTried = errs.toString(); }
                    return r;
                } catch (Exception e) {
                    if (cancelled) throw e;
                    String msg = String.valueOf(e.getMessage());
                    log((download ? "Téléchargement" : "Envoi") + " via " + s.name + " (" + tries[k] + " flux) impossible : " + msg);
                    if (k == tries.length - 1 || msg.contains("HTTP 429")) {
                        say("Serveur " + s.name + " indisponible, essai du suivant…");
                        if (errs.length() > 0) errs.append(" ; ");
                        errs.append(s.name).append(" : ").append(msg);
                        sleepQuiet(600);
                        break;
                    }
                    say("Serveur " + s.name + " : nouvel essai avec 1 seul flux…");
                    sleepQuiet(500);
                }
            }
        }
        throw new IOException(errs.length() > 0 ? errs.toString() : "aucun serveur disponible");
    }

    Rate multiPass(boolean download) throws Exception {
        List<Rate> rs = new ArrayList<>();
        for (int i = 0; i < passes; i++) {
            chk();
            if (passes > 1) say((download ? "Descendant" : "Montant") + " : passe " + (i + 1) + "/" + passes);
            rs.add(onePass(download));
            if (i < passes - 1) sleepQuiet(1200);
        }
        return combine(rs);
    }

    /** Vrai téléchargement de 50 Mo avec bascule automatique de serveur. */
    double[] real50Any() throws Exception {
        List<Server> order = candidates(cur, false);
        StringBuilder errs = new StringBuilder();
        for (Server s : order) {
            chk();
            try {
                double[] r = real50(s);
                cur = s;
                return r;
            } catch (Exception e) {
                if (cancelled) throw e;
                log("Test 50 Mo via " + s.name + " impossible : " + e.getMessage());
                say("Serveur " + s.name + " indisponible, essai du suivant…");
                if (errs.length() > 0) errs.append(" ; ");
                errs.append(s.name).append(" : ").append(e.getMessage());
                sleepQuiet(600);
            }
        }
        throw new IOException(errs.length() > 0 ? errs.toString() : "aucun serveur disponible");
    }

    /**
     * Vrai téléchargement de 50 Mo. On utilise exactement la même méthode que le test de débit
     * (4 flux), et on chronomètre jusqu'à l'instant précis où le 50e mégaoctet est arrivé.
     */
    double[] real50(final Server srv) throws Exception {
        final long total = 50_000_000L;
        final long chunk = srv.exact ? total : 90_000_000L;
        final AtomicLong got = new AtomicLong();
        final AtomicLong doneAt = new AtomicLong(0);
        final AtomicReference<String> error = new AtomicReference<>(null);
        netStop = false;
        final long t0 = System.nanoTime();
        List<Thread> ts = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Thread t = new Thread(new Runnable() {
                @Override public void run() {
                    byte[] buf = new byte[65536];
                    HttpURLConnection c = null;
                    try {
                        c = openDown(srv, 0, chunk);
                        synchronized (conns) { conns.add(c); }
                        checkDown(c);
                        InputStream in = c.getInputStream();
                        int n;
                        while (!netStop && (n = in.read(buf)) >= 0) {
                            long g = got.addAndGet(n);
                            if (g >= total) {
                                doneAt.compareAndSet(0, System.nanoTime());
                                netStop = true;
                                break;
                            }
                        }
                        in.close();
                    } catch (Exception e) {
                        if (!netStop) error.compareAndSet(null, String.valueOf(e.getMessage()));
                    } finally {
                        if (c != null) {
                            synchronized (conns) { conns.remove(c); }
                            c.disconnect();
                        }
                    }
                }
            });
            t.setDaemon(true);
            t.start();
            ts.add(t);
        }
        while (true) {
            boolean alive = false;
            for (Thread t : ts) if (t.isAlive()) alive = true;
            double el = (System.nanoTime() - t0) / 1e9;
            gset(Math.min(got.get(), total) * 8 / 1e6 / Math.max(el, 0.05),
                    "50 Mo : " + fr(Math.min(got.get(), total) / 1e6, 1) + " / 50", "Mb/s", ACC);
            if (doneAt.get() != 0 || !alive || cancelled) break;
            if (el > 120) break;
            sleepQuiet(30);
        }
        netStop = true;
        disconnectAll();
        joinAll(ts);
        chk();
        if (doneAt.get() == 0) {
            throw new IOException(error.get() != null ? error.get()
                    : "téléchargement incomplet (" + got.get() + " octets reçus sur " + total + ")");
        }
        return new double[]{(doneAt.get() - t0) / 1e9, total};
    }

    // =====================================================================================
    //  PROCESSEUR : VRAIES FRÉQUENCES
    // =====================================================================================

    static long readLong(String path) {
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String s = br.readLine();
            return s == null ? -1 : Long.parseLong(s.trim());
        } catch (Exception e) {
            return -1;
        }
    }

    static String readText(String path) {
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String s = br.readLine();
            return s == null ? "" : s.trim();
        } catch (Exception e) {
            return "";
        }
    }

    static String cpuPath(int core, String file) {
        return "/sys/devices/system/cpu/cpu" + core + "/cpufreq/" + file;
    }

    /** Lit en boucle la fréquence actuelle de chaque cœur et garde le maximum vu. */
    static class FreqSampler extends Thread {
        final int cores;
        volatile boolean stop;
        volatile boolean readable;
        final long[] max;
        final List<Double> top = Collections.synchronizedList(new ArrayList<Double>());

        FreqSampler(int cores) {
            this.cores = cores;
            this.max = new long[cores];
            setDaemon(true);
        }

        @Override public void run() {
            while (!stop) {
                long best = 0;
                for (int i = 0; i < cores; i++) {
                    long f = readLong(cpuPath(i, "scaling_cur_freq"));
                    if (f > 0) {
                        readable = true;
                        if (f > max[i]) max[i] = f;
                        if (f > best) best = f;
                    }
                }
                if (best > 0) top.add(best / 1e6);
                sleepQuiet(100);
            }
        }
    }

    static long chain8(long n) {
        long x = 1, y = 2;
        for (long i = 0; i < n; i++) {
            x += y; y ^= x; x += y; y ^= x; x += y; y ^= x; x += y; y ^= x;
        }
        return x ^ y;
    }

    static long chain16(long n) {
        long x = 1, y = 2;
        for (long i = 0; i < n; i++) {
            x += y; y ^= x; x += y; y ^= x; x += y; y ^= x; x += y; y ^= x;
            x += y; y ^= x; x += y; y ^= x; x += y; y ^= x; x += y; y ^= x;
        }
        return x ^ y;
    }

    /** Estimation par calcul chronométré : sert de contrôle si les fichiers cpufreq sont illisibles. */
    double chainGhz() {
        long n = 2_000_000L;
        while (true) {                                   // chaque mesure doit durer ≥ 150 ms
            long t0 = System.nanoTime();
            sink ^= chain16(n);
            long t = System.nanoTime() - t0;
            if (t >= 150_000_000L || n >= 2_000_000_000L) break;
            n *= 2;
        }
        double min8 = 1e18, min16 = 1e18;
        for (int r = 0; r < 10; r++) {
            chk();
            long t0 = System.nanoTime();
            sink ^= chain8(n);
            double a = (System.nanoTime() - t0) / 1e6;
            t0 = System.nanoTime();
            sink ^= chain16(n);
            double b = (System.nanoTime() - t0) / 1e6;
            if (a < min8) min8 = a;
            if (b < min16) min16 = b;
        }
        double direct = n * 16.0 / (min16 / 1000.0) / 1e9;
        double g = direct;
        if (min16 > min8) {
            double slope = n * 8.0 / ((min16 - min8) / 1000.0) / 1e9;
            double ratio = slope / direct;
            if (ratio > 0.85 && ratio < 1.2) g = slope;
        }
        return g;
    }

    Thread busy(final AtomicBoolean stop) {
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                long x = 1, y = 2;
                while (!stop.get()) {
                    for (int i = 0; i < 2_000_000; i++) { x += y; y ^= x; }
                }
                sink ^= x ^ y;
            }
        });
        t.setDaemon(true);
        t.start();
        return t;
    }

    static class CpuRes {
        int cores;
        boolean readable;
        long[] maxSeen;
        long[] officialMax;
        long[] officialMin;
        String[] governor;
        double peakGhz;          // fréquence maximale réellement vue
        double calcGhz;          // estimation par calcul
        double drop;             // ralentissement pendant la charge (0 à 1)
    }

    CpuRes cpuTest() throws Exception {
        CpuRes res = new CpuRes();
        int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
        res.cores = cores;

        // phase 1 : un seul cœur sollicité (le plus rapide monte à son maximum)
        say("Processeur : 1 cœur…");
        gset(0, "Processeur", "GHz", PUR);
        FreqSampler sa = new FreqSampler(cores);
        sa.start();
        res.calcGhz = chainGhz();
        sa.stop = true;
        chk();

        // phase 2 : tous les cœurs sollicités ensemble
        say("Processeur : " + cores + " cœurs ensemble…");
        AtomicBoolean stopB = new AtomicBoolean(false);
        FreqSampler sb = new FreqSampler(cores);
        sb.start();
        List<Thread> bs = new ArrayList<>();
        for (int i = 0; i < cores; i++) bs.add(busy(stopB));
        for (int k = 0; k < 40; k++) {
            sleepQuiet(100);
            List<Double> snap;
            synchronized (sb.top) { snap = new ArrayList<>(sb.top); }
            if (!snap.isEmpty()) gset(snap.get(snap.size() - 1), "Processeur (tous cœurs)", "GHz", PUR);
            chk();
        }
        stopB.set(true);
        sb.stop = true;
        joinAll(bs);

        res.readable = sa.readable || sb.readable;
        res.maxSeen = new long[cores];
        res.officialMax = new long[cores];
        res.officialMin = new long[cores];
        res.governor = new String[cores];
        for (int i = 0; i < cores; i++) {
            res.maxSeen[i] = Math.max(sa.max[i], sb.max[i]);
            res.officialMax[i] = readLong(cpuPath(i, "cpuinfo_max_freq"));
            res.officialMin[i] = readLong(cpuPath(i, "cpuinfo_min_freq"));
            res.governor[i] = readText(cpuPath(i, "scaling_governor"));
            res.peakGhz = Math.max(res.peakGhz, res.maxSeen[i] / 1e6);
        }
        List<Double> series;
        synchronized (sb.top) { series = new ArrayList<>(sb.top); }
        if (series.size() >= 10) {
            double peak = 0;
            for (double v : series) peak = Math.max(peak, v);
            double tail = 0;
            for (int i = series.size() - 5; i < series.size(); i++) tail += series.get(i);
            tail /= 5;
            res.drop = peak > 0 ? Math.max(0, (peak - tail) / peak) : 0;
        }
        log("CPU : lecture cpufreq " + (res.readable ? "OK" : "impossible") + ", pic " + fr(res.peakGhz, 2)
                + " GHz, calcul " + fr(res.calcGhz, 2) + " GHz");
        return res;
    }

    // =====================================================================================
    //  MÉMOIRE
    // =====================================================================================

    static class RamRes {
        double copy, write, lat;
    }

    static RamRes ramBench(int mb, int reps) {
        int n = mb * 1024 * 1024;
        byte[] a = new byte[n], b = new byte[n];
        for (int i = 0; i < n; i += 4096) a[i] = (byte) i;
        System.arraycopy(a, 0, b, 0, n);
        double bc = 1e18, bw = 1e18;
        for (int r = 0; r < reps; r++) {
            long t0 = System.nanoTime();
            System.arraycopy(a, 0, b, 0, n);
            double t = (System.nanoTime() - t0) / 1e9;
            if (t < bc) bc = t;
        }
        for (int r = 0; r < reps; r++) {
            long t0 = System.nanoTime();
            Arrays.fill(b, (byte) 1);
            double t = (System.nanoTime() - t0) / 1e9;
            if (t < bw) bw = t;
        }
        RamRes res = new RamRes();
        res.copy = (double) n * 2 / Math.max(bc, 1e-6) / 1e9;     // lecture + écriture
        res.write = (double) n / Math.max(bw, 1e-6) / 1e9;

        // latence : on suit un chemin aléatoire qui visite toute la mémoire (algorithme de Sattolo)
        int m = 1 << 22;
        int[] p = new int[m];
        for (int i = 0; i < m; i++) p[i] = i;
        Random rnd = new Random(42);
        for (int i = m - 1; i > 0; i--) {
            int j = rnd.nextInt(i);
            int tmp = p[i]; p[i] = p[j]; p[j] = tmp;
        }
        int idx = 0, steps = 3_000_000;
        for (int i = 0; i < 100_000; i++) idx = p[idx];
        double best = 1e18;
        for (int q = 0; q < 5; q++) {
            long t0 = System.nanoTime();
            for (int i = 0; i < steps; i++) idx = p[idx];
            double t = (System.nanoTime() - t0) / (double) steps;
            if (t < best) best = t;
        }
        sink ^= idx;
        res.lat = best;
        return res;
    }

    // =====================================================================================
    //  INFOS TÉLÉPHONE
    // =====================================================================================

    String batteryInfo() {
        try {
            Intent b = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (b == null) return "?";
            int level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            int temp = b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1);
            String s = Math.round(level * 100f / Math.max(1, scale)) + " %";
            if (temp > 0) s += " · " + fr(temp / 10.0, 1) + " °C";
            return s;
        } catch (Exception e) {
            return "?";
        }
    }

    String networkInfo() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            Network n = cm.getActiveNetwork();
            if (n == null) return "Aucune connexion";
            NetworkCapabilities nc = cm.getNetworkCapabilities(n);
            if (nc == null) return "Inconnue";
            String type = nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? "Wi-Fi"
                    : nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ? "Données mobiles"
                    : nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ? "Ethernet" : "Autre";
            return type + " · débit annoncé par Android : ↓ "
                    + fr(nc.getLinkDownstreamBandwidthKbps() / 1000.0, 0) + " Mb/s, ↑ "
                    + fr(nc.getLinkUpstreamBandwidthKbps() / 1000.0, 0) + " Mb/s";
        } catch (Exception e) {
            return "Inconnue";
        }
    }

    void showPhoneInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("Modèle : ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(" · Android ").append(Build.VERSION.RELEASE).append('\n');
        String soc = Build.HARDWARE;
        if (Build.VERSION.SDK_INT >= 31) soc = Build.SOC_MANUFACTURER + " " + Build.SOC_MODEL + " (" + Build.HARDWARE + ")";
        sb.append("Puce : ").append(soc).append('\n');
        sb.append("Cœurs : ").append(Runtime.getRuntime().availableProcessors()).append('\n');
        try {
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            sb.append("RAM : ").append(fr(mi.totalMem / 1073741824.0, 1)).append(" Go au total, ")
                    .append(fr(mi.availMem / 1073741824.0, 1)).append(" Go libres\n");
        } catch (Exception ignored) { }
        try {
            Display d = getWindowManager().getDefaultDisplay();
            sb.append("Écran : ").append(fr(d.getRefreshRate(), 0)).append(" Hz\n");
        } catch (Exception ignored) { }
        sb.append("Batterie : ").append(batteryInfo()).append('\n');
        sb.append("Connexion : ").append(networkInfo());
        cPhone.val.setText(sb.toString());
        cPhone.val.setTextColor(TXT);
    }

    // =====================================================================================
    //  VERDICT
    // =====================================================================================

    String verdict(double ping, double jitter, double dl, double ul, double loadedPing) {
        StringBuilder sb = new StringBuilder();
        if (ping >= 0) {
            if (ping <= 40 && jitter <= 10) sb.append("✅ Jeux en ligne : excellent\n");
            else if (ping <= 80 && jitter <= 25) sb.append("⚠️ Jeux en ligne : correct\n");
            else sb.append("❌ Jeux en ligne : difficile\n");
        }
        if (dl >= 0) {
            sb.append(dl >= 8 ? "✅" : dl >= 4 ? "⚠️" : "❌").append(" Vidéo HD (≈ 8 Mb/s) : ")
                    .append(dl >= 8 ? "fluide" : dl >= 4 ? "limite" : "saccadé").append('\n');
            sb.append(dl >= 25 ? "✅" : dl >= 15 ? "⚠️" : "❌").append(" Vidéo 4K (≈ 25 Mb/s) : ")
                    .append(dl >= 25 ? "fluide" : dl >= 15 ? "limite" : "non").append('\n');
        }
        if (dl >= 0 && ul >= 0 && ping >= 0) {
            boolean ok = dl >= 5 && ul >= 3 && ping <= 100;
            boolean mid = dl >= 2 && ul >= 1;
            sb.append(ok ? "✅" : mid ? "⚠️" : "❌").append(" Visio (Zoom, Teams, Meet) : ")
                    .append(ok ? "bon" : mid ? "moyen" : "mauvais").append('\n');
        }
        if (loadedPing >= 0 && ping >= 0) {
            double inc = loadedPing - ping;
            sb.append(inc < 30 ? "✅" : inc < 100 ? "⚠️" : "❌").append(" Stabilité sous charge : ")
                    .append(inc < 30 ? "très bonne" : inc < 100 ? "moyenne" : "mauvaise")
                    .append(" (+").append(fr(Math.max(0, inc), 0)).append(" ms)\n");
        }
        String s = sb.toString().trim();
        return s.isEmpty() ? "Pas assez de données réseau." : s;
    }

    // =====================================================================================
    //  LANCEMENT
    // =====================================================================================

    void startTest() {
        int sel = spin.getSelectedItemPosition();
        dur = sel == 0 ? 6 : sel == 1 ? 10 : sel == 2 ? 15 : 20;
        passes = sel == 2 ? 2 : sel == 3 ? 3 : 1;
        pingCount = 20 * passes;
        running = true;
        cancelled = false;
        go.setText("Annuler");
        copy.setEnabled(false);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        Card[] all = {cPing, cDl, c50, cUl, cCpu, cRam};
        for (Card c : all) c.reset();
        cVerdict.val.setText("…");
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    runAll();
                } catch (Throwable e) {
                    log("ERREUR GÉNÉRALE : " + e);
                    say("Erreur : " + e.getMessage());
                }
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        running = false;
                        go.setText("Relancer le test complet");
                        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                        showPhoneInfo();
                    }
                });
            }
        });
        t.setDaemon(true);
        t.start();
    }

    void runAll() {
        List<String> out = new ArrayList<>();
        out.add("Test téléphone — " + new java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRANCE)
                .format(new java.util.Date()));
        out.add("Modèle : " + Build.MANUFACTURER + " " + Build.MODEL);
        String batStart = batteryInfo();
        double pingMed = -1, jitter = 0, dlAvg = -1, ulAvg = -1, loaded = -1;
        cur = SERVERS[0];
        upCur = null;
        dlTried = "";
        upTried = "";
        log("=== nouveau test (" + dur + " s, " + passes + " passe(s)) ===");

        // ---- 1) ping
        try {
            PingRes p = measurePing(pingCount);
            pingMed = p.med;
            jitter = p.jitter;
            cPing.value(fr(p.med, 1) + " ms", GOOD);
            cPing.detail("Meilleur : " + fr(p.best, 1) + " ms · Pire : " + fr(p.worst, 1) + " ms · Gigue : "
                    + fr(p.jitter, 1) + " ms · Perdus : " + p.lost + "/" + pingCount
                    + "\nMoyenne : " + fr(p.mean, 2) + " ms ± " + fr(p.margin, 2)
                    + " ms (marge d'erreur à 95 %) · Écart-type : " + fr(p.std, 2)
                    + " ms\n95 % des pings sous " + fr(p.p95, 1) + " ms · " + p.n + " mesures, "
                    + p.outliers + " pic(s) écarté(s)\n" + probeInfo);
            out.add("Ping : " + fr(p.med, 1) + " ms (gigue " + fr(p.jitter, 1) + " ms, ± " + fr(p.margin, 2) + ")");
        } catch (Throwable e) {
            log("Ping ÉCHEC : " + e.getMessage());
            cPing.error("⚠️ Ping : " + e.getMessage());
        }

        // ---- 2) descendant + 3) 50 Mo
        Rate d = null;
        if (pingMed >= 0 && !cancelled) {
            try {
                say("Débit descendant… (" + dur + " s)");
                d = multiPass(true);
                dlAvg = d.avg;
                if (d.loaded >= 0) loaded = d.loaded;
                cDl.value(fr(d.avg, 1) + " Mb/s", GOOD);
                cDl.detail("Soit " + fr(d.avg / 8, 2) + " Mo/s · Pic : " + fr(d.peak, 1) + " Mb/s · Plus bas : "
                        + fr(d.low, 1) + " Mb/s\nMarge : ± " + fr(d.margin, 1) + " Mb/s (" + reliability(d.rel) + ")"
                        + (d.runs.size() > 1 ? " · Passes : " + joinRuns(d.runs) : "")
                        + "\nServeur : " + cur.name
                        + (dlTried.isEmpty() ? "" : "\nServeurs refusés avant : " + dlTried)
                        + (d.loaded >= 0 ? "\nPing pendant le téléchargement : " + fr(d.loaded, 0) + " ms ("
                        + (d.loaded - pingMed >= 0 ? "+" : "") + fr(d.loaded - pingMed, 0) + " ms)" : ""));
                gset(d.avg, "Téléchargement", "Mb/s", GOOD);
                out.add("Descendant : " + fr(d.avg, 1) + " Mb/s ± " + fr(d.margin, 1) + " (pic " + fr(d.peak, 1) + ")");
            } catch (Throwable e) {
                log("Téléchargement ÉCHEC : " + e.getMessage());
                cDl.error("⚠️ Téléchargement : " + e.getMessage());
            }
        } else if (pingMed < 0) {
            cDl.error("⚠️ Ignoré : le ping n'a pas fonctionné (pas de réseau ?).");
        }

        if (d != null && !cancelled) {
            double secs = 400.0 / d.avg;
            c50.value(fmtTime(secs), GOOD);
            String txt = "Calculé à " + fr(d.avg, 1) + " Mb/s (moyenne stable). Au pic (" + fr(d.peak, 1)
                    + " Mb/s) : " + fmtTime(400.0 / d.peak) + ".\nMarge : entre "
                    + fmtTime(400.0 / (d.avg + d.margin)) + " et " + fmtTime(400.0 / Math.max(0.1, d.avg - d.margin)) + ".";
            out.add("50 Mo : " + fmtTime(secs) + " (calculé)");
            try {
                say("Vrai téléchargement de 50 Mo…");
                List<Double> times = new ArrayList<>();
                double bytes = 0;
                for (int i = 0; i < Math.min(passes, 2); i++) {
                    double[] m = real50Any();
                    times.add(m[0]);
                    bytes = m[1];
                }
                double real = median(times);
                double gap = (real - secs) / secs * 100;
                txt += "\nVérifié en vrai : " + fr(real, 1) + " s (" + fr(bytes / 1e6, 1) + " Mo reçus, ≈ "
                        + fr(bytes * 8 / 1e6 / real, 1) + " Mb/s). Écart avec le calcul : "
                        + (gap >= 0 ? "+" : "") + fr(gap, 0) + " %";
                out.add("50 Mo : " + fr(real, 1) + " s (mesuré en vrai)");
            } catch (Throwable e) {
                log("Test réel 50 Mo ÉCHEC : " + e.getMessage());
                c50.error("⚠️ Test réel 50 Mo : " + e.getMessage());
            }
            c50.detail(txt);
        }

        // ---- 4) montant
        if (pingMed >= 0 && !cancelled) {
            try {
                say("Débit montant… (" + dur + " s)");
                Rate u = multiPass(false);
                ulAvg = u.avg;
                if (u.loaded >= 0) loaded = Math.max(loaded, u.loaded);
                cUl.value(fr(u.avg, 1) + " Mb/s", GOOD);
                cUl.detail("Soit " + fr(u.avg / 8, 2) + " Mo/s · Pic : " + fr(u.peak, 1) + " Mb/s · Plus bas : "
                        + fr(u.low, 1) + " Mb/s\nMarge : ± " + fr(u.margin, 1) + " Mb/s (" + reliability(u.rel) + ")"
                        + (u.runs.size() > 1 ? " · Passes : " + joinRuns(u.runs) : "")
                        + (upCur != null ? "\nServeur : " + upCur.name : "")
                        + (upTried.isEmpty() ? "" : "\nServeurs refusés avant : " + upTried)
                        + (u.loaded >= 0 ? "\nPing pendant l'envoi : " + fr(u.loaded, 0) + " ms ("
                        + (u.loaded - pingMed >= 0 ? "+" : "") + fr(u.loaded - pingMed, 0) + " ms)" : ""));
                gset(u.avg, "Envoi", "Mb/s", GOOD);
                out.add("Montant : " + fr(u.avg, 1) + " Mb/s ± " + fr(u.margin, 1));
            } catch (Throwable e) {
                log("Envoi ÉCHEC : " + e.getMessage());
                cUl.error("⚠️ Envoi : " + e.getMessage());
            }
        }

        // ---- 5) processeur
        if (!cancelled) {
            try {
                CpuRes c = cpuTest();
                if (c.readable) {
                    cCpu.value(fr(c.peakGhz, 2) + " GHz", PUR);
                    gset(c.peakGhz, "Processeur", "GHz", GOOD);
                } else {
                    cCpu.value("≈ " + fr(c.calcGhz, 2) + " GHz", PUR);
                    gset(c.calcGhz, "Processeur (calcul)", "GHz", GOOD);
                }
                StringBuilder sb = new StringBuilder();
                if (c.readable) {
                    sb.append("Fréquence maximale réellement atteinte pendant le test, cœur par cœur :\n");
                    for (int i = 0; i < c.cores; i++) {
                        sb.append("• cœur ").append(i).append(" : ")
                                .append(c.maxSeen[i] > 0 ? fr(c.maxSeen[i] / 1e6, 2) + " GHz" : "illisible");
                        if (c.officialMax[i] > 0) sb.append("  (max du processeur : ").append(fr(c.officialMax[i] / 1e6, 2)).append(" GHz)");
                        sb.append('\n');
                    }
                    sb.append("Contrôle par calcul chronométré : ").append(fr(c.calcGhz, 2)).append(" GHz\n");
                } else {
                    sb.append("Android bloque la lecture de la fréquence sur ce téléphone.\n");
                    long mx = 0;
                    for (long v : c.officialMax) mx = Math.max(mx, v);
                    if (mx > 0) sb.append("Maximum du processeur : ").append(fr(mx / 1e6, 2)).append(" GHz\n");
                    sb.append("Valeur ci-dessus : estimation par calcul chronométré (± 5 à 10 %).\n");
                }
                if (c.drop > 0.1) sb.append("🔥 La fréquence a baissé d'environ ").append(fr(c.drop * 100, 0))
                        .append(" % pendant le test (chauffe ou économie d'énergie).");
                else sb.append("✅ Pas de ralentissement détecté pendant le test.");
                cCpu.detail(sb.toString());
                out.add("Processeur : " + (c.readable ? fr(c.peakGhz, 2) + " GHz (lu sur le téléphone)"
                        : "≈ " + fr(c.calcGhz, 2) + " GHz (calculé)") + ", " + c.cores + " cœurs");
            } catch (Throwable e) {
                log("Processeur ÉCHEC : " + e.getMessage());
                cCpu.error("⚠️ Processeur : " + e.getMessage());
            }
        }

        // ---- 6) RAM
        if (!cancelled) {
            try {
                say("Mémoire RAM…");
                gset(0, "RAM", "Go/s", ACC);
                int reps = 12 * passes;
                RamRes one = ramBench(16, reps);
                int threads = Math.min(4, Math.max(2, Runtime.getRuntime().availableProcessors() / 2));
                final RamRes[] many = new RamRes[threads];
                final int fr2 = reps;
                final CountDownLatch done = new CountDownLatch(threads);
                for (int i = 0; i < threads; i++) {
                    final int k = i;
                    Thread t = new Thread(new Runnable() {
                        @Override public void run() {
                            try { many[k] = ramBench(16, fr2); } catch (Throwable ignored) { }
                            done.countDown();
                        }
                    });
                    t.setDaemon(true);
                    t.start();
                }
                done.await();
                double copyN = 0, writeN = 0;
                int okN = 0;
                for (RamRes r : many) {
                    if (r != null) { copyN += r.copy; writeN += r.write; okN++; }
                }
                if (okN == 0) { copyN = one.copy; writeN = one.write; }
                cRam.value(fr(copyN, 1) + " Go/s", GOOD);
                gset(copyN, "RAM", "Go/s", GOOD);
                cRam.detail("Copie avec " + Math.max(1, okN) + " threads : " + fr(copyN, 1) + " Go/s · Écriture : "
                        + fr(writeN, 1) + " Go/s\n1 seul thread : copie " + fr(one.copy, 1) + " Go/s · écriture "
                        + fr(one.write, 1) + " Go/s\nLatence d'accès à la mémoire : ≈ " + fr(one.lat, 0) + " ns");
                out.add("RAM : " + fr(copyN, 1) + " Go/s en copie, latence ≈ " + fr(one.lat, 0) + " ns");
            } catch (Throwable e) {
                log("RAM ÉCHEC : " + e);
                cRam.error("⚠️ RAM : " + e.getMessage());
            }
        }

        // ---- verdict et résumé
        final String v = verdict(pingMed, jitter, dlAvg, ulAvg, loaded);
        runOnUiThread(new Runnable() {
            @Override public void run() {
                cVerdict.val.setText(v);
                cVerdict.val.setTextColor(TXT);
            }
        });
        out.add("Batterie : " + batStart + " au début, " + batteryInfo() + " à la fin");
        lastReport = join(out);
        runOnUiThread(new Runnable() {
            @Override public void run() { copy.setEnabled(true); }
        });
        say(cancelled ? "Test annulé" : "Test terminé ✅");
        log(cancelled ? "Test annulé." : "Test terminé.");
    }

    static String joinRuns(List<Double> runs) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < runs.size(); i++) {
            if (i > 0) sb.append(" / ");
            sb.append(fr(runs.get(i), 0));
        }
        return sb.toString();
    }

    static String join(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (String s : lines) sb.append(s).append('\n');
        return sb.toString();
    }
}
