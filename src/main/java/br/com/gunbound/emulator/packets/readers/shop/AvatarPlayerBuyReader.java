package br.com.gunbound.emulator.packets.readers.shop;

import java.sql.Timestamp;
import java.time.LocalDateTime;

import br.com.gunbound.emulator.handlers.GameAttributes;
import br.com.gunbound.emulator.model.DAO.ChestDAO;
import br.com.gunbound.emulator.model.DAO.DAOFactory;
import br.com.gunbound.emulator.model.DAO.UserDAO;
import br.com.gunbound.emulator.model.entities.DTO.ChestDTO;
import br.com.gunbound.emulator.model.entities.DTO.MenuDTO;
import br.com.gunbound.emulator.model.entities.game.GameMenu;
import br.com.gunbound.emulator.model.entities.game.PlayerAvatar;
import br.com.gunbound.emulator.model.entities.game.PlayerSession;
import br.com.gunbound.emulator.packets.readers.CashUpdateReader;
import br.com.gunbound.emulator.utils.PacketUtils;
import br.com.gunbound.emulator.utils.crypto.GunBoundCipher;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;

public class AvatarPlayerBuyReader {

	private static final int OPCODE_REQUEST = 0x6020;
	private static final int OPCODE_CONFIRMATION = 0x6021;
	private static final int SHOP_STATUS_OK = 0x0000;
	private static final int SHOP_STATUS_ERROR = 0x6002;
	private static final int SHOP_STATUS_NOT_ENOUGH_MONEY = 0x6003;

	public static void read(ChannelHandlerContext ctx, byte[] payload) {
		System.out.println("RECV> SVC_PROP_BUY (0x" + Integer.toHexString(OPCODE_REQUEST) + ")");
		PlayerSession player = ctx.channel().attr(GameAttributes.USER_SESSION).get();
		byte[] authToken = ctx.channel().attr(GameAttributes.AUTH_TOKEN).get();

		if (player == null)
			return;

		ByteBuf request = Unpooled.buffer();
		try {
			try (ChestDAO factoryChestDAO = DAOFactory.CreateChestDao(); UserDAO factoryUserDAO = DAOFactory.CreateUserDao()) {

				byte[] decryptedPayload = GunBoundCipher.gunboundDynamicDecrypt(payload, player.getUserNameId(),
						player.getPassword(), authToken, OPCODE_REQUEST);

				request = Unpooled.wrappedBuffer(decryptedPayload);

				// payload: dword avatar code + byte moeda (0 = gold, 1 = cash)
				if (request.readableBytes() < 5) {
					System.err.println("Pacote de compra de avatar invalido (payload curto).");
					writeBuyError(ctx, SHOP_STATUS_ERROR);
					return;
				}

				int avatarCode = request.readIntLE();
				int goldOrCash = request.readUnsignedByte();

				GameMenu gameMenu = GameMenu.getInstance();
				MenuDTO avatarData = gameMenu.getByNo(avatarCode);
				Integer idNewAvatarOnChest = null;

				if (avatarData != null) {
					int priceAvatar = AvatarShopPricing.resolvePurchasePrice(avatarData, goldOrCash);
					PlayerAvatar highestOrderAvatar = player.getAvatarWithHighestPlaceOrder();
					String highestPlaceOrder = highestOrderAvatar == null ? null : highestOrderAvatar.getPlaceOrder();

					if (priceAvatar <= 0) {
						System.err.println("Preco invalido para compra do avatar [" + avatarCode + "] com moeda "
								+ goldOrCash);
						writeBuyError(ctx, SHOP_STATUS_ERROR);
						return;
					}

					if (goldOrCash == 0 && player.getGold() < priceAvatar) {
						System.err.println("Gold insuficiente para compra do avatar [" + avatarCode + "]");
						writeBuyError(ctx, SHOP_STATUS_NOT_ENOUGH_MONEY);
						return;
					}

					if (goldOrCash == 1 && player.getCash() < priceAvatar) {
						System.err.println("Cash insuficiente para compra do avatar [" + avatarCode + "]");
						writeBuyError(ctx, SHOP_STATUS_NOT_ENOUGH_MONEY);
						return;
					}

					// calculo do place order (util apenas no WC pra frente)
					if (highestPlaceOrder == null) {
						highestPlaceOrder = "0";
					} else {
						highestPlaceOrder = Integer.toString((Integer.parseInt(highestPlaceOrder) + 10000));
					}

					ChestDTO avatarBought = new ChestDTO();
					avatarBought.setItem(avatarCode);
					avatarBought.setWearing(Integer.toString(0));

					if (avatarCode == 204802) {
						avatarBought.setItem(204801);//fixa o codigo do PU
						avatarBought.setExpire(Timestamp.valueOf(LocalDateTime.now().plusDays(7)));
					} else if (avatarCode == 204803) {
						avatarBought.setItem(204801);//fixa o codigo do PU
						avatarBought.setExpire(Timestamp.valueOf(LocalDateTime.now().plusDays(14)));
					} else if (avatarCode == 204804) {
						avatarBought.setItem(204801);//fixa o codigo do PU
						avatarBought.setExpire(Timestamp.valueOf(LocalDateTime.now().plusDays(30)));
					} else {
						avatarBought.setExpire(null);
					}

					avatarBought.setVolume(avatarData.getVolume1());
					avatarBought.setPlaceOrder(highestPlaceOrder);
					avatarBought.setRecovered("0");
					avatarBought.setOwnerId(player.getUserNameId());
					avatarBought.setExpireType("I");

					if (goldOrCash == 0) {// Comprado com Gold
						avatarBought.setAcquisition("G");
						factoryUserDAO.updateMinusGold(player.getUserNameId(), priceAvatar);
						player.setGold(player.getGold() - priceAvatar);
					} else if (goldOrCash == 1) {// Comprado com Cash
						avatarBought.setAcquisition("C");
						factoryUserDAO.updateMinusCash(player.getUserNameId(), priceAvatar);
						player.setCash(player.getCash() - priceAvatar);
					} else {
						System.err.println("Moeda invalida na compra do avatar. Valor recebido: " + goldOrCash);
						writeBuyError(ctx, SHOP_STATUS_ERROR);
						return;
					}

					idNewAvatarOnChest = factoryChestDAO.insert(avatarBought);

					ChestDTO avatar = factoryChestDAO.getByIdx(idNewAvatarOnChest);
					if (avatar == null) {
						System.err.println("Avatar comprado nao encontrado no chest: idx=" + idNewAvatarOnChest);
						writeBuyError(ctx, SHOP_STATUS_ERROR);
						return;
					}

					// Mantem a sessao sincronizada com o item real no chest.
					player.getPlayerAvatars().add(new PlayerAvatar(avatar));

					avatar.setItem(avatarCode);

					// chama o metodo para escrever o avatar no shopping
					// writeNewAvatar(ctx, factoryChestDAO, idNewAvatarOnChest); // desativado pelo hacky do PU
					writeNewAvatar(ctx, avatar);
					CashUpdateReader.read(ctx, null);

					System.out.println("Recebida pedido de compra do Avatar : " + avatarData.getMenuName() + " [ "
							+ avatarCode + "] " + " Moeda: " + (goldOrCash == 0 ? "Gold" : "Cash") + " do jogador "
							+ player.getNickName());

				} else {
					System.err.println("Nao foi encontrado o avatar solicitado. ID: [" + avatarCode + "]");
					writeBuyError(ctx, SHOP_STATUS_ERROR);
				}
			}
		} catch (Exception e) {
			System.err.println("Erro ao processar Compra de avatar");
			e.printStackTrace();
		} finally {
			request.release();
		}
	}

	// Hacky por causa do PU
	// private static void writeNewAvatar(ChannelHandlerContext ctx, ChestDAO chestDao, int idxAvatar) {
	private static void writeNewAvatar(ChannelHandlerContext ctx, ChestDTO chestNew) {
		System.out.println("SEND> SVC_ITEM_CONFIRMATION (0x" + Integer.toHexString(OPCODE_CONFIRMATION) + ")");
		PlayerSession player = ctx.channel().attr(GameAttributes.USER_SESSION).get();

		// ChestDTO avatar = chestDao.getByIdx(idxAvatar);
		ChestDTO avatar = chestNew;
		if (player == null || avatar == null)
			return;

		int avatarCount = 1; // hardcoded nao implementou os sets ainda

		ByteBuf avatarData = Unpooled.buffer();
		try {
			avatarData.writeShortLE(SHOP_STATUS_OK);
			avatarData.writeByte(1);
			avatarData.writeByte(0);

			// 3. Itera sobre os dados dos avatares
			for (int i = 0; i < avatarCount; i++) {
				avatarData.writeIntLE(avatar.getIdx());
				avatarData.writeIntLE(avatar.getItem());
			}

		} catch (Exception e) {
			System.err.println("Erro ao processar adicao de avatar no shopping:");
			e.printStackTrace();
		}

		ByteBuf finalPacket = PacketUtils.generatePacket(player, OPCODE_CONFIRMATION, avatarData, false);
		player.getPlayerCtxChannel().writeAndFlush(finalPacket);
	}

	private static void writeBuyError(ChannelHandlerContext ctx, int statusCode) {
		PlayerSession player = ctx.channel().attr(GameAttributes.USER_SESSION).get();
		if (player == null)
			return;

		ByteBuf data = Unpooled.buffer();
		try {
			data.writeShortLE(statusCode);
			ByteBuf finalPacket = PacketUtils.generatePacket(player, OPCODE_CONFIRMATION, data, false);
			player.getPlayerCtxChannel().writeAndFlush(finalPacket);
		} finally {
			data.release();
		}
	}
}
