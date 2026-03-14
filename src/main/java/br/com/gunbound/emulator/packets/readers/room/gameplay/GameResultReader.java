package br.com.gunbound.emulator.packets.readers.room.gameplay;

import java.util.Map;

import org.mariadb.jdbc.plugin.authentication.standard.ed25519.Utils;

import br.com.gunbound.emulator.handlers.GameAttributes;
import br.com.gunbound.emulator.model.DAO.DAOFactory;
import br.com.gunbound.emulator.model.DAO.UserDAO;
import br.com.gunbound.emulator.model.entities.game.PlayerGameResult;
import br.com.gunbound.emulator.model.entities.game.PlayerSession;
import br.com.gunbound.emulator.packets.writers.RoomWriter;
import br.com.gunbound.emulator.room.GameRoom;
import br.com.gunbound.emulator.utils.PacketUtils;
import br.com.gunbound.emulator.utils.crypto.GunBoundCipher;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;

public class GameResultReader {

	private static final int OPCODE_REQUEST = 0x4412;
	private static final int OPCODE_CONFIRMATION = 0x4413;

	public static void read(ChannelHandlerContext ctx, byte[] payload) {
		System.out.println("RECV> SVC_PLAY_RESULT (0x" + Integer.toHexString(OPCODE_REQUEST) + ")");
		PlayerSession ps = ctx.channel().attr(GameAttributes.USER_SESSION).get();
		if (ps == null)
			return;

		GameRoom room = ps.getCurrentRoom();
		if (room == null)
			return;

		try {
			// 1. Descriptografa o payload do chat
			byte[] authToken = ctx.channel().attr(GameAttributes.AUTH_TOKEN).get();
			byte[] decryptedPayload = GunBoundCipher.gunboundDynamicDecrypt(payload, ps.getUserNameId(),
					ps.getPassword(), authToken, OPCODE_REQUEST);

			System.out.println("ResultGame decrypted >> " + Utils.bytesToHex(decryptedPayload));
			
			
			ctx.channel().eventLoop().schedule(() -> {
				processEndGame(room, decryptedPayload);
			}, 150, java.util.concurrent.TimeUnit.MILLISECONDS);
			
			
			//processEndGame(room, decryptedPayload);

		} catch (Exception e) {
			System.err.println("Erro ao processar pacote ResultGame: " + e.getMessage());
			e.printStackTrace();
		}

	}

	public static void processEndGame(GameRoom room, byte[] payload) {
		ByteBuf rcvPayload = Unpooled.wrappedBuffer(payload);
		int parsedResults = 0;

		try {
			if (!rcvPayload.isReadable()) {
				System.err.println("GameResultReader: payload vazio em 0x4412.");
			} else {
				int qtdPlayers = rcvPayload.readUnsignedByte();
				for (int i = 0; i < qtdPlayers; i++) {
					// Cada entrada de resultado requer no mínimo 18 bytes neste layout.
					if (rcvPayload.readableBytes() < 18) {
						System.err.println("GameResultReader: payload de resultado curto para slot " + i + ". readable="
								+ rcvPayload.readableBytes());
						break;
					}

					rcvPayload.skipBytes(1);

					int slot = rcvPayload.readUnsignedByte();
					int gold = rcvPayload.readUnsignedShortLE();
					int bonusGold = rcvPayload.readUnsignedShortLE();
					rcvPayload.readUnsignedShortLE();
					int gp = rcvPayload.readUnsignedShortLE();
					int bonusGp = rcvPayload.readUnsignedShortLE();
					rcvPayload.readUnsignedShortLE();
					rcvPayload.readUnsignedShortLE();
					rcvPayload.readUnsignedShortLE();

					PlayerGameResult pResult = new PlayerGameResult(gold, bonusGold, gp, bonusGp);
					System.out.println("pResult >>> SLOT: " + slot + " Valores: " + pResult);
					room.setResultGameBySlot(slot, pResult);
					parsedResults++;
				}

				// Alguns clientes enviam trailer de 8 bytes, outros não (ex: Jewel solo).
				if (rcvPayload.readableBytes() >= 8) {
					rcvPayload.skipBytes(8);
				}
			}

			persistMatchRewards(room, parsedResults);
		} catch (Exception e) {
			System.err.println("Erro ao parsear resultado da partida (0x4412): " + e.getMessage());
			e.printStackTrace();
		} finally {
			rcvPayload.release();
		}

		sendResultConfirmation(room);
		room.isGameStarted(false);// sala deixa de estar em estado playing
		RoomWriter.broadcastLobbyRoomListRefresh();
	}

	private static void sendResultConfirmation(GameRoom room) {
		if (room == null) {
			return;
		}

		try {
			for (Map.Entry<Integer, PlayerSession> entry : room.getPlayersBySlot().entrySet()) {
				PlayerSession player = entry.getValue();
				ByteBuf confirmationPacket = PacketUtils.generatePacket(player, OPCODE_CONFIRMATION, Unpooled.EMPTY_BUFFER,
						false);
				player.getPlayerCtxChannel().writeAndFlush(confirmationPacket);
			}
		} catch (Exception e) {
			System.err.println("Erro ao enviar confirmação 0x4413");
			e.printStackTrace();
		}
	}
	
	private static void persistMatchRewards(GameRoom room, int reportedResultCount) {
		if (room == null) {
			return;
		}

		if (room.getResultGameBySlot().size() < reportedResultCount) {
			return;
		}

		if (!room.tryPersistMatchRewards()) {
			System.out.println("[MATCH_REWARD] Rewards already persisted for room " + (room.getRoomId() + 1));
			return;
		}

		try (UserDAO userDAO = DAOFactory.CreateUserDao()) {
			for (Map.Entry<Integer, PlayerSession> entry : room.getPlayersBySlot().entrySet()) {
				int slot = entry.getKey();
				PlayerSession player = entry.getValue();
				PlayerGameResult pResult = room.getResultGameBySlot().get(slot);
				if (pResult == null) {
					continue;
				}

				int goldDelta = sumRewards(pResult.getNormalGold(), pResult.getBonusGold());
				int gpDelta = sumRewards(pResult.getNormalGp(), pResult.getBonusGp());
				if (goldDelta == 0 && gpDelta == 0) {
					continue;
				}

				userDAO.applyMatchResult(player.getUserNameId(), goldDelta, gpDelta);
				player.setGold(Math.max(0, player.getGold() + goldDelta));
				player.setTotalScore(Math.max(0, player.getTotalScore() + gpDelta));
				player.setSeasonScore(Math.max(0, player.getSeasonScore() + gpDelta));
				System.out.println("[MATCH_REWARD] " + player.getNickName() + " gold=" + goldDelta + ", gp=" + gpDelta);
			}
		} catch (Exception e) {
			System.err.println("Erro ao persistir recompensa da partida: " + e.getMessage());
			e.printStackTrace();
		}
	}
	
	private static int sumRewards(Integer normal, Integer bonus) {
		int normalValue = normal == null ? 0 : normal.intValue();
		int bonusValue = bonus == null ? 0 : bonus.intValue();
		return normalValue + bonusValue;
	}

}
