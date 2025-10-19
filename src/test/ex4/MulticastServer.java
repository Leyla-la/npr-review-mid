package test.ex4;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Paths;

public class MulticastServer {

    public static void main(String[] args) throws IOException, InterruptedException {
        System.out.println("Multicast Server started!!!");
        MulticastSocket socket = new MulticastSocket();
        InetAddress address = InetAddress.getByName("224.7.7.7");
        socket.joinGroup(address);
        String path = "C:\\Users\\ADMIN\\IdeaProjects\\java-group-chat-video\\src\\test\\ex4\\serverData.txt";
        FileChannel fChannel = FileChannel.open(Paths.get(path));
        ByteBuffer buffer = ByteBuffer.allocate(1024);
        //Read file data
        byte[] data = new byte[1024];
        DatagramPacket packet;
        String fileData = "";
        while (fChannel.read(buffer) != -1) {
            buffer.flip();
            while(buffer.hasRemaining())
                fileData += (char) buffer.get();
            buffer.clear();
        }

        //Send each Line of data
        for (int i = 0; i < fileData.split("\n").length; i++) {
            data = fileData.split("\n")[i].getBytes();
            packet = new DatagramPacket(data, data.length, address, 6969);
            socket.send(packet);
            Thread.sleep(5000);
        }
        fChannel.close();
    }

}
