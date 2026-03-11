package br.com.gunbound.emulator.packets.readers.buddy;

import br.com.gunbound.emulator.buddy.BuddySession;
import br.com.gunbound.emulator.buddy.BuddySessionManager;
import br.com.gunbound.emulator.packets.writers.buddy.BuddyFriendListWriter;
import io.netty.channel.ChannelHandlerContext;

/**
 * Handles user state updates (0x3011).
 * Example: Changing status from Online to In-Game or Away.
 */
public class BuddyUserStateReader {

    public static void handle(ChannelHandlerContext ctx, byte[] payload) {
        BuddySession session = BuddySessionManager.getInstance().getSessionByChannel(ctx.channel());
        if (session == null || !session.isAuthenticated()) return;

        // BuddyServ2 responds with a sync marker; actual status relays come via 0x2020.
        BuddyFriendListWriter.sendSync(session);
    }
}
