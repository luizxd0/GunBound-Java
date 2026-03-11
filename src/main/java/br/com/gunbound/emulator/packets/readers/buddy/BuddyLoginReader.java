package br.com.gunbound.emulator.packets.readers.buddy;

import br.com.gunbound.emulator.buddy.BuddyConstants;
import br.com.gunbound.emulator.buddy.BuddyPacketUtils;
import br.com.gunbound.emulator.buddy.BuddySession;
import br.com.gunbound.emulator.buddy.BuddySessionManager;
import br.com.gunbound.emulator.utils.crypto.GunBoundCipher;
import br.com.gunbound.emulator.model.DAO.BuddyDAO;
import br.com.gunbound.emulator.model.DAO.impl.BuddyJDBC;
import br.com.gunbound.emulator.packets.writers.buddy.BuddyFriendListWriter;
import br.com.gunbound.emulator.utils.Utils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.AttributeKey;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.Arrays;
import java.security.SecureRandom;

/**
 * Handles login requests for the Buddy Server.
 */
public class BuddyLoginReader {

    private static final AttributeKey<byte[]> AUTH_TOKEN_KEY = AttributeKey.valueOf("BuddyAuthToken");

    public static void handleLoginReq(ChannelHandlerContext ctx, byte[] payload) {
        // Generate an auth token to send back to the client
        byte[] authToken = new byte[4];
        for (int i = 0; i < 4; i++) {
            authToken[i] = (byte) (Math.random() * 256);
        }
        ctx.channel().attr(AUTH_TOKEN_KEY).set(authToken);

        ByteBuf respPayload = Unpooled.buffer(4);
        respPayload.writeBytes(authToken);

        ctx.writeAndFlush(BuddyPacketUtils.buildPacket(BuddyConstants.SVC_LOGIN_RESP, respPayload));
    }

    public static void handleLoginData(ChannelHandlerContext ctx, byte[] payload) {
        if (payload.length < 16) {
            System.err.println("BS: SVC_LOGIN_DATA payload too short");
            return;
        }
        
        System.out.println("BS: SVC_LOGIN_DATA raw hex: " + Utils.bytesToHex(payload));

        String userId = null;

        // Try decrypting with Buddy static key
        try {
            byte[] block = new byte[16];
            System.arraycopy(payload, 0, block, 0, 16);
            byte[] decrypted = GunBoundCipher.gunboundBuddyStaticDecrypt(block);
            
            System.out.println("BS: Buddy Static decrypted hex: " + Utils.bytesToHex(decrypted));
            userId = extractString(decrypted);
            System.out.println("BS: Buddy Static decrypted string: " + userId);
        } catch (Exception e) {
            System.err.println("BS: Decrypt error: " + e.getMessage());
        }

        if (userId == null || userId.isEmpty() || !userId.matches("^[a-zA-Z0-9_]+$")) {
            System.err.println("BS: Failed to parse valid userId from static 16 bytes using buddy key.");
            return;
        }

        processLoginSuccess(ctx, userId);
    }

    public static void handleBuddyLogin(ChannelHandlerContext ctx, byte[] payload) {
        if (payload.length < 5) return;
        
        // Structure: Version (4) + UserId
        try {
            String userId = extractString(Arrays.copyOfRange(payload, 4, payload.length));
            if (userId != null && !userId.isEmpty()) {
                processLoginSuccess(ctx, userId);
            }
        } catch (Exception e) {
             System.err.println("BS: Error parsing buddy login: " + e.getMessage());
        }
    }

    private static String extractString(byte[] data) {
        int nullIdx = data.length;
        for (int i = 0; i < data.length; i++) {
            if (data[i] == 0) {
                nullIdx = i;
                break;
            }
        }
        return new String(data, 0, nullIdx).trim();
    }

    private static void processLoginSuccess(ChannelHandlerContext ctx, String requestedUserId) {
        BuddyDAO buddyDAO = new BuddyJDBC();
        Map<String, Object> gameData = buddyDAO.getUserGameData(requestedUserId);

        if (gameData == null) {
            System.err.println("BS: User " + requestedUserId + " not found in DB.");
            return;
        }

        String actualUserId = (String) gameData.get("UserId");
        String nickName = (String) gameData.get("NickName");
        String guild = (String) gameData.get("Guild");
        int totalRank = gameData.get("TotalRank") != null ? (Integer) gameData.get("TotalRank") : 0;
        int totalGrade = gameData.get("TotalGrade") != null ? (Integer) gameData.get("TotalGrade") : 0;

        BuddySessionManager manager = BuddySessionManager.getInstance();
        BuddySession session = manager.getSessionByChannel(ctx.channel());
        
        if (session == null) {
            session = new BuddySession(ctx.channel());
        }

        session.setUserId(actualUserId);
        session.setNickName(nickName);
        session.setGuild(guild != null ? guild : "");
        session.setTotalRank(totalRank);
        session.setTotalGrade(totalGrade);
        session.setAuthenticated(true);

        byte[] syncToken = new byte[6];
        new SecureRandom().nextBytes(syncToken);
        session.setSyncToken(syncToken);

        InetSocketAddress remoteAddress = (InetSocketAddress) ctx.channel().remoteAddress();
        session.setClientInternalIp(remoteAddress.getAddress().getAddress());
        session.setClientInternalPort(remoteAddress.getPort());

        manager.registerSession(session);

        buddyDAO.loginLog(actualUserId, remoteAddress.getAddress().getHostAddress(), remoteAddress.getPort(), Utils.getLocalIP(), BuddyConstants.DEFAULT_PORT, 212);

        System.out.println("BS: User " + actualUserId + " (" + nickName + ") logged in.");

        // Send Login Success
        ByteBuf resp = Unpooled.buffer(5);
        resp.writeByte(0);  // 0 = ok
        resp.writeIntLE(0); // 4-byte padding/int
        ctx.writeAndFlush(BuddyPacketUtils.buildPacket(BuddyConstants.SVC_LOGIN_RESP, resp));

        // Send Buddy List (contains UDP nonce). 
        // We do NOT broadcast presence yet. We must wait for the client to echo the nonce over UDP.
        BuddyFriendListWriter.sendBuddyList(session);
    }

    public static void processOfflinePackets(BuddySession session, BuddyDAO buddyDAO) {
        List<Map<String, Object>> offlinePackets = buddyDAO.getOfflinePackets(session.getUserId());
        for (Map<String, Object> pkt : offlinePackets) {
            long serialNo = (Long) pkt.get("SerialNo");
            byte[] body = (byte[]) pkt.get("Body");
            
            if (body != null) {
                // Relay as 0x2021
                ByteBuf payloadBuf = Unpooled.copiedBuffer(body);
                session.getChannel().writeAndFlush(BuddyPacketUtils.buildPacket(BuddyConstants.SVC_RELAY_BUDDY_REQ, payloadBuf));
            }
            buddyDAO.deleteOfflinePacket(serialNo);
        }
    }
}
