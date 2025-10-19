package test.tcpip.ex22;

import org.w3c.dom.ls.LSOutput;
import test.tcpip.ex21.ControllerMethod;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;

public class Server {
    public static void main(String[] args) throws IOException {
        String path = "C:\\Users\\ADMIN\\IdeaProjects\\java-group-chat-video\\src\\test\\tcpip\\ex22\\serverData.txt";
        RandomAccessFile file = new RandomAccessFile(path, "rw");

        FileChannel fileChannel = file.getChannel();
        ByteBuffer buffer = ByteBuffer.allocate(128);

        //Open server
        InetSocketAddress address = new InetSocketAddress(6969);
        System.out.println("Server started !!!");
        ServerSocketChannel server = ServerSocketChannel.open();
        server.bind(address);

        SocketChannel channel = server.accept();
        System.out.println("Received connection from " + channel.getRemoteAddress());

        //Connection success
        ControllerMethod.sendMessage(channel, "Connect success!!!");
        String message = ControllerMethod.receiveMessage(channel);
        System.out.println(message);
        String condition = "yes";
        if (condition.equals("no"))
            ControllerMethod.sendMessage(channel, "No");
        else {
            ControllerMethod.sendMessage(channel, "File is transfering...");
            String fileData = "";
            System.out.println("File is transfering");
            while(fileChannel.read(buffer) != -1) {
                buffer.flip();

                while(buffer.hasRemaining())
                    fileData += (char) buffer.get();

                buffer.clear();
            }
            ControllerMethod.sendMessage(channel, fileData);
            System.out.println("Transfer done!");
            ControllerMethod.sendMessage(channel, "terminate");
        }
        System.out.println("Done");
    }
}


