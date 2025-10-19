package test.tcpip.ex22;

import test.tcpip.ex21.ControllerMethod;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

public class Client {
    public static void main(String[] args) throws IOException {
        InetSocketAddress address = new InetSocketAddress("localhost", 6969);
        SocketChannel channel = SocketChannel.open(address);

        String message = "";
        message = ControllerMethod.receiveMessage(channel);
        System.out.println(message);

        ControllerMethod.sendMessage(channel, "File transfer!!!");
        message = ControllerMethod.receiveMessage(channel);
        System.out.println(message);

        if (message.equals("No")) channel.close();
        else {
            String serverFileData = ControllerMethod.receiveMessage(channel);
            String path = "C:\\Users\\ADMIN\\IdeaProjects\\java-group-chat-video\\src\\test\\tcpip\\ex22\\clientData.txt";
            FileChannel fileChannel = FileChannel.open(Paths.get(path), StandardOpenOption.APPEND);
            ByteBuffer buffer = ByteBuffer.wrap(serverFileData.getBytes());
            fileChannel.write(buffer);

            fileChannel.close();

            String stopConnect = ControllerMethod.receiveMessage(channel);
            System.out.println(stopConnect);
            if (stopConnect.equals("terminate")) channel.close();



        }
    }
}
