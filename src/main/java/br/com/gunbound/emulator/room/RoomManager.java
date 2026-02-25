package br.com.gunbound.emulator.room;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.PriorityBlockingQueue;

import br.com.gunbound.emulator.model.entities.game.PlayerSession;

/**
 * Manages all active game rooms (GameRooms) on the server. Uses
 * PriorityBlockingQueue to manage IDs (takes the smallest) in a thread-safe way.
 */
public final class RoomManager {

	private static final RoomManager INSTANCE = new RoomManager();
	private static final int MAX_ROOMS = 1000; // Maximum number of rooms

	// Maps room ID to its instance.
	private final Map<Integer, GameRoom> activeRooms = new ConcurrentHashMap<>();

	// Thread-safe queue of available IDs, always offering the smallest first.
	private final PriorityBlockingQueue<Integer> availableRoomIds;

	/**
	 * Private constructor for Singleton. Initializes the queue with all possible room IDs.
	 */
	private RoomManager() {
		availableRoomIds = new PriorityBlockingQueue<>(MAX_ROOMS);
		for (int i = 0; i < MAX_ROOMS; i++) {
			availableRoomIds.add(i);
		}
	}

	public static RoomManager getInstance() {
		return INSTANCE;
	}

	/**
	 * Creates a new game room using the smallest available ID from the queue.
	 *
	 * @return The newly created GameRoom instance, or null if no IDs are available.
	 */
	public GameRoom createRoom(PlayerSession creator, String title, String password, int capacity) {
		// Atomically take the smallest available ID from the queue.
		Integer roomId = availableRoomIds.poll();

		if (roomId == null) {
			System.err.println("ROOM MANAGER: No room IDs available. Maximum limit reached.");
			return null;
		}

		GameRoom room = new GameRoom(roomId, title, creator, capacity);

		if (password != null && !password.isEmpty()) {
			room.setPassword(password);
		}

		activeRooms.put(roomId, room);
		System.out.println("ROOM MANAGER: Room created by " + creator.getNickName() + " with ID " + roomId);
		return room;
	}

	/**
	 * Removes a room from the manager and returns its ID to the queue.
	 *
	 * @param roomId The ID of the room to remove.
	 */
	public void removeRoom(int roomId) {
		GameRoom room = activeRooms.remove(roomId);
		if (room != null) {
			// Return the ID to the queue so it can be reused.
			availableRoomIds.add(roomId);
			System.out.println("ROOM MANAGER: Room " + roomId + " removed and ID released.");
		}
	}

	/**
	 * Handles a player disconnecting.
	 *
	 * @param player The player who disconnected.
	 */
	public void handlePlayerLeave(PlayerSession player) {
		if (player == null)
			return;

		GameRoom room = player.getCurrentRoom();

		if (room != null) {
			boolean wasHost = room.getRoomMaster().equals(player);

			// 1. Remove the player and find which slot they occupied.
			int removedPlayerSlot = room.removePlayer(player);

			if (room.getPlayerCount() == 0) {
				// 2. If the room is empty, remove it.
				removeRoom(room.getRoomId());
			} else if (removedPlayerSlot != -1) {
				// 3. If the room is not empty, notify everyone about the freed slot.
				room.notifyPlayerLeft(removedPlayerSlot, wasHost);
			}
		}
	}
	/*
	 * public boolean isPlayerInAnyRoom(PlayerSession player) { if (player == null)
	 * { return false; } // A forma mais eficiente de verificar é checar se a sala
	 * current room is not null. return player.getCurrentRoom() != null; }
	 */

	// Less efficient: iterate all rooms
	public boolean isPlayerInAnyRoom(PlayerSession player) {
		if (player == null) {
			return false;
		}
		// Iterate over all active rooms
		for (GameRoom room : activeRooms.values()) {
			if (room.getPlayersBySlot().containsValue(player)) {
				return true; // Found the player
			}
		}
		return false; // Player not found in any room
	}

	// --- Other methods ---

	public GameRoom getRoomById(int roomId) {
		return activeRooms.get(roomId);
	}

	public Collection<GameRoom> getAllRooms() {
		return Collections.unmodifiableCollection(activeRooms.values());
	}
}