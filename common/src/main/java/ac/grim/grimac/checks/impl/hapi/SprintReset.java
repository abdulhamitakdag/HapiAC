package ac.grim.grimac.checks.impl.hapi;

import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.player.GrimPlayer;
import org.jetbrains.annotations.NotNull;

/**
 * W-tap / S-tap makrosu: kosarak vurduktan sonra ileri tusunu birakip yeniden basmak (sprint reset)
 * mesru bir tekniktir; elle yapildiginda birakma ani ve suresi tickten ticke oynar. Makro her vurusta
 * tusu ayni tickte birakir ve ayni sure sonra yeniden basar. Pencere icindeki orneklerin neredeyse
 * tamami birebir ayni (gecikme, sure) ciftiyse flag. Sprint reset yapmayan vuruslar ornek uretmez.
 */
@CheckData(name = "SprintReset", stableKey = "hapi.combat.sprint_reset", description = "W-tap / S-tap makrosu (her vurusta birebir ayni tus zamanlamasi)", decay = 0.01)
public final class SprintReset extends Check {
    private static final int MAX_RELEASE_DELAY = 3; // vurustan sonra birakma icin beklenen en cok tick
    private static final int MAX_HOLD = 6;          // tusun birakili kalabilecegi en cok tick
    private static final int STALE_TICKS = 1200;    // ornekler arasi bosluk bunu asarsa pencere sifirlanir

    private int sampleSize, minIdentical; // onReload atar (super() icinde)
    private int[] codes;

    private int phase; // 0 bos, 1 birakma bekleniyor, 2 yeniden basma bekleniyor
    private int attackTick, releaseTick, lastSampleTick;
    private boolean prevForward;
    private int count;

    public SprintReset(@NotNull GrimPlayer player) {
        super(player);
    }

    @Override
    public void onReload(@NotNull ConfigManager config) {
        sampleSize = Math.max(4, config.getIntElse("hapi.combat.sprint-reset.sample-size", 10));
        minIdentical = config.getIntElse("hapi.combat.sprint-reset.min-identical", 9);
        codes = new int[sampleSize];
        count = 0;
    }

    void onTick(int tick, boolean attackedPlayer) {
        final boolean forward = player.packetStateData.knownInput.forward() && !player.packetStateData.knownInput.backward();

        if (attackedPlayer && prevForward && (player.isSprinting || player.lastSprinting)) {
            phase = 1;
            attackTick = tick;
        }

        if (phase == 1) {
            if (prevForward && !forward) {
                phase = 2;
                releaseTick = tick;
            } else if (tick - attackTick > MAX_RELEASE_DELAY) {
                phase = 0;
            }
        } else if (phase == 2) {
            if (forward) {
                phase = 0;
                record(tick, (releaseTick - attackTick) * 16 + (tick - releaseTick));
            } else if (tick - releaseTick > MAX_HOLD) {
                phase = 0;
            }
        }
        prevForward = forward;
    }

    private void record(int tick, int code) {
        if (tick - lastSampleTick > STALE_TICKS) count = 0;
        lastSampleTick = tick;
        codes[count++] = code;
        if (count < sampleSize) return;
        count = 0;

        int best = 0, bestCode = 0;
        for (int i = 0; i < sampleSize; i++) {
            int same = 0;
            for (int j = 0; j < sampleSize; j++) if (codes[j] == codes[i]) same++;
            if (same > best) {
                best = same;
                bestCode = codes[i];
            }
        }
        if (best >= minIdentical) {
            flag("ayni=" + best + "/" + sampleSize + " gecikme=" + (bestCode >> 4) + " sure=" + (bestCode & 15));
        }
    }
}
