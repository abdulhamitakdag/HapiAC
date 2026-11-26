package ac.grim.grimac.hapi;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.utils.anticheat.LogUtil;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * HapiAC "Sezgi" katmaninin ortak hafizasi: dovus olcutlerinin sunucu genelindeki dagilimi
 * (nufus) ve oyuncu basina kalici profil. Dovus bittiginde bir kez dokunulur; paket yolunda degil.
 * <p>
 * Nufus: olcut basina son {@link #RESERVOIR} dovusun degeri; ortanca ve MAD (ortancadan mutlak
 * sapmanin ortancasi) buradan cikar. Boylece esikler sunucunun kendi oyuncularina gore kendini
 * ayarlar; hileci azinlikta oldugu surece ortanca/MAD bundan etkilenmez.
 * <p>
 * Profil: oyuncunun kendi olagan duzeyi (taban) ve iki birikimli toplam (CUSUM). Dosyada tutulur,
 * yeniden giriste ve restartta kaybolmaz.
 */
public final class HapiSezgi {
    /** Olcutler; yon +1 ise yuksek deger, -1 ise dusuk deger hile yonudur. */
    public static final String[] NAMES = {"kenar", "ani", "merkez", "iz"};
    public static final int[] DIRECTION = {+1, +1, -1, +1};
    public static final int FEATURES = NAMES.length;
    /** Profil dizisi: olcut basina [taban ortalamasi, taban ornek sayisi, nufus toplami, degisim toplami], sonda son gorulme (ms). */
    public static final int PER_FEATURE = 4;
    public static final int PROFILE_SIZE = FEATURES * PER_FEATURE + 1;

    private static final int RESERVOIR = 512;
    private static final int RECOMPUTE_EVERY = 16;
    private static final long PROFILE_TTL_MS = 45L * 24 * 3600 * 1000;

    private static final float[][] values = new float[FEATURES][RESERVOIR];
    private static final int[] count = new int[FEATURES];
    private static final int[] index = new int[FEATURES];
    private static final int[] sinceRecompute = new int[FEATURES];
    private static final double[] median = new double[FEATURES];
    private static final double[] mad = new double[FEATURES];

    private static final Map<UUID, double[]> PROFILES = new ConcurrentHashMap<>();
    private static boolean loaded;
    private static volatile boolean dirty;

    private HapiSezgi() {}

    public static double[] profile(UUID uuid) {
        ensureLoaded();
        final double[] profile = PROFILES.computeIfAbsent(uuid, k -> new double[PROFILE_SIZE]);
        profile[PROFILE_SIZE - 1] = System.currentTimeMillis();
        dirty = true;
        return profile;
    }

    /**
     * Degerin nufusa gore saglam z puani, hile yonu pozitif olacak sekilde. Nufus henuz
     * {@code minSamples} dovuse ulasmadiysa NaN doner. Deger ardindan nufusa eklenir.
     */
    public static synchronized double scoreAndAdd(int feature, double value, int minSamples) {
        ensureLoaded();
        double z = Double.NaN;
        if (count[feature] >= minSamples) {
            if (sinceRecompute[feature] >= RECOMPUTE_EVERY || mad[feature] == 0) recompute(feature);
            // MAD tabani: oran olcutlerinde nufusun yarisi ayni degerde olabilir (MAD ~ 0)
            final double scale = Math.max(1.4826 * mad[feature], 0.05);
            z = DIRECTION[feature] * (value - median[feature]) / scale;
        }
        values[feature][index[feature]] = (float) value;
        if (++index[feature] == RESERVOIR) index[feature] = 0;
        if (count[feature] < RESERVOIR) count[feature]++;
        sinceRecompute[feature]++;
        dirty = true;
        return z;
    }

    private static void recompute(int feature) {
        final int n = count[feature];
        sinceRecompute[feature] = 0;
        if (n == 0) return;
        final float[] sorted = Arrays.copyOf(values[feature], n);
        Arrays.sort(sorted);
        final double med = sorted[n / 2];
        for (int i = 0; i < n; i++) sorted[i] = (float) Math.abs(sorted[i] - med);
        Arrays.sort(sorted);
        median[feature] = med;
        mad[feature] = sorted[n / 2];
    }

    private static File file() {
        return new File(new File(GrimAPI.INSTANCE.getGrimPlugin().getDataFolder(), "hapi"), "sezgi.dat");
    }

    private static synchronized void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        final File file = file();
        if (!file.isFile()) return;
        try (BufferedReader in = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            String line;
            while ((line = in.readLine()) != null) {
                final String[] parts = line.split("\t");
                if (parts[0].equals("N") && parts.length >= 2) { // nufus: N <olcut> <deger>...
                    int f = Arrays.asList(NAMES).indexOf(parts[1]);
                    if (f < 0) continue;
                    final int n = Math.min(parts.length - 2, RESERVOIR);
                    for (int i = 0; i < n; i++) values[f][i] = Float.parseFloat(parts[i + 2]);
                    count[f] = n;
                    index[f] = n % RESERVOIR;
                    recompute(f);
                } else if (parts[0].equals("P") && parts.length == PROFILE_SIZE + 2) { // profil: P <uuid> <alanlar>...
                    final double[] profile = new double[PROFILE_SIZE];
                    for (int i = 0; i < PROFILE_SIZE; i++) profile[i] = Double.parseDouble(parts[i + 2]);
                    PROFILES.put(UUID.fromString(parts[1]), profile);
                }
            }
        } catch (IOException | RuntimeException e) { // bozuk dosya: sifirdan basla
            LogUtil.warn("HapiAC sezgi.dat okunamadi, sifirdan baslaniyor: " + e);
        }
    }

    /** Nufusu ve profilleri diske yazar (arka plan thread'inden ve kapanista cagrilir). */
    public static void save() {
        if (!dirty) return;
        dirty = false;
        try {
            final StringBuilder sb = new StringBuilder(1 << 16);
            synchronized (HapiSezgi.class) {
                if (!loaded) return;
                for (int f = 0; f < FEATURES; f++) {
                    sb.append("N\t").append(NAMES[f]);
                    for (int i = 0; i < count[f]; i++) sb.append('\t').append(values[f][i]);
                    sb.append('\n');
                }
            }
            final long limit = System.currentTimeMillis() - PROFILE_TTL_MS;
            PROFILES.entrySet().removeIf(e -> e.getValue()[PROFILE_SIZE - 1] < limit);
            for (Map.Entry<UUID, double[]> e : PROFILES.entrySet()) {
                sb.append("P\t").append(e.getKey());
                for (double v : e.getValue()) sb.append('\t').append((float) v);
                sb.append('\n');
            }
            final File file = file();
            final File dir = file.getParentFile();
            if (!dir.isDirectory() && !dir.mkdirs()) return;
            final File tmp = new File(dir, "sezgi.dat.tmp");
            try (BufferedWriter out = Files.newBufferedWriter(tmp.toPath(), StandardCharsets.UTF_8)) {
                out.append(sb);
            }
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException e) {
            LogUtil.warn("HapiAC sezgi.dat yazilamadi: " + e);
        }
    }
}
