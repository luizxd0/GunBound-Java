package br.com.gunbound.emulator.packets.readers.shop;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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
				MenuDTO requestMenu = gameMenu.getByNo(avatarCode);
				if (requestMenu == null) {
					System.err.println("Nao foi encontrado o avatar solicitado. ID: [" + avatarCode + "]");
					writeBuyError(ctx, SHOP_STATUS_ERROR);
					return;
				}

				List<Integer> requestedItems = resolveRequestedItems(requestMenu, avatarCode);
				List<MenuDTO> purchasableItems = resolvePurchasableItems(gameMenu, requestedItems);

				if (purchasableItems.isEmpty()) {
					System.err.println("Nao foi encontrado o avatar solicitado. ID: [" + avatarCode + "]");
					writeBuyError(ctx, SHOP_STATUS_ERROR);
					return;
				}

				int priceAvatar = resolvePurchasePriceForRequest(requestMenu, purchasableItems, goldOrCash);
				PlayerAvatar highestOrderAvatar = player.getAvatarWithHighestPlaceOrder();
				String highestPlaceOrder = resolveInitialPlaceOrder(highestOrderAvatar);

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

				String acquisition;
				if (goldOrCash == 0) {// Comprado com Gold
					acquisition = "G";
					factoryUserDAO.updateMinusGold(player.getUserNameId(), priceAvatar);
					player.setGold(player.getGold() - priceAvatar);
				} else if (goldOrCash == 1) {// Comprado com Cash
					acquisition = "C";
					factoryUserDAO.updateMinusCash(player.getUserNameId(), priceAvatar);
					player.setCash(player.getCash() - priceAvatar);
				} else {
					System.err.println("Moeda invalida na compra do avatar. Valor recebido: " + goldOrCash);
					writeBuyError(ctx, SHOP_STATUS_ERROR);
					return;
				}

				List<ChestDTO> avatarsForConfirmation = new ArrayList<>();
				for (int i = 0; i < requestedItems.size(); i++) {
					int requestedItem = requestedItems.get(i);
					MenuDTO menuItem = purchasableItems.get(i);

					ChestDTO avatarBought = createChestAvatar(player, requestedItem, menuItem, acquisition,
							highestPlaceOrder);
					Integer idNewAvatarOnChest = factoryChestDAO.insert(avatarBought);

					ChestDTO avatar = factoryChestDAO.getByIdx(idNewAvatarOnChest);
					if (avatar == null) {
						System.err.println("Avatar comprado nao encontrado no chest: idx=" + idNewAvatarOnChest);
						writeBuyError(ctx, SHOP_STATUS_ERROR);
						return;
					}

					// Mantem a sessao sincronizada com o item real no chest.
					player.getPlayerAvatars().add(new PlayerAvatar(avatar));

					// Mantem o item confirmado igual ao codigo solicitado no cliente.
					avatar.setItem(requestedItem);
					avatarsForConfirmation.add(avatar);
					highestPlaceOrder = incrementPlaceOrder(highestPlaceOrder);
				}

				writeNewAvatars(ctx, avatarsForConfirmation);
				CashUpdateReader.read(ctx, null);

				System.out.println("Recebida pedido de compra do Avatar : "
						+ buildPurchaseLabel(requestMenu, avatarCode, requestedItems) + " Moeda: "
						+ (goldOrCash == 0 ? "Gold" : "Cash") + " do jogador " + player.getNickName());
			}
		} catch (Exception e) {
			System.err.println("Erro ao processar Compra de avatar");
			e.printStackTrace();
		} finally {
			request.release();
		}
	}

	private static List<Integer> resolveRequestedItems(MenuDTO requestMenu, int avatarCode) {
		int itemCount = requestMenu.getItemCount() == null ? 0 : requestMenu.getItemCount();
		if (itemCount <= 0) {
			return new ArrayList<>(Collections.singletonList(avatarCode));
		}

		List<Integer> setItems = new ArrayList<>();
		appendSetItem(setItems, requestMenu.getItem1());
		appendSetItem(setItems, requestMenu.getItem2());
		appendSetItem(setItems, requestMenu.getItem3());
		appendSetItem(setItems, requestMenu.getItem4());
		appendSetItem(setItems, requestMenu.getItem5());

		if (setItems.isEmpty()) {
			return new ArrayList<>(Collections.singletonList(avatarCode));
		}

		if (itemCount > 0 && setItems.size() > itemCount) {
			return new ArrayList<>(setItems.subList(0, itemCount));
		}

		return setItems;
	}

	private static void appendSetItem(List<Integer> setItems, Integer menuItemCode) {
		if (menuItemCode != null && menuItemCode > 0) {
			setItems.add(menuItemCode);
		}
	}

	private static List<MenuDTO> resolvePurchasableItems(GameMenu gameMenu, List<Integer> requestedItems) {
		List<MenuDTO> purchasableItems = new ArrayList<>();
		for (Integer requestedItem : requestedItems) {
			MenuDTO menuItem = gameMenu.getByNo(requestedItem);
			if (menuItem == null) {
				System.err.println("Item de compra nao encontrado no menu: " + requestedItem);
				return Collections.emptyList();
			}
			purchasableItems.add(menuItem);
		}
		return purchasableItems;
	}

	private static int resolvePurchasePriceForRequest(MenuDTO requestMenu, List<MenuDTO> purchasableItems, int goldOrCash) {
		int directPrice = AvatarShopPricing.resolvePurchasePrice(requestMenu, goldOrCash);
		if (directPrice > 0) {
			return directPrice;
		}

		// fallback para set sem preco configurado no item-pai
		int total = 0;
		for (MenuDTO menuItem : purchasableItems) {
			int itemPrice = AvatarShopPricing.resolvePurchasePrice(menuItem, goldOrCash);
			if (itemPrice > 0) {
				total += itemPrice;
			}
		}
		return total;
	}

	private static String resolveInitialPlaceOrder(PlayerAvatar highestOrderAvatar) {
		if (highestOrderAvatar == null || highestOrderAvatar.getPlaceOrder() == null) {
			return "0";
		}
		try {
			return Integer.toString(Integer.parseInt(highestOrderAvatar.getPlaceOrder()) + 10000);
		} catch (NumberFormatException e) {
			return "0";
		}
	}

	private static String incrementPlaceOrder(String currentPlaceOrder) {
		try {
			return Integer.toString(Integer.parseInt(currentPlaceOrder) + 10000);
		} catch (Exception e) {
			return "10000";
		}
	}

	private static ChestDTO createChestAvatar(PlayerSession player, int requestedItemCode, MenuDTO menuItem,
			String acquisition, String placeOrder) {
		ChestDTO avatarBought = new ChestDTO();
		avatarBought.setItem(requestedItemCode);
		avatarBought.setWearing(Integer.toString(0));

		if (requestedItemCode == 204802) {
			avatarBought.setItem(204801);//fixa o codigo do PU
			avatarBought.setExpire(Timestamp.valueOf(LocalDateTime.now().plusDays(7)));
		} else if (requestedItemCode == 204803) {
			avatarBought.setItem(204801);//fixa o codigo do PU
			avatarBought.setExpire(Timestamp.valueOf(LocalDateTime.now().plusDays(14)));
		} else if (requestedItemCode == 204804) {
			avatarBought.setItem(204801);//fixa o codigo do PU
			avatarBought.setExpire(Timestamp.valueOf(LocalDateTime.now().plusDays(30)));
		} else {
			avatarBought.setExpire(null);
		}

		avatarBought.setVolume(normalizeVolume(menuItem.getVolume1()));
		avatarBought.setPlaceOrder(placeOrder);
		avatarBought.setRecovered("0");
		avatarBought.setOwnerId(player.getUserNameId());
		avatarBought.setExpireType("I");
		avatarBought.setAcquisition(acquisition);
		return avatarBought;
	}

	private static int normalizeVolume(Integer volume) {
		if (volume == null || volume <= 0) {
			return 1;
		}
		return volume;
	}

	private static String buildPurchaseLabel(MenuDTO requestMenu, int avatarCode, List<Integer> requestedItems) {
		String menuName = requestMenu.getMenuName();
		if (requestedItems != null && requestedItems.size() > 1) {
			if (menuName == null || menuName.trim().isEmpty()) {
				menuName = "Set";
			}
			return menuName + " [" + requestedItems + "]";
		}
		if (menuName != null && !menuName.trim().isEmpty()) {
			return menuName + " [" + avatarCode + "]";
		}
		return "[" + avatarCode + "]";
	}

	private static void writeNewAvatars(ChannelHandlerContext ctx, List<ChestDTO> newAvatars) {
		System.out.println("SEND> SVC_ITEM_CONFIRMATION (0x" + Integer.toHexString(OPCODE_CONFIRMATION) + ")");
		PlayerSession player = ctx.channel().attr(GameAttributes.USER_SESSION).get();

		if (player == null || newAvatars == null || newAvatars.isEmpty())
			return;

		// Some clients do not handle multi-item confirmations in one packet reliably.
		// Send one confirmation packet per item.
		for (ChestDTO avatar : newAvatars) {
			ByteBuf avatarData = Unpooled.buffer();
			try {
				avatarData.writeShortLE(SHOP_STATUS_OK);
				avatarData.writeByte(1);
				avatarData.writeByte(0);
				avatarData.writeIntLE(avatar.getIdx());
				avatarData.writeIntLE(avatar.getItem());

				ByteBuf finalPacket = PacketUtils.generatePacket(player, OPCODE_CONFIRMATION, avatarData, false);
				player.getPlayerCtxChannel().writeAndFlush(finalPacket);
			} catch (Exception e) {
				System.err.println("Erro ao processar adicao de avatar no shopping:");
				e.printStackTrace();
				break;
			}
		}
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

