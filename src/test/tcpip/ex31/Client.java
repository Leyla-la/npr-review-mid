package test.tcpip.ex31;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SocketChannel;

public class Client {
    public static void main(String[] args) throws IOException {
        InetSocketAddress address = new InetSocketAddress("localhost", 6969);
        SocketChannel channel = SocketChannel.open(address);

        String message = "";
        message = ControllerMethod.receiveMessage(channel);
        System.out.println(message);
        //Send request
        ControllerMethod.sendMessage(channel, "Weather Information");
        message = ControllerMethod.receiveMessage(channel);
        System.out.println(message);
        channel.close();
        System.out.println("Done");

    }
}
