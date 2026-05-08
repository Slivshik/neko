package io.nekohasekai.sagernet.fmt.wireguard;

import androidx.annotation.NonNull;

import com.esotericsoftware.kryo.io.ByteBufferInput;
import com.esotericsoftware.kryo.io.ByteBufferOutput;

import org.jetbrains.annotations.NotNull;

import io.nekohasekai.sagernet.fmt.AbstractBean;
import io.nekohasekai.sagernet.fmt.KryoConverters;

public class WireGuardBean extends AbstractBean {

    public String localAddress;
    public String privateKey;
    public String peerPublicKey;
    public String peerPreSharedKey;
    public Integer mtu;
    public String reserved;
    public Boolean turnEnabled;
    public String turnServer;
    public Integer turnPort;
    public String turnPeer;
    public String turnAuthLink;
    public String turnSource;
    public Boolean turnUseUdp;
    public Integer turnRelayPort;

    @Override
    public void initializeDefaultValues() {
        super.initializeDefaultValues();
        if (localAddress == null) localAddress = "";
        if (privateKey == null) privateKey = "";
        if (peerPublicKey == null) peerPublicKey = "";
        if (peerPreSharedKey == null) peerPreSharedKey = "";
        if (mtu == null) mtu = 1420;
        if (reserved == null) reserved = "";
        if (turnEnabled == null) turnEnabled = true;
        if (turnServer == null) turnServer = "";
        if (turnPort == null) turnPort = 3478;
        if (turnPeer == null) turnPeer = "";
        if (turnAuthLink == null) turnAuthLink = "";
        if (turnSource == null) turnSource = "vk";
        if (turnUseUdp == null) turnUseUdp = false;
        if (turnRelayPort == null) turnRelayPort = 9000;
    }

    @Override
    public void serialize(ByteBufferOutput output) {
        output.writeInt(4);
        super.serialize(output);
        output.writeString(localAddress);
        output.writeString(privateKey);
        output.writeString(peerPublicKey);
        output.writeString(peerPreSharedKey);
        output.writeInt(mtu);
        output.writeString(reserved);
        output.writeBoolean(turnEnabled);
        output.writeString(turnServer);
        output.writeInt(turnPort);
        output.writeString(turnPeer);
        output.writeString(turnAuthLink);
        output.writeString(turnSource);
        output.writeBoolean(turnUseUdp);
        output.writeInt(turnRelayPort);
    }

    @Override
    public void deserialize(ByteBufferInput input) {
        int version = input.readInt();
        super.deserialize(input);
        localAddress = input.readString();
        privateKey = input.readString();
        peerPublicKey = input.readString();
        peerPreSharedKey = input.readString();
        mtu = input.readInt();
        reserved = input.readString();
        if (version >= 4) {
            turnEnabled = input.readBoolean();
            turnServer = input.readString();
            turnPort = input.readInt();
            turnPeer = input.readString();
            turnAuthLink = input.readString();
            turnSource = input.readString();
            turnUseUdp = input.readBoolean();
            turnRelayPort = input.readInt();
        } else if (version >= 3) {
            turnEnabled = input.readBoolean();
            turnServer = input.readString();
            turnPort = input.readInt();
            turnSource = input.readString();
            turnUseUdp = input.readBoolean();
            turnRelayPort = input.readInt();
        }
    }

    @Override
    public boolean canTCPing() {
        return false;
    }

    @NotNull
    @Override
    public WireGuardBean clone() {
        return KryoConverters.deserialize(new WireGuardBean(), KryoConverters.serialize(this));
    }

    public static final Creator<WireGuardBean> CREATOR = new CREATOR<WireGuardBean>() {
        @NonNull
        @Override
        public WireGuardBean newInstance() {
            return new WireGuardBean();
        }

        @Override
        public WireGuardBean[] newArray(int size) {
            return new WireGuardBean[size];
        }
    };
}
