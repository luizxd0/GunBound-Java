package br.com.gunbound.emulator.packets.readers.room.change;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import br.com.gunbound.emulator.handlers.GameAttributes;
import br.com.gunbound.emulator.model.DAO.DAOFactory;
import br.com.gunbound.emulator.model.DAO.UserDAO;
import br.com.gunbound.emulator.model.entities.game.PlayerSession;
import br.com.gunbound.emulator.model.entities.game.PlayerSessionManager;
import br.com.gunbound.emulator.packets.readers.CashUpdateReader;
import br.com.gunbound.emulator.packets.readers.GoldUpdateReader;
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
import io.netty.channel.ChannelOption;

public class RoomCommandReader {

	private static final int OPCODE_REQUEST = 0x5100;
	private static final int OPCODE_CONFIRMATION_CMD = 0x3FFF;
	private static final int AUTHORITY_ADMIN = 100;
	private static final long KICK_REJOIN_BLOCK_MILLIS = TimeUnit.MINUTES.toMillis(5);
	private static final Map<String, Long> KICK_REJOIN_BLOCK = new ConcurrentHashMap<>();

	public static void read(ChannelHandlerContext ctx, byte[] payload) {
		System.out.println("RECV> GENERIC_COMMAND (0x" + Integer.toHexString(OPCODE_REQUEST) + ")");
		PlayerSession player = ctx.channel().attr(GameAttributes.USER_SESSION).get();

		if (player == null) {
			return;
		}

		processCommand(ctx, payload, player);
	}

	private static void processCommand(ChannelHandlerContext ctx, byte[] payload, PlayerSession player) {
		if (payload == null || payload.length <= 1) {
			return;
		}

		ByteBuf request = Unpooled.wrappedBuffer(payload).skipBytes(1);
		String decodedCommand = Utils.stringDecode(request).trim();
		request.release();

		if (decodedCommand.isEmpty()) {
			return;
		}

		String[] commandParts = decodedCommand.split("\\s+", 2);
		String command = normalizeCommand(commandParts[0]);
		String paramCmd = commandParts.length > 1 ? commandParts[1].trim() : "";
		System.out.println("COMMAND: " + command + " PARAM: " + paramCmd);

		switch (command) {
		case "close":
			handleCloseCommand(ctx, player);
			break;
		case "bcm":
			handleBcmCommand(player, paramCmd);
			break;
		case "disconnect":
			handleDisconnectCommand(player, paramCmd);
			break;
		case "add":
			handleAddCommand(player, paramCmd);
			break;
		case "kick":
			handleKickCommand(ctx, player, paramCmd);
			break;
		case "start":
			handleStartCommand(ctx, player);
			break;
		default:
			MessageBcmReader.printMsgToPlayer(player, "ADMIN >> Unknown Command");
			break;
		}
	}

	private static void handleCloseCommand(ChannelHandlerContext ctx, PlayerSession player) {
		GameRoom room = player.getCurrentRoom();
		if (room == null) {
			MessageBcmReader.printMsgToPlayer(player, "ADMIN >> You are not in a room.");
			return;
		}
		if (!isRoomMaster(player, room)) {
			MessageBcmReader.printMsgToPlayer(player, "ADMIN >> Only room master can use /close.");
			return;
		}

		MessageBcmReader.printMsgToPlayer(player, "The Room Was Closed");
		room.submitAction(() -> closeRoom(room), ctx);
	}

	private static void handleBcmCommand(PlayerSession player, String message) {
		if (!isAdmin(player)) {
			MessageBcmReader.printMsgToPlayer(player, "ADMIN >> You do not have permission.");
			return;
		}
		if (message == null || message.isBlank()) {
			MessageBcmReader.printMsgToPlayer(player, "ADMIN >> Usage: /bcm Message");
			return;
		}
		MessageBcmReader.broadcastSendMessage(message);
	}

	private static void handleDisconnectCommand(PlayerSession actor, String paramCmd) {
		if (!isAdmin(actor)) {
			MessageBcmReader.printMsgToPlayer(actor, "ADMIN >> You do not have permission.");
			return;
		}
		if (paramCmd == null || paramCmd.isBlank()) {
			MessageBcmReader.printMsgToPlayer(actor, "ADMIN >> Usage: /disconnect NickName");
			return;
		}

		String nickName = paramCmd.split("\\s+")[0];
		PlayerSession target = PlayerSessionManager.getInstance().getSessionPlayerByNickname(nickName);
		if (target == null) {
			MessageBcmReader.printMsgToPlayer(actor, "ADMIN >> Player not found online: " + nickName);
			return;
		}

		MessageBcmReader.printMsgToPlayer(actor, "ADMIN >> Disconnecting: " + target.getNickName());
		forcePlayerDisconnect(target);
	}

	private static void handleAddCommand(PlayerSession actor, String paramCmd) {
		if (!isAdmin(actor)) {
			MessageBcmReader.printMsgToPlayer(actor, "ADMIN >> You do not have permission.");
			return;
		}

		String[] parts = paramCmd.split("\\s+");
		if (parts.length < 3) {
			MessageBcmReader.printMsgToPlayer(actor, "ADMIN >> Usage: /add cash|gold NickName Amount");
			return;
		}

		String currency = parts[0].toLowerCase(Locale.ROOT);
		String nickName = parts[1];
		Integer amount = parsePositiveInteger(parts[2]);
		if (amount == null) {
			MessageBcmReader.printMsgToPlayer(actor, "ADMIN >> Amount must be a positive number.");
			return;
		}

		PlayerSession target = PlayerSessionManager.getInstance().getSessionPlayerByNickname(nickName);
		if (target == null) {
			MessageBcmReader.printMsgToPlayer(actor, "ADMIN >> Player not found online: " + nickName);
			return;
		}

		try (UserDAO userDAO = DAOFactory.CreateUserDao()) {
			if ("cash".equals(currency)) {
				target.setCash(target.getCash() + amount);
				userDAO.updateAddCash(target.getUserNameId(), amount);
				CashUpdateReader.read(target.getPlayerCtx(), null);
			} else if ("gold".equals(currency)) {
				target.setGold(target.getGold() + amount);
				userDAO.updateAddGold(target.getUserNameId(), amount);
				GoldUpdateReader.goldUpdateWriter(target.getPlayerCtx());
			} else {
				MessageBcmReader.printMsgToPlayer(actor, "ADMIN >> Usage: /add cash|gold NickName Amount");
				return;
			}
		} catch (Exception e) {
			System.err.println("Falha ao executar /add: " + e.getMessage());
			MessageBcmReader.printMsgToPlayer(actor, "ADMIN >> Failed to update balance.");
			return;
		}

		MessageBcmReader.printMsgToPlayer(actor,
				"ADMIN >> Added " + amount + " " + currency + " to " + target.getNickName());
		MessageBcmReader.printMsgToPlayer(target,
				"ADMIN >> You received +" + amount + " " + currency + " from GM.");
	}

	private static void handleKickCommand(ChannelHandlerContext ctx, PlayerSession actor, String paramCmd) {
		GameRoom room = actor.getCurrentRoom();
		if (room == null) {
			MessageBcmReader.printMsgToPlayer(actor, "ADMIN >> You are not in a room.");
			return;
		}
		if (!isRoomMaster(actor, room)) {
			MessageBcmReader.printMsgToPlayer(actor, "ADMIN >> Only room master can use /kick.");
			return;
		}
		if (paramCmd == null || paramCmd.isBlank()) {
			MessageBcmReader.printMsgToPlayer(actor, "ADMIN >> Usage: /kick NickName");
			return;
		}

		PlayerSession target = findPlayerInRoomByNickname(room, paramCmd);
		if (target == null) {
			MessageBcmReader.printMsgToPlayer(actor, "ADMIN >> Player not found in room.");
			return;
		}
		if (target.equals(actor)) {
			MessageBcmReader.printMsgToPlayer(actor, "ADMIN >> You cannot kick yourself.");
			return;
		}

		requestKickByMaster(ctx, actor, target);
	}

	private static void handleStartCommand(ChannelHandlerContext ctx, PlayerSession actor) {
		GameRoom room = actor.getCurrentRoom();
		if (room == null) {
			MessageBcmReader.printMsgToPlayer(actor, "ADMIN >> You are not in a room.");
			return;
		}
		if (room.isGameStarted()) {
			MessageBcmReader.printMsgToPlayer(actor, "ADMIN >> Game is already in progress.");
			return;
		}

		boolean gmOverride = isAdmin(actor);
		boolean jewelMode = GameMode.fromId(room.getGameMode()) == GameMode.JEWEL;
		if (!gmOverride && !jewelMode) {
			MessageBcmReader.printMsgToPlayer(actor,
					"ADMIN >> /start is GM-only outside Jewel mode.");
			return;
		}
		if (!gmOverride && !isRoomMaster(actor, room)) {
			MessageBcmReader.printMsgToPlayer(actor,
					"ADMIN >> Only the room master can use /start.");
			return;
		}
		if (!gmOverride && !areNonMasterPlayersReady(room)) {
			MessageBcmReader.printMsgToPlayer(actor,
					"ADMIN >> All other players must be ready to use /start.");
			return;
		}

		room.submitAction(() -> room.startGame(null), ctx);
	}

	private static boolean areNonMasterPlayersReady(GameRoom room) {
		if (room == null) {
			return false;
		}
		PlayerSession master = room.getRoomMaster();
		for (var entry : room.getPlayersBySlot().entrySet()) {
			PlayerSession player = entry.getValue();
			if (player == null || player.equals(master)) {
				continue;
			}
			int slot = entry.getKey();
			if (!room.isPlayerReady(slot)) {
				return false;
			}
		}
		return true;
	}

	private static void closeRoom(GameRoom room) {
		List<PlayerSession> recipients = new ArrayList<>(room.getPlayersBySlot().values());
		for (PlayerSession playerInRoom : recipients) {
			RoomManager.getInstance().handlePlayerLeave(playerInRoom);

			ByteBuf confirmationPacket = PacketUtils.generatePacket(playerInRoom, OPCODE_CONFIRMATION_CMD,
					Unpooled.EMPTY_BUFFER, false);

			playerInRoom.getPlayerCtxChannel().writeAndFlush(confirmationPacket)
					.addListener((ChannelFutureListener) future -> {
						if (!future.isSuccess()) {
							System.err.println("Falha ao fechar sala para: " + playerInRoom.getNickName());
							future.cause().printStackTrace();
							playerInRoom.getPlayerCtxChannel().close();
						} else {
							System.out.println("RoomID: " + (room.getRoomId() + 1) + ", Command Sent: '0x"
									+ Integer.toHexString(OPCODE_CONFIRMATION_CMD) + "'");
						}
					});
		}
	}

	private static void kickPlayerFromRoom(GameRoom room, PlayerSession actor, PlayerSession target) {
		if (target.getCurrentRoom() == null || !target.getCurrentRoom().equals(room)) {
			MessageBcmReader.printMsgToPlayer(actor, "ADMIN >> Player already left the room.");
			return;
		}

		registerKickRestriction(target, room.getRoomId());
		RoomManager.getInstance().handlePlayerLeave(target);

		MessageBcmReader.printMsgToPlayer(actor, "ADMIN >> " + target.getNickName() + " was kicked.");
		MessageBcmReader.printMsgToPlayer(target,
				"You were kicked from room. Rejoin available in " + formatDuration(KICK_REJOIN_BLOCK_MILLIS) + ".");

		if (target.getPlayerCtx() != null && target.getPlayerCtxChannel().isActive()) {
			LobbyJoin.read(target.getPlayerCtx(), null);
		}
	}

	private static PlayerSession findPlayerInRoomByNickname(GameRoom room, String paramCmd) {
		String targetNick = paramCmd.split("\\s+")[0];
		for (PlayerSession playerInRoom : room.getPlayersBySlot().values()) {
			if (playerInRoom.getNickName().equalsIgnoreCase(targetNick)) {
				return playerInRoom;
			}
		}
		return null;
	}

	private static String normalizeCommand(String command) {
		String normalized = command.trim().toLowerCase(Locale.ROOT);
		if (normalized.startsWith("/")) {
			normalized = normalized.substring(1);
		}
		return normalized;
	}

	private static Integer parsePositiveInteger(String value) {
		try {
			int parsed = Integer.parseInt(value);
			return parsed > 0 ? parsed : null;
		} catch (NumberFormatException e) {
			return null;
		}
	}

	private static boolean isAdmin(PlayerSession player) {
		return player.getAuthority() >= AUTHORITY_ADMIN;
	}

	private static boolean isRoomMaster(PlayerSession player, GameRoom room) {
		return room != null && player.equals(room.getRoomMaster());
	}

	public static void requestKickByMaster(ChannelHandlerContext ctx, PlayerSession actor, PlayerSession target) {
		if (ctx == null || actor == null || target == null) {
			return;
		}

		GameRoom room = actor.getCurrentRoom();
		if (room == null) {
			MessageBcmReader.printMsgToPlayer(actor, "ADMIN >> You are not in a room.");
			return;
		}
		if (!isRoomMaster(actor, room)) {
			MessageBcmReader.printMsgToPlayer(actor, "ADMIN >> Only room master can use /kick.");
			return;
		}
		if (target.equals(actor)) {
			MessageBcmReader.printMsgToPlayer(actor, "ADMIN >> You cannot kick yourself.");
			return;
		}

		room.submitAction(() -> kickPlayerFromRoom(room, actor, target), ctx);
	}

	public static boolean isRoomKickRestricted(PlayerSession player, int roomId) {
		long now = System.currentTimeMillis();
		String restrictionKey = buildKickRestrictionKey(player, roomId);
		Long expiresAt = KICK_REJOIN_BLOCK.get(restrictionKey);

		if (expiresAt == null) {
			return false;
		}
		if (expiresAt <= now) {
			KICK_REJOIN_BLOCK.remove(restrictionKey, expiresAt);
			return false;
		}
		return true;
	}

	public static long getKickRestrictionRemainingMillis(PlayerSession player, int roomId) {
		long now = System.currentTimeMillis();
		String restrictionKey = buildKickRestrictionKey(player, roomId);
		Long expiresAt = KICK_REJOIN_BLOCK.get(restrictionKey);

		if (expiresAt == null || expiresAt <= now) {
			if (expiresAt != null) {
				KICK_REJOIN_BLOCK.remove(restrictionKey, expiresAt);
			}
			return 0L;
		}
		return expiresAt - now;
	}

	private static void registerKickRestriction(PlayerSession player, int roomId) {
		String restrictionKey = buildKickRestrictionKey(player, roomId);
		long expiresAt = System.currentTimeMillis() + KICK_REJOIN_BLOCK_MILLIS;
		KICK_REJOIN_BLOCK.put(restrictionKey, expiresAt);
	}

	private static String buildKickRestrictionKey(PlayerSession player, int roomId) {
		return player.getUserNameId().toLowerCase(Locale.ROOT) + ":" + roomId;
	}

	private static String formatDuration(long millis) {
		long totalSeconds = Math.max(1L, (millis + 999L) / 1000L);
		long minutes = totalSeconds / 60L;
		long seconds = totalSeconds % 60L;
		return minutes + "m " + seconds + "s";
	}

	private static void forcePlayerDisconnect(PlayerSession target) {
		if (target == null || target.getPlayerCtxChannel() == null || !target.getPlayerCtxChannel().isActive()) {
			return;
		}

		// Force RST-style close to trigger immediate client-side connection popup (201 / 10053).
		try {
			target.getPlayerCtxChannel().config().setOption(ChannelOption.SO_LINGER, 0);
		} catch (Exception e) {
			// Fallback to normal close if the transport does not allow SO_LINGER changes.
		}

		target.getPlayerCtxChannel().eventLoop().execute(() -> {
			target.getPlayerCtxChannel().close();
		});
	}
}
