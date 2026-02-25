package br.com.gunbound.emulator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import br.com.gunbound.emulator.model.entities.ServerOption;

public class GunBoundStarter {
    private static final int BROKER_PORT = 8400;
    private static final int GAME_SERVER_PORT = 8360;
    private static final String SERVER_HOST = "127.0.0.1"; // Use 127.0.0.1 for local test; 0.0.0.0 or your IP for LAN

	public static void main(String[] args) {
		// Use an ExecutorService to start the broker and game server in separate threads.
		ExecutorService executor = Executors.newFixedThreadPool(2);

		// List that simulates player sessions on the game server.
		// The broker needs to know the player count to report occupancy.
		// This list must be thread-safe, hence Collections.synchronizedList.
		List<Object> gameServerSessions = Collections.synchronizedList(new ArrayList<>());

		// List of servers that the broker will show to clients.
		List<ServerOption> serverOptions = new ArrayList<>();
		try {
			// Add the local game server to the list that the broker will show.
			serverOptions.add(new ServerOption("Gunbound Classic", "Avatar OFF", "127.0.0.1",
					GAME_SERVER_PORT, 0, 500, true));
		} catch (Exception e) {
			System.err.println("Error creating ServerOption for Game Server: " + e.getMessage());
			return; // Abort if the server option cannot be configured.
		}

		// --- 1. Initialize the Game Server ---
		// The GameServer is started first so it is online before the Broker Server
		// accepts connections and directs clients.
		GunBoundGameServer gameServer = new GunBoundGameServer(GAME_SERVER_PORT);
		AtomicBoolean gameServerStarted = new AtomicBoolean(false); // Flag to verify the Game Server started successfully.

		executor.submit(() -> {
			try {
				gameServer.start();
				gameServerStarted.set(true); // Mark as started successfully.
			} catch (Exception e) {
				System.err.println("Failed to start Game Server on port " + GAME_SERVER_PORT + "!");
				e.printStackTrace();
			}
		});

		// Brief pause to allow the Game Server to initialize fully.
		// Common practice to ensure startup order in distributed systems.
		try {
			Thread.sleep(2000); // Wait 2 seconds.
		} catch (InterruptedException e) {
			System.err.println("Game Server initialization interrupted.");
			Thread.currentThread().interrupt(); // Restore interrupt status.
		}

		// Check if the Game Server actually started.
		if (!gameServerStarted.get()) {
			System.err.println("Could not start Game Server. Aborting Broker Server initialization.");
			executor.shutdownNow(); // Shut down all threads in the executor.
			return;
		}

		// --- 2. Initialize the GunBoundBrokerServer ---
		// The GunBoundBrokerServer is now configured to advertise and direct
		// clients to the Game Server that was just started.
		// The 'gameServerSessions' list is passed to the broker so it can
		// report the current game server occupancy.
		GunBoundBrokerServer gunBoundBrokerServer = new GunBoundBrokerServer(SERVER_HOST, BROKER_PORT, serverOptions,
				gameServerSessions);
		executor.submit(() -> {
			try {
				gunBoundBrokerServer.start();
			} catch (Exception e) {
				System.err.println("Failed to start Broker Server on port " + BROKER_PORT + "!");
				e.printStackTrace();
			}
		});

		System.out.println("--- GunBound Starter Started ---");
		System.out.println("Broker Server listening on " + SERVER_HOST + ":" + BROKER_PORT);
		System.out.println("Game Server listening on " + SERVER_HOST + ":" + GAME_SERVER_PORT);
		System.out.println("Press Ctrl+C to stop.");

		// The main thread now waits for the executor threads to finish.
		// This keeps the Java application running until the servers are shut down.
		try {
			executor.awaitTermination(Long.MAX_VALUE, TimeUnit.HOURS);
		} catch (InterruptedException e) {
			System.out.println("Servers stopped by interrupt.");
			Thread.currentThread().interrupt();
		} finally {
			// Ensure the executor is shut down in all cases.
			executor.shutdownNow();
			System.out.println("--- GunBound Starter Stopped ---");
		}
	}
}