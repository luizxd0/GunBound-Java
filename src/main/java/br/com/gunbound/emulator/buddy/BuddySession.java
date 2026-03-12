package br.com.gunbound.emulator.buddy;

import io.netty.channel.Channel;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Represents an authenticated buddy server session.
 * Tracks the user's identity, game data, and Netty channel.
 */
public class BuddySession {

    private String userId;
    private String nickName;
    private String guild;
    private int totalRank;
    private int totalGrade;
    private Channel channel;
    private boolean authenticated;
    private byte[] syncToken;
    private int udpNonce;
    private byte[] clientExternalIp;
    private int clientExternalPort;
    private byte[] clientInternalIp;
    private int clientInternalPort;
    private String password;
    private byte[] authToken;
    private final AtomicBoolean loginHandshakeFinalized = new AtomicBoolean(false);

    public BuddySession(Channel channel) {
        this.channel = channel;
        this.authenticated = false;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getNickName() {
        return nickName;
    }

    public void setNickName(String nickName) {
        this.nickName = nickName;
    }

    public String getGuild() {
        return guild;
    }

    public void setGuild(String guild) {
        this.guild = guild;
    }

    public int getTotalRank() {
        return totalRank;
    }

    public void setTotalRank(int totalRank) {
        this.totalRank = totalRank;
    }

    public int getTotalGrade() {
        return totalGrade;
    }

    public void setTotalGrade(int totalGrade) {
        this.totalGrade = totalGrade;
    }

    public Channel getChannel() {
        return channel;
    }

    public void setChannel(Channel channel) {
        this.channel = channel;
    }

    public boolean isAuthenticated() {
        return authenticated;
    }

    public void setAuthenticated(boolean authenticated) {
        this.authenticated = authenticated;
    }

    public byte[] getSyncToken() {
        return syncToken;
    }

    public void setSyncToken(byte[] syncToken) {
        this.syncToken = syncToken;
    }

    public int getUdpNonce() {
        return udpNonce;
    }

    public void setUdpNonce(int udpNonce) {
        this.udpNonce = udpNonce;
    }

    public byte[] getClientExternalIp() {
        return clientExternalIp;
    }

    public void setClientExternalIp(byte[] clientExternalIp) {
        this.clientExternalIp = clientExternalIp;
    }

    public int getClientExternalPort() {
        return clientExternalPort;
    }

    public void setClientExternalPort(int clientExternalPort) {
        this.clientExternalPort = clientExternalPort;
    }

    public byte[] getClientInternalIp() {
        return clientInternalIp;
    }

    public void setClientInternalIp(byte[] clientInternalIp) {
        this.clientInternalIp = clientInternalIp;
    }

    public int getClientInternalPort() {
        return clientInternalPort;
    }

    public void setClientInternalPort(int clientInternalPort) {
        this.clientInternalPort = clientInternalPort;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public byte[] getAuthToken() {
        return authToken != null ? authToken.clone() : null;
    }

    public void setAuthToken(byte[] authToken) {
        this.authToken = authToken != null ? authToken.clone() : null;
    }

    public boolean tryFinalizeLoginHandshake() {
        return loginHandshakeFinalized.compareAndSet(false, true);
    }

    public void resetLoginHandshakeFinalized() {
        loginHandshakeFinalized.set(false);
    }

    public boolean isLoginHandshakeFinalized() {
        return loginHandshakeFinalized.get();
    }

    /**
     * Checks if the underlying channel is still active.
     */
    public boolean isActive() {
        return channel != null && channel.isActive();
    }

    @Override
    public String toString() {
        return "BuddySession{userId='" + userId + "', nick='" + nickName + "', guild='" + guild + "'}";
    }
}
