package testcheck.ex2aAReserve;

import java.io.*;
import java.net.*;

public class TCPServer {
    private static final int PORT = 6789;

    public static void main(String[] args) {
        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            System.out.println("TCP Server running on port " + PORT);
            while (true) {
                Socket clientSocket = serverSocket.accept();
                System.out.println("Client connected: " + clientSocket);
                new ClientHandler(clientSocket).start();
            }
        } catch (IOException e) {
            System.err.println("Server error: " + e.getMessage());
        }
    }

    private static class ClientHandler extends Thread {
        private final Socket socket;

        public ClientHandler(Socket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                 BufferedWriter out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()))) {

                String input = in.readLine();
                if (input == null) return;

                // Thay 'a' bằng 'A'
                String replaced = input.replace('a', 'A');
                out.write(replaced);
                out.newLine();
                out.flush();

                // Đảo ngược
                String reversed = new StringBuilder(input).reverse().toString();
                out.write(reversed);
                out.newLine();
                out.flush();

            } catch (IOException e) {
                System.err.println("Client error: " + e.getMessage());
            } finally {
                try {
                    socket.close();
                } catch (IOException e) {
                    System.err.println("Close error: " + e.getMessage());
                }
            }
        }
    }
}
