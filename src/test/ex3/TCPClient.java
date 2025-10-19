package test.ex3;

import test.ex2.ControllerMethod;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

public class TCPClient {

    public static void main(String[] args) throws IOException {
        System.out.println("Client started");

        // ═══════════════════════════════════════════════════
        // BƯỚC 1: Kết nối đến server
        // ═══════════════════════════════════════════════════
        InetSocketAddress serverAddress = new InetSocketAddress("localhost", 6969);
        SocketChannel channel = SocketChannel.open(serverAddress);

        System.out.println("✅ Connected to server");

        // ═══════════════════════════════════════════════════
        // BƯỚC 2: Gửi request
        // ═══════════════════════════════════════════════════
        ControllerMethod.sendMessage(channel, "download!!");
        System.out.println("📤 Request sent");

        // ═══════════════════════════════════════════════════
        // BƯỚC 3: Nhận file
        // ═══════════════════════════════════════════════════
        receiveFile(channel);

        channel.close();
        System.out.println("✅ Done");
    }

    // ✅ SỬA: Đọc NHIỀU LẦN cho đến khi hết
    public static void receiveFile(SocketChannel channel) throws IOException {
        String path = "C:\\Users\\ADMIN\\IdeaProjects\\java-group-chat-video\\src\\test\\ex3\\clientData.txt";

        FileChannel fChannel = FileChannel.open(
                Paths.get(path),
                StandardOpenOption.WRITE,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
        );

        ByteBuffer buffer = ByteBuffer.allocate(1024);
        int bytesRead;
        int totalReceived = 0;

        // ✅ SỬA: Loop đọc cho đến khi server đóng kết nối
        while ((bytesRead = channel.read(buffer)) != -1) {
            buffer.flip();

            fChannel.write(buffer);
            totalReceived += bytesRead;

            buffer.clear();
        }

        fChannel.close();
        System.out.println("📥 Total received: " + totalReceived + " bytes");
        System.out.println("💾 Saved to: " + path);
    }
}