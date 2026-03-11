package br.com.gunbound.emulator.model.DAO;

import java.util.List;
import java.util.Map;

/**
 * Data Access Object for buddy server operations.
 * Mirrors the SQL operations from the original BuddyServ2.exe:
 * DBIN_PASSWORD, DBIN_BUDDYLIST, DBIN_BUDDYADD, DBIN_BUDDYDEL,
 * DBIN_SEARCH, DBIN_GETPACKET, DBIN_DELPACKET, DBIN_SAVEPACKET.
 */
public interface BuddyDAO {

    /**
     * DBIN_BUDDYLIST: Get the buddy list for a user.
     * SQL: SELECT bl.Buddy, bl.Category, u.NickName, g.Guild, g.TotalRank,
     * g.TotalGrade
     * FROM BuddyList bl
     * JOIN user u ON ...
     * LEFT JOIN game g ON ...
     * WHERE bl.Id = ?
     */
    List<Map<String, Object>> getBuddyList(String userId);

    /**
     * DBIN_BUDDYADD: Add a buddy relationship.
     * SQL: INSERT INTO BuddyList (Id, Category, Buddy) VALUES (?, '', ?)
     */
    boolean addBuddy(String userId, String buddyId);
    boolean isBuddy(String userId, String buddyId);

    /**
     * DBIN_BUDDYDEL: Remove a buddy relationship.
     * SQL: DELETE FROM BuddyList WHERE Id=? AND Buddy=?
     */
    boolean removeBuddy(String userId, String buddyId);

    /**
     * Get user game data by userId or nickname.
     * Returns: UserId, NickName, Guild, TotalRank, TotalGrade, Password
     */
    Map<String, Object> getUserGameData(String userIdOrNickName);

    /**
     * Resolve nickname to userId.
     */
    Map<String, Object> getUserByNickname(String nickname);

    /**
     * DBIN_SEARCH: Search for users.
     * SQL: SELECT u.Id, u.NickName, g.Guild, g.TotalRank FROM user u
     * LEFT JOIN game g ON ... WHERE u.NickName LIKE ?
     */
    List<Map<String, Object>> searchUsers(String keyword, int offset, int limit);

    /**
     * DBIN_GETPACKET: Retrieve offline packets for a user.
     * SQL: SELECT SerialNo, Sender, Code, Body, Time FROM Packet WHERE Receiver=?
     */
    List<Map<String, Object>> getOfflinePackets(String receiverId);

    /**
     * DBIN_DELPACKET: Delete an offline packet by serial number.
     * SQL: DELETE FROM Packet WHERE SerialNo=?
     */
    boolean deleteOfflinePacket(long serialNo);

    /**
     * DBIN_SAVEPACKET: Save an offline packet/tunnel message.
     * SQL: INSERT INTO Packet (Receiver, Sender, Code, Body, Time) VALUES (?, ?, ?,
     * ?, NOW())
     */
    boolean saveOfflinePacket(String receiverId, String senderId, int code, byte[] body);

    /**
     * Log a buddy server login event.
     */
    void loginLog(String userId, String ip, int port, String serverIp, int serverPort, int country);
}
