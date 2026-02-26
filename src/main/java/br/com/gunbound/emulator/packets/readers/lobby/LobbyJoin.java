package br.com.gunbound.emulator.packets.readers.lobby;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Optional;

import br.com.gunbound.emulator.handlers.GameAttributes;
import br.com.gunbound.emulator.lobby.GunBoundLobby;
import br.com.gunbound.emulator.lobby.GunBoundLobbyManager;
import br.com.gunbound.emulator.model.entities.game.PlayerSession;
import br.com.gunbound.emulator.packets.readers.CashUpdateReader;
import br.com.gunbound.emulator.packets.writers.model.JoinChannelSuccessPacket;
import br.com.gunbound.emulator.room.RoomManager;
import br.com.gunbound.emulator.utils.PacketUtils;
import br.com.gunbound.emulator.utils.Utils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;

public class LobbyJoin {

	// Pega instancia do LobbyManager
	private static GunBoundLobbyManager gbLobbyManager = GunBoundLobbyManager.getInstance();

	private static final int OPCODE_REQUEST = 0x2000;
	private static final int DEFAULT_LOBBY_ID = 7;
	private static final int LOBBY_ALT_ID = 2;

	public static void read(ChannelHandlerContext ctx, byte[] payload) {
		System.out.println("RECV> SVC_JOIN_CHANNEL (0x" + Integer.toHexString(OPCODE_REQUEST) + ")");

		// --- LÓGICA DE DISTRIBUIÇÃO EM AÇÃO ---
	    // 1. Ask LobbyManager to find the best channel.
	    int bestChannelId = GunBoundLobbyManager.getInstance().findBestChannelForNewPlayer();
		Integer channelId = 7; // default set for lobby 8 (1 on client)
		Integer channelIdEntered = -1;

		// If payload is empty, the request came from loginWriter (World List), so we need to assign a channel
		if (payload == null) {
			//JoinGameLobby(ctx, DEFAULT_LOBBY_ID);
			JoinGameLobby(ctx, bestChannelId);
		} else {
			// Get the requested channel
			channelId = (int) PacketUtils.readShortLEFromByteArray(payload);

			// If 0xFFFF (-1), player left channel (e.g. to shop or room)
			if (channelId == -1) {
				PlayerSession ps = ctx.channel().attr(GameAttributes.USER_SESSION).get();

				// Remove possible duplicates (when entering shop we don't leave the channel)
				gbLobbyManager.playerLeaveLobby(ps);

				// If player left a room, remove them from the room
				RoomManager roomManager = RoomManager.getInstance();

				if (roomManager.isPlayerInAnyRoom(ps)) {
					roomManager.handlePlayerLeave(ps);
				}

				//JoinGameLobby(ctx, LOBBY_ALT_ID); // se o saiu do avatar shop ou de um room
				//channelIdEntered = LOBBY_ALT_ID;
				JoinGameLobby(ctx, bestChannelId); // e.g. returned from avatar shop or room
				channelIdEntered = bestChannelId;
			} else {
				JoinGameLobby(ctx, channelId); // player requested a lobby via lobby button
				channelIdEntered = channelId;
			}

		}

		System.out.println(
				"[DEBUG][JoinLobby.read] Requested IdLobby: " + channelId + " IdLobby assigned: " + channelIdEntered);
	}

	// Private method, called only from read
	private static void JoinGameLobby(ChannelHandlerContext ctx, int channelId) {
		System.out.println("RECV> SVC_JOIN_CHANNEL PT2");

		PlayerSession player = ctx.channel().attr(GameAttributes.USER_SESSION).get();
		if (player == null) {
			System.err.println("JoinChannel: PlayerSession not found in channel context.");
			ctx.close();
			return;
		}
		// if (player.getCurrentLobby() != null) {
		// player.getCurrentLobby().removePlayerFromLobby(player);
		// }

		gbLobbyManager.playerLeaveLobby(player);

	    // Get the channel instance
	    Optional<GunBoundLobby> lobbyOpt = gbLobbyManager.getLobbyById(channelId);
	    if (lobbyOpt.isEmpty()) {
	        System.err.println("Error: Channel " + channelId + " does not exist.");
	        return;
	    }
	    GunBoundLobby lobby = lobbyOpt.get();

	    // Try to get a free slot
	    Integer newPosition = lobby.takeSlot();

	    if (newPosition == null) {
	        System.out.println("GS: Lobby is full. Player " + player.getNickName() + " cannot enter.");
	        return;
	    }

		player.setChannelPosition(newPosition);
		System.out.println("[DEBUG] Player entering position (" + player.getNickName() + "): ["
				+ player.getChannelPosition() + "]");

		// Add the player to the appropriate lobby
		gbLobbyManager.playerJoinLobby(player, channelId);

		// Delay added so that when closing a room the collection has time to load
		ctx.channel().eventLoop().schedule(() -> {
			// Intentional delay to synchronize the result
			putPlayerAtLobby(channelId, player, newPosition);
		}, 150, java.util.concurrent.TimeUnit.MILLISECONDS);

		// After entering the channel, update Cash
		CashUpdateReader.read(ctx, null);
	}

	// Method to add a delay before loading the player list
	private static void putPlayerAtLobby(int channelId, PlayerSession player,
			Integer newPosition) {
		// Get the full list of players in the same lobby. The player who just joined is already included.

		Collection<PlayerSession> playersInLobby = gbLobbyManager.getPlayersInLobby(channelId);
		// Packet constructor expects ArrayList, so we convert.
		ArrayList<PlayerSession> usersInLobby = new ArrayList<>(playersInLobby);

		String clientVersion = String.valueOf(player.getPlayerCtxChannel().attr(GameAttributes.CLIENT_VERSION).get());
		String motd = "#Gunbound Classic Thor's Hammer"; // Message of the day (can be configurable)

		// Create the packet object with the data
		JoinChannelSuccessPacket joinPacketData = new JoinChannelSuccessPacket(channelId, newPosition,
				usersInLobby, motd, clientVersion, player.getNickName());

		// Use PacketWriter to build the payload
		ByteBuf joinChannelPayload = writeJoinChannelSuccess(joinPacketData);

		// Build the final packet with the correct header
		ByteBuf finalPacket = PacketUtils.generatePacket(player, JoinChannelSuccessPacket.getOpcode(),
				joinChannelPayload, false);

		// Send the packet to the client
		player.getPlayerCtxChannel().writeAndFlush(finalPacket);

		System.out.println("GS: Channel join packet (0x2001) sent to " + player.getNickName());
		//return joinChannelPayload;
	}

	/**
	 * Builds the payload for the channel join success packet (opcode 0x2001).
	 *
	 * @param packetData The object containing all data needed for the packet.
	 * @return A ByteBuf with the payload ready to send.
	 */
	private static ByteBuf writeJoinChannelSuccess(JoinChannelSuccessPacket packetData) {
		ByteBuf buffer = Unpooled.buffer();

		// 1. Payload header: 2 null bytes followed by channel ID
		buffer.writeBytes(new byte[] { 0x00, 0x00 });

		buffer.writeShortLE(packetData.getDesiredChannelId()); // lobby 1 is index 0 on client
		// 2. Channel occupancy info (1 byte each)
		buffer.writeByte(packetData.getHighestChannelPosition());
		System.out.println("DEBUG: size.channel " + packetData.getActiveChannelUsers().size());
		buffer.writeByte(packetData.getActiveChannelUsers().size());

		// 3. Write summary data block for each player
		Collection<PlayerSession> recipients = new ArrayList<>(packetData.getActiveChannelUsers());
		for (PlayerSession player : recipients) {
			// Player position (7 bits) + power user flag (0x80)
			buffer.writeByte(player.getLobbyIdentityByte());

			buffer.writeBytes(Utils.resizeBytes(player.getNickName().getBytes(StandardCharsets.ISO_8859_1), 12));
			buffer.writeByte(player.getGender());

			// Guild (8 bytes, with padding)
			buffer.writeBytes(Utils.resizeBytes(player.getGuild().getBytes(StandardCharsets.ISO_8859_1), 8));

			// Current rank (2 bytes) and Season rank (2 bytes)
			buffer.writeShortLE(player.getRankCurrent());
			buffer.writeShortLE(player.getRankSeason());
		}

		// 4. Channel MOTD (Message of the Day)
		String motd = packetData.getExtendedChannelMotd();
		byte[] motdBytes = motd.getBytes(StandardCharsets.ISO_8859_1);

		// Write MOTD bytes directly, without a length prefix.
		buffer.writeBytes(motdBytes);

		return buffer;
	}
}
