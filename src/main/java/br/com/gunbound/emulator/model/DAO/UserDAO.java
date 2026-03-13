package br.com.gunbound.emulator.model.DAO;

import br.com.gunbound.emulator.model.entities.DTO.UserDTO;

public interface UserDAO extends AutoCloseable {
	public UserDTO getUserByUserId(String userIdQuery);

	/**
	 * Upsert presence in currentuser table.
	 * Context: 1 = online, 0 = offline.
	 */
	void upsertCurrentUser(String userId, int context, String serverIp, int serverPort);
	
	//operacoes com Gold
	void updateMinusGold(String playerId, int value);
	void updateAddGold(String playerId, int value);
	
	//operacoes com Cash
	void updateMinusCash(String playerId, int value);
	void updateAddCash(String playerId, int value);
	
	//operacoes de recompensa de partida
	void applyMatchResult(String playerId, int goldDelta, int gpDelta);

	@Override
	void close();
}
