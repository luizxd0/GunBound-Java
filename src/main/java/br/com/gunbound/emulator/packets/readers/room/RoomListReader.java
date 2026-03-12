package br.com.gunbound.emulator.packets.readers.room;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import br.com.gunbound.emulator.handlers.GameAttributes;
import br.com.gunbound.emulator.model.entities.game.PlayerSession;
import br.com.gunbound.emulator.packets.writers.RoomWriter;
import br.com.gunbound.emulator.room.GameRoom;
import br.com.gunbound.emulator.room.RoomManager;
import br.com.gunbound.emulator.utils.PacketUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;

public class RoomListReader {

    private static final int OPCODE_REQUEST = 0x2100;
    private static final int OPCODE_RESPONSE = 0x2103;
    private static final int FILTER_ALL = 1;
    private static final int FILTER_WAITING = 2;
    // Client displays 6 rooms per page.
    private static final int ROOMS_PER_PAGE = 6;

    public static void read(ChannelHandlerContext ctx, byte[] payload) {
        System.out.println("RECV> SVC_ROOM_SORTED_LIST (0x" + Integer.toHexString(OPCODE_REQUEST) + ")");
        PlayerSession player = ctx.channel().attr(GameAttributes.USER_SESSION).get();
        if (player == null) {
            return;
        }

        ByteBuf request = Unpooled.wrappedBuffer(payload);
        try {
            int requestedFilterMode = request.readUnsignedByte();
            int startIndex = 0;
            if (request.isReadable()) {
                startIndex = request.readUnsignedByte();
            }

            List<GameRoom> allRooms = RoomManager.getInstance().getAllRooms().stream()
                    .filter(room -> room != null)
                    .collect(Collectors.toList());

            boolean initialRoomListPending = Boolean.TRUE
                    .equals(ctx.channel().attr(GameAttributes.INITIAL_ROOM_LIST_PENDING).get());
            boolean worldListJoinPending = Boolean.TRUE
                    .equals(ctx.channel().attr(GameAttributes.WORLD_LIST_JOIN_PENDING).get());

            int effectiveFilterMode = requestedFilterMode;
            boolean forceInitialWorldListOrdering = false;
            if (initialRoomListPending) {
                if (worldListJoinPending) {
                    // First room list after world-list/avatar-shop lobby join:
                    // force ALL with waiting-first ordering.
                    effectiveFilterMode = FILTER_ALL;
                    forceInitialWorldListOrdering = true;
                }
                ctx.channel().attr(GameAttributes.INITIAL_ROOM_LIST_PENDING).set(Boolean.FALSE);
                ctx.channel().attr(GameAttributes.WORLD_LIST_JOIN_PENDING).set(Boolean.FALSE);
            }

            System.out.println("Filtro de sala: " + filterModeName(effectiveFilterMode)
                    + ", Indice Inicial Solicitado: " + startIndex);

            List<GameRoom> filteredRooms;
            if (effectiveFilterMode == FILTER_WAITING) {
                // WAITING: only waiting rooms, power user first, then room id.
                filteredRooms = allRooms.stream()
                        .filter(room -> !room.isGameStarted())
                        .sorted(Comparator.comparingInt((GameRoom room) -> room.hasPowerUserHost() ? 0 : 1)
                                .thenComparingInt(GameRoom::getRoomId))
                        .collect(Collectors.toList());
            } else {
                filteredRooms = new ArrayList<>(allRooms);
                if (forceInitialWorldListOrdering) {
                    // Initial lobby list from world list/avatar shop:
                    // waiting first, then playing; each group power user first, then room id.
                    filteredRooms.sort(Comparator.comparingInt((GameRoom room) -> room.isGameStarted() ? 1 : 0)
                            .thenComparingInt(room -> room.hasPowerUserHost() ? 0 : 1)
                            .thenComparingInt(GameRoom::getRoomId));
                } else {
                    // ALL: strict room id order only.
                    filteredRooms.sort(Comparator.comparingInt(GameRoom::getRoomId));
                }
            }

            int totalRooms = filteredRooms.size();
            int safeStart = Math.max(0, startIndex);
            int endIndex = Math.min(safeStart + ROOMS_PER_PAGE, totalRooms);

            List<GameRoom> roomsForPage;
            if (safeStart >= totalRooms) {
                roomsForPage = Collections.emptyList();
            } else {
                roomsForPage = filteredRooms.subList(safeStart, endIndex);
            }

            // Persist current list view for broadcast refreshes.
            ctx.channel().attr(GameAttributes.CURRENT_ROOM_LIST_FILTER).set(effectiveFilterMode);
            ctx.channel().attr(GameAttributes.CURRENT_ROOM_LIST_START_INDEX).set(safeStart);
            ctx.channel().attr(GameAttributes.CURRENT_ROOM_LIST_ROOM_IDS).set(
                    roomsForPage.stream().map(GameRoom::getRoomId).collect(Collectors.toList()));

            ByteBuf responsePayload = RoomWriter.writeRoomList(roomsForPage);
            ByteBuf responsePacket = PacketUtils.generatePacket(player, OPCODE_RESPONSE, responsePayload, true);
            ctx.writeAndFlush(responsePacket);

        } catch (Exception e) {
            System.err.println("Erro ao processar a lista de salas");
            e.printStackTrace();
        } finally {
            request.release();
        }
    }

    private static String filterModeName(int filterMode) {
        if (filterMode == FILTER_ALL) {
            return "ALL";
        }
        if (filterMode == FILTER_WAITING) {
            return "WAITING";
        }
        if (filterMode == 3) {
            return "FRIENDS";
        }
        return "UNKNOWN";
    }
}
