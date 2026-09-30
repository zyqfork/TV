import org.jupnp.UpnpService;
import org.jupnp.UpnpServiceConfiguration;
import org.jupnp.model.message.IncomingDatagramMessage;
import org.jupnp.model.types.ServiceType;
import org.jupnp.model.types.UDAServiceType;
import org.jupnp.protocol.ProtocolFactoryImpl;
import org.jupnp.transport.impl.DatagramProcessorImpl;
import java.lang.reflect.Proxy;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;

/** Run against the actual org.jupnp 3.0.4 + slf4j-api jars, not a mocked parser/filter. */
public final class DlnaDiscoveryFilterProbe extends ProtocolFactoryImpl {
    private DlnaDiscoveryFilterProbe(UpnpService service) { super(service); }
    private boolean accepts(String type) throws Exception {
        String response = "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\nEXT:\r\n"
                + "LOCATION: http://127.0.0.1:4044/dev.xml\r\nST: " + type + "\r\n"
                + "USN: uuid:60bd2fb3-dabe-cb14-c766-0e319b54c29a::" + type + "\r\n\r\n";
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        InetAddress local = InetAddress.getLoopbackAddress();
        IncomingDatagramMessage message = new DatagramProcessorImpl().read(local, new DatagramPacket(bytes, bytes.length, local, 1900));
        return isSupportedServiceAdvertisement(message);
    }
    public static void main(String[] args) throws Exception {
        UpnpServiceConfiguration config = (UpnpServiceConfiguration) Proxy.newProxyInstance(
                UpnpServiceConfiguration.class.getClassLoader(), new Class[]{UpnpServiceConfiguration.class},
                (p,m,a) -> m.getName().equals("getExclusiveServiceTypes") ? new ServiceType[]{new UDAServiceType("ContentDirectory",1)} : null);
        UpnpService service = (UpnpService) Proxy.newProxyInstance(UpnpService.class.getClassLoader(), new Class[]{UpnpService.class},
                (p,m,a) -> m.getName().equals("getConfiguration") ? config : null);
        DlnaDiscoveryFilterProbe probe = new DlnaDiscoveryFilterProbe(service);
        if (probe.accepts("urn:schemas-upnp-org:device:MediaServer:1")) throw new AssertionError("device USN unexpectedly accepted");
        if (!probe.accepts("urn:schemas-upnp-org:service:ContentDirectory:1")) throw new AssertionError("directory service USN rejected");
        if (probe.accepts("urn:schemas-upnp-org:service:AVTransport:1")) throw new AssertionError("unrelated service accepted");
        System.out.println("PASS actual jUPnP filter: MediaServer dropped / ContentDirectory accepted / unrelated service excluded");
    }
}
