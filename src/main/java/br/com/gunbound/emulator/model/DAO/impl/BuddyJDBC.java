package br.com.gunbound.emulator.model.DAO.impl;

import br.com.gunbound.emulator.db.DatabaseManager;
import br.com.gunbound.emulator.model.DAO.BuddyDAO;

import java.sql.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * JDBC implementation of BuddyDAO.
 * All SQL queries replicate those found in BuddyServ2.exe string extraction.
 * Uses the existing HikariCP connection pool via DatabaseManager.
 */
public class BuddyJDBC implements BuddyDAO {

    @Override
    public List<Map<String, Object>> getBuddyList(String userId) {
        List<Map<String, Object>> buddies = new ArrayList<>();
        String sql = "SELECT bl.Buddy, bl.Category, g.NickName, g.Guild, g.TotalRank, g.TotalGrade "
                + "FROM BuddyList bl "
                + "LEFT JOIN game g ON bl.Buddy = g.UserId "
                + "WHERE bl.Id = ?";
        try (Connection conn = DatabaseManager.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> buddy = new HashMap<>();
                    buddy.put("Buddy", rs.getString("Buddy"));
                    buddy.put("Category", rs.getString("Category"));
                    buddy.put("NickName", rs.getString("NickName"));
                    buddy.put("Guild", rs.getString("Guild"));
                    buddy.put("TotalRank", rs.getInt("TotalRank"));
                    buddy.put("TotalGrade", rs.getInt("TotalGrade"));
                    buddies.add(buddy);
                }
            }
        } catch (SQLException e) {
            System.err.println("BS DB: Error getting buddy list for " + userId + ": " + e.getMessage());
        }
        return buddies;
    }

    @Override
    public boolean addBuddy(String userId, String buddyId) {
        if (isBuddy(userId, buddyId)) return true;
        String sql = "INSERT IGNORE INTO BuddyList (Id, Category, Buddy) VALUES (?, '', ?)";
        try (Connection conn = DatabaseManager.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, userId);
            ps.setString(2, buddyId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("BS DB: Error adding buddy " + buddyId + " for " + userId + ": " + e.getMessage());
            return false;
        }
    }

    @Override
    public boolean isBuddy(String userId, String buddyId) {
        String sql = "SELECT 1 FROM BuddyList WHERE Id = ? AND Buddy = ?";
        try (Connection conn = DatabaseManager.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, userId);
            ps.setString(2, buddyId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            System.err.println("BS DB: Error checking buddy " + buddyId + " for " + userId + ": " + e.getMessage());
            return false;
        }
    }

    @Override
    public boolean removeBuddy(String userId, String buddyId) {
        String sql = "DELETE FROM BuddyList WHERE Id = ? AND Buddy = ?";
        try (Connection conn = DatabaseManager.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, userId);
            ps.setString(2, buddyId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("BS DB: Error removing buddy " + buddyId + " for " + userId + ": " + e.getMessage());
            return false;
        }
    }

    @Override
    public Map<String, Object> getUserGameData(String userIdOrNickName) {
        // Try by UserId first, then by NickName
        String sql = "SELECT u.UserId, g.NickName, g.Guild, g.TotalRank, g.TotalGrade, u.Password "
                + "FROM user u "
                + "LEFT JOIN game g ON u.UserId = g.UserId "
                + "WHERE u.UserId = ? OR g.NickName = ? LIMIT 1";
        try (Connection conn = DatabaseManager.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, userIdOrNickName);
            ps.setString(2, userIdOrNickName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Map<String, Object> data = new HashMap<>();
                    data.put("UserId", rs.getString("UserId"));
                    data.put("NickName", rs.getString("NickName"));
                    data.put("Guild", rs.getString("Guild"));
                    data.put("TotalRank", rs.getInt("TotalRank"));
                    data.put("TotalGrade", rs.getInt("TotalGrade"));
                    data.put("Password", rs.getString("Password"));
                    return data;
                }
            }
        } catch (SQLException e) {
            System.err.println("BS DB: Error getting user data for " + userIdOrNickName + ": " + e.getMessage());
        }
        return null;
    }

    @Override
    public Map<String, Object> getUserByNickname(String nickname) {
        String sql = "SELECT u.UserId, g.NickName, g.Guild, g.TotalRank, g.TotalGrade "
                + "FROM user u "
                + "LEFT JOIN game g ON u.UserId = g.UserId "
                + "WHERE g.NickName = ? LIMIT 1";
        try (Connection conn = DatabaseManager.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, nickname);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Map<String, Object> data = new HashMap<>();
                    data.put("UserId", rs.getString("UserId"));
                    data.put("NickName", rs.getString("NickName"));
                    data.put("Guild", rs.getString("Guild"));
                    data.put("TotalRank", rs.getInt("TotalRank"));
                    data.put("TotalGrade", rs.getInt("TotalGrade"));
                    return data;
                }
            }
        } catch (SQLException e) {
            System.err.println("BS DB: Error getting user by nickname " + nickname + ": " + e.getMessage());
        }
        return null;
    }

    @Override
    public List<Map<String, Object>> searchUsers(String keyword, int offset, int limit) {
        List<Map<String, Object>> results = new ArrayList<>();
        String sql = "SELECT u.UserId, g.NickName, g.Guild, g.TotalRank, g.TotalGrade "
                + "FROM user u "
                + "LEFT JOIN game g ON u.UserId = g.UserId "
                + "WHERE g.NickName LIKE ? "
                + "LIMIT ? OFFSET ?";
        try (Connection conn = DatabaseManager.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, "%" + keyword + "%");
            ps.setInt(2, limit);
            ps.setInt(3, offset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> user = new HashMap<>();
                    user.put("UserId", rs.getString("UserId"));
                    user.put("NickName", rs.getString("NickName"));
                    user.put("Guild", rs.getString("Guild"));
                    user.put("TotalRank", rs.getInt("TotalRank"));
                    user.put("TotalGrade", rs.getInt("TotalGrade"));
                    results.add(user);
                }
            }
        } catch (SQLException e) {
            System.err.println("BS DB: Error searching users for '" + keyword + "': " + e.getMessage());
        }
        return results;
    }

    @Override
    public List<Map<String, Object>> getOfflinePackets(String receiverId) {
        List<Map<String, Object>> packets = new ArrayList<>();
        String sql = "SELECT SerialNo, Sender, Code, Body, Time FROM Packet WHERE Receiver = ? ORDER BY SerialNo ASC";
        try (Connection conn = DatabaseManager.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, receiverId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> pkt = new HashMap<>();
                    pkt.put("SerialNo", rs.getLong("SerialNo"));
                    pkt.put("Sender", rs.getString("Sender"));
                    pkt.put("Code", rs.getInt("Code"));
                    pkt.put("Body", rs.getBytes("Body"));
                    pkt.put("Time", rs.getTimestamp("Time"));
                    packets.add(pkt);
                }
            }
        } catch (SQLException e) {
            System.err.println("BS DB: Error getting offline packets for " + receiverId + ": " + e.getMessage());
        }
        return packets;
    }

    @Override
    public boolean deleteOfflinePacket(long serialNo) {
        String sql = "DELETE FROM Packet WHERE SerialNo = ?";
        try (Connection conn = DatabaseManager.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, serialNo);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("BS DB: Error deleting packet " + serialNo + ": " + e.getMessage());
            return false;
        }
    }

    @Override
    public boolean saveOfflinePacket(String receiverId, String senderId, int code, byte[] body) {
        String sql = "INSERT INTO Packet (Receiver, Sender, Code, Body, Time) VALUES (?, ?, ?, ?, NOW())";
        try (Connection conn = DatabaseManager.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, receiverId);
            ps.setString(2, senderId);
            ps.setInt(3, code);
            ps.setBytes(4, body);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("BS DB: Error saving offline packet from " + senderId + " to " + receiverId + ": "
                    + e.getMessage());
            return false;
        }
    }

    @Override
    public void loginLog(String userId, String ip, int port, String serverIp, int serverPort, int country) {
        String sql = "INSERT INTO loginlog (Id, Ip, Ip_v, Port, Port_v, ServerIp, ServerPort, Country, Time) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, NOW())";
        try (Connection conn = DatabaseManager.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, userId);
            ps.setString(2, ip);
            ps.setString(3, ip);
            ps.setInt(4, port);
            ps.setInt(5, port);
            ps.setString(6, serverIp);
            ps.setInt(7, serverPort);
            ps.setInt(8, country);
            ps.executeUpdate();
        } catch (SQLException e) {
            // Login log failures should not crash the server
            System.err.println("BS DB: Error logging login for " + userId + ": " + e.getMessage());
        }
    }
}
