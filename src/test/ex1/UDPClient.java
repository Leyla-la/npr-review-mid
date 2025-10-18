package test.ex1;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

public class UDPClient {
    public static void main(String[] args) throws IOException {
        System.out.println("UDP Client started!!");

        // ═══════════════════════════════════════════════════
        // BƯỚC 1: Tạo socket + địa chỉ server
        // ═══════════════════════════════════════════════════
        InetSocketAddress serverAddress = new InetSocketAddress("localhost", 6969);
        DatagramSocket clientSocket = new DatagramSocket();

        String path = "C:\\Users\\ADMIN\\IdeaProjects\\java-group-chat-video\\src\\test\\ex1\\clientData.txt";

        // ✅ SỬA: Thêm CREATE nếu file chưa tồn tại
        FileChannel fChannel = FileChannel.open(
                Paths.get(path),
                StandardOpenOption.WRITE,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING  // Xóa nội dung cũ
        );

        // ═══════════════════════════════════════════════════
        // BƯỚC 2: Gửi request tới server
        // ═══════════════════════════════════════════════════
        String message = "File Transfer!!!";
        byte[] requestData = message.getBytes(StandardCharsets.UTF_8);  // ✅ SỬA: UTF-8

        DatagramPacket requestPacket = new DatagramPacket(
                requestData,
                requestData.length,
                serverAddress
        );
        clientSocket.send(requestPacket);
        System.out.println("Request sent to server");

        // ═══════════════════════════════════════════════════
        // BƯỚC 3: Nhận file data từ server
        // ═══════════════════════════════════════════════════
        byte[] fileData = new byte[2048];
        DatagramPacket responsePacket = new DatagramPacket(fileData, fileData.length);

        clientSocket.receive(responsePacket);
        System.out.println("Received " + responsePacket.getLength() + " bytes from server");

        // ═══════════════════════════════════════════════════
        // BƯỚC 4: Lưu vào clientData.txt
        // ═══════════════════════════════════════════════════
        // ✅ SỬA: Chỉ ghi đúng độ dài nhận được
        ByteBuffer buffer = ByteBuffer.wrap(fileData, 0, responsePacket.getLength());
        fChannel.write(buffer);
        fChannel.close();

        // ✅ THÊM: In ra xem nhận được gì
        String receivedContent = new String(
                fileData,
                0,
                responsePacket.getLength(),
                StandardCharsets.UTF_8
        );
        System.out.println("Received content: " + receivedContent);
        System.out.println("Saved to clientData.txt");

        clientSocket.close();
    }
}