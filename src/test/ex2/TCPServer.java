package test.ex2;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;

public class TCPServer {
    public static void main(String[] args) throws IOException {
        System.out.println("TCP Server Start!!!");
        InetSocketAddress address = new InetSocketAddress(6969);
        ServerSocketChannel server = ServerSocketChannel.open();
        server.bind(address);

        SocketChannel channel = server.accept();
        //Receive msg from client
        String message = "";
        message = ControllerMethod.receiveMessage(channel);
        System.out.println("CLIENT> " + message);

        //Send back msg after process
        uppercaseMsg(channel, message);
        System.out.println("Done uppercase");

        reverseMessage(channel, message);
        System.out.println("Done reverse");
    }

    public static void uppercaseMsg(SocketChannel channel, String msg) throws IOException {
        String newMsg = msg.toUpperCase();
        ControllerMethod.sendMessage(channel, newMsg);
    }

    public static void reverseMessage(SocketChannel channel, String message) throws IOException {
        String newMessage = "";
        for (int i = message.length() - 1; i >= 0; i--)
            newMessage += message.charAt(i);
        ControllerMethod.sendMessage(channel, newMessage);
    }
}
