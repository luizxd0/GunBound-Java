package br.com.gunbound.emulator.buddy;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Utility methods for building buddy server packets.
 * BuddyServ uses a 4-byte header: length(2LE) + packetId(2LE) + payload.
 * No sequence calculation (unlike the game server's 6-byte header).
 */
public final class BuddyPacketUtils {

    private BuddyPacketUtils() {
    }

    /**
     * Builds a complete buddy server packet with 4-byte header.
     * Format: length(2LE) + packetId(2LE) + payload
     * Length includes the header itself.
     */
    public static ByteBuf buildPacket(int packetId, ByteBuf payload) {
        int payloadLen = payload != null ? payload.readableBytes() : 0;
        int totalLen = 4 + payloadLen; // 4-byte header + payload

        ByteBuf packet = Unpooled.buffer(totalLen);
        packet.writeShortLE(totalLen);
        packet.writeShortLE(packetId);
        if (payload != null && payloadLen > 0) {
            packet.writeBytes(payload);
        }
        return packet;
    }

    /**
     * Builds a complete buddy server packet with raw byte array payload.
     */
    public static ByteBuf buildPacket(int packetId, byte[] payload) {
        int payloadLen = payload != null ? payload.length : 0;
        int totalLen = 4 + payloadLen;

        ByteBuf packet = Unpooled.buffer(totalLen);
        packet.writeShortLE(totalLen);
        packet.writeShortLE(packetId);
        if (payload != null && payloadLen > 0) {
            packet.writeBytes(payload);
        }
        return packet;
    }

    /**
     * Builds a packet with no payload (header only).
     */
    public static ByteBuf buildPacket(int packetId) {
        ByteBuf packet = Unpooled.buffer(4);
        packet.writeShortLE(4);
        packet.writeShortLE(packetId);
        return packet;
    }

    /**
     * Encode a string into a fixed-size null-padded byte array (latin-1).
     * Matches the original BuddyServ2.exe behavior.
     */
    public static byte[] encFixed(String value, int size) {
        byte[] result = new byte[size];
        if (value != null) {
            byte[] encoded = value.getBytes(StandardCharsets.ISO_8859_1);
            int len = Math.min(encoded.length, size);
            System.arraycopy(encoded, 0, result, 0, len);
        }
        return result;
    }

    /**
     * Read a null-terminated string from a byte array starting at offset.
     * Returns the string (latin-1 decoded) and trims trailing nulls.
     */
    public static String readFixedString(byte[] data, int offset, int maxLen) {
        if (data == null || offset >= data.length)
            return "";
        int len = Math.min(maxLen, data.length - offset);
        byte[] chunk = new byte[len];
        System.arraycopy(data, offset, chunk, 0, len);
        // Find first null
        int nullIdx = len;
        for (int i = 0; i < len; i++) {
            if (chunk[i] == 0) {
                nullIdx = i;
                break;
            }
        }
        return new String(chunk, 0, nullIdx, StandardCharsets.ISO_8859_1).trim();
    }

    /**
     * Build 28-byte metadata for 0x2021 relay with a given status.
     */
    public static byte[] buildMeta2021(int status) {
        byte[] meta = Arrays.copyOf(BuddyConstants.META_2021_TEMPLATE, 28);
        // Status is at offset 4 (little-endian short)
        meta[4] = (byte) (status & 0xFF);
        meta[5] = (byte) ((status >> 8) & 0xFF);
        return meta;
    }

    public static byte[] buildSyncTail(BuddySession session, boolean online) {
        if (!online || session == null || session.getSyncToken() == null) {
            return new byte[] {0x00};
        }
        
        byte[] tail = new byte[13];
        tail[0] = 0x01;
        
        // External IP & Port
        byte[] extIp = session.getClientExternalIp();
        if (extIp != null && extIp.length == 4) {
            System.arraycopy(extIp, 0, tail, 1, 4);
            tail[5] = (byte) ((session.getClientExternalPort() >> 8) & 0xFF);
            tail[6] = (byte) (session.getClientExternalPort() & 0xFF);
        }
        
        // Internal IP & Port
        byte[] intIp = session.getClientInternalIp();
        if (intIp != null && intIp.length == 4) {
            System.arraycopy(intIp, 0, tail, 7, 4);
            tail[11] = (byte) ((session.getClientInternalPort() >> 8) & 0xFF);
            tail[12] = (byte) (session.getClientInternalPort() & 0xFF);
        }
        
        return tail;
    }

    public static byte[] ipv4Bytes(String ip) {
        if (ip == null || ip.isEmpty()) {
            return new byte[] {127, 0, 0, 1};
        }
        String[] parts = ip.split("\\.");
        if (parts.length != 4) {
            return new byte[] {127, 0, 0, 1};
        }
        byte[] out = new byte[4];
        for (int i = 0; i < 4; i++) {
            try {
                int v = Integer.parseInt(parts[i]);
                out[i] = (byte) (v & 0xFF);
            } catch (NumberFormatException e) {
                return new byte[] {127, 0, 0, 1};
            }
        }
        return out;
    }
}
