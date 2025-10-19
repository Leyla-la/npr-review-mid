package test.tcpip.ex32;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SocketChannel;
import java.util.Scanner;

public class Client {
    public static void main(String[] args) throws IOException {
        InetSocketAddress address = new InetSocketAddress("localhost", 6969);
        SocketChannel channel = SocketChannel.open(address);

        String message = "";
        message = ControllerMethod.receiveMessage(channel);
        System.out.println("SERVER> " + message);

        Scanner input = new Scanner(System.in);

        while (true) {
            message = ControllerMethod.receiveMessage(channel);
            System.out.println("SERVER> " + message);
            if (message.equals("Login success")) {
                message = ControllerMethod.receiveMessage(channel);
                System.out.println("SERVER> " + message);
                ControllerMethod.sendMessage(channel, "bye");
                break;
            } else if (message.equals("bye")) {
                break;

            }
            System.out.println("Send: ");
            message = input.nextLine();
            ControllerMethod.sendMessage(channel, message);
        }
        channel.close();
    }
}
