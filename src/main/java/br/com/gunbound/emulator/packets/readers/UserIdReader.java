package br.com.gunbound.emulator.packets.readers;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;

import br.com.gunbound.emulator.handlers.GameAttributes;
import br.com.gunbound.emulator.model.DAO.BuddyDAO;
import br.com.gunbound.emulator.model.DAO.impl.BuddyJDBC;
import br.com.gunbound.emulator.model.entities.game.PlayerSession;
import br.com.gunbound.emulator.model.entities.game.PlayerSessionManager;
import br.com.gunbound.emulator.utils.PacketUtils;
import br.com.gunbound.emulator.utils.Utils;
import br.com.gunbound.emulator.utils.crypto.GunBoundCipher;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;

public class UserIdReader {

    private static final int OPCODE_REQUEST = 0x1020;
    private static final int OPCODE_RESPONSE = 0x1021;

    public static void read(ChannelHandlerContext ctx, byte[] payload) {
        System.out.println("RECV> SVC_USER_ID (0x" + Integer.toHexString(OPCODE_REQUEST) + ")");

        PlayerSession requesterSession = ctx.channel().attr(GameAttributes.USER_SESSION).get();
        if (requesterSession == null) {
            ctx.close();
            return;
        }

        ByteBuf request = Unpooled.wrappedBuffer(payload);
        try {
            // Skip request flags/padding.
            request.skipBytes(2);

            // 12-byte fixed string (nick/user).
            String targetUsername = Utils.stringDecode(request.readBytes(12));
            System.out.println(requesterSession.getNickName() + " esta procurando por: " + targetUsername);

            // Prefer online session info when available.
            PlayerSession targetSession = PlayerSessionManager.getInstance().getSessionPlayerByNickname(targetUsername);
            BuddyDAO buddyDAO = new BuddyJDBC();
            Map<String, Object> targetData = null;
            if (targetSession == null) {
                targetData = buddyDAO.getUserByNickname(targetUsername);
                if (targetData == null) {
                    targetData = buddyDAO.getUserGameData(targetUsername);
                }
            }

            ByteBuf responsePayload = (targetSession != null)
                    ? writeUserIdResponse(targetSession)
                    : writeUserIdResponse(targetData);
            if (targetSession != null) {
                System.out.println("SVC_USER_ID resp: nick=" + targetSession.getNickName()
                        + " userId=" + targetSession.getUserNameId());
                ctx.channel().attr(GameAttributes.LAST_USER_SEARCH_USER_ID).set(targetSession.getUserNameId());
                ctx.channel().attr(GameAttributes.LAST_USER_SEARCH_NICK).set(targetSession.getNickName());
                ctx.channel().attr(GameAttributes.LAST_USER_SEARCH_TS).set(System.currentTimeMillis());
            } else if (targetData != null) {
                System.out.println("SVC_USER_ID resp: nick=" + targetData.get("NickName")
                        + " userId=" + targetData.get("UserId"));
                String targetUserId = targetData.get("UserId") != null ? targetData.get("UserId").toString() : null;
                String targetNick = targetData.get("NickName") != null ? targetData.get("NickName").toString() : null;
                ctx.channel().attr(GameAttributes.LAST_USER_SEARCH_USER_ID).set(targetUserId);
                ctx.channel().attr(GameAttributes.LAST_USER_SEARCH_NICK).set(targetNick);
                ctx.channel().attr(GameAttributes.LAST_USER_SEARCH_TS).set(System.currentTimeMillis());
            } else {
                ctx.channel().attr(GameAttributes.LAST_USER_SEARCH_USER_ID).set(null);
                ctx.channel().attr(GameAttributes.LAST_USER_SEARCH_NICK).set(null);
                ctx.channel().attr(GameAttributes.LAST_USER_SEARCH_TS).set(null);
            }

            // 0x1021 response is dynamically encrypted.
            byte[] authToken = ctx.channel().attr(GameAttributes.AUTH_TOKEN).get();
            if (authToken == null || authToken.length < 4) {
                System.err.println("SVC_USER_ID: AUTH_TOKEN ausente para " + requesterSession.getNickName());
                responsePayload.release();
                return;
            }

            // Only encrypt written bytes; never use full backing array capacity.
            byte[] bytesToEncrypt = new byte[responsePayload.readableBytes()];
            responsePayload.getBytes(responsePayload.readerIndex(), bytesToEncrypt);

            int paddingAmount = 12 - (bytesToEncrypt.length % 12);
            if (paddingAmount != 12) {
                bytesToEncrypt = Arrays.copyOf(bytesToEncrypt, bytesToEncrypt.length + paddingAmount);
            }

            byte[] encryptedResponse = GunBoundCipher.gunboundDynamicEncrypt(
                    bytesToEncrypt,
                    requesterSession.getUserNameId(),
                    requesterSession.getPassword(),
                    authToken,
                    OPCODE_RESPONSE
            );

            ByteBuf finalPacket = PacketUtils.generatePacket(
                    requesterSession,
                    OPCODE_RESPONSE,
                    Unpooled.wrappedBuffer(encryptedResponse),
                    true
            );
            ctx.writeAndFlush(finalPacket);

            System.out.println("Enviada resposta de ID de usuario (0x1021) para " + requesterSession.getNickName());
            responsePayload.release();

        } catch (Exception e) {
            System.err.println("Erro ao processar SVC_USER_ID: " + e.getMessage());
            e.printStackTrace();
            ctx.close();
        } finally {
            request.release();
        }
    }

    public static ByteBuf writeUserIdResponse(PlayerSession targetSession) {
        ByteBuf buffer = Unpooled.buffer();

        if (targetSession == null) {
            buffer.writeBytes(new byte[32]); // 12 userId + 12 nick, 8 guild
            buffer.writeShortLE(0); // rank
            buffer.writeShortLE(0); // rank
            return buffer;
        }

        String userId = targetSession.getUserNameId() != null ? targetSession.getUserNameId() : "";
        String nick = targetSession.getNickName() != null ? targetSession.getNickName() : "";
        String guild = targetSession.getGuild() != null ? targetSession.getGuild() : "";

        // Expected layout for SVC_USER_ID response:
        // userId(12) + nick(12) + guild(8) + rank(2) + rank(2)
        buffer.writeBytes(Utils.resizeBytes(userId.getBytes(StandardCharsets.ISO_8859_1), 12));
        buffer.writeBytes(Utils.resizeBytes(nick.getBytes(StandardCharsets.ISO_8859_1), 12));
        buffer.writeBytes(Utils.resizeBytes(guild.getBytes(StandardCharsets.ISO_8859_1), 8));
        buffer.writeShortLE(targetSession.getRankCurrent());
        buffer.writeShortLE(targetSession.getRankSeason());
        return buffer;
    }

    public static ByteBuf writeUserIdResponse(Map<String, Object> targetData) {
        ByteBuf buffer = Unpooled.buffer();

        if (targetData == null) {
            buffer.writeBytes(new byte[32]); // 12 userId + 12 nick, 8 guild
            buffer.writeShortLE(0);
            buffer.writeShortLE(0);
            return buffer;
        }

        String userId = (String) targetData.get("UserId");
        if (userId == null) {
            userId = "";
        }

        String nick = (String) targetData.get("NickName");
        if (nick == null || nick.isEmpty()) {
            nick = userId;
        }

        String guild = (String) targetData.get("Guild");
        if (guild == null) {
            guild = "";
        }

        int totalRank = 0;
        Object rankObj = targetData.get("TotalRank");
        if (rankObj instanceof Number) {
            totalRank = ((Number) rankObj).intValue();
        }

        buffer.writeBytes(Utils.resizeBytes(userId.getBytes(StandardCharsets.ISO_8859_1), 12));
        buffer.writeBytes(Utils.resizeBytes(nick.getBytes(StandardCharsets.ISO_8859_1), 12));
        buffer.writeBytes(Utils.resizeBytes(guild.getBytes(StandardCharsets.ISO_8859_1), 8));
        buffer.writeShortLE(totalRank);
        buffer.writeShortLE(totalRank);
        return buffer;
    }
}
