package br.com.gunbound.emulator.packets.readers.room.change;

import br.com.gunbound.emulator.handlers.GameAttributes;
import br.com.gunbound.emulator.model.entities.game.PlayerSession;
import br.com.gunbound.emulator.packets.readers.MessageBcmReader;
import br.com.gunbound.emulator.room.GameRoom;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;

public class RoomKickReader {

	private static final int OPCODE_REQUEST = 0x3150;

	public static void read(ChannelHandlerContext ctx, byte[] payload) {
		System.out.println("RECV> SVC_ROOM_KICK (0x" + Integer.toHexString(OPCODE_REQUEST) + ")");

		PlayerSession actor = ctx.channel().attr(GameAttributes.USER_SESSION).get();
		if (actor == null || payload == null || payload.length < 1) {
			return;
		}

		GameRoom room = actor.getCurrentRoom();
		if (room == null) {
			return;
		}

		ByteBuf request = Unpooled.wrappedBuffer(payload);
		try {
			int targetSlot = request.readUnsignedByte();
			PlayerSession target = room.getPlayersBySlot().get(targetSlot);

			if (target == null) {
				MessageBcmReader.printMsgToPlayer(actor, "ADMIN >> Invalid target slot.");
				return;
			}

			RoomCommandReader.requestKickByMaster(ctx, actor, target);
		} finally {
			request.release();
		}
	}
}
