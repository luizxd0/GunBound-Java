package br.com.gunbound.emulator.packets.readers.room;

import br.com.gunbound.emulator.handlers.GameAttributes;
import br.com.gunbound.emulator.model.entities.game.PlayerSession;
import br.com.gunbound.emulator.room.GameRoom;
import br.com.gunbound.emulator.room.model.enums.GameMode;
import io.netty.channel.ChannelHandlerContext;

public class RoomGameStartReader {
	public static void read(ChannelHandlerContext ctx, byte[] payload) {
		PlayerSession player = ctx.channel().attr(GameAttributes.USER_SESSION).get();
		GameRoom room = player != null ? player.getCurrentRoom() : null;

		if (player == null || room == null)
			return;

		System.out.println("RECV> SVC_ROOM_GAME_START (0x3430) from " + player.getNickName()
				+ ", room " + (room.getRoomId() + 1) + ", mode=" + GameMode.fromId(room.getGameMode()).getName()
				+ ", players=" + room.getPlayerCount());

		// Run start on the room's action queue for consistent state
		room.submitAction(() -> processStartGame(payload, player, room), ctx);
	}

	private static void processStartGame(byte[] payload, PlayerSession player, GameRoom room) {

		// Apenas o dono da sala pode iniciar
		if (!player.equals(room.getRoomMaster())) {
			System.err.println("Game start rejected: " + player.getNickName() + " is not room master.");
			return;
		}
		room.startGame(payload);
	}
}