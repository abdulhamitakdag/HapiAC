package ac.grim.grimac.hapi;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.LogUtil;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * HapiAC: her flag'i (alarm esigine takilsa bile) sayar ve gunluk dosyaya yazar.
 * Netty thread'inde yalnizca sayac artisi + kuyruga ekleme yapilir; disk yazimi tek bir
 * arka plan thread'inde 2 saniyede bir toplu yapilir.
 */
public final class HapiFlagLog {
    public enum Kind {CHECK, PLAYER, WORLD, SETBACK}

    private static final int MAX_QUEUE = 20_000;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private static final Map<String, LongAdder> BY_CHECK = new ConcurrentHashMap<>();
    private static final Map<String, LongAdder> BY_PLAYER = new ConcurrentHashMap<>();
    private static final Map<String, LongAdder> BY_WORLD = new ConcurrentHashMap<>();
    private static final Map<String, LongAdder> BY_SETBACK = new ConcurrentHashMap<>();
    private static final ConcurrentLinkedQueue<String> QUEUE = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger QUEUED = new AtomicInteger();
    private static final LongAdder DROPPED = new LongAdder();

    private static volatile boolean fileEnabled = true;
    private static volatile ScheduledExecutorService writer;
    private static volatile long since = System.currentTimeMillis();

    private HapiFlagLog() {}

    public static void setFileEnabled(boolean enabled) {
        fileEnabled = enabled;
    }

    public static long since() {
        return since;
    }

    public static void record(GrimPlayer player, Check check, String verbose) {
        String name = player.getName();
        if (name == null) name = "?";
        String world = player.getWorldName();
        if (world == null) world = "?";

        BY_CHECK.computeIfAbsent(check.getCheckName(), k -> new LongAdder()).increment();
        BY_PLAYER.computeIfAbsent(name, k -> new LongAdder()).increment();
        BY_WORLD.computeIfAbsent(world, k -> new LongAdder()).increment();

        write(player, name, world, check.getCheckName(), (int) check.getViolations() + 1, verbose);
    }

    /**
     * Gercekten gonderilen bir geri cekme (setback). Ayni dosyaya check sutunu "SETBACK", vl sutunu
     * "son 10 sn icindeki kacinci geri cekme" olacak sekilde yazilir; boylece oncesindeki flag'lerle
     * ayni zaman cizgisinde okunur.
     */
    public static void recordSetback(GrimPlayer player, String cause, int streak, String detail) {
        String name = player.getName();
        if (name == null) name = "?";
        String world = player.getWorldName();
        if (world == null) world = "?";

        BY_SETBACK.computeIfAbsent(cause, k -> new LongAdder()).increment();
        write(player, name, world, "SETBACK", streak, detail);
    }

    private static void write(GrimPlayer player, String name, String world, String check, int vl, String detail) {
        if (!fileEnabled) return;
        if (QUEUED.get() >= MAX_QUEUE) {
            DROPPED.increment();
            return;
        }

        String brand = player.getBrand();
        if (brand == null) brand = "?";
        if (brand.length() > 32) brand = brand.substring(0, 32);

        StringBuilder sb = new StringBuilder(128);
        sb.append(LocalTime.now().format(TIME)).append('\t')
                .append(name).append('\t')
                .append(check).append('\t')
                .append(vl).append('\t')
                .append(world).append('\t')
                .append(player.getTransactionPing()).append('\t')
                .append(player.getClientVersion().getReleaseName()).append('\t')
                .append(clean(brand)).append('\t')
                .append(detail == null ? "" : clean(detail));
        QUEUE.add(sb.toString());
        QUEUED.incrementAndGet();
        ensureWriter();
    }

    private static String clean(String text) {
        return text.replace('\t', ' ').replace('\n', ' ');
    }

    private static void ensureWriter() {
        if (writer != null) return;
        synchronized (HapiFlagLog.class) {
            if (writer != null) return;
            ScheduledExecutorService created = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "HapiAC-FlagLog");
                t.setDaemon(true);
                return t;
            });
            created.scheduleWithFixedDelay(HapiFlagLog::flush, 2, 2, TimeUnit.SECONDS);
            writer = created;
        }
    }

    /** Kuyruktaki satirlari diske yazar. Sunucu kapanirken de cagrilabilir. */
    public static synchronized void flush() {
        if (QUEUE.isEmpty()) return;
        try {
            File dir = new File(GrimAPI.INSTANCE.getGrimPlugin().getDataFolder(), "hapi");
            if (!dir.isDirectory() && !dir.mkdirs()) return;
            File file = new File(dir, "flags-" + LocalDate.now() + ".log");
            try (BufferedWriter out = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                String line;
                while ((line = QUEUE.poll()) != null) {
                    QUEUED.decrementAndGet();
                    out.write(line);
                    out.write('\n');
                }
                long dropped = DROPPED.sumThenReset();
                if (dropped > 0) {
                    out.write("# " + dropped + " satir kuyruk dolu oldugu icin yazilmadi\n");
                }
            }
        } catch (IOException | RuntimeException e) {
            LogUtil.warn("HapiAC flag logu yazilamadi: " + e);
        }
    }

    public static List<Map.Entry<String, Long>> top(Kind kind, int limit) {
        Map<String, LongAdder> source = switch (kind) {
            case CHECK -> BY_CHECK;
            case PLAYER -> BY_PLAYER;
            case WORLD -> BY_WORLD;
            case SETBACK -> BY_SETBACK;
        };
        List<Map.Entry<String, Long>> list = new ArrayList<>(source.size());
        for (Map.Entry<String, LongAdder> e : source.entrySet()) {
            list.add(Map.entry(e.getKey(), e.getValue().sum()));
        }
        list.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        return list.size() > limit ? list.subList(0, limit) : list;
    }

    public static long total(Kind kind) {
        long sum = 0;
        for (LongAdder adder : (kind == Kind.SETBACK ? BY_SETBACK : BY_CHECK).values()) sum += adder.sum();
        return sum;
    }

    public static void reset() {
        BY_CHECK.clear();
        BY_PLAYER.clear();
        BY_WORLD.clear();
        BY_SETBACK.clear();
        since = System.currentTimeMillis();
    }
}
