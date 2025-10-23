
/*
 ServerMaster.java
 Master server containing many exam-case blocks for 61FIT3NPR Network Programming.
 - Thread-per-client model (as requested)
 - Port derived from student ID (see PORT_SELECTION below)
 - Many blocks are provided. Keep base mandatory code. Comment / uncomment indicated blocks before compilation for specific exam variant.
 - Comments are English-only and concise for exam readability.
 
 USAGE NOTES (edit before compile/run):
  - Choose PORT by uncommenting one of the PORT_SELECTION lines.
  - The server starts and accepts multiple clients; each client is handled by a separate ServiceThread (extends Thread).
  - For file transfer operations, the code uses DataInputStream/DataOutputStream and NOT readLine().
  - For text/chat operations, the code uses BufferedReader/BufferedWriter and readLine().
  - To enable a behavior (e.g., broadcast, file receive), uncomment the marker in run() where indicated.
*/

import java.io.*;
import java.math.BigInteger;
import java.net.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ServerMaster {

    // ==================== PORT SELECTION ====================
    // Student ID provided: 4075
    // Choose one of the following ports by uncommenting it.
    // Mandatory: Leave exactly one PORT active before compiling.
    // ----------------- Edit here -----------------
    // public static final int PORT = 44075; // use this for "4abcc" style (default)
    public static final int PORT = 54075;   // alternative "5abcc" style (uncomment only one)
    // ----------------------------------------------------

    // Mandatory base structures
    private static final Set<ServiceThread> clients = ConcurrentHashMap.newKeySet();
    private static volatile boolean running = true; // used for graceful shutdown

    public static void main(String[] args) {
        System.out.println("ServerMaster starting on port " + PORT);
        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("Shutdown hook: stopping server...");
                running = false;
                // close all client sockets
                for (ServiceThread c : clients) {
                    c.safeClose();
                }
            }));

            while (running) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    ServiceThread handler = new ServiceThread(clientSocket);
                    clients.add(handler);
                    handler.start();
                    System.out.println("Accepted connection from " + clientSocket.getRemoteSocketAddress());
                } catch (SocketException se) {
                    System.out.println("Server socket closed or interrupted.");
                }
            }

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // Broadcast helper (used when broadcast mode is enabled)
    public static void broadcast(ServiceThread sender, String message) {
        for (ServiceThread c : clients) {
            if (c != sender) {
                c.sendText("[BROADCAST from " + sender.getClientId() + "] " + message);
            }
        }
    }

    // Remove client from set
    public static void removeClient(ServiceThread c) {
        clients.remove(c);
    }

    // ==================== ServiceThread ====================
    static class ServiceThread extends Thread {
        private static int NEXT_ID = 1;
        private final int clientId;
        private final Socket socket;
        private BufferedReader reader;
        private BufferedWriter writer;
        private DataInputStream dataIn;
        private DataOutputStream dataOut;
        private volatile boolean connected = true;

        ServiceThread(Socket socket) {
            this.socket = socket;
            this.clientId = NEXT_ID++;
            setName("ServiceThread-" + clientId);
        }

        public int getClientId() {
            return clientId;
        }

        // Mandatory base: safe close used by shutdown hook
        public void safeClose() {
            connected = false;
            try { socket.close(); } catch (IOException ignored) {}
        }

        @Override
        public void run() {
            try {
                // Base: open text streams (UTF-8) for line-based commands
                reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
                writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), "UTF-8"));

                // Also prepare binary streams for file transfer if needed
                dataIn = new DataInputStream(socket.getInputStream());
                dataOut = new DataOutputStream(socket.getOutputStream());

                // Print connection notice (teacher requires)
                System.out.println("Client#" + clientId + " connected: " + socket.getRemoteSocketAddress());

                // --- Protocol design notes: ---
                // This server supports many modes. In exam, the professor usually expects a
                // small subset. Use one of the following patterns by uncommenting the blocks:
                //
                // 1) SIMPLE TEXT LOOP (Echo/Upper/Reverse/BigInt detection)
                //    -> keep mandatory base code below (text loop) and uncomment one action line
                //
                // 2) COMMAND-BASED MINIPROTOCOL (explicit commands like SLICE/REPLACE/GET/PUT/BROADCAST)
                //    -> uncomment the commandParser block below
                //
                // 3) FILE TRANSFER (client sends file bytes to server)
                //    -> uncomment receiveFile block; ensure client uses sendFile()
                //
                // 4) BROADCAST / CHAT (multi-client)
                //    -> uncomment broadcast handling in text loop
                //
                // 5) MIXED: initial student-ID math then loop (classic original style)
                //    -> uncomment math-initial block and BigInteger handling in loop

                // ------------------ 1) SIMPLE TEXT LOOP ------------------
                // MANDATORY: Keep the loop structure. Choose the single-line action you need by uncommenting.
                // IMPORTANT: Do not keep multiple conflicting response assignments active at once.
                String line;
                while (connected && (line = reader.readLine()) != null) {
                    // If professor asks "print message on server when accept connection" we already printed above.
                    // Trim CR/leading/trailing spaces for reliable parsing
                    line = line.trim();

                    // Exit condition
                    if (line.equalsIgnoreCase("QUIT") || line.equalsIgnoreCase("/QUIT")) {
                        sendText("Goodbye!");
                        break;
                    }

                    // ----------------- Enable one of these transformations -----------------
                    // Uncomment exactly one response assignment for simple text tasks.

                    // 1. Echo (default) - good for basic echo tasks
                    // String response = line;

                    // 2. Uppercase - uncomment for uppercase task
                    // String response = line.toUpperCase();

                    // 3. Lowercase - uncomment for lowercase task
                    // String response = line.toLowerCase();

                    // 4. Reverse - uncomment for reverse string task
                    // String response = new StringBuilder(line).reverse().toString();

                    // 5. Trim/Collapse whitespaces
                    // String response = line.trim().replaceAll("\\s+", " ");

                    // 6. BigInteger detection: if positive integer -> pow(4) else echo (classic math)
                    // Note: professor may ask either multiply by 4 or pow(4); adjust below.
                    // String response = handleBigInt(line);

                    // 7. Command parsing mode (uncomment the following block instead of above simple response)
                    // if (line.startsWith("CMD ")) { String r = commandParser(line.substring(4)); sendText(r); continue; }

                    // 8. Broadcast mode: broadcast received text to all other clients (uncomment to enable)
                    // broadcast(this, line);
                    // String response = "SENT_TO_ALL";

                    // ------------------------------------------------------------------------
                    // MANDATORY BASE: When using the simple text loop choose one response variable
                    // For safety, if none of above uncommented, default to echo:
                    String response = line; // keep this as default fallback

                    // Send text response (line-based)
                    sendText(response);
                }
                // ---------------- END TEXT LOOP ------------------

                // Optional: receive file (uncomment to enable file receive after text loop)
                // receiveFile("received_from_client_" + clientId + ".dat");

            } catch (IOException e) {
                System.out.println("Client#" + clientId + " IO error: " + e.getMessage());
            } finally {
                cleanup();
            }
        }

        // Utility: send text safely
        public void sendText(String text) {
            try {
                writer.write(text);
                writer.newLine();
                writer.flush();
            } catch (IOException e) {
                // If sending fails, assume client disconnected; mark for removal
                connected = false;
            }
        }

        // -------------------- BigInteger helper --------------------
        // When professor asks for '4 times Student_ID' or '[number]^4' use this helper.
        private String handleBigInt(String line) {
            try {
                // Accept only positive integer (no sign, no decimal)
                if (!line.matches("\\d+")) return line; // echo back if not positive integer
                BigInteger n = new BigInteger(line);
                // Option A: multiply by 4 (uncomment if exam asks multiply)
                // BigInteger result = n.multiply(BigInteger.valueOf(4));

                // Option B: power of 4 (uncomment if exam asks n^4)
                BigInteger result = n.pow(4);

                return result.toString();
            } catch (NumberFormatException ex) {
                return line;
            }
        }

        // -------------------- Command parser (example) --------------------
        // Use this block when professor requires specific commands like SLICE, REPLACE, COUNT, GET, PUT
        private String commandParser(String cmdLine) {
            // Expected format examples:
            // SLICE start end text...
            // COUNT char text...
            // REPLACE old new text...
            // GET filename   -> server should send file (requires binary protocol)
            // PUT filename   -> client will upload file (server receives)
            // PM targetId message...
            // ROOM join/leave messages...  (advanced)
            String[] parts = cmdLine.split("\\s+", 2);
            String cmd = parts[0].toUpperCase();
            String rest = parts.length > 1 ? parts[1] : "";

            try {
                switch (cmd) {
                    case "SLICE": {
                        String[] a = rest.split("\\s+", 3);
                        int s = Integer.parseInt(a[0]);
                        int e = Integer.parseInt(a[1]);
                        String txt = a.length >= 3 ? a[2] : "";
                        if (s < 0 || e > txt.length() || s >= e) return "ERROR_INVALID_SLICE";
                        return txt.substring(s, e);
                    }
                    case "COUNT": {
                        String[] a = rest.split("\\s+", 2);
                        String needle = a[0];
                        String txt = a.length >= 2 ? a[1] : "";
                        long count = txt.chars().filter(ch -> ch == needle.charAt(0)).count();
                        return Long.toString(count);
                    }
                    case "REPLACE": {
                        String[] a = rest.split("\\s+", 3);
                        if (a.length < 3) return "ERROR_MALFORMED_REPLACE";
                        return a[2].replace(a[0], a[1]);
                    }
                    case "ECHO": return rest;
                    case "UPPER": return rest.toUpperCase();
                    case "LOWER": return rest.toLowerCase();
                    case "PUT":
                        // For PUT: client will follow with: <filename>\n<long size>\n<bytes...>
                        // Implementation: Use receiveFile helper to store file. This requires that client uses DataOutputStream protocol.
                        receiveFileSanitized(rest.isEmpty() ? "upload_" + clientId : rest);
                        return "PUT_OK";
                    case "GET":
                        // For GET: server should send file to client using binary transfer.
                        // This method will trigger sendFile helper. NOTE: sendFile uses dataOut and will block if client not ready.
                        try {
                            sendFileToClient(rest);
                            return "GET_SENT";
                        } catch (IOException ioe) {
                            return "GET_ERROR: " + ioe.getMessage();
                        }
                    default:
                        return "UNKNOWN_CMD";
                }
            } catch (Exception ex) {
                return "CMD_ERROR: " + ex.getMessage();
            }
        }

        // -------------------- File receive (safe) --------------------
        // This method reads a long size then that many bytes and writes to fileName.
        private void receiveFile(String fileName) throws IOException {
            try (FileOutputStream fos = new FileOutputStream(fileName)) {
                long size = dataIn.readLong();
                byte[] buffer = new byte[4096];
                long remaining = size;
                while (remaining > 0) {
                    int read = dataIn.read(buffer, 0, (int)Math.min(buffer.length, remaining));
                    if (read == -1) throw new EOFException("Unexpected EOF during file receive");
                    fos.write(buffer, 0, read);
                    remaining -= read;
                }
                System.out.println("Received file: " + fileName + " (" + size + " bytes)");
            }
        }

        // Sanitized receive: ensures no path traversal and uses unique file name
        private void receiveFileSanitized(String suppliedName) throws IOException {
            String safeName = suppliedName.replaceAll("[\\\\/]+", "_");
            String uniq = "server_recv_" + clientId + "_" + System.currentTimeMillis() + "_" + safeName;
            receiveFile(uniq);
        }

        // -------------------- Send file to client --------------------
        // Sends file bytes with long size prefix. Caller must ensure client uses DataInputStream to read.
        private void sendFileToClient(String filePath) throws IOException {
            File f = new File(filePath);
            if (!f.exists() || !f.isFile()) {
                throw new FileNotFoundException("File not found: " + filePath);
            }
            try (FileInputStream fis = new FileInputStream(f)) {
                dataOut.writeLong(f.length());
                byte[] buffer = new byte[4096];
                int read;
                while ((read = fis.read(buffer)) != -1) {
                    dataOut.write(buffer, 0, read);
                }
                dataOut.flush();
            }
        }

        // -------------------- Cleanup --------------------
        private void cleanup() {
            removeClient(this);
            try {
                if (reader != null) reader.close();
            } catch (IOException ignored) {}
            try {
                if (writer != null) writer.close();
            } catch (IOException ignored) {}
            try {
                if (dataIn != null) dataIn.close();
            } catch (IOException ignored) {}
            try {
                if (dataOut != null) dataOut.close();
            } catch (IOException ignored) {}
            try {
                if (!socket.isClosed()) socket.close();
            } catch (IOException ignored) {}
            System.out.println("Client#" + clientId + " disconnected and cleaned up.");
        }
    }
}
