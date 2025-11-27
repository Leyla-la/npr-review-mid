package tools;
import java.net.*;
import java.nio.charset.StandardCharsets;

public class UDPSender {
    public static void main(String[] args) throws Exception {
        String host = "127.0.0.1";
        int port = 5000;
        if (args.length >= 1) host = args[0];
        if (args.length >= 2) port = Integer.parseInt(args[1]);
        InetAddress addr = InetAddress.getByName(host);
        DatagramSocket s = new DatagramSocket();
        byte[] b1 = "ID:giang".getBytes(StandardCharsets.UTF_8);
        s.send(new DatagramPacket(b1, b1.length, addr, port));
        Thread.sleep(200);
        byte[] b2 = "meo".getBytes(StandardCharsets.UTF_8);
        s.send(new DatagramPacket(b2, b2.length, addr, port));
        s.close();
        System.out.println("Sent two UDP packets to " + addr + ":" + port);
    }
}
