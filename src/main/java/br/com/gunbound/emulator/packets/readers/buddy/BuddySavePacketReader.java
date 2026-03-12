package br.com.gunbound.emulator.packets.readers.buddy;

import br.com.gunbound.emulator.buddy.BuddyConstants;
import br.com.gunbound.emulator.buddy.BuddyPacketUtils;
import br.com.gunbound.emulator.buddy.BuddySession;
import br.com.gunbound.emulator.buddy.BuddySessionManager;
import br.com.gunbound.emulator.model.DAO.BuddyDAO;
import br.com.gunbound.emulator.model.DAO.impl.BuddyJDBC;
import br.com.gunbound.emulator.utils.crypto.GunBoundCipher;
import io.netty.channel.ChannelHandlerContext;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Handles legacy 0x2000 save/chat packets.
 * Some clients send offline chat through this opcode with dynamic-encrypted payload.
 */
public final class BuddySavePacketReader {

    private static final Pattern USER_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9_]+$");

    private BuddySavePacketReader() {
    }

    public static void handle(ChannelHandlerContext ctx, byte[] payload) {
        BuddySession session = BuddySessionManager.getInstance().getSessionByChannel(ctx.channel());
        if (session == null || !session.isAuthenticated() || payload == null || payload.length == 0) {
            return;
        }

        byte[] normalized = normalizePayload(session, payload);
        ParsedLegacyChat parsed = parseLegacyChat(normalized);
        if (parsed == null) {
            System.err.println("BS: Unparsed legacy 0x2000 payload (" + payload.length + " bytes) from "
                    + session.getUserId());
            return;
        }

        BuddyDAO buddyDAO = new BuddyJDBC();
        Map<String, Object> targetData = buddyDAO.getUserGameData(parsed.targetIdOrNick);
        if (targetData == null) {
            targetData = buddyDAO.getUserByNickname(parsed.targetIdOrNick);
        }
        if (targetData == null) {
            System.err.println("BS: Legacy 0x2000 target not found: " + parsed.targetIdOrNick);
            return;
        }

        String targetUserId = (String) targetData.get("UserId");
        String senderNick = session.getNickName() != null ? session.getNickName() : session.getUserId();
        byte[] relayBody = buildRelayChatBody(session.getUserId(), senderNick, parsed.message);

        BuddySession targetSession = BuddySessionManager.getInstance().getSession(targetUserId);
        boolean targetOnline = targetSession != null
                && targetSession.isActive()
                && targetSession.isAuthenticated()
                && targetSession.isLoginHandshakeFinalized();

        if (targetOnline) {
            targetSession.getChannel()
                    .writeAndFlush(BuddyPacketUtils.buildPacket(BuddyConstants.SVC_RELAY_BUDDY_REQ, relayBody))
                    .addListener(future -> {
                        if (!future.isSuccess()) {
                            saveOffline(buddyDAO, targetUserId, session.getUserId(), relayBody);
                        }
                    });
            System.out.println("BS: Relayed legacy 0x2000 chat from " + session.getUserId() + " to " + targetUserId);
        } else {
            saveOffline(buddyDAO, targetUserId, session.getUserId(), relayBody);
            System.out.println("BS: Saved legacy 0x2000 offline chat from " + session.getUserId() + " to "
                    + targetUserId);
        }
    }

    private static void saveOffline(BuddyDAO buddyDAO, String targetUserId, String senderUserId, byte[] relayBody) {
        boolean saved = buddyDAO.saveOfflinePacket(
                targetUserId,
                senderUserId,
                BuddyConstants.SVC_RELAY_BUDDY_REQ,
                relayBody
        );
        if (!saved) {
            System.err.println("BS: Failed to save legacy 0x2000 offline chat from " + senderUserId + " to "
                    + targetUserId);
        }
    }

    private static byte[] normalizePayload(BuddySession session, byte[] payload) {
        ParsedLegacyChat plain = parseLegacyChat(payload);
        if (plain != null) {
            return payload;
        }

        byte[] token = session.getAuthToken();
        String password = session.getPassword();
        if (token == null || token.length != 4 || password == null || password.isEmpty() || payload.length % 16 != 0) {
            return payload;
        }

        try {
            byte[] decrypted = GunBoundCipher.gunboundDynamicDecrypt(
                    payload,
                    session.getUserId(),
                    password,
                    token
            );

            // Legacy framing uses 16-byte blocks where the first 4 bytes are control/check data.
            byte[] stripped = stripLegacyBlockPrefix(decrypted);
            if (parseLegacyChat(stripped) != null) {
                return stripped;
            }
            if (parseLegacyChat(decrypted) != null) {
                return decrypted;
            }
        } catch (Exception ignored) {
            // Keep fallback behavior when payload cannot be decoded.
        }

        return payload;
    }

    private static byte[] stripLegacyBlockPrefix(byte[] decrypted) {
        if (decrypted == null || decrypted.length < 16 || decrypted.length % 16 != 0) {
            return decrypted;
        }

        byte[] stripped = new byte[(decrypted.length / 16) * 12];
        int outPos = 0;
        for (int i = 0; i < decrypted.length; i += 16) {
            System.arraycopy(decrypted, i + 4, stripped, outPos, 12);
            outPos += 12;
        }
        return stripped;
    }

    private static byte[] buildRelayChatBody(String senderUserId, String senderNick, byte[] message) {
        byte[] safeMessage = message != null ? message : new byte[0];
        byte[] msgField = new byte[40];
        System.arraycopy(safeMessage, 0, msgField, 0, Math.min(safeMessage.length, msgField.length));

        byte[] relayBody = new byte[16 + 12 + 4 + 40];
        int pos = 0;
        System.arraycopy(BuddyPacketUtils.encFixed(senderUserId, 16), 0, relayBody, pos, 16);
        pos += 16;
        System.arraycopy(BuddyPacketUtils.encFixed(senderNick, 12), 0, relayBody, pos, 12);
        pos += 12;
        relayBody[pos++] = 0x11;
        relayBody[pos++] = (byte) 0xC0;
        relayBody[pos++] = 0x1F;
        relayBody[pos++] = 0x00;
        System.arraycopy(msgField, 0, relayBody, pos, 40);
        return relayBody;
    }

    private static ParsedLegacyChat parseLegacyChat(byte[] payload) {
        if (payload == null || payload.length == 0) {
            return null;
        }

        // Variant A: fixed 16-byte target at payload start.
        if (payload.length >= 16) {
            String fixedTarget = BuddyPacketUtils.readFixedString(payload, 0, 16);
            if (isValidUserToken(fixedTarget)) {
                byte[] message = extractMessage(payload, 16);
                if (message != null && message.length > 0) {
                    return new ParsedLegacyChat(fixedTarget, message);
                }
            }
        }

        // Variant B: target\0 + metadata + message bytes.
        int nullIndex = indexOf(payload, (byte) 0x00, 0);
        if (nullIndex > 0) {
            String target = new String(payload, 0, nullIndex, StandardCharsets.ISO_8859_1).trim();
            if (isValidUserToken(target)) {
                byte[] remainder = Arrays.copyOfRange(payload, nullIndex + 1, payload.length);
                byte[] message = extractMessage(remainder, 0);
                if (message == null || message.length == 0) {
                    int start = Math.min(9, remainder.length);
                    while (start < remainder.length && remainder[start] == 0x00) {
                        start++;
                    }
                    if (start < remainder.length) {
                        message = Arrays.copyOfRange(remainder, start, Math.min(remainder.length, start + 40));
                    }
                }
                if (message != null && message.length > 0) {
                    return new ParsedLegacyChat(target, trimTrailingZeros(message));
                }
            }
        }

        return null;
    }

    private static byte[] extractMessage(byte[] payload, int startIndex) {
        if (payload == null || startIndex >= payload.length) {
            return null;
        }

        for (int i = Math.max(0, startIndex); i <= payload.length - 4; i++) {
            if (payload[i] == 0x11 && payload[i + 1] == (byte) 0xC0) {
                int msgLen = payload[i + 2] & 0xFF;
                int msgStart = i + 4;
                if (msgStart >= payload.length) {
                    return null;
                }
                int available = payload.length - msgStart;
                int actualLen = msgLen > 0 ? Math.min(msgLen, available) : available;
                if (actualLen <= 0) {
                    return null;
                }
                byte[] raw = Arrays.copyOfRange(payload, msgStart, msgStart + actualLen);
                return trimTrailingZeros(raw);
            }
        }
        return null;
    }

    private static byte[] trimTrailingZeros(byte[] data) {
        if (data == null || data.length == 0) {
            return data;
        }
        int end = data.length;
        while (end > 0 && data[end - 1] == 0x00) {
            end--;
        }
        return Arrays.copyOf(data, end);
    }

    private static int indexOf(byte[] data, byte target, int start) {
        if (data == null) {
            return -1;
        }
        for (int i = Math.max(0, start); i < data.length; i++) {
            if (data[i] == target) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isValidUserToken(String value) {
        return value != null && !value.isEmpty() && USER_ID_PATTERN.matcher(value).matches();
    }

    private static final class ParsedLegacyChat {
        private final String targetIdOrNick;
        private final byte[] message;

        private ParsedLegacyChat(String targetIdOrNick, byte[] message) {
            this.targetIdOrNick = targetIdOrNick;
            this.message = message;
        }
    }
}

