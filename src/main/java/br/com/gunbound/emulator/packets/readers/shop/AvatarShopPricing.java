package br.com.gunbound.emulator.packets.readers.shop;

import br.com.gunbound.emulator.model.entities.DTO.MenuDTO;

public final class AvatarShopPricing {

	private static final int SELL_PERCENT = 60;

	private AvatarShopPricing() {
	}

	public static int resolvePurchasePrice(MenuDTO avatarData, int goldOrCash) {
		if (avatarData == null)
			return 0;

		if (goldOrCash == 0) {
			return sanitizePrice(avatarData.getPriceByGoldForI());
		}
		if (goldOrCash == 1) {
			return sanitizePrice(avatarData.getPriceByCashForI());
		}
		return 0;
	}

	public static int resolveResalePayout(MenuDTO avatarData, String acquisition) {
		if (avatarData == null)
			return 0;

		int originalPrice;
		if (paysCash(acquisition)) {
			originalPrice = sanitizePrice(avatarData.getPriceByCashForI());
			if (originalPrice <= 0) {
				originalPrice = sanitizePrice(avatarData.getPriceByGoldForI());
			}
		} else {
			originalPrice = sanitizePrice(avatarData.getPriceByGoldForI());
			if (originalPrice <= 0) {
				originalPrice = sanitizePrice(avatarData.getPriceByCashForI());
			}
		}

		return (int) ((long) originalPrice * SELL_PERCENT / 100L);
	}

	public static int resolveResalePayoutByGold(MenuDTO avatarData) {
		if (avatarData == null)
			return 0;

		int originalGoldPrice = sanitizePrice(avatarData.getPriceByGoldForI());
		if (originalGoldPrice <= 0) {
			originalGoldPrice = sanitizePrice(avatarData.getPriceByGoldForY());
		}
		if (originalGoldPrice <= 0) {
			originalGoldPrice = sanitizePrice(avatarData.getPriceByGoldForM());
		}
		if (originalGoldPrice <= 0) {
			originalGoldPrice = sanitizePrice(avatarData.getPriceByGoldForW());
		}
		if (originalGoldPrice <= 0) {
			originalGoldPrice = sanitizePrice(avatarData.getPriceByCashForI());
		}

		return (int) ((long) originalGoldPrice * SELL_PERCENT / 100L);
	}

	public static boolean paysCash(String acquisition) {
		return "C".equalsIgnoreCase(acquisition);
	}

	private static int sanitizePrice(Integer price) {
		if (price == null)
			return 0;
		return Math.max(price, 0);
	}
}
