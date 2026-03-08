package br.com.gunbound.emulator.packets.readers.room.change;

import br.com.gunbound.emulator.handlers.GameAttributes;
import br.com.gunbound.emulator.model.entities.game.PlayerSession;
import br.com.gunbound.emulator.packets.writers.RoomWriter;
import br.com.gunbound.emulator.room.GameRoom;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;

public class RoomChangeCapacityReader {

	private static final int OPCODE_REQUEST = 0x3103;

	public static void read(ChannelHandlerContext ctx, byte[] payload) {
		System.out.println("RECV> SVC_ROOM_CHANGE_MAXMEN (0x" + Integer.toHexString(OPCODE_REQUEST) + ")");
		PlayerSession player = ctx.channel().attr(GameAttributes.USER_SESSION).get();
		if (player == null) {
			return;
		}

		GameRoom room = player.getCurrentRoom();
		if (room == null || !player.equals(room.getRoomMaster())) {
			return;
		}

		room.submitAction(() -> processChangeMaxMen(payload, room), ctx);
	}

	private static void processChangeMaxMen(byte[] payload, GameRoom room) {
		ByteBuf request = Unpooled.wrappedBuffer(payload);
		try {
			int newCapacity = request.readUnsignedByte();
			room.setCapacity(newCapacity);
			System.out.println("RoomID: " + room.getRoomId() + ", nova capacidade da sala: " + newCapacity);

			room.broadcastRoomUpdate();
			RoomWriter.broadcastLobbyRoomListRefresh();
		} finally {
			request.release();
		}
	}
}
