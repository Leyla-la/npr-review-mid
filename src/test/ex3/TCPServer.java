package test.ex3;

import test.ex2.ControllerMethod;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.file.Paths;
import java.util.Iterator;
import java.util.Set;

public class TCPServer {
    public static void main(String[] args) throws IOException {
        System.out.println("Server started on port 6969");

        // ═══════════════════════════════════════════════════
        // BƯỚC 1: Tạo Selector
        // ═══════════════════════════════════════════════════
        Selector selector = Selector.open();

        // ═══════════════════════════════════════════════════
        // BƯỚC 2: Tạo ServerSocketChannel
        // ═══════════════════════════════════════════════════
        InetSocketAddress address = new InetSocketAddress(6969);
        ServerSocketChannel server = ServerSocketChannel.open();
        server.configureBlocking(false);  // NON-BLOCKING
        server.bind(address);

        // ═══════════════════════════════════════════════════
        // BƯỚC 3: Đăng ký OP_ACCEPT (chỉ nhận kết nối)
        // ═══════════════════════════════════════════════════
        server.register(selector, SelectionKey.OP_ACCEPT);

        // ═══════════════════════════════════════════════════
        // BƯỚC 4: Vòng lặp chờ sự kiện
        // ═══════════════════════════════════════════════════
        while (true) {
            // Chờ tối đa 500ms
            int readyChannels = selector.select(500);

            if (readyChannels == 0) {
                continue;  // Không có sự kiện nào, tiếp tục chờ
            }

            Set<SelectionKey> keys = selector.selectedKeys();
            Iterator<SelectionKey> keyIterator = keys.iterator();

            while (keyIterator.hasNext()) {
                SelectionKey key = keyIterator.next();
                keyIterator.remove();  // ← XÓA NGAY để tránh xử lý 2 lần

                try {
                    // ═══════════════════════════════════════════════════
                    // Sự kiện: Có client kết nối
                    // ═══════════════════════════════════════════════════
                    if (key.isAcceptable()) {
                        ServerSocketChannel serverChannel = (ServerSocketChannel) key.channel();
                        SocketChannel clientChannel = serverChannel.accept();

                        System.out.println("✅ Client connected: " + clientChannel.getRemoteAddress());

                        clientChannel.configureBlocking(false);

                        // ✅ SỬA: CHỈ đăng ký OP_READ (không có OP_WRITE)
                        clientChannel.register(selector, SelectionKey.OP_READ);
                    }

                    // ═══════════════════════════════════════════════════
                    // Sự kiện: Client gửi dữ liệu
                    // ═══════════════════════════════════════════════════
                    else if (key.isReadable()) {
                        SocketChannel clientChannel = (SocketChannel) key.channel();

                        String message = ControllerMethod.receiveMessage(clientChannel);

                        if (message.isEmpty()) {
                            // Client đóng kết nối
                            System.out.println("❌ Client disconnected: " + clientChannel.getRemoteAddress());
                            key.cancel();
                            clientChannel.close();
                            continue;
                        }

                        System.out.println("📩 " + clientChannel.getRemoteAddress() + ": " + message);

                        // Gửi file
                        sendFile(clientChannel);

                        // ✅ SỬA: Đóng sau khi GỬI XONG
                        System.out.println("✅ File sent to " + clientChannel.getRemoteAddress());
                        key.cancel();
                        clientChannel.close();
                    }
                } catch (IOException e) {
                    System.err.println("❌ Error: " + e.getMessage());
                    key.cancel();
                    try {
                        key.channel().close();
                    } catch (IOException ex) {
                        // Ignore
                    }
                }
            }
        }
    }

    public static void sendFile(SocketChannel channel) throws IOException {
        String path = "C:\\Users\\ADMIN\\IdeaProjects\\java-group-chat-video\\src\\test\\ex3\\serverData.txt";
        FileChannel fChannel = FileChannel.open(Paths.get(path));

        ByteBuffer buffer = ByteBuffer.allocate(1024);
        int bytesRead;
        int totalSent = 0;

        // ✅ SỬA: Đảm bảo gửi HẾT file
        while ((bytesRead = fChannel.read(buffer)) != -1) {
            buffer.flip();

            // ✅ SỬA: Loop để ghi hết buffer
            while (buffer.hasRemaining()) {
                int written = channel.write(buffer);
                totalSent += written;
            }

            buffer.clear();
        }

        fChannel.close();
        System.out.println("📤 Total sent: " + totalSent + " bytes");
    }
}