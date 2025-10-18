package test.ex2;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;

public class ControllerMethod {
    public static void sendMessage(SocketChannel channel, String message) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(message.getBytes(StandardCharsets.UTF_8));
        channel.write(buffer);
    }

    public static String receiveMessage(SocketChannel channel) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(1024);
        channel.read(buffer);
        buffer.flip();
        int limit = buffer.limit();
        String message = new String(buffer.array(), 0, limit, StandardCharsets.UTF_8);

        return message;
    }
}
