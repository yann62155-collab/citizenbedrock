package fr.citizen.bedrock.remap;

import fr.citizen.bedrock.BedrockConfig;
import fr.citizen.bedrock.BedrockContext;
import fr.citizen.bedrock.BedrockSession;
import fr.citizen.bedrock.CitizenBedrock;
import fr.citizen.bedrock.mixin.EntityEventPacketAccessor;
import fr.citizen.bedrock.mixin.MoveEntityPacketAccessor;
import fr.citizen.bedrock.mixin.RotateHeadPacketAccessor;
import fr.citizen.bedrock.mixin.TagNetworkPayloadAccessor;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ClientboundUpdateTagsPacket;
import net.minecraft.network.protocol.game.*;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.stats.Stat;
import net.minecraft.tags.TagNetworkSerialization;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.common.extensions.ICommonPacketListener;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Convertit ou retire, pour un joueur console, les paquets que la console ne saurait pas lire.
 * Renvoie le paquet d'origine (rien à changer), un nouveau paquet, ou null (ne pas envoyer).
 */
public final class PacketFilter {
    private static final ThreadLocal<Boolean> REENTRANT = ThreadLocal.withInitial(() -> false);
    private static volatile boolean errorLogged;

    private PacketFilter() {
    }

    public static boolean isReentrant() {
        return REENTRANT.get();
    }

    public static void runReentrant(Runnable action) {
        REENTRANT.set(true);
        try {
            action.run();
        } finally {
            REENTRANT.set(false);
        }
    }

    @Nullable
    public static Packet<?> process(Packet<?> packet, BedrockSession session, ServerCommonPacketListenerImpl listener) {
        try {
            return processUnsafe(packet, session, listener);
        } catch (RuntimeException e) {
            if (!errorLogged) {
                errorLogged = true;
                CitizenBedrock.LOGGER.error("[Citizen Bedrock] Erreur de conversion du paquet {} (affichée une seule fois).",
                        packet.getClass().getSimpleName(), e);
            }
            return packet;
        }
    }

    @Nullable
    @SuppressWarnings("unchecked")
    private static Packet<?> processUnsafe(Packet<?> packet, BedrockSession session, ServerCommonPacketListenerImpl listener) {
        // --- Données de mods : la console ne connaît aucun canal moddé
        if (packet instanceof ClientboundCustomPayloadPacket custom) {
            ResourceLocation id = custom.payload().type().id();
            if (RemapTables.isVanilla(id) || "floodgate".equals(id.getNamespace())) {
                return packet;
            }
            // Cinématiques : titre affiché à l'écran de la console à la place de la vidéo
            List<Packet<?>> cinematic = fr.citizen.bedrock.forms.CinematicsBridge.translate(custom.payload());
            if (cinematic != null) {
                for (Packet<?> p : cinematic) runReentrant(() -> listener.send(p));
                return null;
            }
            // Écrans des mods Citizen (hibou, éclipses, /craft...)
            if (listener instanceof ServerGamePacketListenerImpl citizenGame
                    && fr.citizen.bedrock.forms.CitizenModsBridge.onPayload(citizenGame.player, custom.payload())) {
                return null;
            }
            // Citizen Economy : écrans et annonces convertis pour la console
            if (listener instanceof ServerGamePacketListenerImpl econ) {
                if (fr.citizen.bedrock.forms.EconomyBridge.VIEWS.containsKey(id.toString())) {
                    fr.citizen.bedrock.forms.EconomyBridge.view(econ.player, custom.payload());
                    return null;
                }
                if (fr.citizen.bedrock.forms.EconomyBridge.HUD.equals(id.toString())) {
                    fr.citizen.bedrock.forms.EconomyBridge.announcement(econ.player, custom.payload());
                    return null;
                }
            }
            // Boutique, guildes, mancie, statues guides
            if (listener instanceof ServerGamePacketListenerImpl extra
                    && fr.citizen.bedrock.forms.CitizenExtrasBridge.onPayload(extra.player, custom.payload())) {
                return null;
            }
            // PNJ Easy NPC : on répond à la place de la console pour ouvrir le dialogue ou le commerce
            if (fr.citizen.bedrock.forms.EasyNpcBridge.OPEN_CALLBACK.equals(id.toString()) && listener instanceof ServerGamePacketListenerImpl npcGame) {
                fr.citizen.bedrock.forms.EasyNpcBridge.onOpenCallback(npcGame.player, custom.payload());
                return null;
            }
            // Parchemin /citizen : affiché en menu Bedrock au lieu de l'écran PC
            if (fr.citizen.bedrock.forms.CitizenHubBridge.OPEN_HUB.equals(id.toString()) && listener instanceof ServerGamePacketListenerImpl game) {
                fr.citizen.bedrock.forms.CitizenHubBridge.open(game.player, custom.payload());
                return null;
            }
            if (fr.citizen.bedrock.forms.CitizenExtrasBridge.HANDLED_CHANNELS.contains(id.toString())) {
                return null; // canal annoncé « présent » pour la console mais affiché par nos menus
            }
            boolean allowed = NetworkRegistry.hasChannel((ICommonPacketListener) listener, id);
            return allowed ? packet : null;
        }

        // --- Historique du chat (/historique)
        if (packet instanceof net.minecraft.network.protocol.game.ClientboundSystemChatPacket sys && !sys.overlay()) {
            session.remember(sys.content().getString());
        } else if (packet instanceof net.minecraft.network.protocol.game.ClientboundPlayerChatPacket chat
                && listener instanceof ServerGamePacketListenerImpl g) {
            ServerPlayer from = g.player.server.getPlayerList().getPlayer(chat.sender());
            String text = chat.unsignedContent() != null ? chat.unsignedContent().getString() : chat.body().content();
            session.remember("§e" + (from == null ? "?" : from.getName().getString()) + "§r : " + text);
        } else if (packet instanceof net.minecraft.network.protocol.game.ClientboundDisguisedChatPacket chat) {
            session.remember("§e" + chat.chatType().name().getString() + "§r : " + chat.message().getString());
        }

        // --- Paquets groupés : on filtre chaque morceau
        if (packet instanceof ClientboundBundlePacket bundle) {
            List<Packet<? super ClientGamePacketListener>> kept = new ArrayList<>();
            boolean changed = false;
            for (Packet<? super ClientGamePacketListener> sub : bundle.subPackets()) {
                Packet<?> converted = processUnsafe(sub, session, listener);
                if (converted != sub) changed = true;
                if (converted != null) kept.add((Packet<? super ClientGamePacketListener>) converted);
            }
            if (!changed) return packet;
            return kept.isEmpty() ? null : new ClientboundBundlePacket(kept);
        }

        // --- Chunks : reconstruits avec les blocs convertis
        if (packet instanceof ClientboundLevelChunkWithLightPacket chunkPacket && listener instanceof ServerGamePacketListenerImpl game) {
            ServerPlayer player = game.player;
            ServerLevel level = player.serverLevel();
            LevelChunk chunk = level.getChunkSource().getChunkNow(chunkPacket.getX(), chunkPacket.getZ());
            if (chunk == null) {
                return packet;
            }
            return BedrockContext.with(session, () ->
                    new ClientboundLevelChunkWithLightPacket(chunk, level.getChunkSource().getLightEngine(), null, null));
        }

        // --- Entités moddées : remplacées par une créature vanilla (ou cachées si aucun équivalent)
        if (packet instanceof ClientboundAddEntityPacket add) {
            EntityType<?> type = add.getType();
            if (!RemapTables.isVanilla(BuiltInRegistries.ENTITY_TYPE.getKey(type))) {
                return substitute(add, session, listener);
            }
            if (type == EntityType.FALLING_BLOCK) {
                int data = RemapTables.remapBlockState(add.getData());
                if (data != add.getData()) {
                    return new ClientboundAddEntityPacket(add.getId(), add.getUUID(), add.getX(), add.getY(), add.getZ(),
                            add.getXRot(), add.getYRot(), type, data,
                            new net.minecraft.world.phys.Vec3(add.getXa(), add.getYa(), add.getZa()), add.getYHeadRot());
                }
            }
            return packet;
        }
        if (packet instanceof ClientboundRemoveEntitiesPacket remove) {
            List<java.util.UUID> profiles = new ArrayList<>();
            for (int id : remove.getEntityIds()) {
                session.hiddenEntities.remove(id);
                session.substitutedEntities.remove(id);
                java.util.UUID profile = session.fakePlayers.remove(id);
                if (profile != null) profiles.add(profile);
            }
            if (!profiles.isEmpty()) {
                runReentrant(() -> listener.send(packet));
                return new ClientboundPlayerInfoRemovePacket(profiles);
            }
            return packet;
        }
        Integer entityId = entityIdOf(packet);
        if (entityId != null && session.hiddenEntities.contains(entityId.intValue())) {
            return null;
        }
        if (packet instanceof ClientboundSetPassengersPacket passengers) {
            for (int id : passengers.getPassengers()) {
                if (session.hiddenEntities.contains(id)) return null;
            }
            return packet;
        }
        if (packet instanceof ClientboundSetEntityDataPacket data) {
            Integer maxId = session.substitutedEntities.get(data.id());
            List<SynchedEntityData.DataValue<?>> kept = new ArrayList<>();
            for (SynchedEntityData.DataValue<?> value : data.packedItems()) {
                if (maxId != null && value.id() > maxId) continue;
                if (maxId != null && value.id() == 15 && value.serializer() != net.minecraft.network.syncher.EntityDataSerializers.BYTE) continue;
                if (NeoForgeRegistries.ENTITY_DATA_SERIALIZERS.getKey(value.serializer()) == null) {
                    kept.add(value);
                }
            }
            if (kept.size() == data.packedItems().size()) return packet;
            return kept.isEmpty() ? null : new ClientboundSetEntityDataPacket(data.id(), kept);
        }
        if (packet instanceof ClientboundUpdateMobEffectPacket effect) {
            return isVanillaHolder(effect.getEffect()) ? packet : null;
        }
        if (packet instanceof ClientboundRemoveMobEffectPacket effect) {
            return isVanillaHolder(effect.effect()) ? packet : null;
        }

        // --- Menus moddés : affichés comme un coffre de la bonne taille
        if (packet instanceof ClientboundOpenScreenPacket open) {
            session.containerMappings.remove(open.getContainerId());
            session.hiddenContainers.remove(open.getContainerId());
            if (RemapTables.isVanilla(BuiltInRegistries.MENU.getKey(open.getType()))) {
                return packet;
            }
            if (listener instanceof ServerGamePacketListenerImpl game) {
                ContainerBridge.scheduleOpen(game.player, session, open);
            }
            return null;
        }
        if (packet instanceof ClientboundContainerSetContentPacket p) {
            if (session.hiddenContainers.contains(p.getContainerId()) || session.pendingContainers.contains(p.getContainerId())) return null;
            ContainerBridge.Mapping m = session.containerMappings.get(p.getContainerId());
            return m == null ? packet : ContainerBridge.content(m, p);
        }
        if (packet instanceof ClientboundContainerSetSlotPacket p) {
            if (session.hiddenContainers.contains(p.getContainerId()) || session.pendingContainers.contains(p.getContainerId())) return null;
            ContainerBridge.Mapping m = session.containerMappings.get(p.getContainerId());
            return m == null ? packet : ContainerBridge.slot(m, p);
        }
        if (packet instanceof ClientboundContainerSetDataPacket p) {
            if (session.hiddenContainers.contains(p.getContainerId()) || session.pendingContainers.contains(p.getContainerId())
                    || session.containerMappings.containsKey(p.getContainerId())) return null;
            return packet;
        }
        if (packet instanceof ClientboundContainerClosePacket p) {
            session.containerMappings.remove(p.getContainerId());
            session.pendingContainers.remove(p.getContainerId());
            if (session.hiddenContainers.remove(p.getContainerId())) return null;
            return packet;
        }

        // --- Blocs dans des événements (cassage, brossage) et blocs moddés avec données
        if (packet instanceof ClientboundLevelEventPacket event && (event.getType() == 2001 || event.getType() == 3008)) {
            int data = RemapTables.remapBlockState(event.getData());
            return data == event.getData() ? packet
                    : new ClientboundLevelEventPacket(event.getType(), event.getPos(), data, event.isGlobalEvent());
        }
        if (packet instanceof ClientboundBlockEntityDataPacket be) {
            return RemapTables.isVanilla(BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType())) ? packet : null;
        }
        if (packet instanceof ClientboundLevelParticlesPacket particles) {
            return RemapTables.isVanilla(BuiltInRegistries.PARTICLE_TYPE.getKey(particles.getParticle().getType())) ? packet : null;
        }

        // --- Recettes, tags et statistiques moddés
        if (packet instanceof ClientboundUpdateRecipesPacket recipes) {
            List<RecipeHolder<?>> kept = new ArrayList<>();
            for (RecipeHolder<?> holder : recipes.getRecipes()) {
                if (RemapTables.isVanilla(BuiltInRegistries.RECIPE_SERIALIZER.getKey(holder.value().getSerializer()))) {
                    kept.add(holder);
                }
            }
            return kept.size() == recipes.getRecipes().size() ? packet : new ClientboundUpdateRecipesPacket(kept);
        }
        if (packet instanceof ClientboundUpdateTagsPacket tags) {
            return filterTags(tags);
        }
        if (packet instanceof ClientboundAwardStatsPacket stats) {
            Object2IntMap<Stat<?>> kept = new Object2IntOpenHashMap<>();
            for (Object2IntMap.Entry<Stat<?>> entry : stats.stats().object2IntEntrySet()) {
                if (isVanillaStat(entry.getKey())) kept.put(entry.getKey(), entry.getIntValue());
            }
            return kept.size() == stats.stats().size() ? packet : new ClientboundAwardStatsPacket(kept);
        }
        return packet;
    }

    /** Remplace l'apparition d'une créature moddée par une créature vanilla ou un personnage. */
    @Nullable
    private static Packet<?> substitute(ClientboundAddEntityPacket add, BedrockSession session, ServerCommonPacketListenerImpl listener) {
        if (!(listener instanceof ServerGamePacketListenerImpl game)) {
            session.hiddenEntities.add(add.getId());
            return null;
        }
        net.minecraft.world.entity.Entity entity = game.player.serverLevel().getEntity(add.getId());
        EntityType<?> target = entity == null ? null : EntitySubstitutes.choose(entity);
        if (target == null) {
            session.hiddenEntities.add(add.getId());
            return null;
        }
        session.substitutedEntities.put(add.getId(), EntitySubstitutes.maxSharedDataId(entity, target));
        if (target == EntityType.PLAYER) {
            // Un personnage a besoin d'un profil (nom affiché au-dessus de la tête) avant d'apparaître.
            String display = entity.getName().getString().replace('§', ' ').trim();
            if (display.isEmpty()) display = "PNJ";
            if (display.length() > 16) display = display.substring(0, 16);
            com.mojang.authlib.GameProfile profile = new com.mojang.authlib.GameProfile(add.getUUID(), display);
            ClientboundPlayerInfoUpdatePacket info = new ClientboundPlayerInfoUpdatePacket(
                    java.util.EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER,
                            ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LISTED), java.util.List.of());
            ((fr.citizen.bedrock.mixin.PlayerInfoUpdatePacketAccessor) info).citizenbedrock$setEntries(java.util.List.of(
                    new ClientboundPlayerInfoUpdatePacket.Entry(add.getUUID(), profile, false, 0,
                            net.minecraft.world.level.GameType.SURVIVAL, null, null)));
            session.fakePlayers.put(add.getId(), add.getUUID());
            runReentrant(() -> listener.send(info));
        }
        ClientboundAddEntityPacket replaced = new ClientboundAddEntityPacket(add.getId(), add.getUUID(), add.getX(), add.getY(), add.getZ(),
                add.getXRot(), add.getYRot(), target, 0,
                new net.minecraft.world.phys.Vec3(add.getXa(), add.getYa(), add.getZa()), add.getYHeadRot());
        String carcass = EntitySubstitutes.carcassLabel(entity);
        if (carcass != null) {
            // Nom visible au-dessus de la carcasse, envoyé juste après son apparition
            ClientboundSetEntityDataPacket label = new ClientboundSetEntityDataPacket(add.getId(), List.of(
                    new SynchedEntityData.DataValue<>(2, net.minecraft.network.syncher.EntityDataSerializers.OPTIONAL_COMPONENT,
                            java.util.Optional.of(net.minecraft.network.chat.Component.literal(carcass))),
                    new SynchedEntityData.DataValue<>(3, net.minecraft.network.syncher.EntityDataSerializers.BOOLEAN, true)));
            game.player.server.execute(() -> runReentrant(() -> listener.send(label)));
        }
        return replaced;
    }

    @Nullable
    private static Integer entityIdOf(Packet<?> packet) {
        if (packet instanceof ClientboundSetEntityDataPacket p) return p.id();
        if (packet instanceof ClientboundSetEntityMotionPacket p) return p.getId();
        if (packet instanceof ClientboundMoveEntityPacket p) return ((MoveEntityPacketAccessor) p).citizenbedrock$entityId();
        if (packet instanceof ClientboundTeleportEntityPacket p) return p.getId();
        if (packet instanceof ClientboundRotateHeadPacket p) return ((RotateHeadPacketAccessor) p).citizenbedrock$entityId();
        if (packet instanceof ClientboundSetEquipmentPacket p) return p.getEntity();
        if (packet instanceof ClientboundUpdateAttributesPacket p) return p.getEntityId();
        if (packet instanceof ClientboundEntityEventPacket p) return ((EntityEventPacketAccessor) p).citizenbedrock$entityId();
        if (packet instanceof ClientboundAnimatePacket p) return p.getId();
        if (packet instanceof ClientboundSetPassengersPacket p) return p.getVehicle();
        if (packet instanceof ClientboundTakeItemEntityPacket p) return p.getItemId();
        if (packet instanceof ClientboundHurtAnimationPacket p) return p.id();
        if (packet instanceof ClientboundDamageEventPacket p) return p.entityId();
        if (packet instanceof ClientboundUpdateMobEffectPacket p) return p.getEntityId();
        if (packet instanceof ClientboundRemoveMobEffectPacket p) return p.entityId();
        if (packet instanceof ClientboundSetEntityLinkPacket p) return p.getSourceId();
        if (packet instanceof ClientboundProjectilePowerPacket p) return p.getId();
        return null;
    }

    private static boolean isVanillaHolder(Holder<MobEffect> holder) {
        return holder.unwrapKey().map(k -> RemapTables.isVanilla(k.location())).orElse(false);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static boolean isVanillaStat(Stat<?> stat) {
        ResourceLocation typeKey = BuiltInRegistries.STAT_TYPE.getKey(stat.getType());
        if (!RemapTables.isVanilla(typeKey)) return false;
        Registry registry = stat.getType().getRegistry();
        return RemapTables.isVanilla(registry.getKey(stat.getValue()));
    }

    /** Retire des tags les éléments moddés des registres fixes (blocs, objets, entités...). */
    @SuppressWarnings("unchecked")
    private static Packet<?> filterTags(ClientboundUpdateTagsPacket packet) {
        Map<ResourceKey<? extends Registry<?>>, TagNetworkSerialization.NetworkPayload> result = new HashMap<>();
        boolean changed = false;
        for (Map.Entry<ResourceKey<? extends Registry<?>>, TagNetworkSerialization.NetworkPayload> entry : packet.getTags().entrySet()) {
            Registry<Object> registry = (Registry<Object>) BuiltInRegistries.REGISTRY.get(entry.getKey().location());
            if (registry == null) {
                result.put(entry.getKey(), entry.getValue()); // registre de données : connu du client
                continue;
            }
            Map<ResourceLocation, IntList> tags = ((TagNetworkPayloadAccessor) (Object) entry.getValue()).citizenbedrock$tags();
            Map<ResourceLocation, IntList> filtered = new HashMap<>();
            for (Map.Entry<ResourceLocation, IntList> tag : tags.entrySet()) {
                IntList ids = new IntArrayList();
                for (int id : tag.getValue()) {
                    Object value = registry.byId(id);
                    if (value != null && RemapTables.isVanilla(registry.getKey(value))) {
                        ids.add(id);
                    } else {
                        changed = true;
                    }
                }
                filtered.put(tag.getKey(), ids);
            }
            result.put(entry.getKey(), TagNetworkPayloadAccessor.citizenbedrock$create(filtered));
        }
        return changed ? new ClientboundUpdateTagsPacket(result) : packet;
    }

    /** Pour savoir si un bloc est moddé (utilisé par la commande d'état). */
    public static boolean isModded(Block block) {
        return !RemapTables.isVanilla(BuiltInRegistries.BLOCK.getKey(block));
    }
}
