package ac.grim.grimac.checks;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.api.AbstractCheck;
import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.api.event.events.FlagEvent;
import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.grim.grimac.api.storage.verbose.VerboseBuf;
import ac.grim.grimac.api.storage.verbose.VerboseRenderContext;
import ac.grim.grimac.hapi.HapiFlagLog;
import ac.grim.grimac.internal.storage.verbose.VerboseRegistry;
import ac.grim.grimac.player.GrimPlayer;
import lombok.Getter;
import lombok.Setter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

// Class from https://github.com/Tecnio/AntiCheatBase/blob/master/src/main/java/me/tecnio/anticheat/check/Check.java
@Getter
public class Check extends GrimProcessor implements AbstractCheck {
    private static final FlagEvent.Channel FLAG_CHANNEL = GrimAPI.INSTANCE.getEventBus().get(FlagEvent.class);
    private static final ThreadLocal<VerboseBuf> VERBOSE = ThreadLocal.withInitial(VerboseBuf::new);

    // violations
    public double violations;
    private long lastViolationTime;
    private boolean lastFlagStoredBinaryVerbose;
    private final VerboseBuf verbose = VERBOSE.get();

    // check data
    private final @NotNull String checkName;
    private final @NotNull String configName;
    private final @NotNull String alternativeName;
    private final @NotNull String stableKey;
    private final boolean experimental;
    private final @NotNull String defaultDescription;
    private final double defaultDecay;
    private final double defaultSetbackVL;

    // configurable
    private @NotNull String displayName;
    private @NotNull String description;
    private double decay;
    private double setbackVL;
    @Setter private boolean isEnabled;

    // permissions
    private boolean exemptPermission;
    private boolean noSetbackPermission;
    private boolean noModifyPacketPermission;

    // HapiAC: hapi.yml -> hapi.checks.<configName>.* (reload aninda alanlara cozulur, sicak yolda harita aramasi yok)
    private boolean hapiEnabled = true;
    private boolean hapiSetback = true;
    private double hapiAlertFrom;
    private String @Nullable [] hapiExemptWorlds;
    private double hapiPunishVl;
    private @Nullable String hapiPunishCommand;
    private long hapiPunishCooldownMs;
    private long hapiLastPunish;
    private long hapiAlertCooldownMs;
    private long hapiLastAlert;

    public Check(final @NotNull GrimPlayer player) {
        super(player, false);

        final CheckData checkData = this.getClass().getAnnotation(CheckData.class);
        if (checkData == null) {
            throw new IllegalStateException("Check class must have @CheckData annotation. Consider using GrimProcessor instead.");
        }

        this.checkName = checkData.name();
        this.configName = checkData.configName().equals("DEFAULT")
                ? this.checkName
                : checkData.configName();
        this.defaultDecay = checkData.decay();
        this.defaultSetbackVL = checkData.setback();
        this.alternativeName = checkData.alternativeName();
        this.experimental = checkData.experimental();
        this.description = this.defaultDescription = checkData.description();
        this.stableKey = checkData.stableKey();
        this.displayName = this.checkName;

        reload();
    }

    public boolean shouldModifyPackets() {
        return isEnabled
                && !player.disableGrim
                && !player.noModifyPacketPermission
                && !noModifyPacketPermission
                && !exemptPermission;
    }

    /**
     * Evaluated once when CheckManager builds the dispatch arrays.
     * Implementations must only depend on immutable connection properties.
     */
    public boolean isApplicable() {
        return true;
    }

    public final void updatePermissions() {
        final String id = getConfigName().toLowerCase();
        exemptPermission = player.hasPermission("grim.exempt." + id);
        noSetbackPermission = player.hasPermission("grim.nosetback." + id);
        noModifyPacketPermission = player.hasPermission("grim.nomodifypacket." + id);
    }

    public final boolean flag() {
        return flag("");
    }

    public final boolean flag(String verbose) {
        Supplier<String> alertText = constant(verbose);
        if (recordFlag(alertText)) {
            alert(alertText);
            return true;
        }
        return false;
    }

    public final boolean flag(@NotNull Verbose.Writer verbose) {
        BinaryVerbose binary = lazyVerbose(verbose);
        if (recordFlag(binary)) {
            alert(binary.rendered());
            return true;
        }
        return false;
    }

    public final boolean flag(@NotNull Verbose.Writer verbose, @NotNull Supplier<String> alertText) {
        BinaryVerbose binary = lazyVerbose(verbose);
        if (recordFlag(binary)) {
            alert(memoize(Objects.requireNonNull(alertText, "alertText")));
            return true;
        }
        return false;
    }

    private boolean recordFlag(@NotNull Supplier<String> verbose) {
        if (player.disableGrim || (experimental && !player.isExperimentalChecks()) || exemptPermission || hapiBlocked())
            return false; // Avoid calling event if disabled

        if (FLAG_CHANNEL.fire(player, this, verbose)) return false;

        lastFlagStoredBinaryVerbose = false;
        player.punishmentManager.handleViolation(this);
        lastViolationTime = System.currentTimeMillis();
        player.hapiLastFlagged = this;
        HapiFlagLog.record(player, this, verbose.get());
        violations++;
        hapiPunish();
        return true;
    }

    private boolean recordFlag(@NotNull BinaryVerbose verbose) {
        Supplier<String> rendered = verbose.rendered();
        byte[] verboseData = verbose.data();

        if (player.disableGrim || (experimental && !player.isExperimentalChecks()) || exemptPermission || hapiBlocked())
            return false; // Avoid calling event if disabled

        if (FLAG_CHANNEL.fire(player, this, rendered)) return false;

        lastFlagStoredBinaryVerbose = true;
        player.punishmentManager.handleViolation(this);
        lastViolationTime = System.currentTimeMillis();
        player.hapiLastFlagged = this;
        HapiFlagLog.record(player, this, rendered.get());
        violations++;
        hapiPunish();
        GrimAPI.INSTANCE.getDataStoreLifecycle().liveWriteHooks()
                .recordFlagDataFromCheck(player, this, violations, verboseData);
        return true;
    }

    // HapiAC: check kapaliysa ya da oyuncu muaf dunyadaysa flag hic olusmaz
    private boolean hapiBlocked() {
        if (!hapiEnabled) return true;
        final String[] worlds = hapiExemptWorlds;
        if (worlds == null) return false;
        final String world = player.getWorldName();
        if (world == null) return false;
        for (String exempt : worlds) {
            if (exempt.equalsIgnoreCase(world)) return true;
        }
        return false;
    }

    // HapiAC: check-basina ceza komutu (punishments.yml gruplarindan bagimsiz)
    private void hapiPunish() {
        final String command = hapiPunishCommand;
        if (command == null || hapiPunishVl <= 0 || violations < hapiPunishVl) return;
        final long now = System.currentTimeMillis();
        if (now - hapiLastPunish < hapiPunishCooldownMs) return;
        hapiLastPunish = now;
        final String name = player.getName();
        if (name == null) return;
        final String cmd = command
                .replace("%player%", name)
                .replace("%check_name%", displayName)
                .replace("%vl%", Integer.toString((int) violations));
        GrimAPI.INSTANCE.getScheduler().getGlobalRegionScheduler().run(GrimAPI.INSTANCE.getGrimPlugin(), () ->
                GrimAPI.INSTANCE.getPlatformServer().dispatchCommand(
                        GrimAPI.INSTANCE.getPlatformServer().getConsoleSender(), cmd));
    }

    private @NotNull BinaryVerbose lazyVerbose(@NotNull Verbose.Writer writer) {
        Objects.requireNonNull(writer, "writer");
        byte[] verboseData = writer.end().toByteArray();
        Verbose template = writer.verbose();
        Supplier<String> rendered = memoize(() -> template.render(verboseData, new VerboseRenderContext(
                player.getClientVersion().getProtocolVersion(),
                GrimAPI.INSTANCE.getPlatformServer().getPlatformImplementationString())));
        return new BinaryVerbose(verboseData, rendered);
    }

    public final void registerVerboseTemplates(@Nullable VerboseRegistry registry) {
        if (registry == null || stableKey.isEmpty()) return;
        String pluginVersion = GrimAPI.INSTANCE.getExternalAPI().getGrimVersion();
        for (Verbose template : Verbose.declaredBy(getClass(), Check.class)) {
            registry.registerTemplate(stableKey, checkName, description, pluginVersion, template);
        }
    }

    protected final @NotNull VerboseBuf verbose() {
        return verbose;
    }

    public final boolean flagWithSetback() {
        return flagWithSetback("");
    }

    public final boolean flagWithSetback(String verbose) {
        if (flag(verbose)) {
            setbackIfAboveSetbackVL();
            return true;
        }
        return false;
    }

    public final boolean flagWithSetback(@NotNull Verbose.Writer verbose) {
        if (flag(verbose)) {
            setbackIfAboveSetbackVL();
            return true;
        }
        return false;
    }

    public final boolean flagWithSetback(@NotNull Verbose.Writer verbose, @NotNull Supplier<String> alertText) {
        if (flag(verbose, alertText)) {
            setbackIfAboveSetbackVL();
            return true;
        }
        return false;
    }

    public final void reward() {
        violations = Math.max(0, violations - decay);
    }

    @Override
    public final void reload(@NotNull ConfigManager configuration) {
        decay = configuration.getDoubleElse(getConfigName() + ".decay", defaultDecay);
        setbackVL = configuration.getDoubleElse(getConfigName() + ".setbackvl", defaultSetbackVL);
        displayName = configuration.getStringElse(getConfigName() + ".displayname", checkName);
        description = configuration.getStringElse(getConfigName() + ".description", defaultDescription);

        if (setbackVL == -1) setbackVL = Double.MAX_VALUE;

        final String hapi = "hapi.checks." + getConfigName() + ".";
        hapiEnabled = configuration.getBooleanElse(hapi + "enabled", true);
        hapiSetback = configuration.getBooleanElse(hapi + "setback", true);
        hapiAlertFrom = configuration.getDoubleElse(hapi + "dont-alert-until", defaultAlertFrom());
        final List<String> worlds = configuration.getStringListElse(hapi + "exempt-worlds", List.of());
        hapiExemptWorlds = worlds == null || worlds.isEmpty() ? null : worlds.toArray(new String[0]);
        hapiPunishVl = configuration.getDoubleElse(hapi + "punish-vl", 0);
        final String punish = configuration.getStringElse(hapi + "punish-command", "");
        hapiPunishCommand = punish == null || punish.isBlank() ? null : punish;
        hapiPunishCooldownMs = (long) (configuration.getDoubleElse(hapi + "punish-cooldown-seconds", 60) * 1000);
        hapiAlertCooldownMs = (long) (configuration.getDoubleElse(hapi + "alert-cooldown-seconds", 0) * 1000);
        super.reload(configuration);
    }

    public boolean alert(String verbose) {
        return alert(constant(verbose));
    }

    /**
     * HapiAC: yetkiliye/konsola giden [alert] satiri icin hiz siniri (alert-cooldown-seconds). Ihlal sayimi,
     * log, setback ve ceza komutlari etkilenmez; yalnizca ayni oyuncu+check icin alarm seli kesilir.
     */
    public boolean hapiAlertAllowed() {
        if (hapiAlertCooldownMs <= 0) return true;
        final long now = System.currentTimeMillis();
        if (now - hapiLastAlert < hapiAlertCooldownMs) return false;
        hapiLastAlert = now;
        return true;
    }

    /** HapiAC: hapi.yml'de deger yoksa kullanilacak alarm esigi; sezgisel check'ler yukseltir. */
    protected double defaultAlertFrom() {
        return 0;
    }

    public boolean alert(@NotNull Supplier<String> verbose) {
        if (violations < hapiAlertFrom) return false; // flag sayildi + loglandi, yetkiliye henuz gosterilmez
        return player.punishmentManager.handleAlert(player, memoize(Objects.requireNonNull(verbose, "verbose")), this);
    }

    public boolean setbackIfAboveSetbackVL() {
        if (shouldSetback()) {
            return player.getSetbackTeleportUtil().executeViolationSetback();
        }
        return false;
    }

    public boolean shouldSetback() {
        return !isNoSetbackPermission() && violations > setbackVL;
    }

    public boolean executeViolationSetback() {
        return !isNoSetbackPermission() && player.getSetbackTeleportUtil().executeViolationSetback();
    }

    /** HapiAC: hapi.checks.<ad>.setback: false ise check flag uretir ama oyuncuyu geri cekmez. */
    public boolean isNoSetbackPermission() {
        return noSetbackPermission || !hapiSetback;
    }

    public String formatOffset(double offset) {
        return offset > 0.001 ? String.format("%.5f", offset) : String.format("%.2E", offset);
    }

    private static @NotNull Supplier<String> constant(String verbose) {
        String value = verbose == null ? "" : verbose;
        return () -> value;
    }

    private static @NotNull Supplier<String> memoize(@NotNull Supplier<String> supplier) {
        return new Supplier<>() {
            private String value;
            private boolean computed;

            @Override
            public synchronized String get() {
                if (!computed) {
                    try {
                        value = supplier.get();
                        if (value == null) value = "";
                    } catch (RuntimeException ignored) {
                        value = "";
                    }
                    computed = true;
                }
                return value;
            }
        };
    }

    private record BinaryVerbose(byte @NotNull [] data, @NotNull Supplier<String> rendered) {}
}
