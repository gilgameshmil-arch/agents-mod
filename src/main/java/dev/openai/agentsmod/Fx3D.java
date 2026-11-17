package dev.openai.agentsmod;

import dev.openai.agentsmod.Defs.AbilityDef;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

/**
 * Efeitos 3D da Jett (modelos reais em assets/agentsmod/models/item/jett/*.json, exibidos por item_display):
 * facas orbitando e arremessadas (projétil simulado com trajetória, colisão e dano), canister + nuvem de fumaça
 * (arco, impacto, tufos animados), vento do Tailwind e do Updraft.
 *
 * Convenção de orientação: o item_display gira o modelo em 180° em Y em relação ao mundo; por isso, para apontar o eixo +Y
 * do modelo (ponta da faca) para a direção d no mundo, a rotação esquerda leva +Y a (-dx, dy, -dz). Ver aimQuat().
 */
final class Fx3D {
    private Fx3D() {}

    private static final double[] ID = {0, 0, 0, 1};

    static String mdl(String name) { return AgentsMod.MOD_ID + ":jett/" + name; }

    /** Rotação (xyzw) que aponta a ponta (+Y) do modelo para a direção de mundo d. */
    static double[] aimQuat(Vec3 d) {
        Vec3 n = d.lengthSqr() < 1e-9 ? new Vec3(0, 1, 0) : d.normalize();
        double x = -n.z, y = 0, z = n.x, w = 1 + n.y;
        double len = Math.sqrt(x * x + y * y + z * z + w * w);
        if (len < 1e-6) return new double[] {1, 0, 0, 0}; // ponta para baixo: meia-volta em X
        return new double[] {x / len, y / len, z / len, w / len};
    }

    /** Rotação em Y (rad) aplicada ao modelo. */
    static double[] yawQuat(double angle) { return FxDisplays.quat(0, 1, 0, angle); }

    /** Eixo +Z do modelo apontando para a direção horizontal (dx, dz) de mundo. */
    static double[] zToQuat(double dx, double dz) { return yawQuat(Math.atan2(-dx, -dz)); }

    // ================================================================== projétil (faca / canister)

    interface HitHandler {
        /** @param entity alvo atingido (null = bloco ou fim do alcance) */
        void onHit(Flight f, Vec3 point, LivingEntity entity);
    }

    static final class Flight implements AgentLogic.Effect {
        final ServerLevel level;
        final UUID owner;
        final String kind; // "knife" | "canister"
        final UUID display;
        final double scale;
        final double gravity;
        final double maxRange;
        final int maxAge;
        final HitHandler handler;
        Vec3 pos, vel;
        double travelled = 0;
        int age = 0;
        boolean stuck = false;
        int stuckLeft = 0;
        Vec3 stuckAt;

        Flight(ServerLevel level, UUID owner, String kind, Vec3 pos, Vec3 vel, double gravity, double scale,
               double maxRange, int maxAge, HitHandler handler) {
            this.level = level;
            this.owner = owner;
            this.kind = kind;
            this.pos = pos;
            this.vel = vel;
            this.gravity = gravity;
            this.scale = scale;
            this.maxRange = maxRange;
            this.maxAge = maxAge;
            this.handler = handler;
            double[] q = aimQuat(vel);
            this.display = FxDisplays.spawn(level.getServer(), level, pos, mdl(kind), scale, q[0], q[1], q[2], q[3], 2);
        }

        @Override
        public boolean tick(MinecraftServer server) {
            if (stuck) {
                if (--stuckLeft == 8) FxDisplays.set(server, level, display, aimQuat(vel), ID, 0.02, 0.02, 0.02, 8);
                if (stuckLeft <= 0) {
                    FxDisplays.kill(level, display);
                    return false;
                }
                return true;
            }
            ServerPlayer p = server.getPlayerList().getPlayer(owner);
            if (p == null || !p.isAlive()) {
                FxDisplays.kill(level, display);
                return false;
            }
            age++;
            vel = vel.add(0, -gravity, 0);
            Vec3 from = pos, to = from.add(vel);
            BlockHitResult block = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
            boolean blockHit = block.getType() != HitResult.Type.MISS;
            Vec3 stop = blockHit ? block.getLocation() : to;

            LivingEntity target = null;
            Vec3 targetPoint = null;
            double best = Double.MAX_VALUE;
            AABB search = new AABB(from, stop).inflate(1.0);
            for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, search, x -> AgentLogic.isFoe(p, x))) {
                Optional<Vec3> hit = e.getBoundingBox().inflate(0.12).clip(from, stop);
                if (hit.isEmpty()) continue;
                double d = from.distanceToSqr(hit.get());
                if (d < best) {
                    best = d;
                    target = e;
                    targetPoint = hit.get();
                }
            }

            if (target != null) { // acertou uma entidade
                pos = targetPoint;
                FxDisplays.teleport(server, level, display, pos.x, pos.y, pos.z);
                handler.onHit(this, targetPoint, target);
                if (!stuck) FxDisplays.kill(level, display);
                return stuck;
            }
            if (blockHit) { // bateu em bloco
                pos = stop;
                handler.onHit(this, stop, null);
                if (kind.equals("knife") && !stuck) {
                    stuck = true;
                    stuckLeft = 34;
                    Vec3 dir = vel.normalize();
                    stuckAt = stop.subtract(dir.scale(0.28));
                    FxDisplays.teleport(server, level, display, stuckAt.x, stuckAt.y, stuckAt.z);
                    return true;
                }
                FxDisplays.kill(level, display);
                return false;
            }
            pos = to;
            travelled += vel.length();
            FxDisplays.teleport(server, level, display, pos.x, pos.y, pos.z);
            if (kind.equals("canister")) { // tombando no ar, sempre alinhado à trajetória
                double[] spin = FxDisplays.quat(1, 0, 0, age * 0.62);
                FxDisplays.set(server, level, display, aimQuat(vel), spin, scale, scale, scale, 1);
            }
            if (age % 2 == 0) {
                level.sendParticles(kind.equals("knife") ? ParticleTypes.END_ROD : ParticleTypes.CLOUD,
                        pos.x, pos.y, pos.z, 1, 0.02, 0.02, 0.02, 0.0);
            }
            if (travelled >= maxRange || age >= maxAge) { // fim do alcance
                handler.onHit(this, pos, null);
                if (kind.equals("knife") && !stuck) { // some em faíscas
                    level.sendParticles(ParticleTypes.CRIT, pos.x, pos.y, pos.z, 6, 0.12, 0.12, 0.12, 0.05);
                }
                FxDisplays.kill(level, display);
                return false;
            }
            return true;
        }
    }

    // ================================================================== faca da Blade Storm

    private static final double ORBIT_R = 1.35;
    private static final double KNIFE_ORBIT_SCALE = 0.55;
    private static final double KNIFE_FLIGHT_SCALE = 0.75;

    /** Facas flutuando individualmente ao redor do jogador enquanto o modo está ativo; sincroniza com a munição. */
    static final class Orbit implements AgentLogic.Effect {
        static final Map<UUID, Orbit> BY_OWNER = new HashMap<>();
        final ServerLevel level;
        final UUID owner;
        final int slots;
        final List<UUID> knives = new ArrayList<>();
        final List<Integer> bornAt = new ArrayList<>();
        int age = 0;

        Orbit(ServerLevel level, UUID owner, int slots) {
            this.level = level;
            this.owner = owner;
            this.slots = slots;
        }

        Vec3 slotPos(ServerPlayer p, int i) {
            double a = age * 0.095 + i * (2 * Math.PI / slots);
            double bob = 0.09 * Math.sin(age * 0.13 + i * 1.7);
            return new Vec3(p.getX() + Math.cos(a) * ORBIT_R, p.getY() + 1.1 + bob, p.getZ() + Math.sin(a) * ORBIT_R);
        }

        Vec3 slotDir(int i) {
            double a = age * 0.095 + i * (2 * Math.PI / slots);
            return new Vec3(Math.cos(a) * 0.38, 1.0, Math.sin(a) * 0.38);
        }

        @Override
        public boolean tick(MinecraftServer server) {
            ServerPlayer p = server.getPlayerList().getPlayer(owner);
            int ammo = AgentLogic.modeAmmo(owner);
            if (p == null || !p.isAlive() || p.level() != level || ammo < 0) {
                for (UUID id : knives) FxDisplays.kill(level, id);
                knives.clear();
                BY_OWNER.remove(owner, this);
                return false;
            }
            while (knives.size() > ammo) {
                FxDisplays.kill(level, knives.remove(knives.size() - 1));
                bornAt.remove(bornAt.size() - 1);
            }
            while (knives.size() < ammo) { // (re)materializa a faca: nasce pequena e cresce
                int i = knives.size();
                Vec3 at = slotPos(p, i);
                double[] q = aimQuat(slotDir(i));
                UUID id = FxDisplays.spawn(server, level, at, mdl("knife"), 0.03, q[0], q[1], q[2], q[3], 3);
                if (id != null) {
                    FxDisplays.set(server, level, id, q, ID, KNIFE_ORBIT_SCALE, KNIFE_ORBIT_SCALE, KNIFE_ORBIT_SCALE, 6);
                }
                knives.add(id);
                bornAt.add(age);
                level.sendParticles(ParticleTypes.END_ROD, at.x, at.y, at.z, 6, 0.1, 0.1, 0.1, 0.03);
            }
            for (int i = 0; i < knives.size(); i++) {
                UUID id = knives.get(i);
                if (id == null) continue;
                Vec3 at = slotPos(p, i);
                FxDisplays.teleport(server, level, id, at.x, at.y, at.z);
                if ((age + i) % 2 == 0 && age - bornAt.get(i) > 7) {
                    double roll = age * 0.07 + i;
                    FxDisplays.set(server, level, id, aimQuat(slotDir(i)), FxDisplays.quat(0, 1, 0, roll),
                            KNIFE_ORBIT_SCALE, KNIFE_ORBIT_SCALE, KNIFE_ORBIT_SCALE, 2);
                }
            }
            if (age % 5 == 0 && !knives.isEmpty()) {
                int i = (age / 5) % knives.size();
                Vec3 at = slotPos(p, i);
                level.sendParticles(ParticleTypes.ELECTRIC_SPARK, at.x, at.y + 0.3, at.z, 1, 0.05, 0.05, 0.05, 0.0);
            }
            age++;
            return true;
        }

        /** Retira a última faca da órbita e devolve de onde ela parte (ou null se não há). */
        Vec3 popKnife(MinecraftServer server, ServerPlayer p) {
            if (knives.isEmpty()) return null;
            int i = knives.size() - 1;
            Vec3 from = slotPos(p, i);
            FxDisplays.kill(level, knives.remove(i));
            bornAt.remove(i);
            return from;
        }
    }

    static void startKnives(ServerPlayer p, ServerLevel level, AbilityDef d) {
        Orbit old = Orbit.BY_OWNER.remove(p.getUUID());
        if (old != null) old.knives.forEach(id -> FxDisplays.kill(level, id));
        Orbit orbit = new Orbit(level, p.getUUID(), d.i("ammo", 5));
        Orbit.BY_OWNER.put(p.getUUID(), orbit);
        AgentLogic.addEffect(orbit);
    }

    /** Arremessa uma faca: sai da órbita, voa até a mira, causa dano ao acertar e crava no bloco. */
    static void throwKnife(ServerPlayer p, ServerLevel level, AbilityDef d) {
        MinecraftServer server = level.getServer();
        Vec3 eye = p.getEyePosition();
        Vec3 look = p.getLookAngle();
        double range = d.d("range", 80);
        Orbit orbit = Orbit.BY_OWNER.get(p.getUUID());
        Vec3 origin = orbit != null ? orbit.popKnife(server, p) : null;
        if (origin == null) origin = eye.add(look.scale(0.6)).add(0, -0.25, 0);
        // converge para o ponto onde o jogador mira (primeiro bloco no caminho)
        BlockHitResult aim = level.clip(new ClipContext(eye, eye.add(look.scale(range)),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
        Vec3 target = aim.getType() == HitResult.Type.MISS ? eye.add(look.scale(range)) : aim.getLocation();
        Vec3 dir = target.subtract(origin);
        if (dir.lengthSqr() < 0.25) dir = look;
        double speed = d.d("proj_speed", 2.6);
        Vec3 vel = dir.normalize().scale(speed);
        UUID ownerId = p.getUUID();
        Flight flight = new Flight(level, ownerId, "knife", origin, vel, 0.0, KNIFE_FLIGHT_SCALE, range + 6,
                (int) (range / speed) + 12, (f, point, entity) -> knifeHit(server, level, d, ownerId, f, point, entity));
        AgentLogic.addEffect(flight);
        AgentLogic.play(level, p.position(), "entity.breeze.shoot", 0.8f, 1.7f);
        AgentLogic.play(level, p.position(), "item.trident.throw", 0.6f, 1.9f);
        level.sendParticles(ParticleTypes.CLOUD, origin.x, origin.y, origin.z, 5, 0.08, 0.08, 0.08, 0.05);
    }

    private static void knifeHit(MinecraftServer server, ServerLevel level, AbilityDef d, UUID ownerId, Flight f,
                                 Vec3 point, LivingEntity entity) {
        ServerPlayer owner = server.getPlayerList().getPlayer(ownerId);
        if (entity != null && owner != null) {
            boolean killed = AgentLogic.shotDamage(level, owner, d, entity, point);
            level.sendParticles(ParticleTypes.CRIT, point.x, point.y, point.z, 10, 0.15, 0.15, 0.15, 0.2);
            level.sendParticles(ParticleTypes.ENCHANTED_HIT, point.x, point.y, point.z, 8, 0.2, 0.2, 0.2, 0.3);
            if (killed && d.b("refill", false)) AgentLogic.refillMode(owner);
        } else {
            level.sendParticles(ParticleTypes.CRIT, point.x, point.y, point.z, 8, 0.1, 0.1, 0.1, 0.15);
            AgentLogic.play(level, point, "block.chain.hit", 0.8f, 1.8f);
        }
    }

    // ================================================================== Cloudburst: canister + nuvem

    static boolean cloudburst(ServerPlayer p, ServerLevel level, AbilityDef d) {
        MinecraftServer server = level.getServer();
        Vec3 eye = p.getEyePosition();
        Vec3 look = p.getLookAngle();
        Vec3 origin = eye.add(look.scale(0.55)).add(0, -0.3, 0);
        double range = d.d("range", 24);
        double speed = 1.5;
        Vec3 vel = look.scale(speed).add(0, 0.12, 0);
        UUID ownerId = p.getUUID();
        Flight flight = new Flight(level, ownerId, "canister", origin, vel, 0.045, 0.62, range + 8, 70,
                (f, point, entity) -> landCanister(server, level, d, ownerId, f, point));
        AgentLogic.addEffect(flight);
        AgentLogic.play(level, p.position(), "entity.snowball.throw", 1.0f, 0.8f);
        AgentLogic.play(level, p.position(), "entity.wind_charge.throw", 0.8f, 1.2f);
        return true;
    }

    private static void landCanister(MinecraftServer server, ServerLevel level, AbilityDef d, UUID ownerId, Flight f, Vec3 point) {
        // cai até o chão sob o ponto de impacto (se bateu numa parede ou em uma entidade)
        Vec3 from = point.subtract(f.vel.normalize().scale(0.3)).add(0, 0.4, 0);
        BlockHitResult down = level.clip(new ClipContext(from, from.add(0, -10, 0),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
                server.getPlayerList().getPlayer(ownerId)));
        Vec3 anchor = down.getType() == HitResult.Type.MISS ? point : down.getLocation();
        AgentLogic.play(level, anchor, "block.glass.break", 0.9f, 1.4f);
        AgentLogic.play(level, anchor, "entity.generic.extinguish_fire", 1.2f, 0.7f);
        AgentLogic.addEffect(new SmokeCloud(level, ownerId, d, anchor));
    }

    /** Nuvem 3D: ~16 tufos (modelo smoke_lump) que brotam em sequência, giram, "respiram" e se dissipam; anel de choque no impacto. */
    static final class SmokeCloud implements AgentLogic.Effect {
        final ServerLevel level;
        final UUID owner;
        final Vec3 anchor;
        final double radius;
        final int duration;
        final List<UUID> lumps = new ArrayList<>();
        final List<Vec3> offsets = new ArrayList<>();
        final List<Double> sizes = new ArrayList<>();
        final List<Integer> delays = new ArrayList<>();
        final List<Double> spins = new ArrayList<>();
        UUID ring;
        int age = 0;

        SmokeCloud(ServerLevel level, UUID owner, AbilityDef d, Vec3 anchor) {
            this.level = level;
            this.owner = owner;
            this.anchor = anchor;
            this.radius = d.d("radius", 3.2);
            this.duration = Math.max(40, (int) (d.d("duration", 7.5) * 20));
            Random rnd = new Random((long) (anchor.x * 31 + anchor.z * 17) ^ 0x5EEDL);
            int n = 16;
            for (int i = 0; i < n; i++) {
                double a = rnd.nextDouble() * Math.PI * 2;
                double rho = radius * 0.62 * Math.sqrt((i + 0.5) / n) * (0.8 + 0.4 * rnd.nextDouble());
                double h = 0.5 + (radius * 0.55) * (1 - rho / (radius * 0.9)) * (0.4 + rnd.nextDouble() * 0.9);
                offsets.add(new Vec3(Math.cos(a) * rho, h, Math.sin(a) * rho));
                sizes.add(3.2 + rnd.nextDouble() * 1.8 + (1 - rho / radius) * 0.8);
                delays.add((int) (rho / radius * 5) + rnd.nextInt(3));
                spins.add((rnd.nextDouble() - 0.5) * 0.06);
            }
            MinecraftServer server = level.getServer();
            // impacto: anel de choque + estouro de partículas
            ring = FxDisplays.spawn(server, level, anchor.add(0, 0.12, 0), mdl("shock_ring"), 0.4);
            if (ring != null) FxDisplays.set(server, level, ring, ID, ID, radius * 2.4, 0.4, radius * 2.4, 7);
            level.sendParticles(ParticleTypes.POOF, anchor.x, anchor.y + 0.3, anchor.z, 26, radius * 0.35, 0.25, radius * 0.35, 0.12);
            level.sendParticles(ParticleTypes.CLOUD, anchor.x, anchor.y + 0.5, anchor.z, 18, radius * 0.4, 0.3, radius * 0.4, 0.08);
        }

        @Override
        public boolean tick(MinecraftServer server) {
            if (age == 8 && ring != null) FxDisplays.set(server, level, ring, ID, ID, radius * 2.6, 0.02, radius * 2.6, 6);
            if (age == 15 && ring != null) {
                FxDisplays.kill(level, ring);
                ring = null;
            }
            for (int i = 0; i < offsets.size(); i++) {
                int born = delays.get(i);
                if (age == born) {
                    Vec3 o = offsets.get(i);
                    UUID id = FxDisplays.spawn(server, level, anchor.add(o.x * 0.3, 0.2, o.z * 0.3), mdl("smoke_lump"), 0.1,
                            0, 0, 0, 1, 5);
                    lumps.add(id);
                    if (id != null) {
                        FxDisplays.teleport(server, level, id, anchor.x + o.x, anchor.y + o.y, anchor.z + o.z);
                        double s = sizes.get(i);
                        FxDisplays.set(server, level, id, FxDisplays.quat(0, 1, 0, i * 0.9), ID, s, s * 0.9, s, 9);
                    }
                } else if (age > born + 10 && age < duration - 26 && (age + i) % 6 == 0) {
                    UUID id = i < lumps.size() ? lumps.get(i) : null;
                    if (id != null) {
                        double breathe = 1.0 + 0.06 * Math.sin(age * 0.11 + i * 1.3);
                        double s = sizes.get(i) * breathe;
                        FxDisplays.set(server, level, id, FxDisplays.quat(0.3, 1, 0.2, age * spins.get(i) + i), ID, s, s * 0.9, s, 6);
                    }
                } else if (age == duration - 26 && i < lumps.size()) {
                    UUID id = lumps.get(i);
                    if (id != null) {
                        Vec3 o = offsets.get(i);
                        FxDisplays.teleport(server, level, id, anchor.x + o.x * 1.1, anchor.y + o.y + 0.9, anchor.z + o.z * 1.1);
                        FxDisplays.set(server, level, id, FxDisplays.quat(0, 1, 0, age * 0.05), ID, 0.05, 0.05, 0.05, 24);
                    }
                }
            }
            if (age % 12 == 0 && age < duration - 20) {
                level.sendParticles(ParticleTypes.LARGE_SMOKE, anchor.x, anchor.y + 0.4, anchor.z, 3, radius * 0.4, 0.2, radius * 0.4, 0.01);
            }
            age++;
            if (age >= duration) {
                for (UUID id : lumps) FxDisplays.kill(level, id);
                lumps.clear();
                FxDisplays.kill(level, ring);
                AgentLogic.play(level, anchor, "block.fire.extinguish", 0.5f, 1.4f);
                return false;
            }
            return true;
        }
    }

    // ================================================================== vento (Tailwind / Updraft)

    static void afterActivate(ServerPlayer p, ServerLevel level, AbilityDef d, float dirX, float dirZ) {
        String vfx = d.s("vfx", "");
        if (vfx.equals("tailwind")) {
            double len = Math.sqrt(dirX * dirX + dirZ * dirZ);
            double dx = len < 0.1 ? -Math.sin(Math.toRadians(p.getYRot())) : dirX / len;
            double dz = len < 0.1 ? Math.cos(Math.toRadians(p.getYRot())) : dirZ / len;
            AgentLogic.addEffect(new Wind(level, p.getUUID(), true, dx, dz));
        } else if (vfx.equals("updraft")) {
            AgentLogic.addEffect(new Wind(level, p.getUUID(), false, 0, 0));
        }
    }

    /** Vento em modelo 3D: riscos de vento que acompanham o dash, ou hélice que sobe com o impulso, mais anel de choque no chão. */
    static final class Wind implements AgentLogic.Effect {
        final ServerLevel level;
        final UUID owner;
        final boolean dash;
        final double dx, dz;
        final List<UUID> parts = new ArrayList<>();
        UUID ring;
        int age = 0;
        final int life;

        Wind(ServerLevel level, UUID owner, boolean dash, double dx, double dz) {
            this.level = level;
            this.owner = owner;
            this.dash = dash;
            this.dx = dx;
            this.dz = dz;
            this.life = dash ? 12 : 18;
            ServerPlayer p = level.getServer().getPlayerList().getPlayer(owner);
            if (p == null) return;
            MinecraftServer server = level.getServer();
            Vec3 at = p.position();
            if (dash) {
                double[] q = zToQuat(dx, dz);
                for (int i = 0; i < 2; i++) {
                    UUID id = FxDisplays.spawn(server, level, at.add(0, 0.9 + 0.35 * i, 0), mdl("wind_streak"), 0.2, q[0], q[1], q[2], q[3], 2);
                    parts.add(id);
                    if (id != null) FxDisplays.set(server, level, id, q, ID, 2.6 - 0.5 * i, 2.6 - 0.5 * i, 2.6 - 0.5 * i, 3);
                }
            } else {
                UUID id = FxDisplays.spawn(server, level, at.add(0, -0.1, 0), mdl("wind_spiral"), 0.3, 0, 0, 0, 1, 2);
                parts.add(id);
                if (id != null) FxDisplays.set(server, level, id, FxDisplays.quat(0, 1, 0, 2.5), ID, 2.4, 2.4, 2.4, 6);
            }
            ring = FxDisplays.spawn(server, level, at.add(0, 0.1, 0), mdl("shock_ring"), 0.3);
            if (ring != null) FxDisplays.set(server, level, ring, ID, ID, dash ? 3.4 : 4.4, 0.3, dash ? 3.4 : 4.4, 6);
        }

        @Override
        public boolean tick(MinecraftServer server) {
            ServerPlayer p = server.getPlayerList().getPlayer(owner);
            if (p == null || !p.isAlive() || p.level() != level || age >= life) {
                for (UUID id : parts) FxDisplays.kill(level, id);
                FxDisplays.kill(level, ring);
                return false;
            }
            if (age == 6 && ring != null) {
                FxDisplays.kill(level, ring);
                ring = null;
            }
            Vec3 at = p.position();
            for (int i = 0; i < parts.size(); i++) {
                UUID id = parts.get(i);
                if (id == null) continue;
                if (dash) {
                    FxDisplays.teleport(server, level, id, at.x - dx * (0.9 + 0.5 * i), at.y + 0.9 + 0.35 * i, at.z - dz * (0.9 + 0.5 * i));
                    if (age == 5) {
                        double[] q = zToQuat(dx, dz);
                        FxDisplays.set(server, level, id, q, ID, 0.1, 0.1, 0.1, 7);
                    }
                } else {
                    FxDisplays.teleport(server, level, id, at.x, at.y - 0.1, at.z);
                    double s = age < 10 ? 2.4 + age * 0.12 : Math.max(0.1, 3.6 - (age - 10) * 0.45);
                    FxDisplays.set(server, level, id, FxDisplays.quat(0, 1, 0, 2.5 + age * 0.55), ID, s, s * 1.15, s, 1);
                }
            }
            age++;
            return true;
        }
    }
}
