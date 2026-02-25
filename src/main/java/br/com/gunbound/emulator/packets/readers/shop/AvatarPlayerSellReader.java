package br.com.gunbound.emulator.packets.readers.shop;

import br.com.gunbound.emulator.handlers.GameAttributes;
import br.com.gunbound.emulator.model.DAO.ChestDAO;
import br.com.gunbound.emulator.model.DAO.DAOFactory;
import br.com.gunbound.emulator.model.DAO.UserDAO;
import br.com.gunbound.emulator.model.entities.DTO.ChestDTO;
import br.com.gunbound.emulator.model.entities.DTO.MenuDTO;
import br.com.gunbound.emulator.model.entities.game.GameMenu;
import br.com.gunbound.emulator.model.entities.game.PlayerSession;
import br.com.gunbound.emulator.packets.readers.CashUpdateReader;
import br.com.gunbound.emulator.utils.PacketUtils;
import br.com.gunbound.emulator.utils.crypto.GunBoundCipher;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;

public class AvatarPlayerSellReader {

	private static final int OPCODE_REQUEST_LEGACY = 0x6022;
	private static final int OPCODE_CONFIRMATION_LEGACY = 0x6023;
	private static final int OPCODE_REQUEST = 0x6024;
	private static final int OPCODE_CONFIRMATION = 0x6025;

	public static void read(ChannelHandlerContext ctx, byte[] payload) {
		System.out.println("RECV> SVC_PROP_SELL (0x" + Integer.toHexString(OPCODE_REQUEST) + " / legacy 0x"
				+ Integer.toHexString(OPCODE_REQUEST_LEGACY) + ")");
		PlayerSession player = ctx.channel().attr(GameAttributes.USER_SESSION).get();
		byte[] authToken = ctx.channel().attr(GameAttributes.AUTH_TOKEN).get();

		if (player == null) {
			return;
		}

		ChestDAO chestDAO = DAOFactory.CreateChestDao();
		UserDAO userDAO = DAOFactory.CreateUserDao();

		ByteBuf request = Unpooled.buffer();
		try {
			int requestOpcodeUsed = OPCODE_REQUEST;
			int confirmationOpcodeUsed = OPCODE_CONFIRMATION;
			byte[] decryptedPayload;
			try {
				decryptedPayload = GunBoundCipher.gunboundDynamicDecrypt(payload, player.getUserNameId(),
						player.getPassword(), authToken, OPCODE_REQUEST);
			} catch (Exception primaryDecryptEx) {
				// Compatibility fallback for clients using legacy sell opcode.
				decryptedPayload = GunBoundCipher.gunboundDynamicDecrypt(payload, player.getUserNameId(),
						player.getPassword(), authToken, OPCODE_REQUEST_LEGACY);
				requestOpcodeUsed = OPCODE_REQUEST_LEGACY;
				confirmationOpcodeUsed = OPCODE_CONFIRMATION_LEGACY;
			}
			request = Unpooled.wrappedBuffer(decryptedPayload);

			if (request.readableBytes() < 4) {
				sendSellConfirmation(player, false, confirmationOpcodeUsed);
				return;
			}

			// Client sends the owned avatar idx to sell.
			int avatarIdx = request.readIntLE();
			ChestDTO avatarOwned = chestDAO.getByIdx(avatarIdx);

			if (avatarOwned == null || avatarOwned.getOwnerId() == null
					|| !avatarOwned.getOwnerId().equals(player.getUserNameId())) {
				sendSellConfirmation(player, false, confirmationOpcodeUsed);
				return;
			}

			int avatarCode = avatarOwned.getItem();
			MenuDTO avatarData = GameMenu.getInstance().getByNo(avatarCode);
			int sellPrice = 0;
			if (avatarData != null && avatarData.getPriceByGoldForI() != null) {
				// TH behavior in this client branch appears to be 60% return.
				sellPrice = Math.max(0, (avatarData.getPriceByGoldForI() * 60) / 100);
			}

			boolean deleted = chestDAO.deleteByIdx(avatarIdx);
			if (!deleted) {
				sendSellConfirmation(player, false, confirmationOpcodeUsed);
				return;
			}

			if (sellPrice > 0) {
				userDAO.updateAddGold(player.getUserNameId(), sellPrice);
				player.setGold(player.getGold() + sellPrice);
			}

			player.getPlayerAvatars().removeIf(a -> a.getIdx() == avatarIdx);

			sendSellConfirmation(player, true, confirmationOpcodeUsed);

			// Push live refresh so client updates without relog.
			AvatarPlayerOwnReader.read(ctx, null);
			CashUpdateReader.read(ctx, null);

			System.out.println("Avatar sold successfully: idx=" + avatarIdx + ", item=" + avatarCode + ", gold+="
					+ sellPrice + ", player=" + player.getNickName() + ", opcode=0x"
					+ Integer.toHexString(requestOpcodeUsed));
		} catch (Exception e) {
			System.err.println("Error processing avatar sell");
			e.printStackTrace();
			sendSellConfirmation(player, false, OPCODE_CONFIRMATION);
		} finally {
			request.release();
		}
	}

	private static void sendSellConfirmation(PlayerSession player, boolean success, int confirmationOpcode) {
		ByteBuf payload = Unpooled.buffer();
		// Minimal status payload: 0x0000 success, 0x0001 fail.
		payload.writeShortLE(success ? 0 : 1);

		ByteBuf finalPacket = PacketUtils.generatePacket(player, confirmationOpcode, payload, false);
		player.getPlayerCtxChannel().writeAndFlush(finalPacket);
	}
}
