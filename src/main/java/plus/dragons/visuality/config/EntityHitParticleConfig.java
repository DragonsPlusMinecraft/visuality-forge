package plus.dragons.visuality.config;

import com.google.common.collect.Lists;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.Mth;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.ThrownTrident;
import org.jetbrains.annotations.Nullable;
import plus.dragons.visuality.Visuality;
import plus.dragons.visuality.data.ParticleWithVelocity;
import plus.dragons.visuality.data.VisualityCodecs;
import plus.dragons.visuality.registry.VisualityParticles;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

public class EntityHitParticleConfig extends ReloadableJsonConfig {
    private boolean enabled = true;
    private int minAmount = 1;
    private int maxAmount = 20;
    private List<Entry> entries;
    private final IdentityHashMap<EntityType<?>, ParticleWithVelocity> particles = new IdentityHashMap<>();
    
    public EntityHitParticleConfig() {
        super(Visuality.location("particle_emitters/entity_hit"));
        this.entries = createDefaultEntries();
        for (Entry entry : entries) {
            for (EntityType<?> type : entry.entities) {
                particles.put(type, entry.particle);
            }
        }
    }
    
    public void spawnParticles(LivingEntity entity,DamageSource damageSource, double amount) {
        // Unfortunately LivingDamageEvent only calls in server side. So we use Mixin
        if (!this.enabled)
            return;
    
        EntityType<?> type = entity.getType();
        if (!particles.containsKey(type))
            return;

        Entity sourceEntity = damageSource.getDirectEntity();
        
        if (sourceEntity == null)
            return;
        if (sourceEntity instanceof LivingEntity)
            amount = getAttackDamage((LivingEntity) sourceEntity);
        else if (sourceEntity instanceof ThrownTrident)
            amount = 8.0;
        else if (sourceEntity instanceof AbstractArrow)
            amount = ((AbstractArrow) sourceEntity).getBaseDamage() * 2;
        
        if (amount <= 0)
            return;
    
        int count = Mth.clamp(Mth.ceil(amount), minAmount, maxAmount);
        ParticleWithVelocity particle = particles.get(entity.getType());
        double x = entity.getX();
        double y = entity.getY(0.5);
        double z = entity.getZ();
        for (int i = 0; i < count; ++i) {
            particle.spawn(entity.level(), x, y, z);
        }
    }
    
    private double getAttackDamage(LivingEntity attacker) {
        return attacker.getMainHandItem().getAttributeModifiers().compute(attacker.getAttributeBaseValue(Attributes.ATTACK_DAMAGE),EquipmentSlot.MAINHAND);
    }
    
    @Override
    @Nullable
    protected JsonObject apply(JsonObject input, boolean config, String source, ProfilerFiller profiler) {
        profiler.push(source);
        if (config) {
            enabled = GsonHelper.getAsBoolean(input, "enabled", true);
            minAmount = GsonHelper.getAsInt(input, "min_amount", 1);
            maxAmount = GsonHelper.getAsInt(input, "max_amount", 20);
        }
        JsonArray array = GsonHelper.getAsJsonArray(input, "entries", null);
        if (array == null) {
            logger.warn("Failed to load options entries from {}: Missing JsonArray 'entries'.", source);
            profiler.pop();
            return config ? serializeConfig() : null;
        }
        boolean save = false;
        List<Entry> newEntries = new ArrayList<>();
        List<JsonElement> elements = Lists.newArrayList(array);
        for (JsonElement element : elements) {
            var data = Entry.CODEC.parse(JsonOps.INSTANCE, element);
            if (data.error().isPresent()) {
                save = config;
                logger.warn("Error parsing {} from {}: {}", id, source, data.error().get().message());
                continue;
            }
            if (data.result().isPresent())
                newEntries.add(data.result().get());
            else {
                save = config;
                logger.warn("Error parsing {} from {}: Missing decode result", id, source);
            }
        }
        if (config) {
            entries = newEntries;
            particles.clear();
        }
        for (Entry entry : newEntries) {
            for (EntityType<?> type : entry.entities) {
                particles.put(type, entry.particle);
            }
        }
        profiler.pop();
        return save ? serializeConfig() : null;
    }
    
    @Override
    protected JsonObject serializeConfig() {
        JsonObject object = new JsonObject();
        object.addProperty("enabled", enabled);
        object.addProperty("min_amount", minAmount);
        object.addProperty("max_amount", maxAmount);
        object.add("entries", Entry.LIST_CODEC.encodeStart(JsonOps.INSTANCE, entries).getOrThrow());
        return object;
    }
    
    private record Entry(List<EntityType<?>> entities, ParticleWithVelocity particle) {
    
        private static final Codec<Entry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            VisualityCodecs.compressedListOf(BuiltInRegistries.ENTITY_TYPE.byNameCodec()).fieldOf("entity")
                .forGetter(Entry::entities),
            ParticleWithVelocity.CODEC.fieldOf("particle")
                .forGetter(Entry::particle)
        ).apply(instance, Entry::new));
    
        private static final Codec<List<Entry>> LIST_CODEC = CODEC.listOf();
    
        private static Entry of(ParticleOptions particle, EntityType<?>... types) {
            return new Entry(List.of(types), ParticleWithVelocity.ofZeroVelocity(particle));
        }
        
    }
    
    private static List<Entry> createDefaultEntries() {
        List<Entry> entries = new ArrayList<>();
        entries.add(Entry.of(VisualityParticles.BONE.get(),
            EntityType.SKELETON,
            EntityType.SKELETON_HORSE,
            EntityType.STRAY));
        entries.add(Entry.of(VisualityParticles.WITHER_BONE.get(),
            EntityType.WITHER_SKELETON));
        entries.add(Entry.of(VisualityParticles.FEATHER.get(),
            EntityType.CHICKEN));
        entries.add(Entry.of(VisualityParticles.EMERALD.get(),
            EntityType.VILLAGER,
            EntityType.WANDERING_TRADER
        ));
        return entries;
    }
    
}
