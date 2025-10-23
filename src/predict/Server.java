package predict;

import java.io.*;
import java.net.*;
import java.util.HashMap;
import java.util.Map;

public class Server {
    private static final int PORT = 41234; // Assume abc=123, adjust to 4abcc

    public static void main(String[] args) {
        System.out.println("Server starting on port " + PORT);
        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            while (true) {
                Socket clientSocket = serverSocket.accept();
                System.out.println("Accepted connection from " + clientSocket);
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
                 BufferedWriter out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
                 DataInputStream dataIn = new DataInputStream(socket.getInputStream());
                 DataOutputStream dataOut = new DataOutputStream(socket.getOutputStream())) {

                // Step 3: Handle initial Student_ID (convert to uppercase)
                String studentId = in.readLine();
                if (studentId == null || studentId.trim().isEmpty()) {
                    out.write("INVALID ID");
                    out.newLine();
                    out.flush();
                    return;
                }
                String upperId = studentId.toUpperCase();
                out.write(upperId);
                out.newLine();
                out.flush();

                // Loop for messages
                String message;
                while ((message = in.readLine()) != null) {
                    System.out.println("Received: " + message);

                    // File handling block (Câu 3 style)
                    File file = new File(message);
                    if (file.exists() && file.isFile()) {
                        // Receive file
                        long fileSize = dataIn.readLong();
                        if (fileSize > 0) {
                            try (FileOutputStream fos = new FileOutputStream("received_" + file.getName())) {
                                byte[] buffer = new byte[4096];
                                long remaining = fileSize;
                                int bytesRead;
                                while (remaining > 0 && (bytesRead = dataIn.read(buffer, 0, (int) Math.min(buffer.length, remaining))) != -1) {
                                    fos.write(buffer, 0, bytesRead);
                                    remaining -= bytesRead;
                                }
                            }
                            out.write("FILE RECEIVED: " + file.getName());
                            out.newLine();
                            out.flush();
                        }
                        continue;
                    }

                    // String handling block (character count and variations)
                    if (!message.trim().isEmpty()) {
                        // Default: Count characters
                        Map<Character, Integer> charCount = new HashMap<>();
                        for (char c : message.toCharArray()) {
                            charCount.merge(c, 1, Integer::sum);
                        }
                        StringBuilder countSummary = new StringBuilder();
                        for (Map.Entry<Character, Integer> entry : charCount.entrySet()) {
                            countSummary.append(entry.getKey()).append(":").append(entry.getValue()).append(", ");
                        }
                        if (countSummary.length() > 0) countSummary.setLength(countSummary.length() - 2); // Remove last comma
                        out.write(countSummary.toString());
                        out.newLine();
                        out.flush();

                        // // Uncomment for reverse string variation
                        // String reversed = new StringBuilder(message).reverse().toString();
                        // out.write("Reversed: " + reversed);
                        // out.newLine();
                        // out.flush();

                        // // Uncomment for uppercase variation
                        // String upperCase = message.toUpperCase();
                        // out.write("Uppercase: " + upperCase);
                        // out.newLine();
                        // out.flush();

                    } else {
                        out.write("INVALID INPUT");
                        out.newLine();
                        out.flush();
                    }
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