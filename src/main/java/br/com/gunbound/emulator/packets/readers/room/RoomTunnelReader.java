package br.com.gunbound.emulator.packets.readers.room;

import java.util.concurrent.TimeUnit;

import br.com.gunbound.emulator.handlers.GameAttributes;
import br.com.gunbound.emulator.model.entities.game.PlayerSession;
import br.com.gunbound.emulator.room.GameRoom;
import br.com.gunbound.emulator.utils.PacketUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;

public class RoomTunnelReader {

    private static final int OPCODE_REQUEST = 0x4500;
    private static final int OPCODE_FORWARD = 0x4501;
    private static final int MAX_RETRIES = 3;
    private static final long RETRY_DELAY = 25;

    public static void read(ChannelHandlerContext ctx, byte[] payload) {
        System.out.println("RECV> SVC_ROOM_TUNNEL (0x" + Integer.toHexString(OPCODE_REQUEST) + ")");

        PlayerSession senderPlayer = ctx.channel().attr(GameAttributes.USER_SESSION).get();
        if (senderPlayer == null || senderPlayer.getCurrentRoom() == null) {
            return;
        }

        // Process immediately to avoid per-packet startup delay.
        processTunnel(payload, senderPlayer, senderPlayer.getCurrentRoom());
    }

    private static void processTunnel(byte[] payload, PlayerSession senderPlayer, GameRoom room) {
        ByteBuf request = Unpooled.wrappedBuffer(payload);
        try {
            // Legacy tunnel payload includes a 2-byte prefix before destination slot.
            request.skipBytes(2);

            if (request.readableBytes() < 1) {
                return;
            }

            int destinationSlot = request.readUnsignedByte();
            if (request.readableBytes() <= 0) {
                System.err.println("TUNNEL: Packet received with no game data after destination slot.");
                return;
            }

            byte[] dataBytes = new byte[request.readableBytes()];
            request.readBytes(dataBytes);

            PlayerSession destinationPlayer = room.getPlayersBySlot().get(destinationSlot);
            if (destinationPlayer == null) {
                System.err.println("TUNNEL: invalid destination slot: " + destinationSlot);
                return;
            }

            System.out.println("TUNNEL: sending packet from: " + senderPlayer.getNickName()
                    + " SLOT [" + room.getSlotPlayer(senderPlayer) + "]"
                    + " to: " + destinationPlayer.getNickName() + " SLOT [" + destinationSlot + "]");

            room.submitAction(
                    () -> forwardPacketToPlayer(room, senderPlayer, destinationPlayer, dataBytes, 0),
                    destinationPlayer.getPlayerCtx());

        } catch (Exception e) {
            System.err.println("Error processing tunnel packet (0x4500):");
            e.printStackTrace();
        } finally {
            request.release();
        }
    }

    private static void forwardPacketToPlayer(
            GameRoom room,
            PlayerSession sender,
            PlayerSession recipient,
            byte[] data,
            int retryCount) {

        if (recipient == null || recipient.getPlayerCtxChannel() == null || !recipient.getPlayerCtxChannel().isActive()) {
            return;
        }

        ByteBuf forwardPayload = recipient.getPlayerCtxChannel().alloc().buffer();
        try {
            forwardPayload.writeByte(room.getSlotPlayer(sender));
            forwardPayload.writeBytes(data);

            ByteBuf forwardPacket = PacketUtils.generatePacket(recipient, OPCODE_FORWARD, forwardPayload, false);

            recipient.getPlayerCtxChannel().writeAndFlush(forwardPacket)
                    .addListener((ChannelFutureListener) future -> {
                        if (!future.isSuccess()) {
                            System.err.println("FAILED TO SEND TUNNEL PACKET to: " + recipient.getNickName());
                            future.cause().printStackTrace();

                            if (retryCount < MAX_RETRIES) {
                                recipient.getPlayerCtxChannel().eventLoop().schedule(
                                        () -> forwardPacketToPlayer(room, sender, recipient, data, retryCount + 1),
                                        RETRY_DELAY,
                                        TimeUnit.MILLISECONDS);
                            } else {
                                System.out.println("Retries exhausted, closing connection to: " + recipient.getNickName());
                                recipient.getPlayerCtxChannel().close();
                            }
                        }
                    });
        } finally {
            forwardPayload.release();
        }
    }
}
