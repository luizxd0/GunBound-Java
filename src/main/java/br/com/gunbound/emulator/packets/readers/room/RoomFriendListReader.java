package br.com.gunbound.emulator.packets.readers.room;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import br.com.gunbound.emulator.handlers.GameAttributes;
import br.com.gunbound.emulator.model.entities.game.PlayerSession;
import br.com.gunbound.emulator.model.DAO.BuddyDAO;
import br.com.gunbound.emulator.model.DAO.impl.BuddyJDBC;
import br.com.gunbound.emulator.packets.writers.RoomWriter;
import br.com.gunbound.emulator.room.GameRoom;
import br.com.gunbound.emulator.room.RoomManager;
import br.com.gunbound.emulator.utils.PacketUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;

/**
 * Handles friend-room list requests (0x2101).
 *
 * Client behavior (reverse engineered from Gunbound.gme):
 * - First request carries a list of friend room IDs (u16 LE each).
 * - Paging requests carry a 2-byte cursor token based on 0x08D8 + (startIndex*2).
 * - Response expected by client is still NS_CHANNEL_GAMELIST (0x2103).
 */
public class RoomFriendListReader {

	private static final int OPCODE_REQUEST = 0x2101;
	private static final int OPCODE_RESPONSE = 0x2103;
	private static final int ROOMS_PER_PAGE = 6;
	private static final int FRIEND_CURSOR_BASE = 0x08D8;

	private static final BuddyDAO buddyDAO = new BuddyJDBC();

	public static void read(ChannelHandlerContext ctx, byte[] payload) {
		System.out.println("RECV> SVC_ROOM_FRIEND_LIST (0x" + Integer.toHexString(OPCODE_REQUEST) + ")");

		PlayerSession player = ctx.channel().attr(GameAttributes.USER_SESSION).get();
		if (player == null) {
			return;
		}

		int startIndex = 0;

		ByteBuf request = Unpooled.wrappedBuffer(payload);
		try {
			if (request.readableBytes() >= 2) {
				int value = request.readUnsignedShortLE();
				if (value >= FRIEND_CURSOR_BASE) {
					// Paging token used by the original client while in Friend mode.
					startIndex = decodeFriendCursor(value);
				}
			}
		} catch (Exception e) {
			System.err.println("Erro ao processar lista de salas de amigos");
			e.printStackTrace();
		} finally {
			request.release();
		}

		List<GameRoom> roomsForPage = buildFriendRoomsPage(player, startIndex);
		ctx.channel().attr(GameAttributes.CURRENT_ROOM_LIST_FILTER).set(3);
		ctx.channel().attr(GameAttributes.CURRENT_ROOM_LIST_START_INDEX).set(Math.max(0, startIndex));
		ctx.channel().attr(GameAttributes.CURRENT_ROOM_LIST_ROOM_IDS).set(
				roomsForPage.stream().map(GameRoom::getRoomId).collect(Collectors.toList()));
		ByteBuf responsePayload = RoomWriter.writeRoomList(roomsForPage);
		ByteBuf responsePacket = PacketUtils.generatePacket(player, OPCODE_RESPONSE, responsePayload, true);
		ctx.writeAndFlush(responsePacket);
	}

	private static int decodeFriendCursor(int cursorToken) {
		if (cursorToken <= FRIEND_CURSOR_BASE) {
			return 0;
		}
		return (cursorToken - FRIEND_CURSOR_BASE) / 2;
	}

	private static List<GameRoom> buildFriendRoomsPage(PlayerSession player, int startIndex) {
		if (player == null || player.getUserNameId() == null || player.getUserNameId().isBlank()) {
			return Collections.emptyList();
		}

		Set<String> friendIds = new HashSet<>();
		for (Map<String, Object> entry : buddyDAO.getBuddyList(player.getUserNameId())) {
			Object friend = entry.get("Buddy");
			if (friend instanceof String) {
				String friendId = ((String) friend).trim();
				if (!friendId.isBlank()) {
					friendIds.add(friendId);
				}
			}
		}

		if (friendIds.isEmpty()) {
			return Collections.emptyList();
		}

		List<GameRoom> friendRooms = RoomManager.getInstance().getAllRooms().stream()
				.filter(room -> room != null && room.getRoomMaster() != null
						&& friendIds.contains(room.getRoomMaster().getUserNameId()))
				.sorted(Comparator.comparingInt((GameRoom room) -> room.hasPowerUserHost() ? 0 : 1)
						.thenComparingInt(GameRoom::getRoomId))
				.collect(Collectors.toList());

		if (friendRooms.isEmpty()) {
			return Collections.emptyList();
		}

		int safeStart = Math.max(0, Math.min(startIndex, friendRooms.size()));
		int endIndex = Math.min(safeStart + ROOMS_PER_PAGE, friendRooms.size());

		return new ArrayList<>(friendRooms.subList(safeStart, endIndex));
	}
}
