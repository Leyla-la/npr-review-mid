package mid2025ca01;
import java.io.*;
import java.math.BigInteger;
import java.net.*;
import java.util.Scanner;

//netstat -aon | findstr :4075
//taskkill /PID <PID> /F (thay <PID> bằng số bạn tìm được).


// Server class: Handles listening, accepting clients, and creating threads
public class Server {
    private static final int PORT = 4075; // Sử dụng port 4075 theo ví dụ

    public static void main(String[] args) {
        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            serverSocket.setReuseAddress(true);
            System.out.println("Server listening on port " + PORT);
            while (true) { // Infinite loop to accept multiple clients
                try {
                    Socket clientSocket = serverSocket.accept();
                    // Chọn cách 1 hoặc cách 2 bằng cách comment/uncomment block tương ứng
                    // Cách 1: Dùng số thứ tự (đơn giản nhất)
                    /*
                    static int clientCount = 0;
                    String clientId = "Client #" + ++clientCount + " connected from " + clientSocket.getInetAddress().getHostAddress();
                    System.out.println(clientId);
                    new Thread(new ClientHandler(clientSocket, clientId)).start();
                    */
                    // Cách 2: Dùng Port của client (chuyên nghiệp hơn)
                    String clientId = "Client connected from " + clientSocket.getInetAddress().getHostAddress() + ":" + clientSocket.getPort();
                    System.out.println(clientId);
                    new Thread(new ClientHandler(clientSocket, clientId)).start(); // Create thread per client
                } catch (IOException e) {
                    System.err.println("Error accepting client: " + e.getMessage());
                    // Continue listening for other clients
                }
            }
        } catch (IOException e) {
            System.err.println("Server startup error: " + e.getMessage());
        }
    }

    // Inner class for handling each client connection
    private static class ClientHandler implements Runnable {
        private final Socket socket;
        private BufferedReader reader;
        private BufferedWriter writer;
        private String clientId;

        ClientHandler(Socket socket, String clientId) {
            this.socket = socket;
            this.clientId = clientId;
            try {
                reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
            } catch (IOException e) {
                System.err.println("Error initializing client handler for " + clientId + ": " + e.getMessage());
                close(); // Immediate cleanup if init fails
            }
        }

        @Override
        public void run() {
            try {
                // Receive first message (Student_ID) and process
                String studentId = reader.readLine();
                if (studentId != null) {
                    this.clientId = clientId.replace("connected", studentId + " connected"); // Update clientId with Student_ID
                    String response = processMessage(studentId, true); // First message: 4 * ID
                    writer.write(response);
                    writer.newLine();
                    writer.flush();
                }

                // Loop to handle subsequent messages
                String msg;
                while ((msg = reader.readLine()) != null) {
                    if ("QUIT".equals(msg)) { // Handle quit request (comment this block if Quit not required)
                        System.out.println(clientId.replace("connected", "disconnected"));
                        break; // Exit loop to close connection
                    }
                    String response = processMessage(msg, false); // Normal processing: ^4 or handle
                    writer.write(response);
                    writer.newLine();
                    writer.flush();
                }
            } catch (IOException e) {
                System.err.println(clientId.replace("connected", "disconnected") + ": " + e.getMessage());
            } finally {
                close(); // Ensure resources are closed
                System.out.println(clientId.replace("connected", "disconnected")); // Notify server
            }
        }

        // Process message logic with enhanced exception handling
        private String processMessage(String msg, boolean isFirst) {
            try {
                BigInteger num = new BigInteger(msg);
                if (isFirst) {
                    return num.multiply(BigInteger.valueOf(4)).toString(); // 4 * ID
                } else if (num.compareTo(BigInteger.ZERO) > 0) { // Positive integer
                    return num.pow(4).toString(); // [num]^4
                } else {
                    return msg; // Echo if not positive
                }
            } catch (NumberFormatException e) {
                // Default: Echo as per requirement (comment next line and uncomment below if handle non-number required)
                return msg;
                // If requirement changes to handle non-number (e.g., send error), uncomment:
                // return "INVALID_NUMBER: " + msg; // Custom handle for non-number (comment this line if echo required)
            }
        }

        // Close resources safely
        private void close() {
            try {
                if (reader != null) reader.close();
                if (writer != null) writer.close();
                if (socket != null && !socket.isClosed()) socket.close();
            } catch (IOException e) {
                System.err.println("Error closing resources for " + clientId + ": " + e.getMessage());
            }
        }
    }
}