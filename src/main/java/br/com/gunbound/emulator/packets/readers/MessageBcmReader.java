package br.com.gunbound.emulator.packets.readers;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;

import br.com.gunbound.emulator.handlers.GameAttributes;
import br.com.gunbound.emulator.model.entities.game.PlayerSession;
import br.com.gunbound.emulator.model.entities.game.PlayerSessionManager;
import br.com.gunbound.emulator.utils.PacketUtils;
import br.com.gunbound.emulator.utils.crypto.GunBoundCipher;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;

public class MessageBcmReader {

	private static final int OPCODE_MSG_BCM_REQUEST = 0x5010;
	private static final int OPCODE_MSG_BCM_RESPONSE = 0x5101;
	private static final int AUTHORITY_ADMIN = 100;

	public static void read(ChannelHandlerContext ctx, byte[] payload) {
		System.out.println("RECV> SVC_BCM_MSG (0x" + Integer.toHexString(OPCODE_MSG_BCM_REQUEST) + ")");
		PlayerSession session = ctx.channel().attr(GameAttributes.USER_SESSION).get();
		if (session == null) {
			return;
		}
		if (session.getAuthority() < AUTHORITY_ADMIN) {
			printMsgToPlayer(session, "ADMIN >> You do not have permission.");
			return;
		}

		try {
			byte[] authToken = ctx.channel().attr(GameAttributes.AUTH_TOKEN).get();
			byte[] decryptedPayload = GunBoundCipher.gunboundDynamicDecrypt(payload, session.getUserNameId(),
					session.getPassword(), authToken, OPCODE_MSG_BCM_REQUEST);

			int messageLength = decryptedPayload[1] & 0xFF;
			String chatMessage = new String(decryptedPayload, 2, messageLength, StandardCharsets.ISO_8859_1);

			System.out.println("[BCM] " + session.getNickName() + ": " + chatMessage);
			System.out.println("[DEBUG] Iniciando broadcast de: " + session.getNickName());

			broadcastSendMessage(chatMessage);

		} catch (Exception e) {
			System.err.println("Erro ao processar pacote de chat: " + e.getMessage());
			e.printStackTrace();
		}
	}

	public static void broadcastSendMessage(String message) {
		Collection<PlayerSession> recipients = new ArrayList<>(PlayerSessionManager.getInstance().getAllPlayers());
		for (PlayerSession recipient : recipients) {
			try {
				printMsgToPlayer(recipient, message);
			} catch (Exception e) {
				System.err.println("Falha ao enviar bcm para " + recipient.getNickName() + ": " + e.getMessage());
			}
		}
	}

	/**
	 * Gera uma mensagem para que determinado player visualize (Usado no BCM)
	 *
	 * @param player
	 * @param msg
	 */
	public static void printMsgToPlayer(PlayerSession player, String msg) {
		if (player == null || player.getPlayerCtxChannel() == null || !player.getPlayerCtxChannel().isActive()) {
			return;
		}

		final byte[] messageBytes = msg.getBytes(StandardCharsets.ISO_8859_1);
		player.getPlayerCtxChannel().eventLoop().execute(() -> {
			if (!player.getPlayerCtxChannel().isActive()) {
				return;
			}

			ByteBuf payload = Unpooled.wrappedBuffer(messageBytes);
			try {
				ByteBuf confirmationPacket = PacketUtils.generatePacket(player, OPCODE_MSG_BCM_RESPONSE, payload, false);
				player.getPlayerCtxChannel().writeAndFlush(confirmationPacket);
			} finally {
				payload.release();
			}
		});
	}
}
