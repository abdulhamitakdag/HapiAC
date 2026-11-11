package ac.grim.grimac.checks.impl.hapi;

import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.collisions.CollisionData;
import ac.grim.grimac.utils.collisions.datatypes.CollisionBox;
import ac.grim.grimac.utils.collisions.datatypes.ComplexCollisionBox;
import ac.grim.grimac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.grim.grimac.utils.data.HitData;
import ac.grim.grimac.utils.math.Vector3dm;
import ac.grim.grimac.utils.nmsutil.ReachUtils;
import ac.grim.grimac.utils.nmsutil.WorldRayTrace;
import com.github.retrooper.packetevents.protocol.world.BlockFace;
import com.github.retrooper.packetevents.util.Vector3d;
import org.jetbrains.annotations.NotNull;

/**
 * Duvar arkasindan vurus: Reach'in hedefe isabet ettigini buldugu her olasi bakis isini (bakis yonu x
 * goz yuksekligi) bir blogun carpisma kutusuna takiliyorsa vurus duvarin icinden gecmistir.
 * <p>
 * Yanlis alarmi azaltmak icin bilerek temkinli: yalnizca CARPISMA kutusu olan bloklar engel sayilir
 * (cim, cicek gibi icinden vurulabildigi sanilan/istemci moduyla vurulabilen seyler sayilmaz), kutular
 * hareket belirsizligi kadar kucultulur, tek bir acik isin vurusu mesru sayar ve piston/shulker/tekne
 * yakininda hic bakilmaz. Isinlar yalnizca vurus aninda izlenir; tick basina maliyeti yoktur.
 * <p>
 * Reach tarafindan cagrilir: {@link #begin()}, isabet eden her isin icin {@link #onRay}, sonra {@link #end}.
 */
@CheckData(name = "WallHit", stableKey = "hapi.combat.wall_hit", description = "Duvar arkasindan vurus", decay = 0.05)
public final class WallHit extends Check {
    private final SimpleCollisionBox[] boxes = new SimpleCollisionBox[ComplexCollisionBox.DEFAULT_MAX_COLLISION_BOX_SIZE];
    private boolean anyRay, anyClear, skip;
    private String blockName;
    private int streak;

    public WallHit(@NotNull GrimPlayer player) {
        super(player);
    }

    @Override
    protected double defaultAlertFrom() {
        return 2;
    }

    public void begin() {
        anyRay = anyClear = false;
        blockName = null;
        skip = !isEnabled() || player.compensatedWorld.isNearHardEntity(player.boundingBox.copy().expand(4));
    }

    /** @param hit isinin hedef kutusuna (belirsizlik payiyla genisletilmis) girdigi nokta */
    public void onRay(Vector3d eye, Vector3d hit) {
        if (skip || anyClear) return;
        try {
            trace(eye, hit);
        } catch (RuntimeException e) { // bu denetimdeki bir hata Reach'i (ve paket yolunu) bozmamali
            skip = true;
        }
    }

    private void trace(Vector3d eye, Vector3d hit) {
        anyRay = true;

        final double shrink = player.getMovementThreshold() + 0.005;
        final HitData obstruction = WorldRayTrace.traverseBlocks(player, eye, hit, (state, pos) -> {
            final CollisionBox data = CollisionData.getData(state.getType())
                    .getMovementCollisionBox(player, player.getClientVersion(), state, pos.getX(), pos.getY(), pos.getZ());
            final int size = data.downCast(boxes);
            for (int i = 0; i < size; i++) {
                final SimpleCollisionBox box = boxes[i].copy();
                // Cit/duvar carpisma kutusu 1.5 blok ama nisan (secim) kutusu 1 blok: ustunden vurulabilir
                if (box.maxY > pos.getY() + 1) box.maxY = pos.getY() + 1;
                box.expand(-shrink);
                if (ReachUtils.isVecInside(box, eye)) continue; // goz blogun icinde: belirsiz, engel sayma
                final Vector3d intercept = ReachUtils.calculateIntercept(box, eye, hit).first();
                if (intercept != null) return new HitData(pos, Vector3dm.from(intercept), BlockFace.UP, state);
            }
            return null;
        });

        if (obstruction == null) {
            anyClear = true;
        } else if (blockName == null) {
            blockName = obstruction.state().getType().getName();
        }
    }

    public void end() {
        if (skip || !anyRay) return;
        if (anyClear) {
            if (streak > 0) streak--;
            reward();
        } else if (++streak >= 2) { // tek seferlik blok senkron kaymasi icin iki ardisik vurus sarti
            flag("blok=" + blockName);
        }
    }
}
