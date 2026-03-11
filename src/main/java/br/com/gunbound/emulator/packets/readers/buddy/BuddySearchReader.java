package br.com.gunbound.emulator.packets.readers.buddy;

import br.com.gunbound.emulator.buddy.BuddyConstants;
import br.com.gunbound.emulator.buddy.BuddyPacketUtils;
import br.com.gunbound.emulator.buddy.BuddySession;
import br.com.gunbound.emulator.buddy.BuddySessionManager;
import br.com.gunbound.emulator.model.DAO.BuddyDAO;
import br.com.gunbound.emulator.model.DAO.impl.BuddyJDBC;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;

import java.util.List;
import java.util.Map;

/**
 * Handles user search requests (0x4000).
 */
public class BuddySearchReader {

    public static void handle(ChannelHandlerContext ctx, byte[] payload) {
        BuddySession session = BuddySessionManager.getInstance().getSessionByChannel(ctx.channel());
        if (session == null || !session.isAuthenticated()) return;

        // Payload: Keyword (null-terminated), Offset (4 bytes), Limit (4 bytes)
        // Usually keyword is a 16-byte fixed string for standard clients
        String keyword = BuddyPacketUtils.readFixedString(payload, 0, 16);
        
        int offset = 0;
        int limit = 50;

        if (payload.length >= 24) {
             ByteBuf in = Unpooled.wrappedBuffer(payload);
             in.skipBytes(16);
             offset = in.readIntLE();
             limit = in.readIntLE();
        }

        BuddyDAO buddyDAO = new BuddyJDBC();
        List<Map<String, Object>> results = buddyDAO.searchUsers(keyword, offset, limit);

        // Send Search Response (0x4001)
        ByteBuf resp = Unpooled.buffer();
        resp.writeShortLE(results.size());

        for (Map<String, Object> user : results) {
            String uid = (String) user.get("UserId");
            String nick = (String) user.get("NickName");
            if (nick == null) nick = uid;
            
            String guild = (String) user.get("Guild");
            if (guild == null) guild = "";
            
            int totalRank = user.get("TotalRank") != null ? (Integer) user.get("TotalRank") : 0;
            
            int status = BuddySessionManager.getInstance().isOnline(uid) 
                         ? BuddyConstants.STATUS_ONLINE 
                         : BuddyConstants.STATUS_OFFLINE;

            resp.writeBytes(BuddyPacketUtils.encFixed(uid, 16));
            resp.writeBytes(BuddyPacketUtils.encFixed(nick, 12));
            resp.writeBytes(BuddyPacketUtils.encFixed(guild, 8));
            resp.writeShortLE(totalRank);
            resp.writeShortLE(status);
            // Example layout: UID(16) + Nick(12) + Guild(8) + Rank(2) + Status(2) = 40 bytes
        }

        ctx.writeAndFlush(BuddyPacketUtils.buildPacket(BuddyConstants.SVC_SEARCH_RESP, resp));
    }
}
