package br.com.gunbound.emulator.packets.readers.room;

import br.com.gunbound.emulator.handlers.GameAttributes;
import br.com.gunbound.emulator.model.entities.game.PlayerSession;
import br.com.gunbound.emulator.packets.readers.MessageBcmReader;
import br.com.gunbound.emulator.room.GameRoom;
import br.com.gunbound.emulator.room.model.enums.GameMode;
import br.com.gunbound.emulator.room.model.Tank;
import br.com.gunbound.emulator.utils.PacketUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;

public class RoomSelectTankReader {

	private static final int OPCODE_REQUEST = 0x3200;
	private static final int OPCODE_SUCCESS_RESPONSE = 0x3201;

	public static void read(ChannelHandlerContext ctx, byte[] payload) {
		System.out.println("RECV> SVC_ROOM_SELECT_TANK (0x" + Integer.toHexString(OPCODE_REQUEST) + ")");
		PlayerSession player = ctx.channel().attr(GameAttributes.USER_SESSION).get();
		if (player == null || player.getCurrentRoom() == null) {
			return; // Jogador não está em uma sala
		}

		GameRoom room = player.getCurrentRoom();
		// Empacota toda a lógica em um Runnable e submeta para a fila da sala!
		room.submitAction(() -> processSelectTank(payload, player, room),ctx);
	}

	private static void processSelectTank(byte[] payload, PlayerSession player, GameRoom room) {

		// 1. Decodificar o payload
		ByteBuf request = Unpooled.wrappedBuffer(payload);
		// request.skipBytes(6); // Pula os 6 bytes iniciais, como na referência

		int primaryTankId = request.readUnsignedByte();
		int secondaryTankId = request.readUnsignedByte();
		request.release();

		// 2. Atualizar a sessão do jogador
		player.setRoomTankPrimary(primaryTankId);
		player.setRoomTankSecondary(secondaryTankId);

		String primaryTankName = Tank.fromId(primaryTankId).getName();
		String secondaryTankName = Tank.fromId(secondaryTankId).getName();
		System.out.println(player.getNickName() + " selecionou: " + primaryTankName + " e " + secondaryTankName);

		// 3. Enviar pacote de confirmação (0x3201)
		// Conforme a referência, o pacote de resposta tem payload vazio. AQUI PODE TER
		// O RTC SIM
		//int currentTxSum = ctx.channel().attr(GameAttributes.PACKET_TX_SUM).get();
		ByteBuf successPacket = PacketUtils.generatePacket(player, OPCODE_SUCCESS_RESPONSE, Unpooled.EMPTY_BUFFER,
				true);

		player.getPlayerCtxChannel().writeAndFlush(successPacket);
		GameMode modeFromSettings = GameMode.fromId((room.getGameSettings() >> 16) & 0xFF);
		GameMode modeFromRoom = GameMode.fromId(room.getGameMode());
		boolean tagMode = modeFromSettings == GameMode.TAG
				|| (modeFromSettings == GameMode.UNKNOWN && modeFromRoom == GameMode.TAG);
		String message = tagMode
				? "SISTEMA >> Tanques selecionados: " + primaryTankName + " / " + secondaryTankName + "."
				: "SISTEMA >> Tanque selecionado: " + primaryTankName + ".";
		MessageBcmReader.printMsgToPlayer(player, message);
	}
}
