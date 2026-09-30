package com.fongmi.android.tv.dlna;

import android.os.Build;
import android.text.TextUtils;

import com.fongmi.android.tv.setting.DlnaSetting;

import org.jupnp.android.AndroidNetworkAddressFactory;
import org.jupnp.android.AndroidUpnpServiceConfiguration;
import org.jupnp.binding.xml.DescriptorBindingException;
import org.jupnp.binding.xml.DeviceDescriptorBinder;
import org.jupnp.model.Namespace;
import org.jupnp.model.UnsupportedDataException;
import org.jupnp.model.message.IncomingDatagramMessage;
import org.jupnp.model.message.UpnpResponse;
import org.jupnp.model.message.discovery.IncomingSearchResponse;
import org.jupnp.transport.impl.DatagramProcessorImpl;
import org.jupnp.transport.spi.DatagramProcessor;
import org.jupnp.model.ServerClientTokens;
import org.jupnp.model.meta.Device;
import org.jupnp.model.profile.RemoteClientInfo;
import org.jupnp.transport.spi.NetworkAddressFactory;
import org.jupnp.transport.spi.StreamClient;
import org.jupnp.transport.spi.StreamServer;

import org.w3c.dom.Document;
import org.xml.sax.InputSource;

import java.io.StringReader;
import java.net.DatagramPacket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;

import javax.xml.parsers.DocumentBuilderFactory;

public class DLNAServiceConfiguration extends AndroidUpnpServiceConfiguration {

    /** Renderer mode: the HTTP stream server must listen on a stable, predictable port. */
    private final boolean fixedListenPort;

    /** jUPnP stream server port; 0 means "any free port" but some controllers ignore ephemeral LOCATIONs. */
    private static final int DEFAULT_STREAM_PORT = 49152;

    /**
     * Keep SSDP search replies on the well-known port. UPnP permits an ephemeral response port and
     * jUPnP defaults to one, but several control points only accept a reply from the same UDP/1900
     * endpoint to which they sent M-SEARCH. The affected Bilibili client did not emit M-SEARCH at
     * all in our capture, so its primary compatibility path is the frequent alive pulse below; this
     * fixed port still makes active discovery interoperable with stricter controllers.
     */
    private static final int SSDP_RESPONSE_PORT = 1900;

    public DLNAServiceConfiguration() {
        this(false);
    }

    public DLNAServiceConfiguration(boolean fixedListenPort) {
        // Only the renderer needs this compatibility endpoint. Browser/DMC instances keep an
        // ephemeral port so both roles can run in the same process without competing for 1900.
        super(resolveListenPort(fixedListenPort), fixedListenPort ? SSDP_RESPONSE_PORT : 0);
        this.fixedListenPort = fixedListenPort;
    }

    private static int resolveListenPort(boolean fixedListenPort) {
        if (!fixedListenPort) return 0;
        int port = DlnaSetting.getHttpPort();
        return port > 0 ? port : DEFAULT_STREAM_PORT;
    }

    /**
     * DLNA device capability element.
     *
     * jUPnP emits a plain UPnP description, but DLNA-aware controllers can filter device lists using
     * {@code <dlna:X_DLNADOC>DMR-1.50</dlna:X_DLNADOC>}. Declare the renderer profile explicitly
     * instead of relying on controllers to infer it from the UPnP MediaRenderer device type. The
     * observed Bilibili discovery failure had an additional, independently verified passive-SSDP
     * cause; keeping this declaration still improves interoperability with capability filters.
     */
    private static final String DLNA_DOC = "<dlna:X_DLNADOC xmlns:dlna=\"urn:schemas-dlna-org:device-1-0\">DMR-1.50</dlna:X_DLNADOC>";

    @Override
    public DeviceDescriptorBinder getDeviceDescriptorBinderUDA10() {
        DeviceDescriptorBinder delegate = super.getDeviceDescriptorBinderUDA10();
        return new DeviceDescriptorBinder() {
            @Override
            public <T extends Device> T describe(T device, String descriptorXml) throws DescriptorBindingException, org.jupnp.model.ValidationException {
                return delegate.describe(device, descriptorXml);
            }

            @Override
            public <T extends Device> T describe(T device, Document descriptor) throws DescriptorBindingException, org.jupnp.model.ValidationException {
                return delegate.describe(device, descriptor);
            }

            @Override
            public String generate(Device device, RemoteClientInfo clientInfo, Namespace namespace) throws DescriptorBindingException {
                return injectDlnaDoc(delegate.generate(device, clientInfo, namespace));
            }

            @Override
            public Document buildDOM(Device device, RemoteClientInfo clientInfo, Namespace namespace) throws DescriptorBindingException {
                return toDocument(injectDlnaDoc(delegate.generate(device, clientInfo, namespace)));
            }
        };
    }

    private static String injectDlnaDoc(String xml) {
        if (xml == null || xml.contains("X_DLNADOC")) return xml;
        for (String anchor : new String[]{"</modelNumber>", "</UDN>", "</friendlyName>"}) {
            int at = xml.indexOf(anchor);
            if (at >= 0) return xml.substring(0, at + anchor.length()) + DLNA_DOC + xml.substring(at + anchor.length());
        }
        return xml.replace("</device>", DLNA_DOC + "</device>");
    }

    private static Document toDocument(String xml) throws DescriptorBindingException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        } catch (Exception e) {
            throw new DescriptorBindingException("Could not build device descriptor DOM", e);
        }
    }

    @Override
    public DatagramProcessor getDatagramProcessor() {
        return new DatagramProcessorImpl() {
            @Override public IncomingDatagramMessage read(InetAddress local, DatagramPacket packet) throws UnsupportedDataException {
                IncomingDatagramMessage message = super.read(local, packet);
                if (message.getOperation() instanceof UpnpResponse) {
                    IncomingSearchResponse response = new IncomingSearchResponse(message);
                    DlnaDiscoveryTrace.log("ssdp-response role=" + (fixedListenPort ? "renderer" : "browser")
                            + " from=" + packet.getSocketAddress() + " valid=" + response.isSearchResponseMessage());
                }
                return message;
            }
        };
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

            /**
             * jUPnP's own {@code isUsableAddress} rejects every non-IPv4 address, so the rest of this
             * stack (bind addresses, datagram IOs, stream servers) is IPv4-only. The Android
             * override of this method ignores that and hands back the interface's first <em>IPv6</em>
             * address for multicast datagrams — typically a link-local {@code fe80::…%eth0}.
             *
             * Every SSDP request arrives on the multicast receiver, so that address becomes the
             * incoming message's local address, and jUPnP then builds the response's NetworkAddress
             * and LOCATION from it. A scoped link-local IPv6 cannot be turned into a usable URL, the
             * response never leaves the box, and the renderer stays invisible on the LAN — while the
             * registry, the router and the sockets all look perfectly healthy.
             *
             * Answer with the interface's IPv4 address instead: it is what the rest of the stack
             * binds to, so the response is built from the address controllers can actually reach.
             */
            @Override
            public InetAddress getLocalAddress(NetworkInterface networkInterface, boolean isMulticast, InetAddress remoteAddress) {
                InetAddress ipv4 = firstIpv4Address(networkInterface);
                return ipv4 != null ? ipv4 : super.getLocalAddress(networkInterface, isMulticast, remoteAddress);
            }
        };
    }

    private static InetAddress firstIpv4Address(NetworkInterface networkInterface) {
        if (networkInterface == null) return null;
        try {
            for (InetAddress address : java.util.Collections.list(networkInterface.getInetAddresses())) {
                if (address instanceof Inet4Address && !address.isLoopbackAddress()) return address;
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
