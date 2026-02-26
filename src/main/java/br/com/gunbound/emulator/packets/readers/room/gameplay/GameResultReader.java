package br.com.gunbound.emulator.packets.readers.room.gameplay;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;

import org.mariadb.jdbc.plugin.authentication.standard.ed25519.Utils;

import br.com.gunbound.emulator.handlers.GameAttributes;
import br.com.gunbound.emulator.model.DAO.DAOFactory;
import br.com.gunbound.emulator.model.DAO.UserDAO;
import br.com.gunbound.emulator.packets.readers.LoginReader;
import br.com.gunbound.emulator.model.entities.game.PlayerGameResult;
import br.com.gunbound.emulator.model.entities.game.PlayerSession;
import br.com.gunbound.emulator.packets.readers.CashUpdateReader;
import br.com.gunbound.emulator.room.GameRoom;
import br.com.gunbound.emulator.utils.PacketUtils;
import br.com.gunbound.emulator.utils.crypto.GunBoundCipher;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;

public class GameResultReader {

	private static final int OPCODE_REQUEST = 0x4412;
	private static final int OPCODE_CONFIRMATION = 0x4413;
	private static final int OPCODE_RESULT = 0x4415;

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
			System.err.println("Error processing ResultGame packet: " + e.getMessage());
			e.printStackTrace();
		}

	}

	public static void processEndGame(GameRoom room, byte[] payload) {
		// Some modes trigger endgame (0x4410) before 0x4412 arrives.
		// Guard by room state instead of endGame flag so stats are still persisted.
		if (!room.isGameStarted()) {
			System.out.println("Ignoring 0x4412 for room " + (room.getRoomId() + 1)
					+ " because it is already in waiting state.");
			return;
		}

		ByteBuf rcvPayload = Unpooled.wrappedBuffer(payload);
		try {
			if (!rcvPayload.isReadable()) {
				System.err.println("ResultGame payload is empty.");
				return;
			}

			int qtdPlayers = rcvPayload.readUnsignedByte();

			for (int i = 0; i < qtdPlayers; i++) {
				// Some clients (notably solo Jewel) can send a shorter result payload.
				// Guard reads to avoid IndexOutOfBounds and still finish match cleanup.
				if (rcvPayload.readableBytes() < 16) {
					System.err.println("ResultGame payload shorter than expected for player index " + i
							+ ". Remaining bytes: " + rcvPayload.readableBytes());
					break;
				}

				rcvPayload.skipBytes(1);

				int slot = rcvPayload.readUnsignedByte();
				int gold = rcvPayload.readShortLE();
				int bonusGold = rcvPayload.readShortLE();
				rcvPayload.readShortLE();
				int gp = rcvPayload.readShortLE();
				int bonusGp = rcvPayload.readShortLE();
				int shot = rcvPayload.readUnsignedShortLE();
				int damage = rcvPayload.readUnsignedShortLE();
				rcvPayload.readShortLE();

				PlayerGameResult pResult = new PlayerGameResult(gold, bonusGold, gp, bonusGp, shot, damage);

				System.out.println("pResult >>> SLOT: " + slot + " Valores: " + pResult);
				room.setResultGameBySlot(slot, pResult);
			}

			// Trailer is not always 8 bytes in every client/version packet variant.
			if (rcvPayload.readableBytes() >= 8) {
				rcvPayload.skipBytes(8);
			} else {
				rcvPayload.skipBytes(rcvPayload.readableBytes());
			}

			ByteBuf buffer = Unpooled.EMPTY_BUFFER;

			for (Map.Entry<Integer, PlayerSession> entry : room.getPlayersBySlot().entrySet()) {
				PlayerSession player = entry.getValue();
				applyAndPersistMatchRewards(room, player);
				//int playerTxSum = player.getPlayerCtx().attr(GameAttributes.PACKET_TX_SUM).get();
				ByteBuf confirmationPacket = PacketUtils.generatePacket(player, OPCODE_CONFIRMATION, buffer,false);
				player.getPlayerCtxChannel().writeAndFlush(confirmationPacket);
				// Push immediate client refresh for currency (gold/cash) without relogin.
				CashUpdateReader.read(player.getPlayerCtx(), null);
				// Push state packet containing GP fields (TotalScore/SeasonScore).
				LoginReader.pushSessionStatsRefresh(player);

			}

			
			

		} catch (Exception e) {
			System.err.println("Error processing match result");
			e.printStackTrace();
		} finally {
			if (rcvPayload.refCnt() > 0) {
				rcvPayload.release();
			}
		}
		
		//announceScorePlayer(room);
		room.finishMatchIfRunning("GameResultReader:0x4412");
	}

	private static void applyAndPersistMatchRewards(GameRoom room, PlayerSession player) {
		int slot = room.getSlotPlayer(player);
		PlayerGameResult result = room.getResultGameBySlot().get(slot);
		if (result == null) {
			return;
		}

		int goldDelta = result.getNormalGold() + result.getBonusGold();
		int gpDelta = result.getNormalGp() + result.getBonusGp();
		int shotDelta = Math.max(result.getShot(), 0);
		int damageDelta = Math.max(result.getDamage(), 0);
		int winnerTeam = room.checkGameEndAndGetWinner();
		int winDelta = winnerTeam != -1 && player.getRoomTeam() == winnerTeam ? 1 : 0;
		int lossDelta = winnerTeam != -1 && player.getRoomTeam() != winnerTeam ? 1 : 0;

		// Keep session values in sync for subsequent packets/lobby updates.
		player.setGold(player.getGold() + goldDelta);
		player.setTotalScore(player.getTotalScore() + gpDelta);
		player.setSeasonScore(player.getSeasonScore() + gpDelta);
		player.setEventScore0(player.getEventScore0() + winDelta);
		player.setEventScore1(player.getEventScore1() + lossDelta);
		player.setAccumShot(player.getAccumShot() + shotDelta);
		player.setAccumDamage(player.getAccumDamage() + damageDelta);

		// Persist rewards to database.
		UserDAO userDAO = DAOFactory.CreateUserDao();
		userDAO.updateAddMatchStats(player.getUserNameId(), goldDelta, gpDelta, winDelta, lossDelta, shotDelta, damageDelta);
	}

	//This is Just used on Season 2 Versions.
	private static void announceScorePlayer(GameRoom room) {

		Collection<PlayerSession> recipients = new ArrayList<>(room.getPlayersBySlot().values());

		ByteBuf buffer = Unpooled.buffer();
		buffer.writeByte(0);
		buffer.writeShortLE(recipients.size());

		for (PlayerSession player : recipients) {

			int slot = player.getCurrentRoom().getSlotPlayer(player);

			PlayerGameResult pResult = room.getResultGameBySlot().get(slot);
			
			System.out.println("announceScorePlayer | pResult >>> SLOT: " + slot + " Valores: " + pResult);

			buffer.writeByte(slot);
			buffer.writeShortLE(pResult.getNormalGold());// NormalGold
			buffer.writeShortLE(0);// Unknown
			buffer.writeShortLE(pResult.getBonusGold());// BonusGold
			buffer.writeShortLE(0);// Unknown
			buffer.writeIntLE(0);// Unknown
			buffer.writeShortLE(pResult.getNormalGp());// NormalGP
			buffer.writeShortLE(pResult.getBonusGp());// BonusGP
			buffer.writeShortLE(0);// Unknown
			buffer.writeByte(0);// gb icon
			buffer.writeByte(0);// Unknown
			buffer.writeByte(100);// Hielo
			buffer.writeByte(0);// kill
			//buffer.writeBytes(new byte[] { (byte) 0x00, (byte) 0x00, (byte) 0x00, 
					//(byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00 });

		}

		// Adiciona um padding para garantir o alinhamento de 12 bytes. (Por conta da
		// criptografia)
		int currentSize = buffer.writerIndex();
		int paddingSize = (12 - (currentSize % 12)) % 12; // O % 12 no final lida com o caso de já ser múltiplo.
		if (paddingSize > 0) {
			buffer.writeBytes(new byte[paddingSize]);
		}

		byte[] payloadSend = new byte[buffer.readableBytes()];
		buffer.readBytes(payloadSend);
		buffer.release(); // Libera o buffer temporário.
		
		System.out.println("To Encrpyt: >> " + Utils.bytesToHex(payloadSend));

		for (PlayerSession player : recipients) {

			// Converte o conteúdo do buffer para um array de bytes.

			byte[] encryptedPayload;
			try {
				encryptedPayload = GunBoundCipher.gunboundDynamicEncrypt(payloadSend, player.getUserNameId(),
						player.getPassword(), player.getPlayerCtxChannel().attr(GameAttributes.AUTH_TOKEN).get(), OPCODE_RESULT);

				// Gera o pacote final e envia
				//int txSum = player.getPlayerCtx().attr(GameAttributes.PACKET_TX_SUM).get();
				ByteBuf finalPacket = PacketUtils.generatePacket(player, 0x4415,
						Unpooled.wrappedBuffer(encryptedPayload),false);

				// Enviando packet
				player.getPlayerCtxChannel().eventLoop().execute(() -> {
					player.getPlayerCtxChannel().writeAndFlush(finalPacket);
				});
			} catch (Exception e) {
				e.printStackTrace();
			}
		}

		room.finishMatchIfRunning("GameResultReader:announceScorePlayer");
	}

}