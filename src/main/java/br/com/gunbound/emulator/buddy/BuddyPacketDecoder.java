package br.com.gunbound.emulator.buddy;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.util.List;

/**
 * Netty decoder for buddy server packets.
 * BuddyServ uses a 4-byte header: length(2LE) + packetId(2LE).
 * This is different from the game server's 6-byte header (length + seq + cmd).
 */
public class BuddyPacketDecoder extends ByteToMessageDecoder {

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        // Need at least 4 bytes to read the header
        if (in.readableBytes() < 4) {
            return;
        }

        in.markReaderIndex();

        // Read total packet length (includes the 4-byte header)
        int packetLen = in.readUnsignedShortLE();

        // Sanity check
        if (packetLen < 4 || packetLen > 8192) {
            System.err.println("BS: Invalid buddy packet length: " + packetLen);
            in.resetReaderIndex();
            in.skipBytes(1); // Try to resync
            return;
        }

        // Check if we have the complete packet (excluding the 2 bytes already read)
        if (in.readableBytes() < packetLen - 2) {
            in.resetReaderIndex();
            return;
        }

        // Read the rest of the packet (packetLen - 2 because we already read the
        // length)
        ByteBuf packet = in.readBytes(packetLen - 2);
        out.add(packet);
    }
}
