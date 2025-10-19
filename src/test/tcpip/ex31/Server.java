package test.tcpip.ex31;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;

public class Server {
    public static void main(String[] args) throws IOException {
        InetSocketAddress address = new InetSocketAddress(6969);
        System.out.println("Server started !!!");
        ServerSocketChannel server = ServerSocketChannel.open();
        server.bind(address);

        SocketChannel channel = server.accept();
        System.out.println("Received connection from " + channel.getRemoteAddress());
//Connection success
        ControllerMethod.sendMessage(channel, "Connect success!!!");
        String message = "";
        message = ControllerMethod.receiveMessage(channel);
        if (!message.equals("Weather Information"))
            ControllerMethod.sendMessage(channel, "Wrong syntax!!");
        else {
            ControllerMethod.sendMessage(channel, "Sunny");
        }
        System.out.println("Done");
    }
}
