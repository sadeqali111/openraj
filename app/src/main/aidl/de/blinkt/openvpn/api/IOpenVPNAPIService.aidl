package de.blinkt.openvpn.api;

import de.blinkt.openvpn.api.APIVpnProfile;
import de.blinkt.openvpn.api.IOpenVPNStatusCallback;
import android.content.Intent;
import android.os.ParcelFileDescriptor;

interface IOpenVPNAPIService {
    List<APIVpnProfile> getProfiles();
    void startProfile(String profileUUID);
    APIVpnProfile addVPNProfile(String name, String config);
    void startVPN(String inlineconfig);
    Intent prepare(String packagename);
    Intent prepareVPNService();
    void registerStatusCallback(IOpenVPNStatusCallback cb);
    void unregisterStatusCallback(IOpenVPNStatusCallback cb);
    void removeProfile(String profileUUID);
    boolean protectSocket(in ParcelFileDescriptor fd);
    void disconnect();
    void pause();
    void resume();
    APIVpnProfile addNewVPNProfile(String name, boolean userEditable, String config);
}
