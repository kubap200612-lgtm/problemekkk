package pl.pearltrail;

import net.minecraft.block.ShapeContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.thrown.EnderPearlEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.RaycastContext;

import java.util.*;

/** Śledzi lecące perły, rysuje smugi i wykrywa miejsce uderzenia. */
public class TrailManager {

    /** Odstęp między punktami smugi w blokach (mniej = gęściej). */
    private static final double SPACING = 0.15;
    private static final int MAX_POINTS = 2500;
    /** Przez tyle ticków próbujemy ustalić, kto rzucił perłę. */
    private static final int OWNER_LOOKUP_TICKS = 5;

    private static final Map<Integer, Trail> TRAILS = new HashMap<>();
    public static int clientTicks = 0;
    /** Id najnowszej rzuconej perły (jej smuga ma wyróżniony kolor). */
    private static int latestId = Integer.MIN_VALUE;

    private static class Trail {
        final List<Vec3d> points = new ArrayList<>();
        /** Ile punktów z początku listy zostało już "świeżo" narysowanych. */
        int drawnPoints = 0;
        Vec3d last;
        Vec3d velocity = Vec3d.ZERO;
        int deadTicks = -1; // -1 = perła wciąż leci
        int age = 0;
        String ownerName;
        boolean ownerChecked = false;
        boolean friend = false;
    }

    public static void clear() {
        TRAILS.clear();
        latestId = Integer.MIN_VALUE;
        HighlightRenderer.clear();
    }

    public static void tick(ClientWorld world) {
        clientTicks++;
        TrailConfig cfg = TrailConfig.INSTANCE;
        Random random = world.getRandom();

        HighlightRenderer.tick(world, random);

        if (!cfg.enabled) {
            TRAILS.clear();
            return;
        }

        Set<Integer> alive = new HashSet<>();

        // 1. Zapisywanie trasy lecących pereł
        for (Entity entity : world.getEntities()) {
            if (!(entity instanceof EnderPearlEntity pearl)) continue;

            int id = pearl.getId();
            alive.add(id);
            Trail trail = TRAILS.get(id);
            boolean isNew = trail == null;
            if (isNew) {
                trail = new Trail();
                TRAILS.put(id, trail);
            }
            trail.age++;

            // Kto rzucił? (znajomi nie dostają smugi)
            if (!trail.ownerChecked) {
                PlayerEntity owner = findOwner(world, pearl);
                if (owner != null) {
                    trail.ownerName = owner.getGameProfile().getName();
                    trail.ownerChecked = true;
                    // smuga zaczyna się od ręki gracza, a nie od miejsca, gdzie pierwszy raz zobaczyliśmy perłę
                    if (isNew) {
                        trail.last = owner.getEyePos().subtract(0, 0.1, 0);
                        trail.points.add(trail.last);
                    }
                } else if (trail.age >= OWNER_LOOKUP_TICKS) {
                    trail.ownerChecked = true;
                }
            }
            trail.friend = cfg.isFriend(trail.ownerName);

            // nowa (nie znajoma) perła -> staje się "ostatnią", poprzednia wraca do fioletu
            if (isNew && !trail.friend) latestId = id;
            if (latestId == id && trail.friend) latestId = Integer.MIN_VALUE;

            addSegment(trail, pearl.getPos());
            trail.velocity = pearl.getVelocity();
        }

        int fadeTicks = Math.round(cfg.fadeSeconds * 20);

        // 2. Rysowanie smug, wykrywanie uderzeń, usuwanie starych smug
        Iterator<Map.Entry<Integer, Trail>> it = TRAILS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, Trail> entry = it.next();
            Trail trail = entry.getValue();
            Integer tint = (cfg.latestEnabled && entry.getKey() == latestId) ? cfg.latestColor.rgb : null;

            if (!alive.contains(entry.getKey())) {
                if (trail.deadTicks == -1) {
                    trail.deadTicks = 0;
                    if (!trail.friend) onImpact(world, random, trail, cfg, tint);
                } else {
                    trail.deadTicks++;
                }
                if (trail.deadTicks >= fadeTicks || trail.friend) {
                    it.remove();
                    continue;
                }
            }
            if (trail.friend) continue; // perła znajomego - nic nie rysujemy

            float chance = cfg.density;
            if (trail.deadTicks >= 0 && fadeTicks > 0) {
                chance *= 1.0f - (float) trail.deadTicks / fadeTicks;
            }

            List<Vec3d> pts = trail.points;
            for (int i = 0; i < pts.size(); i++) {
                // świeże punkty (dopiero co przeleciane) dostają cząsteczkę od razu - smuga startuje natychmiast
                boolean fresh = i >= trail.drawnPoints;
                if (fresh || random.nextFloat() < chance) {
                    spawnPortal(world, random, pts.get(i), cfg.thickness, tint, fresh ? 1 : 0);
                }
            }
            trail.drawnPoints = pts.size();
        }
    }

    /** Właściciel perły; jeśli serwer go nie podał, bierzemy najbliższego gracza. */
    private static PlayerEntity findOwner(ClientWorld world, EnderPearlEntity pearl) {
        if (pearl.getOwner() instanceof PlayerEntity p) return p;
        PlayerEntity best = null;
        double bestDist = 3.0 * 3.0;
        for (PlayerEntity p : world.getPlayers()) {
            double d = p.getEyePos().squaredDistanceTo(pearl.getPos());
            if (d < bestDist) {
                bestDist = d;
                best = p;
            }
        }
        // tylko gdy perła dopiero co wyleciała (jest blisko gracza)
        return best;
    }

    private static void addSegment(Trail trail, Vec3d pos) {
        if (trail.last == null) {
            trail.points.add(pos);
        } else {
            double dist = trail.last.distanceTo(pos);
            if (dist > 0.01) {
                int steps = Math.max(1, (int) Math.ceil(dist / SPACING));
                for (int i = 1; i <= steps; i++) {
                    trail.points.add(trail.last.lerp(pos, (double) i / steps));
                }
            }
        }
        trail.last = pos;
        int overflow = trail.points.size() - MAX_POINTS;
        if (overflow > 0) {
            trail.points.subList(0, overflow).clear();
            trail.drawnPoints = Math.max(0, trail.drawnPoints - overflow);
        }
    }

    /** Perła zniknęła - szukamy bloku, w który uderzyła. */
    private static void onImpact(ClientWorld world, Random random, Trail trail, TrailConfig cfg, Integer tint) {
        if (trail.last == null) return;

        Vec3d dir = trail.velocity.lengthSquared() < 1.0E-4 ? new Vec3d(0, -1, 0) : trail.velocity;
        Vec3d end = trail.last.add(dir.multiply(2.0));
        BlockHitResult hit = world.raycast(new RaycastContext(
                trail.last, end, RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE, ShapeContext.absent()));

        Vec3d impact = trail.last;
        if (hit.getType() == HitResult.Type.BLOCK) {
            impact = hit.getPos();
            addSegment(trail, impact); // smuga dochodzi dokładnie do miejsca uderzenia
            if (cfg.highlight) {
                HighlightRenderer.add(hit.getBlockPos());
            }
        }

        // Wybuch cząsteczek jak przy teleporcie endermana (zawsze szeroki)
        for (int i = 0; i < cfg.burstParticles; i++) {
            spawnPortal(world, random, impact, 1.0f, tint, 0);
        }
    }

    /**
     * Cząsteczka teleportacji endermana (PORTAL).
     * thickness 1 = ruch jak u endermana (szeroko), 0 = cząsteczka trzyma się toru lotu perły.
     * tint == null -> oryginalny fiolet, inaczej cząsteczka jest przebarwiana.
     * fresh = 1 -> krótsze życie, żeby cząsteczka szybciej "urosła" i była widoczna od razu.
     */
    public static void spawnPortal(ClientWorld world, Random random, Vec3d p, float thickness, Integer tint, int fresh) {
        double spread = 0.8 * thickness;
        double x = p.x + (random.nextDouble() - 0.5) * spread;
        double y = p.y + (random.nextDouble() - 0.5) * spread;
        double z = p.z + (random.nextDouble() - 0.5) * spread;

        double vx = (random.nextDouble() - 0.5) * 2.0 * thickness;
        double vz = (random.nextDouble() - 0.5) * 2.0 * thickness;
        // Cząsteczka PORTAL sama unosi się ok. 1 bloku w górę i wraca do punktu startu.
        // vy = -0.75 prawie znosi ten ruch, więc cienka smuga trzyma się toru perły (±0.25 bloku).
        double vy = MathHelper.lerp(thickness, -0.75, -random.nextDouble());
        vy += (random.nextDouble() - 0.5) * 0.1 * (1 - thickness);

        Particle particle = MinecraftClient.getInstance().particleManager
                .addParticle(ParticleTypes.PORTAL, x, y, z, vx, vy, vz);
        if (particle == null) return;

        if (fresh == 1) {
            particle.setMaxAge(18 + random.nextInt(8));
        }
        if (tint != null) {
            // jasność losowa jak w oryginalnej cząsteczce portalu
            float shade = random.nextFloat() * 0.6f + 0.4f;
            particle.setColor(
                    ((tint >> 16) & 0xFF) / 255f * shade,
                    ((tint >> 8) & 0xFF) / 255f * shade,
                    (tint & 0xFF) / 255f * shade);
        }
    }
}
