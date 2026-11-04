package dev.decision;

import java.io.IOException;
import java.net.*;
import javax.net.SocketFactory;

/**
 * JDBC must reach its explicitly configured database directly, never a desktop HTTP/SOCKS proxy.
 */
public class DirectSocketFactory extends SocketFactory {
  public DirectSocketFactory() {}

  @Override
  public Socket createSocket() {
    return new Socket(Proxy.NO_PROXY) {
      @Override
      public void connect(SocketAddress endpoint, int timeout) throws IOException {
        if (endpoint instanceof InetSocketAddress address && address.isUnresolved())
          endpoint =
              new InetSocketAddress(
                  InetAddress.getByName(address.getHostString()), address.getPort());
        super.connect(endpoint, timeout);
      }
    };
  }

  @Override
  public Socket createSocket(String host, int port) throws IOException {
    Socket s = createSocket();
    s.connect(new InetSocketAddress(host, port));
    return s;
  }

  @Override
  public Socket createSocket(String host, int port, InetAddress local, int localPort)
      throws IOException {
    Socket s = createSocket();
    s.bind(new InetSocketAddress(local, localPort));
    s.connect(new InetSocketAddress(host, port));
    return s;
  }

  @Override
  public Socket createSocket(InetAddress host, int port) throws IOException {
    return createSocket(host.getHostAddress(), port);
  }

  @Override
  public Socket createSocket(InetAddress host, int port, InetAddress local, int localPort)
      throws IOException {
    return createSocket(host.getHostAddress(), port, local, localPort);
  }
}
