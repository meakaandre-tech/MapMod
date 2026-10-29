package com.meakaandre.mapmod.selftest;

import com.meakaandre.mapmod.MapMod;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

/**
 * What the smoke run's client script waits for, in place of fixed sleeps: {@code /mapmod worldtest ready <n>} asks every
 * client to say, in its own log, "SELFTEST client ready n." once the world around its player has arrived and is drawn
 * ({@code client.ReadyClientTests}). The workflow's {@code wait} lines watch the logs for it.
 */
public final class SmokeWaits {
    private static final TestReport REPORT = new TestReport("SELFTEST smoke ");

    /** Server to client: tell the log once the world around the player is loaded and drawn, with this token. */
    public record Ready(int token) implements CustomPacketPayload {
        public static final Type<Ready> TYPE = new Type<>(TestPois.id("ready"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Ready> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> buf.writeVarInt(p.token), buf -> new Ready(buf.readVarInt()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    private SmokeWaits() {
    }

    public static void register() {
        PayloadTypeRegistry.clientboundPlay().register(Ready.TYPE, Ready.STREAM_CODEC);
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
            Commands.literal(MapMod.ID).requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("worldtest")
                    .then(Commands.literal("ready").then(Commands.argument("token", IntegerArgumentType.integer(0)).executes(SmokeWaits::ready))))));
    }

    private static int ready(CommandContext<CommandSourceStack> context) {
        int token = IntegerArgumentType.getInteger(context, "token");
        int sent = 0;
        for (ServerPlayer player : context.getSource().getServer().getPlayerList().getPlayers()) {
            ServerPlayNetworking.send(player, new Ready(token));
            sent++;
        }
        REPORT.report(context.getSource(), "ready " + token + " asked of " + sent + " players");
        return 1;
    }
}
