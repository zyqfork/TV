package com.fongmi.android.tv.dlna;

import android.os.Build;
import android.text.TextUtils;

import com.fongmi.android.tv.setting.DlnaSetting;

import org.jupnp.android.AndroidNetworkAddressFactory;
import org.jupnp.android.AndroidUpnpServiceConfiguration;
import org.jupnp.model.ServerClientTokens;
import org.jupnp.transport.spi.NetworkAddressFactory;
import org.jupnp.transport.spi.StreamClient;
import org.jupnp.transport.spi.StreamServer;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;

public class DLNAServiceConfiguration extends AndroidUpnpServiceConfiguration {

    /** Renderer mode: the HTTP stream server must listen on a stable, predictable port. */
    private final boolean fixedListenPort;

    /** jUPnP stream server port; 0 means "any free port" but some controllers ignore ephemeral LOCATIONs. */
    private static final int DEFAULT_STREAM_PORT = 49152;

    public DLNAServiceConfiguration() {
        this(false);
    }

    public DLNAServiceConfiguration(boolean fixedListenPort) {
        super(resolveListenPort(fixedListenPort), 0);
        this.fixedListenPort = fixedListenPort;
    }

    private static int resolveListenPort(boolean fixedListenPort) {
        if (!fixedListenPort) return 0;
        int port = DlnaSetting.getHttpPort();
        return port > 0 ? port : DEFAULT_STREAM_PORT;
    }

    @Override
    @SuppressWarnings("rawtypes")
    public StreamClient createStreamClient() {
        return new OkHttpStreamClient(new OkHttpStreamClient.Configuration(getSyncProtocolExecutorService()) {
            @Override
            public String getUserAgentValue(int majorVersion, int minorVersion) {
                ServerClientTokens tokens = new ServerClientTokens(majorVersion, minorVersion);
                tokens.setOsVersion(Build.VERSION.RELEASE);
                tokens.setOsName("Android");
                return tokens.toString();
            }
        });
    }

    @Override
    @SuppressWarnings("rawtypes")
    public StreamServer createStreamServer(NetworkAddressFactory networkAddressFactory) {
        boolean fallbackToEphemeral = fixedListenPort && DlnaSetting.getHttpPort() == 0;
        return new SocketHttpStreamServer(new SocketHttpStreamServer.Configuration(networkAddressFactory.getStreamListenPort(), fallbackToEphemeral));
    }

    @Override
    protected NetworkAddressFactory createNetworkAddressFactory(int streamListenPort, int multicastResponsePort) {
        // Narrow the bound interfaces for both roles (renderer and control point). Hosts like
        // rk3588 + Docker expose docker0/br-*/tailscale0 plus dozens of IPv6 temporaries, and every
        // extra address adds SSDP sockets that delay MediaServer discovery (e.g. slow IPTV boxes).
        String iface = DlnaSetting.resolveInterfaceName();
        return new AndroidNetworkAddressFactory(streamListenPort, multicastResponsePort) {
            @Override
            protected boolean isUsableNetworkInterface(NetworkInterface networkInterface) throws Exception {
                if (!super.isUsableNetworkInterface(networkInterface)) return false;
                if (!DlnaNetwork.isCandidate(networkInterface)) return false;
                return TextUtils.isEmpty(iface) || iface.equals(networkInterface.getName());
            }

            @Override
            protected boolean isUsableAddress(NetworkInterface networkInterface, InetAddress address) {
                // Prefer IPv4, but never reject a host that only has IPv6: dropping every address
                // leaves jUPnP with nothing to bind and the service silently never starts.
                boolean preferIpv4 = address instanceof Inet4Address || TextUtils.isEmpty(DlnaNetwork.firstIpv4(networkInterface));
                return preferIpv4 && super.isUsableAddress(networkInterface, address);
            }
        };
    }
}
