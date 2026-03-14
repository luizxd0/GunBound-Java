package br.com.gunbound.emulator.room;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import br.com.gunbound.emulator.handlers.GameAttributes;
import br.com.gunbound.emulator.model.entities.game.PlayerGameResult;
import br.com.gunbound.emulator.model.entities.game.PlayerSession;
import br.com.gunbound.emulator.packets.readers.MessageBcmReader;
import br.com.gunbound.emulator.packets.writers.RoomWriter;
import br.com.gunbound.emulator.playdata.MapData;
import br.com.gunbound.emulator.playdata.MapDataLoader;
import br.com.gunbound.emulator.playdata.SpawnPoint;
import br.com.gunbound.emulator.room.model.enums.GameMode;
import br.com.gunbound.emulator.utils.PacketUtils;
import br.com.gunbound.emulator.utils.Utils;
import br.com.gunbound.emulator.utils.crypto.GunBoundCipher;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;

public class GameRoom {

	// InformaÃ§Ãµes bÃ¡sicas da sala
	private final int roomId;
	private String title;
	private String password;

	private boolean isPrivate;
	// Estado do jogo
	private volatile boolean isGameStarted;

	private int mapId;
	private int gameMode; // Ex: 0=Solo, 1=Score, 2=Tag, 3=jewel.
	private int gameSettings; // Ex: A-SIDE, DOUBLE DEATH

	private int itemState = -1;// Items: Dual,Dual+,Teleport etc.

	// Gerenciamento de jogadores
	private int capacity;
	private PlayerSession roomMaster; // O "dono" da sala
	private final Map<Integer, PlayerSession> playersBySlot = new ConcurrentHashMap<>(); // PosiÃ§Ã£o (slot) -> Jogador
	private final Map<Integer, PlayerGameResult> ResultGameBySlot = new ConcurrentHashMap<>(); // PosiÃ§Ã£o (slot) ->
																								// Jogador
	private final Map<Integer, Boolean> readyStatusBySlot = new ConcurrentHashMap<>(); // PosiÃ§Ã£o (slot) -> Status de
																						// Pronto

	// Quando a partida Ã© score ajustar os placares baseados na qtd de player da sala
	int scoreTeamA = 0;
	int scoreTeamB = 0;
	
	
	//Flag para ter apenas um endGame por partida se nÃ£o vira bagunÃ§a
	private final AtomicBoolean endGameTriggered = new AtomicBoolean(false);
	private final AtomicBoolean rewardsPersisted = new AtomicBoolean(false);


	// Fila thread-safe que mantÃ©m os IDs disponÃ­veis, sempre oferecendo o menor
	// primeiro.
	private final Queue<Integer> availableSlots;

	/**
	 * Construtor para uma nova sala de jogo.
	 * 
	 * @param roomId     O ID Ãºnico da sala.
	 * @param title      O tÃ­tulo da sala.
	 * @param roomMaster O jogador que criou a sala.
	 * @param capacity   A capacidade mÃ¡xima de jogadores.
	 */
	public GameRoom(int roomId, String title, PlayerSession roomMaster, int capacity) {
		this.roomId = roomId;
		this.title = title;
		this.roomMaster = roomMaster;
		this.capacity = capacity;
		this.password = "";
		this.isPrivate = false;
		this.isGameStarted = false;
		this.mapId = 0; // Mapa padrÃ£o

		this.availableSlots = new PriorityBlockingQueue<>(8);
		// A fila Ã© prÃ©-populada com um NÃšMERO FINITO de slots (0 a 7)
		for (int i = 0; i < 8; i++) {
			this.availableSlots.add(i);
		}

		// Adiciona o criador da sala no primeiro slot disponÃ­vel
		addPlayer(roomMaster);
	}

	// --- Getters e Setters ---

	public int getRoomId() {
		return roomId;
	}

	public String getTitle() {
		return title;
	}

	public void setTitle(String title) {
		this.title = title;
	}

	public boolean isPrivate() {
		return isPrivate;
	}

	public void setPassword(String password) {
		this.password = password;
		this.isPrivate = (password != null && !password.isEmpty());
	}

	public boolean checkPassword(String pass) {
		return !isPrivate || this.password.equals(pass);
	}

	public int getMapId() {
		return mapId;
	}

	public void setMapId(int mapId) {
		this.mapId = mapId;
	}

	public PlayerSession getRoomMaster() {
		return roomMaster;
	}

	public boolean hasPowerUserHost() {
		return roomMaster != null && roomMaster.hasActivePowerUser();
	}

	public Map<Integer, PlayerSession> getPlayersBySlot() {
		return playersBySlot;
	}

	public Map<Integer, PlayerGameResult> getResultGameBySlot() {
		return ResultGameBySlot;
	}

	public void setResultGameBySlot(int slot, PlayerGameResult pGameResult) {
		ResultGameBySlot.put(slot, pGameResult);
	}

	public void cleanResultGameBySlot() {
		ResultGameBySlot.clear();
	}

	public int getPlayerCount() {
		return playersBySlot.size();
	}

	public int getCapacity() {
		return capacity;
	}

	public boolean isGameStarted() {
		return isGameStarted;
	}

	public void isGameStarted(boolean state) {
		this.isGameStarted = state;
	}

	public int getGameMode() {
		return gameMode;
	}

	public void setGameMode(int gameMode) {
		this.gameMode = gameMode;
	}

	public int getItemState() {
		return itemState;
	}

	public void setItemState(int itemState) {
		this.itemState = itemState;
	}

	public int getGameSettings() {
		return gameSettings;
	}

	public void setGameSettings(int gameConfig) {
		this.gameSettings = gameConfig;
	}

	public Integer takeSlot() {
		return this.availableSlots.poll();
	}

	public void releaseSlot(int slot) {
		this.availableSlots.add(slot);
	}

	public boolean isFull() {
		return getPlayerCount() >= getCapacity();
	}

	public int getScoreTeamA() {
		return scoreTeamA;
	}

	public void setScoreTeamA(int scoreTeamA) {
		this.scoreTeamA = scoreTeamA;
	}

	public int getScoreTeamB() {
		return scoreTeamB;
	}

	public void setScoreTeamB(int scoreTeamB) {
		this.scoreTeamB = scoreTeamB;
	}
	
	public boolean isAscoreRoom() {
		return GameMode.fromId(getGameMode()).equals(GameMode.SCORE);
	}
	
	
	public void setScoreTeam(int teamId) {
	    if (teamId == 0) {
	        int newScore = Math.max(getScoreTeamA() - 1, 0);
	        setScoreTeamA(newScore);
	        System.out.println("[DEBUG] Novo Score Team A: " + getScoreTeamA());
	    } else if (teamId == 1) {
	        int newScore = Math.max(getScoreTeamB() - 1, 0);
	        setScoreTeamB(newScore);
	        System.out.println("[DEBUG] Novo Score Team B: " + getScoreTeamB());
	    }
	}

	public boolean isTeamHasScore(int teamId) {
	    int checkScoreTeam = 0;
	    if (teamId == 0) {
	        checkScoreTeam = getScoreTeamA();
	    } else if (teamId == 1) {
	        checkScoreTeam = getScoreTeamB();
	    }
	    return checkScoreTeam > 0;
	}
	

	/**
	 * Verifica se ainda hÃ¡ jogadores vivos em uma equipe especÃ­fica.
	 * 
	 * @param teamId O ID da equipe a ser verificada (0 para Time A, 1 para Time B).
	 * @return true se pelo menos um jogador estiver vivo, false caso contrÃ¡rio.
	 */
	public boolean isTeamAlive(int teamId) {
		// Itera sobre todos os jogadores na sala
		for (PlayerSession player : playersBySlot.values()) {
			// Verifica se o jogador pertence Ã  equipe e se estÃ¡ vivo
			if (player.getRoomTeam() == teamId && player.getIsAlive() == 1) {
				return true; // Encontrou um jogador vivo, a equipe ainda estÃ¡ no jogo.
			}
		}
		// Se o loop terminar, significa que nenhum jogador vivo foi encontrado nesta
		// equipe.
		return false;
	}
	
	
	
	/**
	 * Tenta ativar o flag de fim de jogo. 
	 * Garante que o endgame sÃ³ serÃ¡ processado uma Ãºnica vez por partida.
	 * ---------------
	 * O mÃ©todo compareAndSet(false, true) verifica se o valor atual Ã© false: 
	 * Se for, coloca como true e retorna true (indica que vocÃª foi o primeiro a disparar).
	 * Se jÃ¡ estiver true, retorna false (outro fluxo jÃ¡ disparou/finalizou antes).
	 *----------------
	 * @return true se Ã© a primeira vez que estÃ¡ sendo chamado, false se jÃ¡ foi disparado antes.
	 */
	public boolean tryTriggerEndGame() {

	    return endGameTriggered.compareAndSet(false, true);
	}

	/**
	 * Reseta o controle do flag de endgame, permitindo que uma nova partida processe o fim normalmente.
	 * Deve ser chamado apÃ³s preparar a sala para uma nova partida.
	 */
	public void resetEndGameFlag() {
	    endGameTriggered.set(false);
	}

	public boolean tryPersistMatchRewards() {
		return rewardsPersisted.compareAndSet(false, true);
	}

	public void resetMatchRewardsFlag() {
		rewardsPersisted.set(false);
	}

	/**
	 * Realiza toda a checagem se o jogo atingiu condiÃ§Ã£o de fim.
	 * Se sim, retorna o time vencedor.
	 * 
	 * Regras:
	 * - Em modo SCORE: 
	 *      O time que zerar o placar perde. Retorna o ID do time vencedor.
	 * - Em outros modos:
	 *      O time que nÃ£o tem mais jogadores vivos perde. Retorna ID do vencedor.
	 * 
	 * @return 0 se o time A venceu, 1 se o time B venceu, -1 se a partida nÃ£o finalizou ainda.
	 */
	public int checkGameEndAndGetWinner() {
	    if (isAscoreRoom()) {
	        if (!isTeamHasScore(0)) return 1;
	        if (!isTeamHasScore(1)) return 0;
	    }
	    if (!isTeamAlive(0)) return 1;
	    if (!isTeamAlive(1)) return 0;
	    return -1;
	}

	/**
	 * Calcula e retorna o time ideal para um novo jogador entrar, priorizando a
	 * equipe com menos membros.
	 * 
	 * @return 0 para Time A, 1 para Time B.
	 */
	private int getBalancedTeamForNewPlayer() {
		if (playersBySlot.isEmpty()) {
			return 0; // Se a sala estÃ¡ vazia, o primeiro jogador entra no Time A.
		}

		int teamACount = 0;
		int teamBCount = 0;

		// Itera sobre os jogadores da sala para contar os membros de cada time.
		for (PlayerSession player : playersBySlot.values()) {
			if (player.getRoomTeam() == 0) {
				teamACount++;
			} else {
				teamBCount++;
			}
		}

		// Retorna o time que tiver menos jogadores. Em caso de empate, prioriza o Time
		// A.
		if (teamACount <= teamBCount) {
			return 0; // Time A
		} else {
			return 1; // Time B
		}
	}

	/**
	 * Altera o status de "pronto" de um jogador em um determinado slot.
	 * 
	 * @param slot    O slot do jogador.
	 * @param isReady O novo status (true para pronto, false para nÃ£o pronto).
	 */
	public void setPlayerReady(int slot, boolean isReady) {
		if (playersBySlot.containsKey(slot)) {
			readyStatusBySlot.put(slot, isReady);
		}
	}

	/**
	 * ObtÃ©m o status de "pronto" de um jogador em um determinado slot.
	 * 
	 * @param slot O slot do jogador.
	 * @return true se o jogador estiver pronto, false caso contrÃ¡rio.
	 */
	public boolean isPlayerReady(int slot) {
		return readyStatusBySlot.getOrDefault(slot, false);
	}

	/**
	 * Define a nova capacidade mÃ¡xima de jogadores para a sala.
	 * 
	 * @param capacity O novo nÃºmero mÃ¡ximo de jogadores.
	 */
	public void setCapacity(int capacity) {
		// Adicionar validaÃ§Ã£o para garantir que a capacidade nÃ£o seja menor que a
		// contagem atual de jogadores
		if (capacity >= this.playersBySlot.size() && capacity > 0) {
			this.capacity = capacity;
		}
	}

	// --- MÃ©todos de Gerenciamento de Jogadores ---

	public int getRoomMasterSlot() {
		if (roomMaster == null) {
			return -1; // NÃ£o hÃ¡ master na sala.
		}

		PlayerSession master = getRoomMaster();

		return getSlotPlayer(master);

	}

	public int addPlayer(PlayerSession player) {
		// ValidaÃ§Ã£o: Impede que jogadores entrem se a sala estiver cheia ou se o jogo
		// jÃ¡ comeÃ§ou.
		if (isFull() || isGameStarted) {
			System.err.println("Tentativa falhou: Sala " + roomId + " cheia ou jogo em andamento.");
			return -1;
		}

		Integer newSlot = takeSlot();

		// Define um time padrÃ£o usando a logica para balancear as equipes
		player.setRoomTeam(getBalancedTeamForNewPlayer());

		// 1. Adiciona o jogador ao mapa de slots.
		playersBySlot.put(newSlot, player);
		// 2. Define os estados iniciais para o jogador neste slot.
		readyStatusBySlot.put(newSlot, false); // Todo jogador entra como "nÃ£o pronto".
		// player.setRoomTeam(newSlot % 2); // Define um time padrÃ£o (slots pares no
		// time A, Ã­mpares no time B).

		// 3. Associa a sala Ã  sessÃ£o do jogador para fÃ¡cil referÃªncia futura.
		player.setCurrentRoom(this);

		System.out.println("Jogador " + player.getNickName() + " entrou na sala " + roomId + " no slot " + newSlot
				+ " e time " + (player.getRoomTeam() == 0 ? "A" : "B"));

		return newSlot;
	}

	public int getSlotPlayer(PlayerSession player) {
		int slotToRemove = -1;
		for (Map.Entry<Integer, PlayerSession> entry : playersBySlot.entrySet()) {
			if (entry.getValue().equals(player)) {
				slotToRemove = entry.getKey();
				break;
			}
		}
		return slotToRemove;
	}

	/**
	 * Remove um jogador da sala.
	 * 
	 * @param player O jogador a ser removido.
	 */
	public int removePlayer(PlayerSession player) {
		int slotToRemove = -1;
		for (Map.Entry<Integer, PlayerSession> entry : playersBySlot.entrySet()) {
			PlayerSession sessionInSlot = entry.getValue();
			boolean sameReference = sessionInSlot == player;
			boolean sameSession = sessionInSlot != null && sessionInSlot.equals(player);
			boolean sameUser = sessionInSlot != null && player != null && sessionInSlot.getUserNameId() != null
					&& player.getUserNameId() != null
					&& sessionInSlot.getUserNameId().equalsIgnoreCase(player.getUserNameId());
			if (sameReference || sameSession || sameUser) {
				slotToRemove = entry.getKey();
				break;
			}
		}

		if (slotToRemove != -1) {
			releaseSlot(slotToRemove);
			playersBySlot.remove(slotToRemove);
			readyStatusBySlot.remove(slotToRemove);
			player.setCurrentRoom(null); // Desassocia a sala do jogador
			System.out.println("Player " + player.getNickName() + " saiu da sala " + roomId);

			// Se o jogador que saiu era o dono da sala, elege um novo.
			if (player.equals(roomMaster)) {
				electNewRoomMaster();
			}
		}
		return slotToRemove;
	}

	public void notifyPlayerLeftBcm(PlayerSession leftPlayer) {
		if (leftPlayer == null) {
			return;
		}
		broadcastBcmRoomMessage("SISTEMA >> " + leftPlayer.getNickName() + " saiu da sala.");
	}

	private void broadcastBcmRoomMessage(String message) {
		if (message == null || message.isBlank() || playersBySlot.isEmpty()) {
			return;
		}

		Collection<PlayerSession> recipients = new ArrayList<>(playersBySlot.values());
		for (PlayerSession recipient : recipients) {
			if (recipient == null) {
				continue;
			}
			MessageBcmReader.printMsgToPlayer(recipient, message);
		}
	}

	/**
	 * Elege um novo jogador a dono da sala, caso o dono saia ou repasse a key.
	 */
	private void electNewRoomMaster() {
		if (playersBySlot.isEmpty()) {
			this.roomMaster = null;
			// A sala estÃ¡ vazia, pode ser destruÃ­da pelo RoomManager
			System.out.println("Sala " + roomId + " estÃ¡ vazia e pode ser fechada.");
		} else {
			// Elegemos o jogador no menor slot ainda ocupado
			int menorSlot = playersBySlot.keySet().stream().min(Integer::compareTo).orElse(-1);

			if (menorSlot != -1) {
				this.roomMaster = playersBySlot.get(menorSlot);
				System.out.println("Novo dono da sala " + (roomId + 1) + ": " + this.roomMaster.getNickName());
			}

		}
	}

	/**
	 * LÃ³gica principal para iniciar o jogo.
	 */
	private static final int OPCODE_START_GAME = 0x3432;
	private static final int START_METADATA_BASE_LENGTH = 4;
	private static final int JEWEL_SYNC_ENTRY_COUNT = 6;
	private static final int JEWEL_SYNC_ENTRY_SIZE = 3;
	private static final int JEWEL_SYNC_BOUND = 100;
	private final Random random = new Random();

	public void startGame(byte[] payload) {
		if (isGameStarted)
			return;

		/*
		 * boolean allReady =
		 * readyStatusBySlot.values().stream().allMatch(Boolean::booleanValue); if
		 * (!allReady && playersBySlot.size() > 1) { // LÃ³gica de "pronto" simplificada
		 * System.err.
		 * println("Tentativa de iniciar o jogo, mas nem todos estÃ£o prontos."); return;
		 * }
		 */
		
		//Caso a sala teve um jogo anterior reseta a flag de endGame
		this.resetEndGameFlag(); 
		this.resetMatchRewardsFlag();
		this.cleanResultGameBySlot();

		this.isGameStarted = true;
		RoomWriter.broadcastLobbyRoomListRefresh();
		System.out.println("Iniciando jogo na sala " + (roomId + 1));

		// 1. Seleciona o mapa
		if (this.mapId == 0) {
			this.mapId = random.nextInt(10) + 1; // Randomiza entre mapas de 1 a 10
		}
		MapData mapData = MapDataLoader.getMapById(this.mapId);
		if (mapData == null) {
			System.err.println("Mapa com ID " + this.mapId + " nÃ£o encontrado!");
			return;
		}

		// 2. Determina os spawn points
		// List<SpawnPoint> availableSpawns = ((this.gameConfig & 1) == 0) ?
		// mapData.getPositionsASide() : mapData.getPositionsBSide();
		// List<SpawnPoint> availableSpawns = mapData.getPositionsASide();
		// Collections.shuffle(availableSpawns);

		// Collections.shuffle(availableSpawns);

		// 2. Determina os spawn points disponÃ­veis e os embaralha.
		int isASide = (this.gameSettings >> 16) & 0xFF;// pega o byte para ver se Ã© ASide ou BSide
		// boolean isASide = true;
		List<SpawnPoint> shuffledSpawns = new ArrayList<>(
				(isASide == 0) ? mapData.getPositionsASide() : mapData.getPositionsBSide());
		Collections.shuffle(shuffledSpawns);

		Map<Integer, SpawnPoint> playerSpawns = new HashMap<>();
		int spawnIndex = 0;

		// 3. Associa um spawn point para cada jogador e seta alive para cada um deles.
		for (Map.Entry<Integer, PlayerSession> entry : playersBySlot.entrySet()) {
			int slot = entry.getKey();
			PlayerSession player = entry.getValue();

			// seta para "vivo" ao iniciar o jogo
			player.setIsAlive(1);

			System.out.println("[DEBUG] Associando slot do player: " + player.getNickName() + " [" + slot + "]");

			// Associa o spawn point ao slot do jogador.
			playerSpawns.put(slot, shuffledSpawns.get(spawnIndex % shuffledSpawns.size()));
			spawnIndex++;

			// Randomiza os tanques aqui, pois jÃ¡ estamos iterando sobre os jogadores.
			// if (player.getRoomTankPrimary() == 0xFF) {
			// player.setRoomTankPrimary(random.nextInt(14));
			// player.setRoomTankPrimary(Utils.randomMobile(99));
			// }
			// if (player.getRoomTankSecondary() == 0xFF) {
			// player.setRoomTankSecondary(random.nextInt(14));
			// player.setRoomTankSecondary(Utils.randomMobile(99));
			// }
		}

		// 4. Determina a ordem dos turnos
		List<Integer> turnOrder = new ArrayList<>();
		for (int i = 0; i < playersBySlot.size(); i++) {
			turnOrder.add(i);
		}
		Collections.shuffle(turnOrder);

		// 5. Baseado no estilo de jogo seta o score padrao
		if (isAscoreRoom()) {
			int size = playersBySlot.size() / 2;
			// verifica se as equipes estao desbalanceadas (Ex: Gm start 3x2)
			size = (size % 2 == 0) ? size : size + 1;

			setScoreTeamA(size + 1);
			setScoreTeamB(size + 1);
			
			System.out.println("DEBUG Entrou no if do Score: " + GameMode.fromId(gameMode).getName());
			System.out.println("ScoreTeam A" + getScoreTeamA());
			System.out.println("ScoreTeam B" + getScoreTeamA());
		}
		


		// 6. ConstrÃ³i e envia o pacote de inÃ­cio
		byte[] startMetadata = buildStartMetadata(payload);
		ByteBuf startPayload = Unpooled
				.wrappedBuffer(RoomWriter.writeGameStartPacketTest(this, turnOrder, playerSpawns, startMetadata));
		System.out.println("[DEBUG] Antes Start -> " + Utils.bytesToHex(startPayload.array()));

		// 6. Envia o pacote criptografado e com padding para cada jogador
		for (PlayerSession player : playersBySlot.values()) {
			try {
				// System.out.println("[DEBUG] Start -> " + Utils.bytesToHex(bytesToEncrypt));
				byte[] encryptedPayload = GunBoundCipher.gunboundDynamicEncrypt(
						startPayload.retainedDuplicate().array(), // Usa o array com
						// padding
						player.getUserNameId(), player.getPassword(),
						player.getPlayerCtxChannel().attr(GameAttributes.AUTH_TOKEN).get(), OPCODE_START_GAME);

				// Envia o pacote criptografado
				//int txSum = player.getPlayerCtx().attr(GameAttributes.PACKET_TX_SUM).get();
				ByteBuf finalPacket = PacketUtils.generatePacket(player, OPCODE_START_GAME,
						Unpooled.wrappedBuffer(encryptedPayload),false);

				// Thread.sleep(150);
				submitAction(() -> 
				player.getPlayerCtxChannel().eventLoop().execute(() -> {
					player.getPlayerCtxChannel().writeAndFlush(finalPacket);
				}),player.getPlayerCtx());

				// player.getPlayerCtx().eventLoop().schedule(() -> {
				// player.getPlayerCtx().writeAndFlush(finalPacket);
				// }, 500, java.util.concurrent.TimeUnit.MILLISECONDS);

			} catch (Exception e) {
				System.err.println("Falha ao criptografar ou enviar pacote de inÃ­cio para " + player.getNickName());
				e.printStackTrace();
			}
		}

		startPayload.release();
		System.out.println("Pacotes de inÃ­cio de jogo enfileirados para " + getPlayerCount() + " jogadores.");
	}

	/**
	 * Notifica TODOS os jogadores na sala com o comando de atualizaÃ§Ã£o (0x3105). A
	 * prÃ³pria sala Ã© responsÃ¡vel por criar e enviar o pacote para cada membro com a
	 * sequÃªncia correta.
	 */
	private byte[] buildStartMetadata(byte[] clientPayload) {
		boolean jewelMode = GameMode.fromId(this.gameMode) == GameMode.JEWEL;
		int expectedLength = jewelMode ? START_METADATA_BASE_LENGTH + (JEWEL_SYNC_ENTRY_COUNT * JEWEL_SYNC_ENTRY_SIZE)
				: START_METADATA_BASE_LENGTH;

		if (clientPayload != null && clientPayload.length == expectedLength) {
			return clientPayload;
		}

		ByteBuf generated = Unpooled.buffer(expectedLength);
		try {
			// Same metadata shape used by the client start button (0x3430).
			generated.writeIntLE((int) (System.nanoTime() / 1_000_000L));

			if (jewelMode) {
				for (int i = 0; i < JEWEL_SYNC_ENTRY_COUNT; i++) {
					generated.writeShortLE(random.nextInt(0x10000));
					generated.writeByte(random.nextInt(JEWEL_SYNC_BOUND));
				}
			}

			byte[] metadata = new byte[generated.readableBytes()];
			generated.readBytes(metadata);

			System.out.println("[START] Fallback metadata generated. mode=" + GameMode.fromId(this.gameMode).getName()
					+ ", expectedLen=" + expectedLength + ", receivedLen="
					+ (clientPayload == null ? -1 : clientPayload.length));
			return metadata;
		} finally {
			generated.release();
		}
	}

	private static final int OPCODE_ROOM_UPDATE = 0x3105;

	public void broadcastRoomUpdate() {
		// O payload da notificacao e vazio.
		ByteBuf notifyPayload = Unpooled.EMPTY_BUFFER;
		System.out.println("Iniciando broadcast de atualizacao (0x3105) para a sala " + this.roomId);
		// snapshot para evitar concorrencia
		List<PlayerSession> recipients = new ArrayList<>(getPlayersBySlot().values());
		for (PlayerSession playerInRoom : recipients) {
			if (playerInRoom == null || playerInRoom.getPlayerCtxChannel() == null
					|| !playerInRoom.getPlayerCtxChannel().isActive()) {
				continue;
			}
			try {
				playerInRoom.getPlayerCtxChannel().eventLoop().execute(() -> {
					if (!playerInRoom.getPlayerCtxChannel().isActive()) {
						return;
					}
					ByteBuf notifyPacket = PacketUtils.generatePacket(playerInRoom, OPCODE_ROOM_UPDATE, notifyPayload, true);
					playerInRoom.getPlayerCtxChannel().writeAndFlush(notifyPacket).addListener((ChannelFutureListener) future -> {
						if (!future.isSuccess()) {
							System.err.println("FALHA AO ENVIAR PACOTE DE TUNEL para: " + playerInRoom.getNickName());
							future.cause().printStackTrace();
							playerInRoom.getPlayerCtxChannel().close();
						}
					});
				});
			} catch (Exception e) {
				System.err.println("Falha ao enviar broadcast de atualizacao para " + playerInRoom.getNickName());
				e.printStackTrace();
			}
		}
	}

	/**
	 * Notifica os jogadores existentes na sala que um novo jogador entrou.
	 * 
	 * @param newPlayer     O jogador que acabou de entrar.
	 * @param newPlayerSlot O slot que o novo jogador ocupou.
	 */
	private static final int OPCODE_NOTIFY_JOIN = 0x3010;

	public void notifyOthersPlayerJoined(PlayerSession newPlayer) {
		for (PlayerSession playerInRoom : playersBySlot.values()) {
			// Envia para todos, EXCETO o jogador que acabou de entrar.
			if (!playerInRoom.equals(newPlayer)) {
				if (playerInRoom.getPlayerCtxChannel() == null || !playerInRoom.getPlayerCtxChannel().isActive()) {
					continue;
				}
				playerInRoom.getPlayerCtxChannel().eventLoop().execute(() -> {
					if (!playerInRoom.getPlayerCtxChannel().isActive()) {
						return;
					}
					ByteBuf notifyPayload = RoomWriter.writeNotifyPlayerJoinedRoom(newPlayer);
					try {
						ByteBuf notifyPacket = PacketUtils.generatePacket(playerInRoom, OPCODE_NOTIFY_JOIN,
								notifyPayload, false);
						playerInRoom.getPlayerCtxChannel().writeAndFlush(notifyPacket);
					} finally {
						notifyPayload.release();
					}
				});
			}
		}
		System.out.println("Notificacao de entrada (0x3010) enviada para " + (getPlayerCount() - 1) + " jogador(es).");
	}

	/**
	 * Notifica os jogadores restantes que um jogador saiu de um slot especÃ­fico.
	 * 
	 * @param leftPlayerSlot O slot que ficou vago.
	 */

	private static final int OPCODE_PLAYER_LEFT = 0x3020;

	public void notifyPlayerLeft(int leftPlayerSlot,boolean wasHost) {
		// snapshot para evitar concorrencia
		Collection<PlayerSession> recipients = new ArrayList<>(getPlayersBySlot().values());
		for (PlayerSession playerInRoom : recipients) {
			if (playerInRoom == null || playerInRoom.getPlayerCtxChannel() == null
					|| !playerInRoom.getPlayerCtxChannel().isActive()) {
				continue;
			}
			playerInRoom.getPlayerCtxChannel().eventLoop().execute(() -> {
				if (!playerInRoom.getPlayerCtxChannel().isActive()) {
					return;
				}
				ByteBuf payload = Unpooled.buffer(2).writeShortLE(leftPlayerSlot);
				try {
					ByteBuf notifyPacket = PacketUtils.generatePacket(playerInRoom, OPCODE_PLAYER_LEFT,
							payload, false);
					playerInRoom.getPlayerCtxChannel().writeAndFlush(notifyPacket);
				} finally {
					payload.release();
				}
			});
		}
		System.out.println("Notificacao de saida do slot (0x3020) " + leftPlayerSlot + " enviada para a sala.");
		if (wasHost && !playersBySlot.isEmpty()) {
			System.out.println("[DEBUG]: Entrou no if de washost");
			notifyHostMigration();
		}
	}

	/**
	 * Notifica os jogadores restantes sobre a migraÃ§Ã£o do host.
	 */
	private static final int OPCODE_HOST_MIGRATION = 0x3400;

	public void notifyHostMigration() {

		this.electNewRoomMaster();

		System.out.println("[DEBUG] Slot do Novo Master:" + this.getRoomMasterSlot());

		// snapshot para evitar concorrencia
		Collection<PlayerSession> recipients = new ArrayList<>(getPlayersBySlot().values());
		for (PlayerSession playerInRoom : recipients) {
			if (playerInRoom == null || playerInRoom.getPlayerCtxChannel() == null
					|| !playerInRoom.getPlayerCtxChannel().isActive()) {
				continue;
			}

			playerInRoom.getPlayerCtxChannel().eventLoop().execute(() -> {
				if (!playerInRoom.getPlayerCtxChannel().isActive()) {
					return;
				}

				ByteBuf payload = buildHostMigrationPayload();
				try {
					ByteBuf notifyPacket = PacketUtils.generatePacket(playerInRoom, OPCODE_HOST_MIGRATION, payload, false);
					playerInRoom.getPlayerCtxChannel().writeAndFlush(notifyPacket);
				} finally {
					payload.release();
				}
			});
		}

		System.out.println("Notificacao de migracao de host (0x3400) enviada para a sala.");
	}

	private ByteBuf buildHostMigrationPayload() {
		ByteBuf buffer = Unpooled.buffer();
		buffer.writeByte(getRoomMasterSlot());

		byte[] titleBytes = getTitle().getBytes(StandardCharsets.ISO_8859_1);
		buffer.writeByte(titleBytes.length);
		buffer.writeBytes(titleBytes);

		buffer.writeByte(getMapId());
		buffer.writeIntLE(getGameSettings());
		buffer.writeIntLE(getItemState());
		buffer.writeBytes(new byte[] { (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF });
		buffer.writeByte(getCapacity());
		return buffer;
	}

	// *****************************FILA PARA PROCESSAR A
	// SALA*****************************

    // Classe interna que associa a aÃ§Ã£o ao seu contexto do Netty
    private static class QueuedAction {
        final Runnable task;
        final ChannelHandlerContext ctx;

        QueuedAction(Runnable task, ChannelHandlerContext ctx) {
            this.task = task;
            this.ctx = ctx;
        }
    }

    // Fila thread-safe para as aÃ§Ãµes
    private final Queue<QueuedAction> actionQueue = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean processing = new AtomicBoolean(false);
    
    // Enfileira aÃ§Ã£o + contexto
    public void submitAction(Runnable action, ChannelHandlerContext ctx) {
        actionQueue.add(new QueuedAction(action, ctx));
        processNext(); // Tenta processar (sÃ³ dispara se nÃ£o estiver em processamento)
    }

    // Controle thread-safe: sÃ³ um processador por vez
    private void processNext() {
        if (processing.compareAndSet(false, true)) {
            nextAction();
        }
    }

    private void nextAction() {
        QueuedAction act = actionQueue.poll();
        if (act != null) {
            // Executa a aÃ§Ã£o no event loop certo do Netty (para evitar problema de concorrÃªncia no canal)
            act.ctx.executor().execute(() -> {
                try {
                    act.task.run();
                } catch (Exception e) {
                    System.out.println("[ERRO] ExceÃ§Ã£o em Room Action: " + e.getMessage());
                    e.printStackTrace();
                } finally {
                    nextAction();
                }
            });
        } else {
            // NinguÃ©m na fila: libera o flag de processamento
            processing.set(false);
            // Confere se, enquanto processava, outra thread nÃ£o enfileirou nova aÃ§Ã£o nesse meio tempo
            if (!actionQueue.isEmpty()) {
                processNext();
            }
        }
    }

}

