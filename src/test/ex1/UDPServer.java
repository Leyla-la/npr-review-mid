package test.ex1;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;

public class UDPServer {
    public static void main(String[] args) throws IOException {
        System.out.println("UDP Server started!!!");

        // ═══════════════════════════════════════════════════
        // BƯỚC 1: Tạo socket ở cổng 6969
        // ═══════════════════════════════════════════════════
        DatagramSocket serverSocket = new DatagramSocket(6969);

        // ═══════════════════════════════════════════════════
        // BƯỚC 2: Mở file cần gửi
        // ═══════════════════════════════════════════════════
        String path = "C:\\Users\\ADMIN\\IdeaProjects\\java-group-chat-video\\src\\test\\ex1\\serverData.txt";
        FileChannel fChannel = FileChannel.open(Paths.get(path));

        // ═══════════════════════════════════════════════════
        // BƯỚC 3: Nhận request từ client
        // ═══════════════════════════════════════════════════
        byte[] requestData = new byte[1024];
        DatagramPacket requestPacket = new DatagramPacket(requestData, requestData.length);
        serverSocket.receive(requestPacket);

        // ✅ SỬA: Dùng StandardCharsets.UTF_8 để đọc đúng
        String message = new String(
                requestPacket.getData(),
                0,                              // ← Từ vị trí 0
                requestPacket.getLength(),      // ← Đọc đúng độ dài
                StandardCharsets.UTF_8          // ← Encoding đúng
        );
        System.out.println("CLIENT> " + message);

        // ═══════════════════════════════════════════════════
        // BƯỚC 4: Đọc file thành String
        // ═══════════════════════════════════════════════════
        ByteBuffer buffer = ByteBuffer.allocate(2048);
        StringBuilder fileData = new StringBuilder();  // ✅ SỬA: Dùng StringBuilder (hiệu quả hơn)

        while (fChannel.read(buffer) != -1) {
            buffer.flip();  // Chuyển từ write mode → read mode

            // ✅ SỬA: Decode đúng cách
            fileData.append(StandardCharsets.UTF_8.decode(buffer));

            buffer.clear();  // Reset buffer để đọc tiếp
        }
        fChannel.close();

        System.out.println("File content: " + fileData);  // ✅ THÊM: Debug xem file có gì

        // ═══════════════════════════════════════════════════
        // BƯỚC 5: Gửi nội dung file cho client
        // ═══════════════════════════════════════════════════
        byte[] fileBytes = fileData.toString().getBytes(StandardCharsets.UTF_8);  // ✅ SỬA: UTF-8

        DatagramPacket responsePacket = new DatagramPacket(
                fileBytes,
                fileBytes.length,
                requestPacket.getSocketAddress()  // Gửi về địa chỉ client
        );

        serverSocket.send(responsePacket);
        System.out.println("Sent " + fileBytes.length + " bytes to client");

        serverSocket.close();
    }
}