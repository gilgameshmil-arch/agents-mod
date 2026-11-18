package dev.openai.agentsmod;

import com.mojang.serialization.Codec;
import dev.openai.agentsmod.Defs.AbilityDef;
import dev.openai.agentsmod.Defs.AgentDef;
import dev.openai.agentsmod.Defs.EffectSpec;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Lógica de servidor (autoritativa) de TODAS as habilidades. Cada habilidade é uma linha de agents.json com um tipo
 * (DASH, LEAP, BLINK, AREA, ZONE, WALL, BEAM, MODE) e parâmetros; aqui há uma implementação por tipo.
 * Os visuais 3D são entidades item_display reais (visíveis a todos, também em multiplayer); as partículas saem do servidor.
 */
public final class AgentLogic {
    private AgentLogic() {}

    private static Identifier id(String path) { return Identifier.fromNamespaceAndPath(AgentsMod.MOD_ID, path); }

    /** Um bit por habilidade (índice global), em palavras de 32 bits. Persistente no jogador e mantido ao morrer. */
    public static final AttachmentType<List<Integer>> UNLOCKS = AttachmentRegistry.create(
            id("unlocks"), builder -> builder.persistent(Codec.INT.listOf()).copyOnDeath());

    /** Id do agente equipado ("" = nenhum). */
    public static final AttachmentType<String> SELECTED = AttachmentRegistry.create(
            id("selected"), builder -> builder.persistent(Codec.STRING).copyOnDeath());

    private static final Map<UUID, long[]> READY_AT = new HashMap<>();
    private static final Map<UUID, Dash> DASHES = new HashMap<>();
    private static final Map<UUID, ModeState> MODES = new HashMap<>();
    private static final List<Effect> EFFECTS = new ArrayList<>();
    /** Recarga a aplicar após a ativação atual (-1 = a recarga normal da habilidade). */
    private static int cdOverride = -1;
    private static final Map<UUID, Integer> KILLS = new HashMap<>();
    private static final Map<UUID, Ndy> NDY = new HashMap<>();
    private static final Map<UUID, Anchor> ANCHORS = new HashMap<>();
    private static Vec3 lastImpact = null;

    /** "Not Dead Yet": o jogador fica de pé como fantasma por alguns segundos; abater alguém o traz de volta. */
    private static final class Ndy {
        int ticksLeft;
        final AbilityDef def;
        Ndy(AbilityDef def) { this.def = def; this.ticksLeft = (int) (def.d("ndy_time", 12) * 20); }
    }

    /** Âncora de Rendezvous: marca um ponto; a segunda ativação volta até ele. */
    private static final class Anchor {
        final ServerLevel level;
        final Vec3 pos;
        final AbilityDef def;
        final UUID display;
        int ticksLeft;
        int age = 0;
        Anchor(ServerLevel level, Vec3 pos, AbilityDef def, UUID display) {
            this.level = level; this.pos = pos; this.def = def; this.display = display;
            this.ticksLeft = (int) (def.d("anchor_time", 45) * 20);
        }
    }
    /** Efeitos criados enquanto EFFECTS é percorrido entram aqui e são movidos depois. */
    private static final List<Effect> PENDING = new ArrayList<>();
    private static final List<WallEffect> WALLS = new ArrayList<>();
    private static final Map<String, SoundEvent> SOUNDS = new HashMap<>();
    private static final Map<String, ParticleOptions> PARTICLES = new HashMap<>();
    private static long now = 0;

    static void addEffect(Effect e) { PENDING.add(e); }

    static long now() { return now; }

    public static void init() {
        FxDisplays.init();
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            now++;
            tickDashes(server);
            tickModes(server);
            tickNdy(server);
            tickAnchors(server);
            EFFECTS.removeIf(effect -> !effect.tick(server));
            EFFECTS.addAll(PENDING);
            PENDING.clear();
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            for (WallEffect w : new ArrayList<>(WALLS)) w.restore();
            WALLS.clear();
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            EFFECTS.clear();
            PENDING.clear();
            DASHES.clear();
            MODES.clear();
            KILLS.clear();
            NDY.clear();
            ANCHORS.clear();
            READY_AT.clear();
            now = 0;
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> sync(handler.player));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            READY_AT.remove(handler.player.getUUID());
            DASHES.remove(handler.player.getUUID());
            MODES.remove(handler.player.getUUID());
            KILLS.remove(handler.player.getUUID());
            NDY.remove(handler.player.getUUID());
            Anchor an = ANCHORS.remove(handler.player.getUUID());
            if (an != null) FxDisplays.kill(an.level, an.display);
        });
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) ->
                !(entity instanceof ServerPlayer sp && NDY.containsKey(sp.getUUID())));
        ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) ->
                !(entity instanceof ServerPlayer sp) || !tryNotDeadYet(sp));
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (source.getEntity() instanceof ServerPlayer killer && entity != killer) onKill(killer);
        });
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> sync(newPlayer));
    }

    // ------------------------------------------------------------------ desbloqueio e seleção

    private static List<Integer> words(ServerPlayer p) {
        return p.getAttachedOrElse(UNLOCKS, List.of());
    }

    public static boolean isUnlocked(ServerPlayer p, AbilityDef d) {
        List<Integer> w = words(p);
        int word = d.index >> 5;
        return word < w.size() && (w.get(word) & (1 << (d.index & 31))) != 0;
    }

    /** Só se desbloqueia habilidade do agente ativo; sem agente ativo, o primeiro desbloqueio ativa aquele agente. */
    public static boolean canUnlock(ServerPlayer p, AbilityDef d) {
        AgentDef cur = selectedAgent(p);
        return cur == null || cur.index == d.agent.index;
    }

    public static void unlock(ServerPlayer p, AbilityDef d) {
        List<Integer> old = words(p);
        List<Integer> next = new ArrayList<>();
        for (int i = 0; i < Defs.words(); i++) next.add(i < old.size() ? old.get(i) : 0);
        int word = d.index >> 5;
        next.set(word, next.get(word) | (1 << (d.index & 31)));
        p.setAttached(UNLOCKS, next);
        if (selectedAgent(p) == null) p.setAttached(SELECTED, d.agent.id); // primeiro desbloqueio ativa o agente
        sync(p);
    }

    public static AgentDef selectedAgent(ServerPlayer p) {
        return Defs.agent(p.getAttachedOrElse(SELECTED, ""));
    }

    /**
     * Troca de agente. Só é permitida sem agente ativo (depois de usar o Contrato de Reatribuição); escolher o agente
     * que já está ativo é aceito. Habilidades desbloqueadas ficam salvas por agente, e só as do agente ativo funcionam.
     */
    public static void select(ServerPlayer p, int agentIndex) {
        if (agentIndex < -1 || agentIndex >= Defs.AGENTS.size()) return;
        AgentDef current = selectedAgent(p);
        AgentDef agent = Defs.agent(agentIndex);
        if (current != null && (agent == null || agent.index != current.index)) {
            p.displayClientMessage(Component.translatable("message.agentsmod.need_contract"), true);
            sync(p);
            return;
        }
        if (agent == null) return; // sem agente e pedindo "nenhum": nada a fazer
        p.setAttached(SELECTED, agent.id);
        p.displayClientMessage(Component.translatable("message.agentsmod.equipped", Component.translatable(agent.nameKey())), true);
        sync(p);
    }

    /** Contrato de Reatribuição: retira o agente atual (habilidades continuam salvas) e libera a escolha de outro. */
    public static boolean retireAgent(ServerPlayer p) {
        if (selectedAgent(p) == null) return false;
        p.setAttached(SELECTED, "");
        endModes(p);
        DASHES.remove(p.getUUID());
        sync(p);
        return true;
    }

    private static void endModes(ServerPlayer p) {
        MODES.remove(p.getUUID());
        Fx3D.Orbit o = Fx3D.Orbit.BY_OWNER.remove(p.getUUID());
        if (o != null) {
            o.knives.forEach(id -> FxDisplays.kill((ServerLevel) p.level(), id));
            o.knives.clear();
        }
    }

    /** Munição do modo de tiro ativo (-1 = sem modo). */
    public static int modeAmmo(UUID owner) {
        ModeState m = MODES.get(owner);
        return m == null ? -1 : m.ammo;
    }

    /** Abate com Blade Storm devolve toda a munição e renova o tempo. */
    public static void refillMode(ServerPlayer p) {
        ModeState m = MODES.get(p.getUUID());
        if (m == null || !m.def.b("refill", false)) return;
        m.ammo = m.def.i("ammo", 1);
        m.ticksLeft = (int) (m.def.d("duration", 15) * 20);
        play((ServerLevel) p.level(), p.position(), "entity.player.levelup", 0.8f, 1.5f);
        sync(p);
    }

    /** Dano de um tiro/projétil: cabeça = multiplicador ou morte instantânea; corpo = dano fixo ou fração da vida. */
    static boolean shotDamage(ServerLevel level, ServerPlayer p, AbilityDef d, LivingEntity target, Vec3 point) {
        Shot s = Shot.of(d);
        boolean head = point.y >= target.getEyeY() - 0.25;
        float body = s.bodyDmg() > 0 ? (float) s.bodyDmg()
                : (float) Math.ceil(target.getMaxHealth() / (double) Math.max(1, s.bodyHits()));
        float damage = head ? (s.headKill() ? 1000.0f : (float) (body * s.headMult())) : body;
        hurt(level, p, target, damage);
        boolean killed = target.isDeadOrDying();
        if (!killed) addEffects(target, d.effects("effects"), 0);
        play(level, point, head ? "entity.player.attack.crit" : "entity.arrow.hit_player", 1.0f, head ? 1.3f : 1.0f);
        return killed;
    }

    // ------------------------------------------------------------------ ativação

    public static void tryActivate(ServerPlayer p, int slot, float dirX, float dirZ) {
        AgentDef agent = selectedAgent(p);
        if (agent == null || slot < 0 || slot > 3 || p.isSpectator() || !p.isAlive()) return;
        AbilityDef d = agent.abilities[slot];
        if (!isUnlocked(p, d)) {
            sync(p);
            return;
        }
        long[] ready = READY_AT.computeIfAbsent(p.getUUID(), k -> new long[Defs.ABILITIES.size()]);
        if (ready[d.index] > now) return; // em recarga: o servidor decide, nunca o cliente
        if (d.b("passive", false)) {
            p.displayClientMessage(Component.translatable("message.agentsmod.passive"), true);
            sync(p);
            return;
        }
        if (d.b("needs_kill", false) && KILLS.getOrDefault(p.getUUID(), 0) <= 0) {
            p.displayClientMessage(Component.translatable("message.agentsmod.need_kill"), true);
            sync(p);
            return;
        }
        cdOverride = -1;

        ServerLevel level = (ServerLevel) p.level();
        boolean ok = switch (d.type) {
            case DASH -> dash(p, level, d, dirX, dirZ);
            case LEAP -> leap(p, level, d);
            case BLINK -> blink(p, level, d);
            case AREA -> area(p, level, d);
            case ZONE -> zone(p, level, d);
            case WALL -> wall(p, level, d);
            case BEAM -> beam(p, level, d);
            case MODE -> mode(p, level, d);
        };
        if (ok) {
            ready[d.index] = now + (cdOverride >= 0 ? cdOverride : d.cooldownTicks);
            cdOverride = -1;
            if (d.b("needs_kill", false)) KILLS.merge(p.getUUID(), -1, Integer::sum);
            addEffects(p, d.effects("self_fx"), 0);
            if (d.has("launch_up") || d.has("launch_fwd")) launch(p, d.d("launch_up", 0), d.d("launch_fwd", 0));
            Fx3D.afterActivate(p, level, d, dirX, dirZ);
        }
        sync(p);
    }

    // ------------------------------------------------------------------ utilitários

    static SoundEvent sound(String path) {
        return SOUNDS.computeIfAbsent(path,
                k -> SoundEvent.createVariableRangeEvent(Identifier.fromNamespaceAndPath("minecraft", k)));
    }

    static void play(ServerLevel level, Vec3 at, String path, float volume, float pitch) {
        level.playSound(null, at.x, at.y, at.z, sound(path), SoundSource.PLAYERS, volume, pitch);
    }

    static ParticleOptions particle(AbilityDef d) {
        return PARTICLES.computeIfAbsent(d.s("particle", "cloud"), k -> {
            Object type = BuiltInRegistries.PARTICLE_TYPE.getValue(Identifier.fromNamespaceAndPath("minecraft", k));
            return type instanceof SimpleParticleType simple ? simple : ParticleTypes.CLOUD;
        });
    }

    static void puff(ServerLevel level, ParticleOptions pt, Vec3 c, int count, double spread, double speed) {
        level.sendParticles(pt, c.x, c.y, c.z, count, spread, spread, spread, speed);
    }

    static void ring(ServerLevel level, ParticleOptions pt, Vec3 c, double r, int n, double yOff) {
        for (int i = 0; i < n; i++) {
            double a = 2 * Math.PI * i / n;
            level.sendParticles(pt, c.x + Math.cos(a) * r, c.y + yOff, c.z + Math.sin(a) * r, 1, 0, 0.05, 0, 0.01);
        }
    }

    static String model(AbilityDef d, String shape) {
        return AgentsMod.MOD_ID + ":fx/" + shape + "_" + d.agent.id;
    }

    static double smooth(double t) {
        t = Math.max(0, Math.min(1, t));
        return t * t * (3 - 2 * t);
    }

    static String num(double v) { return String.format(Locale.ROOT, "%.3f", v); }

    /** Centro do modelo 3D para um modelo de raio 0.5 (escala = tamanho em blocos) apoiado no chão. */
    static Vec3 visualCenter(String shape, Vec3 ground, double size) {
        return switch (shape) {
            case "orb" -> ground.add(0, size * 0.375, 0);
            case "dome", "disc", "ring", "blade_ring" -> ground.add(0, 0.15, 0);
            default -> ground.add(0, size * 0.5 + 0.1, 0);
        };
    }

    /** Ponto no chão onde o jogador mira (até 'range' blocos). */
    static Vec3 aimGround(ServerPlayer p, ServerLevel level, double range) {
        Vec3 eye = p.getEyePosition();
        Vec3 look = p.getLookAngle();
        BlockHitResult hit = level.clip(new ClipContext(eye, eye.add(look.scale(range)),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
        Vec3 target = hit.getLocation();
        if (hit.getType() != HitResult.Type.MISS) target = target.subtract(look.scale(0.3));
        Vec3 from = target.add(0, 0.6, 0);
        BlockHitResult down = level.clip(new ClipContext(from, from.add(0, -24.0, 0),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
        return down.getType() == HitResult.Type.MISS ? target : down.getLocation();
    }

    /** Inimigo: monstros hostis e, com PvP ligado, jogadores fora da sua equipe. */
    static boolean isFoe(ServerPlayer owner, LivingEntity e) {
        if (e == owner || !e.isAlive() || e.isSpectator()) return false;
        if (e instanceof Player pl) {
            return !pl.isCreative() && owner.canHarmPlayer(pl);
        }
        if (e instanceof Enemy) return true;
        if (e instanceof AbstractVillager || e instanceof IronGolem || e instanceof ArmorStand) return false;
        if (e instanceof TamableAnimal t && t.isOwnedBy(owner)) return false;
        return e instanceof Mob;
    }

    /** Aliado: o próprio jogador e outros jogadores (todos, se o PvP está desligado; senão, só a equipe). */
    static boolean isAlly(ServerPlayer owner, LivingEntity e) {
        if (!(e instanceof Player pl) || !e.isAlive() || e.isSpectator()) return false;
        return e == owner || !owner.canHarmPlayer(pl);
    }

    static List<LivingEntity> near(ServerLevel level, Vec3 c, double r) {
        List<LivingEntity> list = level.getEntitiesOfClass(LivingEntity.class, new AABB(c, c).inflate(r), e -> e.isAlive());
        list.removeIf(e -> e.getBoundingBox().getCenter().distanceToSqr(c) > r * r);
        return list;
    }

    static void addEffects(LivingEntity e, List<EffectSpec> list, int maxTicks) {
        for (EffectSpec s : list) {
            Holder<MobEffect> holder = BuiltInRegistries.MOB_EFFECT
                    .get(Identifier.fromNamespaceAndPath("minecraft", s.id())).orElse(null);
            if (holder == null) continue;
            int ticks = maxTicks > 0 ? Math.min(maxTicks, s.ticks()) : s.ticks();
            e.addEffect(new MobEffectInstance(holder, ticks, s.amplifier(), false, true));
        }
    }

    static void hurt(ServerLevel level, ServerPlayer owner, LivingEntity target, float damage) {
        if (damage <= 0) return;
        DamageSource source = level.damageSources().indirectMagic(owner, owner); // ignora armadura; o crédito é do jogador
        target.invulnerableTime = 0; // sem isso, golpes seguidos seriam ignorados pelo tempo de invulnerabilidade
        target.hurtServer(level, source, damage);
    }

    static void launch(ServerPlayer p, double up, double fwd) {
        Vec3 v = p.getDeltaMovement();
        double yaw = Math.toRadians(p.getYRot());
        p.setDeltaMovement(v.x + -Math.sin(yaw) * fwd, up > 0 ? up : v.y, v.z + Math.cos(yaw) * fwd);
        p.hurtMarked = true; // força o envio da nova velocidade ao cliente do jogador
        p.fallDistance = 0;
    }

    /**
     * Aplica o "pulso" de uma habilidade de área: inimigos recebem dano/efeitos/empurrão; aliados e o dono recebem cura/efeitos.
     * effectTicks > 0 limita a duração dos efeitos (zonas renovam o efeito a cada pulso).
     */
    static void pulse(ServerLevel level, ServerPlayer owner, AbilityDef d, Vec3 center, double radius,
                              double damage, double heal, int effectTicks) {
        List<EffectSpec> foeFx = d.effects("effects");
        List<EffectSpec> allyFx = d.effects("ally_effects");
        double knock = d.d("knock", 0), lift = d.d("lift", 0);
        int ignite = (int) Math.round(d.d("ignite", 0) * 20);
        boolean facing = d.b("facing", false);
        for (LivingEntity e : near(level, center, radius)) {
            if (isFoe(owner, e)) {
                if (facing) {
                    Vec3 toPoint = center.subtract(e.getEyePosition());
                    if (toPoint.lengthSqr() > 1e-4 && e.getLookAngle().dot(toPoint.normalize()) < 0.25) continue;
                }
                hurt(level, owner, e, (float) damage);
                if (e.isAlive()) {
                    addEffects(e, foeFx, effectTicks);
                    if (ignite > 0) e.setRemainingFireTicks(ignite);
                    if (knock != 0 || lift != 0) {
                        Vec3 away = e.position().subtract(center);
                        double len = Math.sqrt(away.x * away.x + away.z * away.z);
                        double dx = len < 1e-3 ? 0 : away.x / len, dz = len < 1e-3 ? 0 : away.z / len;
                        Vec3 v = e.getDeltaMovement();
                        e.setDeltaMovement(v.x + dx * knock, v.y + lift, v.z + dz * knock);
                        e.hurtMarked = true;
                    }
                }
            } else if (isAlly(owner, e)) {
                if (heal > 0) e.heal((float) heal);
                addEffects(e, allyFx, effectTicks);
            }
        }
    }

    // ------------------------------------------------------------------ DASH

    private static final class Dash {
        final double dx, dz, speed, up;
        final AbilityDef def;
        int left;

        Dash(double dx, double dz, AbilityDef def) {
            this.dx = dx;
            this.dz = dz;
            this.def = def;
            this.speed = def.d("speed", 2.4);
            this.up = def.d("up", 0);
            this.left = def.i("ticks", 5);
        }
    }

    private static boolean dash(ServerPlayer p, ServerLevel level, AbilityDef d, float dirX, float dirZ) {
        double dx = dirX, dz = dirZ;
        double len = Math.sqrt(dx * dx + dz * dz);
        if (!Double.isFinite(len) || len < 0.1 || len > 1.5) {
            double yaw = Math.toRadians(p.getYRot());
            dx = -Math.sin(yaw);
            dz = Math.cos(yaw);
            len = 1.0;
        }
        Dash dash = new Dash(dx / len, dz / len, d);
        DASHES.put(p.getUUID(), dash);
        applyDash(p, dash); // primeiro tick já neste instante
        play(level, p.position(), d.s("sound", "entity.breeze.slide"), 1.0f, 1.5f);
        puff(level, particle(d), p.position().add(0, 0.8, 0), 14, 0.35, 0.05);
        return true;
    }

    private static void applyDash(ServerPlayer p, Dash dash) {
        double speed = dash.left > 1 ? dash.speed : 0.35;
        p.setDeltaMovement(dash.dx * speed, dash.up, dash.dz * speed);
        p.hurtMarked = true;
        p.fallDistance = 0;
        dash.left--;
    }

    private static void tickDashes(MinecraftServer server) {
        Iterator<Map.Entry<UUID, Dash>> it = DASHES.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Dash> entry = it.next();
            ServerPlayer p = server.getPlayerList().getPlayer(entry.getKey());
            Dash dash = entry.getValue();
            if (p == null || !p.isAlive() || dash.left <= 0) {
                it.remove();
                continue;
            }
            applyDash(p, dash);
            ServerLevel level = (ServerLevel) p.level();
            puff(level, particle(dash.def), p.position().add(0, 0.8, 0), 3, 0.25, 0.02);
            if (dash.left <= 0) it.remove();
        }
    }

    // ------------------------------------------------------------------ LEAP / BLINK

    private static boolean leap(ServerPlayer p, ServerLevel level, AbilityDef d) {
        launch(p, d.d("up", 1.0), d.d("fwd", 0));
        play(level, p.position(), d.s("sound", "entity.wind_charge.wind_burst"), 1.0f, 1.1f);
        ring(level, particle(d), p.position(), 1.0, 14, 0.1);
        puff(level, particle(d), p.position().add(0, 0.2, 0), 12, 0.3, 0.1);
        return true;
    }

    private static boolean blink(ServerPlayer p, ServerLevel level, AbilityDef d) {
        if (d.b("anchor", false)) return anchor(p, level, d);
        Vec3 eye = p.getEyePosition();
        Vec3 look = p.getLookAngle();
        double range = d.d("range", 12);
        BlockHitResult hit = level.clip(new ClipContext(eye, eye.add(look.scale(range)),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
        double dist = hit.getType() == HitResult.Type.MISS ? range : eye.distanceTo(hit.getLocation()) - 0.6;
        for (double t = dist; t >= 1.5; t -= 0.5) {
            Vec3 feet = eye.add(look.scale(t)).subtract(0, p.getEyeHeight(), 0);
            AABB box = p.getBoundingBox().move(feet.subtract(p.position()));
            if (!level.noCollision(p, box)) continue;
            Vec3 from = p.position();
            puff(level, particle(d), from.add(0, 1, 0), 30, 0.4, 0.05);
            play(level, from, d.s("sound", "entity.enderman.teleport"), 1.0f, 1.0f);
            FxDisplays.run(level.getServer(), level, "tp " + p.getUUID() + " " + num(feet.x) + " " + num(feet.y) + " " + num(feet.z));
            p.fallDistance = 0;
            puff(level, particle(d), feet.add(0, 1, 0), 30, 0.4, 0.05);
            play(level, feet, d.s("sound", "entity.enderman.teleport"), 1.0f, 1.2f);
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ projétil que voa e depois dispara 'then'

    private static void throwOrb(ServerPlayer p, ServerLevel level, AbilityDef d, Vec3 anchor, Runnable then) {
        MinecraftServer server = level.getServer();
        Vec3 eye = p.getEyePosition();
        Vec3 start = eye.add(p.getLookAngle().scale(0.6)).add(0, -0.25, 0);
        int flight = (int) Math.max(4, Math.min(30, Math.ceil(start.distanceTo(anchor) / 1.4)));
        UUID display = FxDisplays.spawn(server, level, start, model(d, "orb"), 0.45, 0, 0, 0, 1, flight);
        if (display != null) FxDisplays.teleport(server, level, display, anchor.x, anchor.y + 0.3, anchor.z);
        play(level, p.position(), "entity.snowball.throw", 1.0f, 1.0f);
        PENDING.add(new ThrowEffect(level, display, flight, then));
    }

    // ------------------------------------------------------------------ AREA (pulso instantâneo)

    private static boolean area(ServerPlayer p, ServerLevel level, AbilityDef d) {
        boolean self = d.s("center", "self").equals("self");
        Vec3 anchor = self ? p.position() : aimGround(p, level, d.d("range", 20));
        play(level, p.position(), d.s("sound", "entity.firework_rocket.blast"), 0.9f, 1.2f);
        Runnable boom = () -> detonate(level, p, d, anchor);
        if (!self && d.b("throw", false)) throwOrb(p, level, d, anchor, boom);
        else boom.run();
        return true;
    }

    private static void detonate(ServerLevel level, ServerPlayer p, AbilityDef d, Vec3 anchor) {
        double radius = d.d("radius", 4);
        Vec3 center = anchor.add(0, 0.9, 0);
        pulse(level, p, d, center, radius, d.d("damage", 0), d.d("heal", 0), 0);
        ParticleOptions pt = particle(d);
        ring(level, pt, center, Math.min(radius, 14), (int) Math.min(48, 10 + radius * 4), -0.6);
        puff(level, pt, center, 24, Math.min(radius * 0.4, 3.0), 0.08);
        play(level, center, d.s("sound", "entity.firework_rocket.blast"), 1.1f, 1.0f);
        String shape = d.s("shape", "");
        if (shape.isEmpty()) return;
        double size = Math.min(radius * 2, 24.0);
        UUID display = FxDisplays.spawn(level.getServer(), level, visualCenter(shape, anchor, size), model(d, shape), 0.3);
        if (display != null) PENDING.add(new PulseEffect(level, display, size));
    }

    // ------------------------------------------------------------------ ZONE (área que dura)

    /** Parte "de jogo" de uma fumaça que também causa dano/efeitos (ex.: Poison Cloud da Viper). */
    static void smokeZone(ServerLevel level, UUID ownerId, AbilityDef d, Vec3 anchor) {
        PENDING.add(new ZoneEffect(level, ownerId, d, anchor, false, null, 0));
    }

    private static boolean zone(ServerPlayer p, ServerLevel level, AbilityDef d) {
        if (d.s("vfx", "").equals("smoke")) return Fx3D.smoke(p, level, d);
        boolean self = d.s("center", "self").equals("self");
        Vec3 anchor = self ? p.position() : aimGround(p, level, d.d("range", 20));
        boolean follow = d.b("follow", self);
        play(level, p.position(), d.s("sound", "block.beacon.activate"), 0.9f, 1.4f);
        Runnable start = () -> {
            String shape = d.s("shape", "");
            double radius = d.d("radius", 4);
            double size = d.d("size", Math.min(radius * 2, 28.0));
            UUID display = null;
            if (!shape.isEmpty()) {
                display = FxDisplays.spawn(level.getServer(), level, visualCenter(shape, anchor, size), model(d, shape), 0.05);
            }
            play(level, anchor, d.s("sound", "block.beacon.activate"), 1.2f, 0.8f);
            PENDING.add(new ZoneEffect(level, p.getUUID(), d, anchor, follow, display, size));
        };
        if (!self && d.b("throw", false)) throwOrb(p, level, d, anchor, start);
        else start.run();
        return true;
    }

    // ------------------------------------------------------------------ WALL (blocos temporários)

    private static boolean wall(ServerPlayer p, ServerLevel level, AbilityDef d) {
        Block block = BuiltInRegistries.BLOCK.getValue(Identifier.fromNamespaceAndPath("minecraft", d.s("block", "glass")));
        int width = Math.max(1, d.i("width", 5));
        int height = Math.max(1, d.i("height", 3));
        boolean along = d.b("along", false);
        double yaw = Math.toRadians(p.getYRot());
        double fx = -Math.sin(yaw), fz = Math.cos(yaw);
        Vec3 base = along ? p.position().add(fx * 1.5, 0, fz * 1.5) : aimGround(p, level, d.d("range", 12));
        double ax = along ? fx : -fz, az = along ? fz : fx;

        List<BlockPos> placed = new ArrayList<>();
        List<Vec3> centers = new ArrayList<>();
        for (int i = 0; i < width; i++) {
            double off = along ? i + 0.5 : i - (width - 1) / 2.0;
            double x = base.x + ax * off, z = base.z + az * off;
            Vec3 from = new Vec3(x, base.y + 2.5, z);
            BlockHitResult down = level.clip(new ClipContext(from, new Vec3(x, base.y - 4.0, z),
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
            double y = down.getType() == HitResult.Type.MISS ? base.y : down.getLocation().y;
            int bx = Mth.floor(x), bz = Mth.floor(z), by = Mth.floor(y + 0.05);
            for (int h = 0; h < height; h++) {
                BlockPos pos = new BlockPos(bx, by + h, bz);
                if (level.getBlockState(pos).isAir() && !placed.contains(pos)) {
                    level.setBlock(pos, block.defaultBlockState(), 3);
                    placed.add(pos);
                }
            }
            centers.add(new Vec3(bx + 0.5, by + height / 2.0, bz + 0.5));
        }
        if (placed.isEmpty()) return false;
        play(level, p.position(), d.s("sound", "block.glass.place"), 1.0f, 1.0f);
        for (Vec3 c : centers) puff(level, particle(d), c, 6, 0.4, 0.02);
        WallEffect effect = new WallEffect(level, p.getUUID(), d, placed, centers, block, (int) (d.d("duration", 8) * 20));
        WALLS.add(effect);
        PENDING.add(effect);
        return true;
    }

    // ------------------------------------------------------------------ BEAM / MODE (tiros)

    private record Shot(double range, double bodyDmg, int bodyHits, double headMult, boolean headKill,
                        double aoe, double aoeDmg) {
        static Shot of(AbilityDef d) {
            return new Shot(d.d("range", 60), d.d(d.type == Defs.Type.BEAM ? "damage" : "body_dmg", 0),
                    d.i("body_hits", 0), d.d("head", 1.0), d.b("head_kill", false), d.d("aoe", 0), d.d("aoe_damage", 0));
        }
    }

    private static boolean beam(ServerPlayer p, ServerLevel level, AbilityDef d) {
        fire(p, level, d, Shot.of(d));
        return true;
    }

    /** Tiro instantâneo (raio). Cabeça = multiplicador ou morte; o dano mágico ignora armadura e respeita PvP/criativo. */
    private static boolean fire(ServerPlayer p, ServerLevel level, AbilityDef d, Shot s) {
        MinecraftServer server = level.getServer();
        Vec3 eye = p.getEyePosition();
        Vec3 look = p.getLookAngle();
        Vec3 end = eye.add(look.scale(s.range));
        BlockHitResult blockHit = level.clip(new ClipContext(eye, end,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
        Vec3 stop = blockHit.getType() == HitResult.Type.MISS ? end : blockHit.getLocation();

        LivingEntity target = null;
        Vec3 targetPoint = null;
        double best = Double.MAX_VALUE;
        AABB search = new AABB(eye, stop).inflate(1.0);
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, search, x -> isFoe(p, x))) {
            Optional<Vec3> point = e.getBoundingBox().inflate(0.1).clip(eye, stop);
            if (point.isEmpty()) continue;
            double dist = eye.distanceToSqr(point.get());
            if (dist < best) {
                best = dist;
                target = e;
                targetPoint = point.get();
            }
        }

        Vec3 impact = target != null ? targetPoint : stop;
        lastImpact = impact;
        boolean killed = false;
        if (target != null) {
            boolean head = targetPoint.y >= target.getEyeY() - 0.25;
            float body = s.bodyDmg > 0 ? (float) s.bodyDmg
                    : (float) Math.ceil(target.getMaxHealth() / (double) Math.max(1, s.bodyHits));
            float damage = head ? (s.headKill ? 1000.0f : (float) (body * s.headMult)) : body;
            hurt(level, p, target, damage);
            killed = target.isDeadOrDying();
            if (!killed) addEffects(target, d.effects("effects"), 0);
            play(level, impact, head ? "entity.player.attack.crit" : "entity.arrow.hit_player", 1.0f, head ? 1.3f : 1.0f);
        }
        if (s.aoe > 0) {
            pulse(level, p, d, impact, s.aoe, s.aoeDmg, 0, 0);
            puff(level, ParticleTypes.EXPLOSION, impact, 3, s.aoe * 0.3, 0.0);
            play(level, impact, "entity.generic.explode", 1.0f, 1.2f);
            killed = killed || target != null && target.isDeadOrDying();
        }

        // rastro de partículas e projétil 3D deslizando até o ponto de impacto
        ParticleOptions pt = particle(d);
        double total = eye.distanceTo(impact);
        for (double t = 1.0; t < total && t < 70; t += 1.5) {
            Vec3 at = eye.add(look.scale(t));
            level.sendParticles(pt, at.x, at.y - 0.2, at.z, 1, 0, 0, 0, 0);
        }
        Vec3 start = eye.add(look.scale(0.5)).add(0, -0.25, 0);
        Vec3 stuck = impact.subtract(look.scale(0.35));
        int ticks = (int) Math.max(1, Math.min(40, Math.ceil(start.distanceTo(stuck) / 3.5)));
        // quaternion do caminho mais curto que leva o eixo +Y (comprimento do modelo) até a direção do voo
        double cx = -look.z, cy = 0.0, cz = look.x, cw = 1.0 + look.y;
        double norm = Math.sqrt(cx * cx + cy * cy + cz * cz + cw * cw);
        if (norm < 1e-6) {
            cx = 1;
            cy = 0;
            cz = 0;
            cw = 0;
            norm = 1;
        }
        String shape = d.s("shape", "bolt");
        UUID display = FxDisplays.spawn(server, level, start, model(d, shape.isEmpty() ? "bolt" : shape), 0.8,
                cx / norm, cy / norm, cz / norm, cw / norm, ticks);
        if (display != null) {
            FxDisplays.teleport(server, level, display, stuck.x, stuck.y, stuck.z);
            PENDING.add(new TimedKillEffect(level, display, ticks + 25));
        }
        play(level, p.position(), d.s("sound", "entity.arrow.shoot"), 0.9f, 1.4f);
        return killed;
    }

    // ------------------------------------------------------------------ MODE (munição com clique esquerdo)

    private static final class ModeState {
        final AbilityDef def;
        int ammo;
        int ticksLeft;
        int cooldown = 0;
        int reload = 0;

        ModeState(AbilityDef def) {
            this.def = def;
            this.ammo = def.i("ammo", 1);
            this.ticksLeft = (int) (def.d("duration", 15) * 20);
        }
    }

    private static boolean mode(ServerPlayer p, ServerLevel level, AbilityDef d) {
        if (d.b("gun", false)) { // arma equipada: sem recarga de habilidade; apertar de novo guarda a arma
            ModeState cur = MODES.get(p.getUUID());
            cdOverride = 0;
            if (cur != null && cur.def == d) {
                MODES.remove(p.getUUID());
                play(level, p.position(), "item.armor.equip_chain", 0.7f, 0.8f);
                return true;
            }
            MODES.put(p.getUUID(), new ModeState(d));
            play(level, p.position(), "item.armor.equip_iron", 0.9f, 1.3f);
            play(level, p.position(), "block.piston.extend", 0.4f, 1.8f);
            return true;
        }
        MODES.put(p.getUUID(), new ModeState(d));
        if (d.s("vfx", "").equals("knives")) {
            Fx3D.startKnives(p, level, d);
            play(level, p.position(), "block.beacon.activate", 1.0f, 1.4f);
            play(level, p.position(), "item.armor.equip_netherite", 0.8f, 1.6f);
            return true;
        }
        play(level, p.position(), d.s("sound", "block.beacon.activate"), 1.0f, 1.4f);
        ring(level, particle(d), p.position(), 1.4, 20, 1.0);
        if (d.b("ring", false)) {
            String ringShape = d.s("ring_shape", "ring");
            UUID display = FxDisplays.spawn(level.getServer(), level, p.position().add(0, 0.95, 0), model(d, ringShape), 0.05);
            if (display != null) PENDING.add(new RingEffect(level, p.getUUID(), display));
        }
        return true;
    }

    private static void tickModes(MinecraftServer server) {
        Iterator<Map.Entry<UUID, ModeState>> it = MODES.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, ModeState> entry = it.next();
            ServerPlayer p = server.getPlayerList().getPlayer(entry.getKey());
            ModeState m = entry.getValue();
            if (m.cooldown > 0) m.cooldown--;
            if (m.reload > 0 && --m.reload == 0 && p != null) {
                m.ammo = m.def.i("ammo", 1);
                play((ServerLevel) p.level(), p.position(), "item.crossbow.loading_end", 0.9f, 1.2f);
                sync(p);
            }
            if (p == null || !p.isAlive() || --m.ticksLeft <= 0) {
                it.remove();
                if (p != null) sync(p);
            }
        }
    }

    /** Clique de disparo com um modo ativo. Abate pode devolver a munição (refill). */
    public static void tryShoot(ServerPlayer p) {
        ModeState m = MODES.get(p.getUUID());
        if (m == null || m.ammo <= 0 || m.cooldown > 0 || m.reload > 0 || !p.isAlive() || p.isSpectator()) return;
        m.ammo--;
        m.cooldown = m.def.i("shot_cd", 5);
        ServerLevel level = (ServerLevel) p.level();
        if (m.def.s("vfx", "").equals("knives")) {
            Fx3D.throwKnife(p, level, m.def); // a faca voa de verdade; o abate (refill) é resolvido ao acertar
            if (m.ammo <= 0) m.ticksLeft = Math.min(m.ticksLeft, 60); // tempo para as facas no ar acertarem
        } else {
            boolean killed = fire(p, level, m.def, Shot.of(m.def));
            if (killed && m.def.has("kill_radius")) killField(level, p, m.def);
            if (m.def.b("gun", false)) {
                if (m.ammo <= 0) {
                    m.reload = m.def.i("reload", 50);
                    play(level, p.position(), "item.crossbow.loading_start", 0.9f, 1.0f);
                }
            } else if (killed && m.def.b("refill", false)) {
                m.ammo = m.def.i("ammo", 1);
                m.ticksLeft = (int) (m.def.d("duration", 15) * 20);
                play(level, p.position(), "entity.player.levelup", 0.8f, 1.5f);
            } else if (m.ammo <= 0) {
                MODES.remove(p.getUUID());
            }
        }
        sync(p);
    }

    // ------------------------------------------------------------------ abates, Not Dead Yet, âncora

    private static void onKill(ServerPlayer killer) {
        KILLS.merge(killer.getUUID(), 1, (a, b) -> Math.min(3, a + b));
        Ndy n = NDY.remove(killer.getUUID());
        if (n != null) { // abateu enquanto "morta": volta à vida
            killer.setHealth(killer.getMaxHealth() * 0.6f);
            killer.removeEffect(net.minecraft.world.effect.MobEffects.INVISIBILITY);
            play((ServerLevel) killer.level(), killer.position(), "item.totem.use", 0.8f, 1.4f);
            puff((ServerLevel) killer.level(), ParticleTypes.HEART, killer.position().add(0, 1, 0), 14, 0.5, 0.05);
            killer.displayClientMessage(Component.translatable("message.agentsmod.revived"), true);
        }
        sync(killer);
    }

    /** @return true se a morte deve ser cancelada (habilidade passiva "ndy" pronta). */
    private static boolean tryNotDeadYet(ServerPlayer p) {
        if (NDY.containsKey(p.getUUID())) return true;
        AgentDef agent = selectedAgent(p);
        if (agent == null) return false;
        for (AbilityDef d : agent.abilities) {
            if (!d.b("ndy", false) || !isUnlocked(p, d)) continue;
            long[] ready = READY_AT.computeIfAbsent(p.getUUID(), k -> new long[Defs.ABILITIES.size()]);
            if (ready[d.index] > now) return false;
            ready[d.index] = now + d.cooldownTicks;
            NDY.put(p.getUUID(), new Ndy(d));
            p.setHealth(1.0f);
            addEffects(p, d.effects("self_fx"), 0);
            addEffects(p, List.of(new EffectSpec("invisibility", (int) (d.d("ndy_time", 12) * 20), 0),
                    new EffectSpec("speed", (int) (d.d("ndy_time", 12) * 20), 1)), 0);
            ServerLevel level = (ServerLevel) p.level();
            play(level, p.position(), "entity.wither.spawn", 0.4f, 1.8f);
            puff(level, ParticleTypes.SOUL, p.position().add(0, 1, 0), 24, 0.5, 0.06);
            p.displayClientMessage(Component.translatable("message.agentsmod.ndy", (int) d.d("ndy_time", 12)), true);
            sync(p);
            return true;
        }
        return false;
    }

    private static void tickNdy(MinecraftServer server) {
        Iterator<Map.Entry<UUID, Ndy>> it = NDY.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Ndy> e = it.next();
            ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
            if (p == null) { it.remove(); continue; }
            if (--e.getValue().ticksLeft <= 0) {
                it.remove();
                p.kill((ServerLevel) p.level()); // não abateu a tempo: morre de verdade
            } else if (e.getValue().ticksLeft % 8 == 0) {
                puff((ServerLevel) p.level(), ParticleTypes.SOUL, p.position().add(0, 1, 0), 2, 0.3, 0.02);
            }
        }
    }

    /** Rendezvous: 1º uso marca a âncora (sem recarga); 2º uso volta até ela. */
    private static boolean anchor(ServerPlayer p, ServerLevel level, AbilityDef d) {
        MinecraftServer server = level.getServer();
        Anchor a = ANCHORS.get(p.getUUID());
        if (a == null) {
            Vec3 pos = p.position();
            UUID disp = FxDisplays.spawn(server, level, visualCenter("disc", pos, 1.8), model(d, "disc"), 0.2);
            if (disp != null) FxDisplays.animate(server, level, disp, 0, 1, 0, 0, 1.8, 8);
            ANCHORS.put(p.getUUID(), new Anchor(level, pos, d, disp));
            cdOverride = 0;
            play(level, pos, "block.end_portal_frame.fill", 1.0f, 1.2f);
            puff(level, particle(d), pos.add(0, 0.3, 0), 20, 0.4, 0.04);
            p.displayClientMessage(Component.translatable("message.agentsmod.anchor_set"), true);
            return true;
        }
        if (a.level != level || a.pos.distanceTo(p.position()) > d.d("range", 30)) {
            p.displayClientMessage(Component.translatable("message.agentsmod.anchor_far"), true);
            return false;
        }
        Vec3 from = p.position();
        puff(level, particle(d), from.add(0, 1, 0), 30, 0.4, 0.05);
        play(level, from, d.s("sound", "entity.enderman.teleport"), 1.0f, 1.0f);
        FxDisplays.run(server, level, "tp " + p.getUUID() + " " + num(a.pos.x) + " " + num(a.pos.y) + " " + num(a.pos.z));
        p.fallDistance = 0;
        puff(level, particle(d), a.pos.add(0, 1, 0), 30, 0.4, 0.05);
        play(level, a.pos, d.s("sound", "entity.enderman.teleport"), 1.0f, 1.2f);
        FxDisplays.kill(level, a.display);
        ANCHORS.remove(p.getUUID());
        return true;
    }

    private static void tickAnchors(MinecraftServer server) {
        Iterator<Map.Entry<UUID, Anchor>> it = ANCHORS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Anchor> e = it.next();
            Anchor a = e.getValue();
            ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
            if (p == null || !p.isAlive() || --a.ticksLeft <= 0) {
                it.remove();
                FxDisplays.kill(a.level, a.display);
                if (p != null) { // a recarga só começa quando a âncora some
                    READY_AT.computeIfAbsent(p.getUUID(), k -> new long[Defs.ABILITIES.size()])[a.def.index] = now + a.def.cooldownTicks;
                    sync(p);
                }
                continue;
            }
            a.age++;
            if (a.display != null && a.age % 4 == 0) {
                FxDisplays.animate(server, level0(a), a.display, 0, 1, 0, a.age * 0.05, 1.8, 4);
            }
            if (a.age % 10 == 0) puff(a.level, ParticleTypes.REVERSE_PORTAL, a.pos.add(0, 0.6, 0), 3, 0.3, 0.02);
        }
    }

    private static ServerLevel level0(Anchor a) { return a.level; }

    /** Tour de Force: ao abater, abre um campo de lentidão no ponto do tiro. */
    private static void killField(ServerLevel level, ServerPlayer p, AbilityDef d) {
        if (lastImpact == null) return;
        double r = d.d("kill_radius", 6);
        for (LivingEntity e : near(level, lastImpact, r)) if (isFoe(p, e)) addEffects(e, d.effects("kill_effects"), 0);
        ParticleOptions pt = particle(d);
        ring(level, pt, lastImpact, r, 40, 0.1);
        puff(level, pt, lastImpact, 20, r * 0.3, 0.05);
        play(level, lastImpact, "block.beacon.deactivate", 1.0f, 0.6f);
        UUID display = FxDisplays.spawn(level.getServer(), level, visualCenter("disc", lastImpact, r * 2), model(d, "disc"), 0.3);
        if (display != null) PENDING.add(new PulseEffect(level, display, r * 2));
    }

    // ------------------------------------------------------------------ sincronização

    public static void sync(ServerPlayer p) {
        if (!ServerPlayNetworking.canSend(p, Net.StateS2C.TYPE)) return;
        List<Integer> have = words(p);
        List<Integer> unlocked = new ArrayList<>();
        for (int i = 0; i < Defs.words(); i++) unlocked.add(i < have.size() ? have.get(i) : 0);
        AgentDef agent = selectedAgent(p);
        long[] ready = READY_AT.get(p.getUUID());
        List<Integer> cooldowns = new ArrayList<>();
        for (int s = 0; s < 4; s++) {
            long remaining = agent == null || ready == null ? 0 : ready[agent.abilities[s].index] - now;
            cooldowns.add((int) Math.max(0, Math.min(remaining, 1_000_000)));
        }
        ModeState m = MODES.get(p.getUUID());
        int packed = Math.min(7, KILLS.getOrDefault(p.getUUID(), 0)) << 22;
        if (m != null) {
            packed |= (1 << 20) | (m.reload > 0 ? 1 << 21 : 0) | (m.def.slot << 16)
                    | (Math.min(255, m.def.i("ammo", 1)) << 8) | Math.min(255, Math.max(0, m.ammo));
        }
        ServerPlayNetworking.send(p, new Net.StateS2C(agent == null ? -1 : agent.index, unlocked, cooldowns, packed));
    }

    // ------------------------------------------------------------------ efeitos com duração

    interface Effect {
        /** @return false quando o efeito terminou e deve ser removido. */
        boolean tick(MinecraftServer server);
    }

    /** Projétil em voo; ao pousar, dispara a ação. */
    private static final class ThrowEffect implements Effect {
        private final ServerLevel level;
        private final UUID display;
        private final int flight;
        private final Runnable then;
        private int age = 0;

        ThrowEffect(ServerLevel level, UUID display, int flight, Runnable then) {
            this.level = level;
            this.display = display;
            this.flight = flight;
            this.then = then;
        }

        @Override
        public boolean tick(MinecraftServer server) {
            if (age++ < flight) return true;
            FxDisplays.kill(level, display);
            then.run();
            return false;
        }
    }

    /** Modelo 3D do pulso de uma AREA: cresce rápido, segura um instante e some. */
    private static final class PulseEffect implements Effect {
        private final ServerLevel level;
        private final UUID display;
        private final double size;
        private int age = 0;

        PulseEffect(ServerLevel level, UUID display, double size) {
            this.level = level;
            this.display = display;
            this.size = size;
        }

        @Override
        public boolean tick(MinecraftServer server) {
            if (age == 1) FxDisplays.animate(server, level, display, 0, 1, 0, 1.2, size, 6);
            if (age == 9) FxDisplays.animate(server, level, display, 0, 1, 0, 2.4, 0.05, 8);
            if (age >= 18) {
                FxDisplays.kill(level, display);
                return false;
            }
            age++;
            return true;
        }
    }

    /** Zona que dura: modelo 3D cresce, gira e some; a cada 10 ticks aplica o pulso (dano/s e cura/s pela metade). */
    private static final class ZoneEffect implements Effect {
        private final ServerLevel level;
        private final UUID ownerId;
        private final AbilityDef d;
        private final boolean follow;
        private final UUID display;
        private final double size;
        private final int duration;
        private final double radius;
        private final String shape;
        private Vec3 anchor;
        private int age = 0;

        ZoneEffect(ServerLevel level, UUID ownerId, AbilityDef d, Vec3 anchor, boolean follow, UUID display, double size) {
            this.level = level;
            this.ownerId = ownerId;
            this.d = d;
            this.anchor = anchor;
            this.follow = follow;
            this.display = display;
            this.size = size;
            this.duration = Math.max(20, (int) (d.d("duration", 6) * 20));
            this.radius = d.d("radius", 4);
            this.shape = d.s("shape", "");
        }

        @Override
        public boolean tick(MinecraftServer server) {
            ServerPlayer owner = server.getPlayerList().getPlayer(ownerId);
            boolean over = owner == null || !owner.isAlive() || owner.level() != level || age >= duration;
            if (over) {
                FxDisplays.kill(level, display);
                play(level, anchor, "block.beacon.deactivate", 0.7f, 1.2f);
                return false;
            }
            if (follow) anchor = owner.position();
            Vec3 center = anchor.add(0, 0.9, 0);
            if (age % 10 == 0) {
                pulse(level, owner, d, center, radius, d.d("damage", 0) * 0.5, d.d("heal", 0) * 0.5, 30);
            }
            if (age % 6 == 0) {
                ParticleOptions pt = particle(d);
                ring(level, pt, center, Math.min(radius, 12) * 0.9, 12, -0.6);
                puff(level, pt, center, 4, Math.min(radius * 0.35, 2.5), 0.01);
            }
            if (display != null) {
                if (follow && age % 2 == 0 && !shape.isEmpty()) {
                    Vec3 vc = visualCenter(shape, anchor, size);
                    FxDisplays.teleport(server, level, display, vc.x, vc.y, vc.z);
                }
                if (age % 4 == 0) {
                    int t = age + 4;
                    double grow = smooth(t / 10.0);
                    double fade = smooth((duration - t) / 30.0);
                    FxDisplays.animate(server, level, display, 0.35, 1.0, 0.15, t * 0.05, Math.max(0.02, size * grow * fade), 4);
                }
            }
            age++;
            return true;
        }
    }

    /** Parede de blocos temporários; pode ferir/afetar inimigos encostados. Restaura o ar ao terminar. */
    private static final class WallEffect implements Effect {
        private final ServerLevel level;
        private final UUID ownerId;
        private final AbilityDef d;
        private final List<BlockPos> placed;
        private final List<Vec3> centers;
        private final Block block;
        private final int duration;
        private final boolean hits;
        private int age = 0;
        private boolean restored = false;

        WallEffect(ServerLevel level, UUID ownerId, AbilityDef d, List<BlockPos> placed, List<Vec3> centers, Block block, int duration) {
            this.level = level;
            this.ownerId = ownerId;
            this.d = d;
            this.placed = placed;
            this.centers = centers;
            this.block = block;
            this.duration = Math.max(20, duration);
            this.hits = d.has("damage") || d.has("effects") || d.has("ignite");
        }

        void restore() {
            if (restored) return;
            restored = true;
            for (BlockPos pos : placed) {
                if (level.getBlockState(pos).is(block)) level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
            }
            if (!centers.isEmpty()) puff(level, particle(d), centers.get(centers.size() / 2), 12, 0.6, 0.02);
        }

        @Override
        public boolean tick(MinecraftServer server) {
            if (age >= duration) {
                restore();
                WALLS.remove(this);
                return false;
            }
            if (hits && age % 10 == 0) {
                ServerPlayer owner = server.getPlayerList().getPlayer(ownerId);
                if (owner != null) {
                    AABB box = null;
                    for (Vec3 c : centers) {
                        AABB one = new AABB(c, c).inflate(2.0);
                        box = box == null ? one : box.minmax(one);
                    }
                    int ignite = (int) Math.round(d.d("ignite", 0) * 20);
                    for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, box, x -> isFoe(owner, x))) {
                        Vec3 ec = e.getBoundingBox().getCenter();
                        boolean touching = false;
                        for (Vec3 c : centers) {
                            if (c.distanceToSqr(ec) <= 2.4 * 2.4) {
                                touching = true;
                                break;
                            }
                        }
                        if (!touching) continue;
                        hurt(level, owner, e, (float) (d.d("damage", 0) * 0.5));
                        if (e.isAlive()) {
                            addEffects(e, d.effects("effects"), 30);
                            if (ignite > 0) e.setRemainingFireTicks(ignite);
                        }
                    }
                }
            }
            if (age % 8 == 0 && !centers.isEmpty()) {
                Vec3 c = centers.get((age / 8) % centers.size());
                puff(level, particle(d), c, 2, 0.4, 0.01);
            }
            age++;
            return true;
        }
    }

    /** Anel 3D girando em volta do jogador na ativação de um modo (ex.: anel de facas do Blade Storm). */
    private static final class RingEffect implements Effect {
        private static final int TICKS = 40;
        private static final double FULL_SCALE = 3.4;
        private final ServerLevel level;
        private final UUID owner;
        private final UUID display;
        private int age = 0;

        RingEffect(ServerLevel level, UUID owner, UUID display) {
            this.level = level;
            this.owner = owner;
            this.display = display;
        }

        @Override
        public boolean tick(MinecraftServer server) {
            ServerPlayer p = server.getPlayerList().getPlayer(owner);
            if (p == null || !p.isAlive() || p.level() != level || age >= TICKS || display == null) {
                FxDisplays.kill(level, display);
                return false;
            }
            FxDisplays.teleport(server, level, display, p.getX(), p.getY() + 0.95, p.getZ());
            if (age % 4 == 0) {
                int t = age + 4;
                double grow = smooth(t / 8.0);
                double fade = smooth((TICKS - t) / 15.0);
                FxDisplays.animate(server, level, display, 0, 1, 0, t * 0.28, Math.max(0.02, FULL_SCALE * grow * fade), 4);
            }
            age++;
            return true;
        }
    }

    /** Remove uma entidade de efeito depois de alguns ticks (projétil cravado). */
    private static final class TimedKillEffect implements Effect {
        private final ServerLevel level;
        private final UUID display;
        private int left;

        TimedKillEffect(ServerLevel level, UUID display, int ticks) {
            this.level = level;
            this.display = display;
            this.left = ticks;
        }

        @Override
        public boolean tick(MinecraftServer server) {
            if (--left > 0) return true;
            FxDisplays.kill(level, display);
            return false;
        }
    }
}
