package br.com.gunbound.emulator.packets.readers.room.change;

import java.util.ArrayList;
import java.util.List;

import br.com.gunbound.emulator.handlers.GameAttributes;
import br.com.gunbound.emulator.model.entities.game.PlayerSession;
import br.com.gunbound.emulator.packets.readers.MessageBcmReader;
import br.com.gunbound.emulator.packets.readers.lobby.LobbyJoin;
import br.com.gunbound.emulator.room.GameRoom;
import br.com.gunbound.emulator.room.RoomManager;
import br.com.gunbound.emulator.room.model.enums.GameMode;
import br.com.gunbound.emulator.utils.PacketUtils;
import br.com.gunbound.emulator.utils.Utils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;

public class RoomCommandReader {

	private static final int OPCODE_REQUEST = 0x5100;
	private static final int OPCODE_KICK_REQUEST = 0x3150;
	private static final Integer OPCODE_CONFIRMATION_CMD = 0x3FFF;

	public static void read(ChannelHandlerContext ctx, byte[] payload) {
		System.out.println("RECV> GENERIC_COMMAND (0x" + Integer.toHexString(OPCODE_REQUEST) + ")");
		PlayerSession player = ctx.channel().attr(GameAttributes.USER_SESSION).get();

		if (player == null)
			return;

		// Empacota toda a lÃ³gica em um Runnable e submeta para a fila da sala!
		processCommand(ctx, payload, player);
	}

	public static void readKickPacket(ChannelHandlerContext ctx, byte[] payload) {
		System.out.println("RECV> SVC_ROOM_KICK (0x" + Integer.toHexString(OPCODE_KICK_REQUEST) + ")");

		PlayerSession requester = ctx.channel().attr(GameAttributes.USER_SESSION).get();
		if (requester == null) {
			return;
		}

		GameRoom room = requester.getCurrentRoom();
		if (room == null) {
			return;
		}

		room.submitAction(() -> processKickPacket(payload, requester, room, false), ctx);
	}

	private static void processCommand(ChannelHandlerContext ctx, byte[] payload, PlayerSession player) {

		ByteBuf request = Unpooled.wrappedBuffer(payload).skipBytes(1);

		// 1. O payload inteiro Ã© a string do commando. Usamos o stringDecode.
		String[] commandParts = Utils.stringDecode(request).split(" ", 2);// limita comando em 2 partes
		String command = commandParts[0];
		String paramCmd = commandParts.length > 1 ? commandParts[1] : ""; // Valor padrÃ£o vazio caso nÃ£o haja parÃ¢metro
		String normalizedCommand = command.startsWith("/") ? command.substring(1) : command;
		System.out.println("COMMAND: " + command);
		GameRoom room = player.getCurrentRoom();

		if (normalizedCommand.equals("close")) {
			if (room == null)
				return;
			// deixa fechar se for Master (alterar para authority > 99
			checkIfaRoomMaster(player, room);
			MessageBcmReader.printMsgToPlayer(player, "The Room Was Closed");
			room.submitAction(() -> closeRoom(player, room), ctx);
		} else if (normalizedCommand.equals("bcm")) {
			if (player.getAuthority() > 0) {
				MessageBcmReader.broadcastSendMessage(paramCmd);
				System.out.println("[BCM-CMD] Admin " + player.getNickName() + " broadcasted: " + paramCmd);
			} else {
				System.out
						.println("[BCM-CMD] Player " + player.getNickName() + " tried to use /bcm without authority.");
				MessageBcmReader.printMsgToPlayer(player, "ADMIN >> You don't have permission to use this command.");
			}
		} else if (normalizedCommand.equals("gold") || normalizedCommand.equals("cash")) {
			if (player.getAuthority() <= 0) {
				MessageBcmReader.printMsgToPlayer(player, "ADMIN >> You don't have permission to use this command.");
				return;
			}

			String[] parts = paramCmd.split(" ");
			if (parts.length < 2) {
				MessageBcmReader.printMsgToPlayer(player, "USAGE >> /" + normalizedCommand + " <NickName> <amount>");
				return;
			}

			String targetNick = parts[0];
			int amount;
			try {
				amount = Integer.parseInt(parts[1]);
			} catch (NumberFormatException e) {
				MessageBcmReader.printMsgToPlayer(player, "ERROR >> Invalid amount.");
				return;
			}

			br.com.gunbound.emulator.model.DAO.UserDAO userDAO = br.com.gunbound.emulator.model.DAO.DAOFactory
					.CreateUserDao();
			br.com.gunbound.emulator.model.entities.DTO.UserDTO targetUser = userDAO.getUserByNickname(targetNick);

			if (targetUser == null) {
				MessageBcmReader.printMsgToPlayer(player, "ERROR >> Player not found: " + targetNick);
				return;
			}

			if (normalizedCommand.equals("gold")) {
				userDAO.updateAddGold(targetUser.getUserId(), amount);
			} else {
				userDAO.updateAddCash(targetUser.getUserId(), amount);
			}

			br.com.gunbound.emulator.model.entities.game.PlayerSessionManager playerSessionManager = br.com.gunbound.emulator.model.entities.game.PlayerSessionManager
					.getInstance();
			PlayerSession targetSession = playerSessionManager.getSessionPlayerByNickname(targetNick);
			if (targetSession != null) {
				if (normalizedCommand.equals("gold")) {
					targetSession.setGold(targetSession.getGold() + amount);
				} else {
					targetSession.setCash(targetSession.getCash() + amount);
				}
				br.com.gunbound.emulator.packets.readers.LoginReader.pushSessionStatsRefresh(targetSession);
			}

			MessageBcmReader.printMsgToPlayer(player,
					"SUCCESS >> " + amount + " " + normalizedCommand + " given to " + targetNick);
			System.out.println("[ADMIN-CMD] " + player.getNickName() + " gave " + amount + " " + normalizedCommand
					+ " to " + targetNick);

		} else if (normalizedCommand.equals("start")) {
			if (room == null)
				return;
			if (!player.equals(room.getRoomMaster())) {
				MessageBcmReader.printMsgToPlayer(player, "Only room master can start.");
				return;
			}
			if (room.getGameMode() != GameMode.JEWEL.getId()) {
				MessageBcmReader.printMsgToPlayer(player, "/start is only allowed in Jewel mode.");
				return;
			}
			MessageBcmReader.printMsgToPlayer(player, "Starting Jewel game...");
			room.submitAction(() -> room.startGame(new byte[0]), ctx);
		} else if (normalizedCommand.equals("disconnect")) {
			if (player.getAuthority() <= 0) {
				MessageBcmReader.printMsgToPlayer(player, "ADMIN >> You don't have permission to use this command.");
				return;
			}
			if (paramCmd.isEmpty()) {
				MessageBcmReader.printMsgToPlayer(player, "USAGE >> /disconnect <NickName>");
				return;
			}

			PlayerSession target = br.com.gunbound.emulator.model.entities.game.PlayerSessionManager.getInstance()
					.getSessionPlayerByNickname(paramCmd.trim());

			if (target == null) {
				MessageBcmReader.printMsgToPlayer(player, "ERROR >> Player not found: " + paramCmd);
				return;
			}

			System.out.println("[ADMIN-CMD] Admin " + player.getNickName() + " disconnected " + target.getNickName());
			MessageBcmReader.printMsgToPlayer(player, "SUCCESS >> Player " + target.getNickName() + " disconnected.");
			target.getPlayerCtxChannel().close();

		} else if (normalizedCommand.equals("kick")) {
			if (room == null) {
				MessageBcmReader.printMsgToPlayer(player, "ERROR >> You must be in a room to use this command.");
				return;
			}

			if (paramCmd.isEmpty()) {
				MessageBcmReader.printMsgToPlayer(player, "USAGE >> /kick <NickName>");
				return;
			}

			String targetNick = paramCmd.trim();
			PlayerSession target = null;
			for (PlayerSession p : room.getPlayersBySlot().values()) {
				if (p.getNickName().equalsIgnoreCase(targetNick)) {
					target = p;
					break;
				}
			}

			if (target == null) {
				MessageBcmReader.printMsgToPlayer(player, "ERROR >> Player " + targetNick + " not found in this room.");
				return;
			}

			PlayerSession finalTarget = target;
			room.submitAction(() -> executeKick(player, room, finalTarget, "command", true), ctx);

		} else {
			MessageBcmReader.printMsgToPlayer(player, "ADMIN >> Unknown Command");
		}

	}

	private static void processKickPacket(byte[] payload, PlayerSession requester, GameRoom room,
			boolean notifyRequesterSuccess) {
		if (payload == null || payload.length == 0) {
			System.err.println("[KICK] Empty payload for 0x3150 from " + requester.getNickName());
			return;
		}

		ByteBuf request = Unpooled.wrappedBuffer(payload);
		try {
			int targetSlot = request.readUnsignedByte();
			PlayerSession target = room.getPlayersBySlot().get(targetSlot);
			if (target == null) {
				System.out.println("[KICK] Invalid target slot " + targetSlot + " in room " + (room.getRoomId() + 1));
				return;
			}

			executeKick(requester, room, target, "0x3150", notifyRequesterSuccess);
		} finally {
			request.release();
		}
	}

	private static void executeKick(PlayerSession requester, GameRoom room, PlayerSession target, String source,
			boolean notifyRequesterSuccess) {
		boolean isMaster = requester.equals(room.getRoomMaster());
		boolean isAdmin = requester.getAuthority() > 0;
		if (!isMaster && !isAdmin) {
			System.out.println("[KICK] Unauthorized kick attempt by " + requester.getNickName() + " in room "
					+ (room.getRoomId() + 1));
			if ("command".equals(source)) {
				MessageBcmReader.printMsgToPlayer(requester, "ERROR >> Only the Room Master or an Admin can kick.");
			}
			return;
		}

		if (room.isGameStarted()) {
			MessageBcmReader.printMsgToPlayer(requester, "ERROR >> Cannot kick during a game.");
			return;
		}

		if (target.equals(requester)) {
			MessageBcmReader.printMsgToPlayer(requester, "ERROR >> You cannot kick yourself.");
			return;
		}

		System.out.println("[KICK] " + requester.getNickName() + " kicked " + target.getNickName() + " from room "
				+ (room.getRoomId() + 1) + " using " + source + ", targetSlot=" + room.getSlotPlayer(target));

		room.addBan(target);
		RoomManager.getInstance().handlePlayerLeave(target);
		room.broadcastSystemMessage("Usuario " + target.getNickName() + " foi kickado.");

		// Force room->lobby transition after kick.
		target.getPlayerCtxChannel().eventLoop().execute(() -> LobbyJoin.read(target.getPlayerCtx(), null));
		MessageBcmReader.printMsgToPlayer(target, "You were kicked from the room (5 min block).");

		if (notifyRequesterSuccess) {
			MessageBcmReader.printMsgToPlayer(requester,
					"SUCCESS >> Player " + target.getNickName() + " has been kicked and blocked for 5 minutes.");
		}
	}

	private static void closeRoom(PlayerSession player, GameRoom room) {
		// snapshot para evitar concorrÃªncia
		List<PlayerSession> recipients = new ArrayList<>(room.getPlayersBySlot().values());
		for (PlayerSession playerInRoom : recipients) {
			RoomManager.getInstance().handlePlayerLeave(playerInRoom);

			ByteBuf confirmationPacket = PacketUtils.generatePacket(playerInRoom, OPCODE_CONFIRMATION_CMD,
					Unpooled.EMPTY_BUFFER, false);

			playerInRoom.getPlayerCtxChannel().writeAndFlush(confirmationPacket)
					.addListener((ChannelFutureListener) future -> {
						if (!future.isSuccess()) {
							System.err.println("Failed to close room for: " + playerInRoom.getNickName());
							future.cause().printStackTrace();
							// Caso o jogador nao esteja impossibilitado de receber pacotes.
							playerInRoom.getPlayerCtxChannel().close();
						} else {
							System.out.println("RoomID: " + (room.getRoomId() + 1) + ", Command Sent: '0x"
									+ Integer.toHexString(OPCODE_CONFIRMATION_CMD) + "'");
							// update sem payload com RTC.
							// RoomWriter.writeRoomUpdate(playerInRoom);
						}
					});
		}
	}

	// logica para verificar se Ã© um GM.
	private static void checkIfaRoomMaster(PlayerSession player, GameRoom room) {
		if (room == null || !player.equals(room.getRoomMaster())) {
			// Apenas o dono da sala pode fechar ela.
			return;
		}
	}

}
