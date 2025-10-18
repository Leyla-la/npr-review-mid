package test.ex2;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SocketChannel;
import java.util.Scanner;

public class TCPClient {
    public static void main(String[] args) throws IOException {
        System.out.println("TCP Client started!!!");
        InetSocketAddress serverAddress = new InetSocketAddress("localhost", 6969);
        SocketChannel channel = SocketChannel.open(serverAddress);
        //Send array
        Scanner input = new Scanner(System.in);
        String message = "";
        System.out.println("Send: " ); message = input.nextLine();
        ControllerMethod.sendMessage(channel, message);

        //Receive array from server
        message = ControllerMethod.receiveMessage(channel);
        System.out.println("SERVER> " + message);
    }
}
