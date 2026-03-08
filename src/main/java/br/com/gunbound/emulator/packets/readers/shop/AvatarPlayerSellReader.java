package br.com.gunbound.emulator.packets.readers.shop;

import java.util.Comparator;
import java.util.List;

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

	private static final int OPCODE_REQUEST = 0x6024;
	private static final int OPCODE_CONFIRMATION = 0x6025;
	private static final int SHOP_STATUS_OK = 0x0000;
	private static final int SHOP_STATUS_ERROR = 0x6002;

	public static void read(ChannelHandlerContext ctx, byte[] payload) {
		System.out.println("RECV> SVC_PROP_SELL (0x" + Integer.toHexString(OPCODE_REQUEST) + ")");
		PlayerSession player = ctx.channel().attr(GameAttributes.USER_SESSION).get();
		byte[] authToken = ctx.channel().attr(GameAttributes.AUTH_TOKEN).get();

		if (player == null)
			return;

		ByteBuf request = Unpooled.buffer();
		try {
			try (ChestDAO chestDAO = DAOFactory.CreateChestDao(); UserDAO userDAO = DAOFactory.CreateUserDao()) {
				byte[] decryptedPayload = GunBoundCipher.gunboundDynamicDecrypt(payload, player.getUserNameId(),
						player.getPassword(), authToken, OPCODE_REQUEST);
				request = Unpooled.wrappedBuffer(decryptedPayload);

				if (request.readableBytes() < 4) {
					System.err.println("Pacote de venda de avatar invalido (payload curto).");
					writeSellConfirmation(ctx, SHOP_STATUS_ERROR, null);
					return;
				}

				int avatarReference = request.readIntLE();
				int requestMeta = request.readableBytes() >= 2 ? request.readUnsignedShortLE() : 0;

				ChestDTO avatarToSell = resolveAvatarToSell(chestDAO, player, avatarReference);
				if (avatarToSell == null) {
					System.err.println("Avatar para venda nao encontrado. referencia=" + avatarReference + " meta="
							+ requestMeta);
					writeSellConfirmation(ctx, SHOP_STATUS_ERROR, null);
					return;
				}

				MenuDTO menuData = GameMenu.getInstance().getByNo(avatarToSell.getItem());
				if (menuData == null) {
					System.err.println("Item nao encontrado no menu para venda. item=" + avatarToSell.getItem());
					writeSellConfirmation(ctx, SHOP_STATUS_ERROR, null);
					return;
				}

				int sellValue = AvatarShopPricing.resolveResalePayoutByGold(menuData);
				if (sellValue <= 0) {
					System.err.println("Valor de venda invalido para item " + avatarToSell.getItem());
					writeSellConfirmation(ctx, SHOP_STATUS_ERROR, null);
					return;
				}

				boolean deleted = chestDAO.deleteByIdx(avatarToSell.getIdx());
				if (!deleted) {
					System.err.println("Falha ao remover item vendido do chest. idx=" + avatarToSell.getIdx());
					writeSellConfirmation(ctx, SHOP_STATUS_ERROR, null);
					return;
				}

				// Comportamento TH: venda sempre retorna Gold usando tabela de Gold.
				userDAO.updateAddGold(player.getUserNameId(), sellValue);
				player.setGold(player.getGold() + sellValue);

				player.getPlayerAvatars().removeIf(avatar -> avatar.getIdx() == avatarToSell.getIdx());
				writeSellConfirmation(ctx, SHOP_STATUS_OK, avatarToSell.getItem());
				CashUpdateReader.read(ctx, null);

				System.out.println("Venda de avatar concluida: item=" + avatarToSell.getItem() + " idx="
						+ avatarToSell.getIdx() + " valor=" + sellValue + " moeda=Gold jogador=" + player.getNickName());
			}
		} catch (Exception e) {
			System.err.println("Erro ao processar venda de avatar");
			e.printStackTrace();
			writeSellConfirmation(ctx, SHOP_STATUS_ERROR, null);
		} finally {
			request.release();
		}
	}

	private static ChestDTO resolveAvatarToSell(ChestDAO chestDAO, PlayerSession player, int avatarReference) {
		ChestDTO byIdx = chestDAO.getByIdx(avatarReference);
		if (isOwnerAvatar(byIdx, player)) {
			return byIdx;
		}

		List<ChestDTO> ownedAvatars = chestDAO.getAllAvatarsByOwnerId(player.getUserNameId());
		return ownedAvatars.stream().filter(avatar -> avatar.getItem() == avatarReference)
				.min(Comparator.comparingInt(ChestDTO::getIdx)).orElse(null);
	}

	private static boolean isOwnerAvatar(ChestDTO avatar, PlayerSession player) {
		if (avatar == null || player == null)
			return false;
		return player.getUserNameId().equalsIgnoreCase(avatar.getOwnerId());
	}

	private static void writeSellConfirmation(ChannelHandlerContext ctx, int statusCode, Integer soldItemCode) {
		PlayerSession player = ctx.channel().attr(GameAttributes.USER_SESSION).get();
		if (player == null)
			return;

		ByteBuf data = Unpooled.buffer();
		try {
			data.writeShortLE(statusCode);
			if (statusCode == SHOP_STATUS_OK && soldItemCode != null) {
				data.writeIntLE(soldItemCode);
			}

			ByteBuf finalPacket = PacketUtils.generatePacket(player, OPCODE_CONFIRMATION, data, false);
			player.getPlayerCtxChannel().writeAndFlush(finalPacket);
		} finally {
			data.release();
		}
	}
}
