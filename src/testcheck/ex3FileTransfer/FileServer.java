package testcheck.ex3FileTransfer;

import java.io.*;
import java.net.*;

public class FileServer {
    private static final int PORT = 6789;

    public static void main(String[] args) {
        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            System.out.println("File Server running on port " + PORT);
            while (true) {
                Socket clientSocket = serverSocket.accept();
                System.out.println("Client connected: " + clientSocket);
                new FileHandler(clientSocket).start();
            }
        } catch (IOException e) {
            System.err.println("Server error: " + e.getMessage());
        }
    }

    private static class FileHandler extends Thread {
        private final Socket socket;

        public FileHandler(Socket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                 DataOutputStream dataOut = new DataOutputStream(socket.getOutputStream())) {

                String fileName = in.readLine();
                File file = new File(fileName);
                if (!file.exists()) {
                    dataOut.writeLong(-1); // File not found
                    return;
                }

                dataOut.writeLong(file.length());
                try (FileInputStream fis = new FileInputStream(file)) {
                    byte[] buffer = new byte[4096];
                    int bytesRead;
                    while ((bytesRead = fis.read(buffer)) != -1) {
                        dataOut.write(buffer, 0, bytesRead);
                    }
                    dataOut.flush();
                }

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