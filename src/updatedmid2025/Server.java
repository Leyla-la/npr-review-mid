package mid2025ca01;

import java.io.*;
import java.math.BigInteger;
import java.net.*;
import java.nio.file.*;

public class Server {
    private static final int PORT = 4075;
    private static final String UPLOAD_DIR = "server_files/";
    private static int clientCount = 0;

    public static void main(String[] args) {
        // Tạo thư mục lưu file nếu chưa có
        new File(UPLOAD_DIR).mkdirs();
        
        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            serverSocket.setReuseAddress(true);
            System.out.println("=== SERVER STARTED ===");
            System.out.println("Port: " + PORT);
            System.out.println("Upload directory: " + UPLOAD_DIR);
            System.out.println("Waiting for clients...\n");
            
            while (true) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    String clientId = "Client #" + (++clientCount);
                    String clientInfo = clientId + " [" + 
                        clientSocket.getInetAddress().getHostAddress() + ":" + 
                        clientSocket.getPort() + "]";
                    
                    System.out.println("✓ " + clientInfo + " connected");
                    new Thread(new ClientHandler(clientSocket, clientId, clientInfo)).start();
                } catch (IOException e) {
                    System.err.println("Error accepting client: " + e.getMessage());
                }
            }
        } catch (IOException e) {
            System.err.println("Server startup error: " + e.getMessage());
        }
    }

    private static class ClientHandler implements Runnable {
        private final Socket socket;
        private final String clientId;
        private String clientInfo;
        private BufferedReader reader;
        private BufferedWriter writer;
        private DataInputStream dataIn;
        private DataOutputStream dataOut;

        ClientHandler(Socket socket, String clientId, String clientInfo) {
            this.socket = socket;
            this.clientId = clientId;
            this.clientInfo = clientInfo;
            try {
                reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
                dataIn = new DataInputStream(socket.getInputStream());
                dataOut = new DataOutputStream(socket.getOutputStream());
            } catch (IOException e) {
                System.err.println("Error initializing " + clientId + ": " + e.getMessage());
                close();
            }
        }

        @Override
        public void run() {
            try {
                // Bước 1: Nhận Student_ID đầu tiên
                String studentId = reader.readLine();
                if (studentId != null && !studentId.isEmpty()) {
                    clientInfo = clientId + " (ID: " + studentId + ")";
                    System.out.println("→ " + clientId + " sent Student_ID: " + studentId);
                    
                    String response = calculateFirstResponse(studentId);
                    writer.write(response);
                    writer.newLine();
                    writer.flush();
                    System.out.println("← Sent to " + clientId + ": " + response);
                }

                // Bước 2: Vòng lặp xử lý message và file
                String command;
                while ((command = reader.readLine()) != null) {
                    System.out.println("\n→ " + clientId + " command: " + command);
                    
                    if ("QUIT".equals(command)) {
                        System.out.println("✗ " + clientInfo + " disconnected (user quit)");
                        break;
                    } 
                    else if ("FILE".equals(command)) {
                        handleFileUpload();
                    } 
                    else {
                        handleMessage(command);
                    }
                }
            } catch (IOException e) {
                System.err.println("✗ " + clientInfo + " disconnected: " + e.getMessage());
            } finally {
                close();
            }
        }

        private String calculateFirstResponse(String studentId) {
            try {
                BigInteger id = new BigInteger(studentId);
                return id.multiply(BigInteger.valueOf(4)).toString();
            } catch (NumberFormatException e) {
                return "ERROR: Invalid Student ID";
            }
        }

        private void handleMessage(String msg) throws IOException {
            String response = processMessage(msg);
            writer.write(response);
            writer.newLine();
            writer.flush();
            System.out.println("← Sent to " + clientId + ": " + response);
        }

        private String processMessage(String msg) {
            try {
                BigInteger num = new BigInteger(msg);
                if (num.compareTo(BigInteger.ZERO) > 0) {
                    BigInteger result = num.pow(4);
                    return result.toString();
                } else {
                    return msg; // Echo nếu không phải số dương
                }
            } catch (NumberFormatException e) {
                return msg; // Echo nếu không phải số
            }
        }

        private void handleFileUpload() throws IOException {
            try {
                // Đọc tên file
                String fileName = dataIn.readUTF();
                // Đọc kích thước file
                long fileSize = dataIn.readLong();
                
                System.out.println("  ↓ Receiving file: " + fileName + " (" + fileSize + " bytes)");
                
                // Tạo đường dẫn lưu file với clientId để tránh trùng
                String savePath = UPLOAD_DIR + clientId + "_" + fileName;
                
                // Nhận file từ client
                try (FileOutputStream fos = new FileOutputStream(savePath)) {
                    byte[] buffer = new byte[4096];
                    long remaining = fileSize;
                    int bytesRead;
                    
                    while (remaining > 0 && 
                           (bytesRead = dataIn.read(buffer, 0, 
                            (int)Math.min(buffer.length, remaining))) != -1) {
                        fos.write(buffer, 0, bytesRead);
                        remaining -= bytesRead;
                    }
                }
                
                // Gửi xác nhận về client
                dataOut.writeUTF("SUCCESS");
                dataOut.flush();
                
                System.out.println("  ✓ File saved: " + savePath);
                
            } catch (IOException e) {
                System.err.println("  ✗ File upload error: " + e.getMessage());
                try {
                    dataOut.writeUTF("ERROR: " + e.getMessage());
                    dataOut.flush();
                } catch (IOException ex) {
                    // Ignore if can't send error
                }
            }
        }

        private void close() {
            try {
                if (reader != null) reader.close();
                if (writer != null) writer.close();
                if (dataIn != null) dataIn.close();
                if (dataOut != null) dataOut.close();
                if (socket != null && !socket.isClosed()) socket.close();
            } catch (IOException e) {
                System.err.println("Error closing " + clientId + ": " + e.getMessage());
            }
        }
    }
}