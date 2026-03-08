package br.com.gunbound.emulator.packets.readers.room.change;

import br.com.gunbound.emulator.handlers.GameAttributes;
import br.com.gunbound.emulator.model.entities.game.PlayerSession;
import br.com.gunbound.emulator.packets.writers.RoomWriter;
import br.com.gunbound.emulator.room.GameRoom;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;

public class RoomChangeMapReader {

	private static final int OPCODE_REQUEST = 0x3100;

	public static void read(ChannelHandlerContext ctx, byte[] payload) {
		System.out.println("RECV> SVC_ROOM_CHANGE_STAGE (0x" + Integer.toHexString(OPCODE_REQUEST) + ")");
		PlayerSession player = ctx.channel().attr(GameAttributes.USER_SESSION).get();
		if (player == null) {
			return;
		}

		GameRoom room = player.getCurrentRoom();
		if (room == null || !player.equals(room.getRoomMaster())) {
			return;
		}

		room.submitAction(() -> processChangeStage(payload, player, room), ctx);
	}

	private static void processChangeStage(byte[] payload, PlayerSession player, GameRoom room) {
		ByteBuf request = Unpooled.wrappedBuffer(payload);
		try {
			int newMapId = request.readUnsignedByte();
			room.setMapId(newMapId);
			System.out.println("RoomID: " + room.getRoomId() + ", mapa alterado para " + newMapId);

			RoomWriter.writeRoomUpdate(player);
			RoomWriter.broadcastLobbyRoomListRefresh();
		} finally {
			request.release();
		}
	}
}
