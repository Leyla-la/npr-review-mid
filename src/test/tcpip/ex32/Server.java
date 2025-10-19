package test.tcpip.ex32;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;

public class Server {
    public static void main(String[] args) throws IOException, InterruptedException {
        InetSocketAddress address = new InetSocketAddress(6969);
        System.out.println("Server started!!!");
        ServerSocketChannel serverSocketChannel = ServerSocketChannel.open();
        serverSocketChannel.bind(address);

        do {
            SocketChannel channel = serverSocketChannel.accept();
            System.out.println("Received connection from " + channel.getRemoteAddress());

            //Connection success
            ControllerMethod.sendMessage(channel, "Connect success!!!");
            Thread.sleep(200);
            int countLogin = 0;
            while (countLogin < 3) {
                ControllerMethod.sendMessage(channel, "Username && Password!!!");
                String message = "";
                message = ControllerMethod.receiveMessage(channel);
                System.out.println("Received message: " + message);
                String userName = message.split(" ")[0];
                String password = message.split(" ")[1];

                if(userName.equals("Giang") && password.equals("1234")) {
                    ControllerMethod.sendMessage(channel, "Login success");
                    Thread.sleep(200);
                    ControllerMethod.sendMessage(channel, "300k/chi");
                    break;
                }
                countLogin += 1;
            }
            ControllerMethod.sendMessage(channel, "Bye");
        } while (true);

    }
}
