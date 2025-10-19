package test.ex4;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;

public class MultiClient {
    public static void main(String[] args) throws IOException {
        System.out.println("Multicast Client Start!!");
        MulticastSocket socket = new MulticastSocket(6969);
        InetAddress address = InetAddress.getByName("224.7.7.7");
        socket.joinGroup(address);

        //Receive file Data from Server
        byte[] data = new byte[1024];
        DatagramPacket packet = new DatagramPacket(data, data.length);
        while (true) {
            socket.receive(packet);
            String message = new String(packet.getData(), 0, packet.getLength());
            System.out.println("SERVER> " + message);
        }
    }
}
